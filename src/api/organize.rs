//! Copying and moving photos and videos to another folder of the library, one at a
//! time or a selection at once.

use std::io;
use std::path::{Path, PathBuf};

use axum::Json;
use axum::extract::{Path as UrlPath, State};
use axum::http::StatusCode;
use rusqlite::{OptionalExtension, params};
use serde::{Deserialize, Serialize};

use super::media::visible;
use super::{ApiError, ApiResult, CurrentUser};
use crate::auth::{Role, User};
use crate::server::AppState;

/// The most items one batch request may name.
pub const MAX_BATCH: usize = 10_000;

#[derive(Deserialize)]
pub struct Destination {
    /// The folder to copy or move into.
    folder_id: i64,
    /// Optionally, a new folder to create inside it and use instead.
    #[serde(default)]
    new_folder: Option<String>,
}

/// A selection to copy or move. (Fields listed out rather than flattening
/// `Destination`, which would turn the numbers into strings.)
#[derive(Deserialize)]
pub struct BatchDestination {
    ids: Vec<i64>,
    folder_id: i64,
    #[serde(default)]
    new_folder: Option<String>,
}

/// A selection to trash or delete.
#[derive(Deserialize)]
pub struct Selection {
    pub ids: Vec<i64>,
}

impl Selection {
    pub fn checked(self) -> ApiResult<Vec<i64>> {
        checked_ids(self.ids)
    }
}

fn checked_ids(mut ids: Vec<i64>) -> ApiResult<Vec<i64>> {
    if ids.len() > MAX_BATCH {
        return Err(ApiError::bad_request(format!(
            "Select at most {MAX_BATCH} items at a time."
        )));
    }
    let mut seen = std::collections::HashSet::new();
    ids.retain(|id| seen.insert(*id));
    Ok(ids)
}

#[derive(Serialize, Debug)]
pub struct Placed {
    /// The copy (or the moved item) in the library; `None` if its new folder isn't
    /// indexed (an excluded name, say).
    id: Option<i64>,
    /// Its file name, which gets " (2)" and so on if the name was taken.
    name: String,
    folder_id: Option<i64>,
}

/// One item of a batch that couldn't be done, and why.
#[derive(Serialize, Debug)]
pub struct Failure {
    pub id: i64,
    pub error: String,
}

/// What a batch did: everything that worked (`placed`, for copies and moves, or a
/// count) and what didn't.
#[derive(Serialize, Debug, Default)]
pub struct BatchResult {
    pub done: usize,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub placed: Vec<Placed>,
    pub failed: Vec<Failure>,
}

#[derive(Clone, Copy, PartialEq)]
enum Mode {
    Copy,
    Move,
}

/// Whether PixelGrove may create files in `dir` (false on read-only mounts). One
/// `access(2)` call, so cheap enough for every folder in a listing.
pub fn is_writable(dir: &Path) -> bool {
    rustix::fs::access(dir, rustix::fs::Access::WRITE_OK).is_ok()
}

pub async fn copy(
    State(state): State<AppState>,
    current: CurrentUser,
    UrlPath(id): UrlPath<i64>,
    Json(to): Json<Destination>,
) -> ApiResult<Json<Placed>> {
    single(state, current, id, to, Mode::Copy).await.map(Json)
}

pub async fn move_media(
    State(state): State<AppState>,
    current: CurrentUser,
    UrlPath(id): UrlPath<i64>,
    Json(to): Json<Destination>,
) -> ApiResult<Json<Placed>> {
    single(state, current, id, to, Mode::Move).await.map(Json)
}

pub async fn copy_many(
    State(state): State<AppState>,
    current: CurrentUser,
    Json(to): Json<BatchDestination>,
) -> ApiResult<Json<BatchResult>> {
    many(state, current, to, Mode::Copy).await.map(Json)
}

pub async fn move_many(
    State(state): State<AppState>,
    current: CurrentUser,
    Json(to): Json<BatchDestination>,
) -> ApiResult<Json<BatchResult>> {
    many(state, current, to, Mode::Move).await.map(Json)
}

/// A name for a new folder: one path component, not hidden (the scanner skips those).
fn clean_folder_name(name: &str) -> Result<&str, ApiError> {
    let name = name.trim();
    if name.is_empty()
        || name.starts_with('.')
        || name.contains(['/', '\\', '\0'])
        || name.len() > 255
    {
        return Err(ApiError::bad_request(
            "Folder names can't be empty, start with a dot or contain slashes.",
        ));
    }
    Ok(name)
}

