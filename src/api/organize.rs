//! Copying and moving a photo or video to another folder of the library.

use std::io;
use std::path::{Path, PathBuf};

use axum::Json;
use axum::extract::{Path as UrlPath, State};
use axum::http::StatusCode;
use rusqlite::{OptionalExtension, params};
use serde::{Deserialize, Serialize};

use super::media::visible;
use super::{ApiError, ApiResult, CurrentUser};
use crate::auth::Role;
use crate::server::AppState;

#[derive(Deserialize)]
pub struct Destination {
    /// The folder to copy or move into.
    folder_id: i64,
    /// Optionally, a new folder to create inside it and use instead.
    #[serde(default)]
    new_folder: Option<String>,
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

#[derive(Clone, Copy, PartialEq)]
enum Mode {
    Copy,
    Move,
}

pub async fn copy(
    State(state): State<AppState>,
    current: CurrentUser,
    UrlPath(id): UrlPath<i64>,
    Json(to): Json<Destination>,
) -> ApiResult<Json<Placed>> {
    transfer(state, current, id, to, Mode::Copy).await.map(Json)
}

pub async fn move_media(
    State(state): State<AppState>,
    current: CurrentUser,
    UrlPath(id): UrlPath<i64>,
    Json(to): Json<Destination>,
) -> ApiResult<Json<Placed>> {
    transfer(state, current, id, to, Mode::Move).await.map(Json)
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

async fn transfer(
    state: AppState,
    current: CurrentUser,
    id: i64,
    to: Destination,
    mode: Mode,
) -> ApiResult<Placed> {
    current.require(Role::Editor)?;
    let user = current.user;
    let m = visible(&state, user.clone(), id).await?;
    let not_found = || ApiError::status(StatusCode::NOT_FOUND, "That folder isn't in the library.");

    let folder_path: String = state
        .db
        .run(move |conn| {
            Ok(conn
                .query_row(
                    "SELECT path FROM folders WHERE id = ?1",
                    [to.folder_id],
                    |r| r.get(0),
                )
                .optional()?)
        })
        .await?
        .ok_or_else(not_found)?;
    if !user.can_access(Path::new(&folder_path)) || user.hidden.hides_folder(&folder_path) {
        return Err(not_found());
    }
    let mut dir = PathBuf::from(&folder_path);
    if let Some(name) = &to.new_folder {
        dir.push(clean_folder_name(name)?);
    }
    let source = m.path();
    if mode == Mode::Move && source.parent() == Some(dir.as_path()) {
        return Err(ApiError::bad_request("It's already in that folder."));
    }

    let from = source.clone();
    let target = tokio::task::spawn_blocking(move || place(&from, &dir, mode)).await?;
    let target = target.map_err(|err| match err.kind() {
        io::ErrorKind::NotFound => {
            ApiError::status(StatusCode::NOT_FOUND, "The file is no longer on disk.")
        }
        io::ErrorKind::PermissionDenied | io::ErrorKind::ReadOnlyFilesystem => ApiError::status(
            StatusCode::CONFLICT,
            "That folder is read-only, so PixelGrove can't put files in it.",
        ),
        _ => ApiError::Internal(anyhow::Error::new(err).context("copying file")),
    })?;

    // Index the new file first, so a moved item's favorites, hidden mark and share
    // links can follow it before the old entry goes.
    state.indexer.rescan(vec![target.clone()]).await;
    let name = target
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let (folder, file) = (
        target
            .parent()
            .map(|p| p.to_string_lossy().into_owned())
            .unwrap_or_default(),
        name.clone(),
    );
    let old_id = m.id;
    let placed = state
        .db
        .run(move |conn| {
            let new: Option<(i64, i64)> = conn
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
                let tx = conn.transaction()?;
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
                tx.commit()?;
            }
            Ok(new)
        })
        .await?;
    if mode == Mode::Move {
        state.indexer.rescan(vec![source]).await;
    }
    Ok(Placed {
        id: placed.map(|(id, _)| id),
        folder_id: placed.map(|(_, folder)| folder),
        name,
    })
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
}