async fn single(
    state: AppState,
    current: CurrentUser,
    id: i64,
    to: Destination,
    mode: Mode,
) -> ApiResult<Placed> {
    current.require(Role::Editor)?;
    let dir = destination(
        &state,
        &current.user,
        to.folder_id,
        to.new_folder.as_deref(),
    )
    .await?;
    let mut done = transfer(&state, &current.user, vec![id], dir, mode).await?;
    match done.pop() {
        Some(Ok(placed)) => Ok(placed),
        Some(Err(err)) => Err(err),
        None => Err(ApiError::status(StatusCode::NOT_FOUND, "Not found.")),
    }
}

async fn many(
    state: AppState,
    current: CurrentUser,
    to: BatchDestination,
    mode: Mode,
) -> ApiResult<BatchResult> {
    current.require(Role::Editor)?;
    let ids = checked_ids(to.ids)?;
    let dir = destination(
        &state,
        &current.user,
        to.folder_id,
        to.new_folder.as_deref(),
    )
    .await?;
    let results = transfer(&state, &current.user, ids.clone(), dir, mode).await?;
    let mut out = BatchResult::default();
    for (id, result) in ids.into_iter().zip(results) {
        match result {
            Ok(placed) => out.placed.push(placed),
            Err(err) => out.failed.push(Failure {
                id,
                error: err.message(),
            }),
        }
    }
    out.done = out.placed.len();
    Ok(out)
}

/// The directory to copy or move into: a folder the user may see, or a new folder
/// inside it.
async fn destination(
    state: &AppState,
    user: &User,
    folder_id: i64,
    new_folder: Option<&str>,
) -> ApiResult<PathBuf> {
    let not_found = || ApiError::status(StatusCode::NOT_FOUND, "That folder isn't in the library.");
    let folder_path: String = state
        .db
        .run(move |conn| {
            Ok(conn
                .query_row("SELECT path FROM folders WHERE id = ?1", [folder_id], |r| {
                    r.get(0)
                })
                .optional()?)
        })
        .await?
        .ok_or_else(not_found)?;
    if !user.can_access(Path::new(&folder_path)) || user.hidden.hides_folder(&folder_path) {
        return Err(not_found());
    }
    let mut dir = PathBuf::from(&folder_path);
    if let Some(name) = new_folder.filter(|n| !n.trim().is_empty()) {
        dir.push(clean_folder_name(name)?);
    }
    Ok(dir)
}

fn io_error(err: io::Error) -> ApiError {
    match err.kind() {
        io::ErrorKind::NotFound => {
            ApiError::status(StatusCode::NOT_FOUND, "The file is no longer on disk.")
        }
        io::ErrorKind::PermissionDenied | io::ErrorKind::ReadOnlyFilesystem => ApiError::status(
            StatusCode::CONFLICT,
            "That folder is read-only, so PixelGrove can't change files in it.",
        ),
        _ => ApiError::Internal(anyhow::Error::new(err).context("copying file")),
    }
}

/// Copies or moves each item into `dir`. One result per id, in order; the folders
/// involved are rescanned once at the end, not once per file.
async fn transfer(
    state: &AppState,
    user: &User,
    ids: Vec<i64>,
    dir: PathBuf,
    mode: Mode,
) -> ApiResult<Vec<ApiResult<Placed>>> {
    // (old id, source, target) for each file that made it.
    let mut placed: Vec<(i64, PathBuf, PathBuf)> = Vec::new();
    let mut results: Vec<Result<usize, ApiError>> = Vec::with_capacity(ids.len());
    for id in ids {
        let m = match visible(state, user.clone(), id).await {
            Ok(m) => m,
            Err(err) => {
                results.push(Err(err));
                continue;
            }
        };
        let source = m.path();
        if mode == Mode::Move && source.parent() == Some(dir.as_path()) {
            results.push(Err(ApiError::bad_request("It's already in that folder.")));
            continue;
        }
        let (from, into) = (source.clone(), dir.clone());
        match tokio::task::spawn_blocking(move || place(&from, &into, mode)).await? {
            Ok(target) => {
                results.push(Ok(placed.len()));
                placed.push((m.id, source, target));
            }
            Err(err) => results.push(Err(io_error(err))),
        }
    }
    // Index the new files first, so moved items' favorites, hidden marks and share
    // links can follow them before the old entries go.
    state
        .indexer
        .rescan(placed.iter().map(|(_, _, t)| t.clone()).collect())
        .await;
    let lookups: Vec<(i64, String, String)> = placed
        .iter()
        .map(|(old, _, target)| {
            (
                *old,
                target
                    .parent()
                    .map(|p| p.to_string_lossy().into_owned())
                    .unwrap_or_default(),
                target
                    .file_name()
                    .map(|n| n.to_string_lossy().into_owned())
                    .unwrap_or_default(),
            )
        })
        .collect();
    let found = state
        .db
        .run(move |conn| {
            let tx = conn.transaction()?;
            let mut found = Vec::with_capacity(lookups.len());
            for (old_id, folder, file) in &lookups {
                let new: Option<(i64, i64)> = tx
                    .query_row(
                        "SELECT m.id, f.id FROM media m JOIN folders f ON f.id = m.folder_id
                         WHERE f.path = ?1 AND m.name = ?2",
                        params![folder, file],
                        |r| Ok((r.get(0)?, r.get(1)?)),
                    )
                    .optional()?;
                if mode == Mode::Move
                    && let Some((new_id, _)) = new
                {
                    tx.execute(
                        "INSERT OR IGNORE INTO favorites (user_id, media_id, created_at)
                         SELECT user_id, ?2, created_at FROM favorites WHERE media_id = ?1",
                        params![old_id, new_id],
                    )?;
                    tx.execute(
                        "INSERT OR IGNORE INTO hidden (user_id, media_id, created_at)
                         SELECT user_id, ?2, created_at FROM hidden WHERE media_id = ?1",
                        params![old_id, new_id],
                    )?;
                    tx.execute(
                        "UPDATE shares SET media_id = ?2 WHERE media_id = ?1",
                        params![old_id, new_id],
                    )?;
                }
                found.push((new, file.clone()));
            }
            tx.commit()?;
            Ok(found)
        })
        .await?;
    if mode == Mode::Move {
        state
            .indexer
            .rescan(placed.iter().map(|(_, s, _)| s.clone()).collect())
            .await;
    }
    Ok(results
        .into_iter()
        .map(|r| {
            r.map(|i| {
                let (new, name) = &found[i];
                Placed {
                    id: new.map(|(id, _)| id),
                    folder_id: new.map(|(_, folder)| folder),
                    name: name.clone(),
                }
            })
        })
        .collect())
}

/// Copies or moves `from` into `dir` (created if needed), under a free name, keeping
/// the modification time (the capture date falls back to it). Returns the new path.
fn place(from: &Path, dir: &Path, mode: Mode) -> io::Result<PathBuf> {
    let name = from
        .file_name()
        .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
    std::fs::create_dir_all(dir)?;
    let target = crate::trash::unused_path(&dir.join(name));
    if mode == Mode::Move {
        match std::fs::rename(from, &target) {
            Ok(()) => return Ok(target),
            // Another volume: copy, then remove the original.
            Err(err) if err.kind() == io::ErrorKind::CrossesDevices => {}
            Err(err) => return Err(err),
        }
    }
    let modified = std::fs::metadata(from)?.modified()?;
    if let Err(err) = std::fs::copy(from, &target)
        .and_then(|_| std::fs::File::options().write(true).open(&target))
        .and_then(|file| file.set_modified(modified))
    {
        let _ = std::fs::remove_file(&target);
        return Err(err);
    }
    if mode == Mode::Move {
        std::fs::remove_file(from)?;
    }
    Ok(target)
}

#[cfg(test)]
mod tests {
    use axum::http::StatusCode;
    use serde_json::json;

    use crate::api::test_support::TestApp;
    use crate::testdata;

    fn library() -> (TestApp, i64) {
        let app = TestApp::new();
        app.write_media("a/x.jpg", &testdata::jpeg_taken_at("2020:01:01 00:00:00"));
        app.write_media("b/y.jpg", &testdata::jpeg_taken_at("2021:01:01 00:00:00"));
        app.scan();
        let id = app.media_id("x.jpg");
        (app, id)
    }

    #[tokio::test]
    async fn copies_keep_the_original_and_its_date() {
        let (app, id) = library();
        let admin = app.setup_admin().await;
        let b = app.folder_id("b");
        let response = app
            .send(
                "POST",
                &format!("/api/media/{id}/copy"),
                Some(json!({"folder_id": b})),
                Some(&admin),
            )
            .await;
        assert_eq!(response.status, StatusCode::OK, "{:?}", response.body);
        assert_eq!(response.body["name"], "x.jpg");
        assert_eq!(response.body["folder_id"], b);
        let copy = response.body["id"].as_i64().unwrap();
        assert_ne!(copy, id);
        assert!(app.media_root().join("a/x.jpg").is_file());
        assert!(app.media_root().join("b/x.jpg").is_file());
        let original = std::fs::metadata(app.media_root().join("a/x.jpg")).unwrap();
        let copied = std::fs::metadata(app.media_root().join("b/x.jpg")).unwrap();
        assert_eq!(original.modified().unwrap(), copied.modified().unwrap());

        // A second copy into the same folder gets a free name.
        let again = app
            .send(
                "POST",
                &format!("/api/media/{id}/copy"),
                Some(json!({"folder_id": b})),
                Some(&admin),
            )
            .await;
        assert_eq!(again.body["name"], "x (2).jpg");
        let folder = app
            .send("GET", &format!("/api/folders/{b}"), None, Some(&admin))
            .await;
        assert_eq!(folder.body["media_count"], 3);
    }

    #[tokio::test]
    async fn moves_carry_favorites_and_can_make_a_folder() {
        let (app, id) = library();
        let admin = app.setup_admin().await;
        app.send(
            "PUT",
            &format!("/api/media/{id}/favorite"),
            None,
            Some(&admin),
        )
        .await;
        let b = app.folder_id("b");
        let response = app
            .send(
                "POST",
                &format!("/api/media/{id}/move"),
                Some(json!({"folder_id": b, "new_folder": "Holiday"})),
                Some(&admin),
            )
            .await;
        assert_eq!(response.status, StatusCode::OK, "{:?}", response.body);
        let moved = response.body["id"].as_i64().unwrap();
        assert!(!app.media_root().join("a/x.jpg").exists());
        assert!(app.media_root().join("b/Holiday/x.jpg").is_file());
        let detail = app
            .send("GET", &format!("/api/media/{moved}"), None, Some(&admin))
            .await;
        assert_eq!(detail.body["favorite"], true);
        let old = app
            .send("GET", &format!("/api/media/{id}"), None, Some(&admin))
            .await;
        assert_eq!(old.status, StatusCode::NOT_FOUND);
        let a = app.folder_id("a");
        let folder = app
            .send("GET", &format!("/api/folders/{a}"), None, Some(&admin))
            .await;
        assert_eq!(folder.body["media_count"], 0);

        let same = app
            .send(
                "POST",
                &format!("/api/media/{moved}/move"),
                Some(json!({"folder_id": app.folder_id("b/Holiday")})),
                Some(&admin),
            )
            .await;
        assert_eq!(same.status, StatusCode::BAD_REQUEST);
        let bad_name = app
            .send(
                "POST",
                &format!("/api/media/{moved}/copy"),
                Some(json!({"folder_id": b, "new_folder": "../escape"})),
                Some(&admin),
            )
            .await;
        assert_eq!(bad_name.status, StatusCode::BAD_REQUEST);
    }

    #[tokio::test]
    async fn needs_an_editor_and_a_folder_in_scope() {
        let (app, id) = library();
        let admin = app.setup_admin().await;
        let viewer = app.user_session(&admin, "viewer", "viewer").await;
        let b = app.folder_id("b");
        let response = app
            .send(
                "POST",
                &format!("/api/media/{id}/copy"),
                Some(json!({"folder_id": b})),
                Some(&viewer),
            )
            .await;
        assert_eq!(response.status, StatusCode::FORBIDDEN);
        let missing = app
            .send(
                "POST",
                &format!("/api/media/{id}/copy"),
                Some(json!({"folder_id": 9999})),
                Some(&admin),
            )
            .await;
        assert_eq!(missing.status, StatusCode::NOT_FOUND);
        app.send(
            "PUT",
            &format!("/api/folders/{b}/hidden"),
            None,
            Some(&admin),
        )
        .await;
        let hidden = app
            .send(
                "POST",
                &format!("/api/media/{id}/copy"),
                Some(json!({"folder_id": b})),
                Some(&admin),
            )
            .await;
        assert_eq!(
            hidden.status,
            StatusCode::NOT_FOUND,
            "hidden folders aren't destinations"
        );
    }

    #[tokio::test]
    async fn batches_copy_move_trash_and_delete_selections() {
        let app = TestApp::new();
        for name in ["p1", "p2", "p3", "p4"] {
            app.write_media(
                &format!("a/{name}.jpg"),
                &testdata::jpeg_taken_at("2020:01:01 00:00:00"),
            );
        }
        app.write_media("b/y.jpg", &testdata::jpeg_taken_at("2021:01:01 00:00:00"));
        app.scan();
        let admin = app.setup_admin().await;
        let ids: Vec<i64> = ["p1.jpg", "p2.jpg", "p3.jpg", "p4.jpg"]
            .iter()
            .map(|n| app.media_id(n))
            .collect();
        let b = app.folder_id("b");

        let folder = app
            .send("GET", &format!("/api/folders/{b}"), None, Some(&admin))
            .await;
        assert_eq!(folder.body["writable"], true);
        let status = app
            .send("GET", "/api/library/status", None, Some(&admin))
            .await;
        assert_eq!(status.body["writable"], true);

        // Copy two (plus an unknown id, reported, not fatal).
        let copied = app
            .send(
                "POST",
                "/api/media/copy",
                Some(json!({"ids": [ids[0], ids[1], 9999], "folder_id": b})),
                Some(&admin),
            )
            .await;
        assert_eq!(copied.status, StatusCode::OK, "{:?}", copied.body);
        assert_eq!(copied.body["done"], 2);
        assert_eq!(copied.body["failed"][0]["id"], 9999);
        assert!(app.media_root().join("b/p1.jpg").is_file());
        assert!(app.media_root().join("a/p1.jpg").is_file());

        // Move two into a new folder.
        let moved = app
            .send(
                "POST",
                "/api/media/move",
                Some(json!({"ids": [ids[2], ids[3]], "folder_id": b, "new_folder": "Sub"})),
                Some(&admin),
            )
            .await;
        assert_eq!(moved.body["done"], 2, "{:?}", moved.body);
        assert!(app.media_root().join("b/Sub/p3.jpg").is_file());
        assert!(!app.media_root().join("a/p3.jpg").exists());

        // Trash one, delete another for good.
        let trashed = app
            .send(
                "POST",
                "/api/media/trash",
                Some(json!({"ids": [ids[0]]})),
                Some(&admin),
            )
            .await;
        assert_eq!(trashed.body["done"], 1, "{:?}", trashed.body);
        let deleted = app
            .send(
                "POST",
                "/api/media/delete",
                Some(json!({"ids": [ids[1]]})),
                Some(&admin),
            )
            .await;
        assert_eq!(deleted.body["done"], 1, "{:?}", deleted.body);
        assert!(!app.media_root().join("a/p2.jpg").exists());
        let trash = app.send("GET", "/api/trash", None, Some(&admin)).await;
        assert_eq!(trash.body["total"], 1, "deleted items skip the trash");
        let a = app.folder_id("a");
        let folder = app
            .send("GET", &format!("/api/folders/{a}"), None, Some(&admin))
            .await;
        assert_eq!(folder.body["media_count"], 0);

        // A single item can be deleted too; viewers can't.
        let y = app.media_id("y.jpg");
        let viewer = app.user_session(&admin, "viewer", "viewer").await;
        let refused = app
            .send("DELETE", &format!("/api/media/{y}"), None, Some(&viewer))
            .await;
        assert_eq!(refused.status, StatusCode::FORBIDDEN);
        let gone = app
            .send("DELETE", &format!("/api/media/{y}"), None, Some(&admin))
            .await;
        assert_eq!(gone.status, StatusCode::NO_CONTENT);
        assert!(!app.media_root().join("b/y.jpg").exists());
    }

    #[tokio::test]
    async fn rescanning_a_folder_picks_up_new_files() {
        let (app, _) = library();
        let admin = app.setup_admin().await;
        let viewer = app.user_session(&admin, "viewer", "viewer").await;
        app.write_media("a/new.jpg", &testdata::jpeg_taken_at("2022:01:01 00:00:00"));
        let a = app.folder_id("a");
        let response = app
            .send(
                "POST",
                &format!("/api/folders/{a}/rescan"),
                None,
                Some(&viewer),
            )
            .await;
        assert_eq!(response.status, StatusCode::NO_CONTENT);
        let folder = app
            .send("GET", &format!("/api/folders/{a}"), None, Some(&viewer))
            .await;
        assert_eq!(folder.body["media_count"], 2);
        let missing = app
            .send("POST", "/api/folders/9999/rescan", None, Some(&viewer))
            .await;
        assert_eq!(missing.status, StatusCode::NOT_FOUND);
    }
}
