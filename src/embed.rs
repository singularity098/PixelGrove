//! PixelGrove inside another app. The PixelG Android app runs this same library engine
//! in its own process: the same database, scanner, watcher, previews, trash and JSON
//! API, called directly (no HTTP server and no network port), as the one local
//! administrator. The app draws its own native interface on top.
//!
//! Differences from the server: thumbnails are always cached on disk, scans aren't
//! started on their own (the app asks for the folders that changed, see
//! [`Library::rescan`]), and HEIC is indexed (the app decodes what the engine can't,
//! such as HEIC and video frames, and hands the result back with
//! [`Library::store_thumbnail`]).

use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use anyhow::{Context, Result};
use axum::Router;
use axum::body::Body;
use axum::http::{Request, header};
use bytes::Bytes;
use rusqlite::OptionalExtension;
use tower::ServiceExt;

use crate::config::Config;
use crate::db::{self, Db};
use crate::indexer::Indexer;
use crate::preview::{Key, Preview, Previews, Variant};
use crate::server::AppState;
use crate::thumbcache::DiskCache;

pub struct Options {
    /// Holds the database.
    pub data_dir: PathBuf,
    /// The thumbnail cache (an app cache directory, which the system may clear).
    pub thumbnail_dir: PathBuf,
    pub thumbnail_cache_mb: u64,
    /// The in-memory preview cache.
    pub preview_cache_mb: u64,
    /// Media folders to start with, the first time (later they're managed through
    /// `/api/settings/roots`). Ones that don't exist are skipped.
    pub default_roots: Vec<PathBuf>,
}

/// What [`Library::thumbnail`] found.
pub enum Thumbnail {
    Jpeg(Bytes),
    /// Not something the engine decodes (HEIC, video without ffmpeg) or small enough to
    /// show as it is: the app should decode the file itself.
    Platform(PathBuf),
}

pub struct Library {
    runtime: tokio::runtime::Runtime,
    router: Router,
    state: AppState,
    /// The local administrator's session.
    cookie: String,
}

const LOCAL_USER: &str = "owner";

impl Library {
    pub fn start(options: Options) -> Result<Self> {
        crate::metadata::enable_platform_formats();
        std::fs::create_dir_all(&options.data_dir)
            .with_context(|| format!("creating {}", options.data_dir.display()))?;
        let mut config = Config::with_config_dir(options.data_dir.clone());
        config.media_roots = Vec::new();
        config.scan_on_start = false;
        config.preview_cache_mb = options.preview_cache_mb;
        config.thumbnail_cache = Some(options.thumbnail_dir.clone());
        config.thumbnail_cache_mb = options.thumbnail_cache_mb;
        config.ffmpeg = "off".into();
        config.video_preload = 0;

        let runtime = tokio::runtime::Builder::new_multi_thread()
            .thread_name("pixelgrove")
            .enable_all()
            .build()?;
        let db = Db::open(&options.data_dir.join(db::DB_FILE_NAME))?;
        let (cookie, first_run) = local_session(&db)?;
        if first_run {
            let roots: Vec<PathBuf> = options
                .default_roots
                .into_iter()
                .filter(|p| p.is_dir())
                .collect();
            crate::indexer::store_extra_roots(&db, &roots)?;
        }
        if let Err(err) = crate::scanner::backfill_places(&db) {
            tracing::warn!("adding place names failed: {err:#}");
        }
        let disk = DiskCache::open(
            &options.thumbnail_dir,
            options.thumbnail_cache_mb * 1024 * 1024,
        )?;
        let previews = Arc::new(
            Previews::new(options.preview_cache_mb as usize * 1024 * 1024)
                .with_disk_cache(Some(disk)),
        );
        let state = runtime.block_on(async {
            let indexer = Indexer::new(db.clone(), &config)?;
            indexer.start();
            if first_run {
                let first = indexer.clone();
                tokio::spawn(async move { first.full_scan().await });
            }
            anyhow::Ok(AppState {
                config: Arc::new(config),
                db: db.clone(),
                indexer,
                login_throttle: Default::default(),
                previews: previews.clone(),
                locations: Default::default(),
            })
        })?;
        crate::thumbcache::spawn_warmup(db, previews);
        Ok(Self {
            router: crate::server::router(state.clone()),
            runtime,
            state,
            cookie,
        })
    }

    /// Calls the JSON API (`uri` starts with `/api/`) as the local administrator.
    /// Returns the status code and the body.
    pub fn request(&self, method: &str, uri: &str, body: Option<Vec<u8>>) -> Result<(u16, Bytes)> {
        let mut request = Request::builder()
            .method(method)
            .uri(uri)
            .header(header::COOKIE, &self.cookie)
            .header(crate::api::CSRF_HEADER, "1");
        let body = match body {
            Some(body) => {
                request = request.header(header::CONTENT_TYPE, "application/json");
                Body::from(body)
            }
            None => Body::empty(),
        };
        let request = request.body(body)?;
        self.runtime.block_on(async {
            let response = self.router.clone().oneshot(request).await?;
            let status = response.status().as_u16();
            let bytes = axum::body::to_bytes(response.into_body(), usize::MAX).await?;
            Ok((status, bytes))
        })
    }

    /// A photo's or video's preview (`variant`: "thumb" or "large"), or a trashed
    /// item's with `trash`. `None` if there's no such item.
    pub fn thumbnail(&self, id: i64, variant: &str, trash: bool) -> Result<Option<Thumbnail>> {
        let variant = Variant::parse(variant).context("unknown preview size")?;
        let Some(file) = self.file(id, trash)? else {
            return Ok(None);
        };
        let key = file.key(variant);
        if file.kind == "video" {
            return Ok(Some(match self.state.previews.cached(&key, &file.path) {
                Some(jpeg) => Thumbnail::Jpeg(jpeg),
                None => Thumbnail::Platform(file.path),
            }));
        }
        let preview =
            self.runtime
                .block_on(self.state.previews.get(key, &file.path, file.orientation));
        Ok(Some(match preview {
            Ok(Preview::Jpeg(jpeg)) => Thumbnail::Jpeg(jpeg),
            Ok(Preview::Original) => Thumbnail::Platform(file.path),
            Err(err) => {
                tracing::debug!(path = %file.path.display(), "preview failed: {err:#}");
                Thumbnail::Platform(file.path)
            }
        }))
    }

    /// Keeps a preview the app made itself (see [`Thumbnail::Platform`]).
    pub fn store_thumbnail(
        &self,
        id: i64,
        variant: &str,
        trash: bool,
        jpeg: Vec<u8>,
    ) -> Result<()> {
        let variant = Variant::parse(variant).context("unknown preview size")?;
        if let Some(file) = self.file(id, trash)? {
            self.state
                .previews
                .store(file.key(variant), &file.path, Bytes::from(jpeg));
        }
        Ok(())
    }

    /// Where an item (or a trashed item) is on disk.
    pub fn path(&self, id: i64, trash: bool) -> Result<Option<PathBuf>> {
        Ok(self.file(id, trash)?.map(|f| f.path))
    }

    /// Rescans the folders holding `paths` (files or folders) in the background: what
    /// the app found changed through MediaStore, or files it just wrote.
    pub fn rescan(&self, paths: Vec<PathBuf>) {
        let indexer = self.state.indexer.clone();
        self.runtime
            .spawn(async move { indexer.rescan(paths).await });
    }

    /// Goes up whenever a scan changed the library (see `IndexStatus::generation`).
    pub fn generation(&self) -> u64 {
        self.state.indexer.status().generation
    }

    /// Rescans every media folder in the background (unless a scan is running).
    pub fn full_scan(&self) {
        if self.state.indexer.status().scanning {
            return;
        }
        let indexer = self.state.indexer.clone();
        self.runtime.spawn(async move { indexer.full_scan().await });
    }

    /// Writes a copy of an item without its location data to `out` (for sharing it
    /// with another app), the same way share links serve files.
    pub fn copy_without_location(&self, id: i64, out: &Path) -> Result<()> {
        let file = self.file(id, false)?.context("no such item")?;
        let patches = crate::location::find(&file.path, &file.kind)?;
        let mut from = std::fs::File::open(&file.path)?;
        let mut to = std::fs::File::create(out)?;
        let mut buffer = vec![0; 1 << 20];
        let mut offset = 0;
        loop {
            let n = from.read(&mut buffer)?;
            if n == 0 {
                break;
            }
            let chunk = Bytes::copy_from_slice(&buffer[..n]);
            to.write_all(&crate::location::apply(&patches, offset, chunk))?;
            offset += n as u64;
        }
        Ok(())
    }

    fn file(&self, id: i64, trash: bool) -> Result<Option<FileInfo>> {
        let sql = if trash {
            "SELECT -id, trash_path, kind, mtime, size, orientation FROM trash WHERE id = ?1"
        } else {
            "SELECT m.id, f.path || '/' || m.name, m.kind, m.mtime, m.size, m.orientation
             FROM media m JOIN folders f ON f.id = m.folder_id WHERE m.id = ?1"
        };
        Ok(self
            .state
            .db
            .conn()?
            .query_row(sql, [id], |r| {
                Ok(FileInfo {
                    cache_id: r.get(0)?,
                    path: PathBuf::from(r.get::<_, String>(1)?),
                    kind: r.get(2)?,
                    mtime: r.get(3)?,
                    size: r.get(4)?,
                    orientation: r.get(5)?,
                })
            })
            .optional()?)
    }
}

struct FileInfo {
    /// Trashed items use negative ids in the preview cache.
    cache_id: i64,
    path: PathBuf,
    kind: String,
    mtime: i64,
    size: i64,
    orientation: Option<u16>,
}

impl FileInfo {
    fn key(&self, variant: Variant) -> Key {
        Key {
            media_id: self.cache_id,
            variant,
            mtime: self.mtime,
            size: self.size,
        }
    }
}

/// Signs in the local administrator (creating it the first time, when it returns
/// `true` too) with a fresh session, ending any earlier ones.
fn local_session(db: &Db) -> Result<(String, bool)> {
    let mut conn = db.conn()?;
    let mut first_run = false;
    let admin: Option<i64> = conn
        .query_row(
            "SELECT id FROM users WHERE role = 'admin' AND NOT disabled ORDER BY id LIMIT 1",
            [],
            |r| r.get(0),
        )
        .optional()?;
    let user_id = match admin {
        Some(id) => id,
        None => {
            // Nobody signs in with it: the app holds the session. A random password
            // keeps the account unusable for anything else.
            let password = crate::auth::new_token()?;
            first_run = true;
            crate::auth::create_first_admin(&mut conn, LOCAL_USER, &password)?
                .context("creating the local account")?
                .id
        }
    };
    let token = crate::auth::new_token()?;
    crate::auth::create_session(&conn, user_id, &token)?;
    crate::auth::delete_other_sessions(&conn, user_id, &token)?;
    Ok((format!("{}={token}", crate::api::SESSION_COOKIE), first_run))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testdata;

    #[test]
    fn runs_the_library_in_process() {
        let data = tempfile::tempdir().unwrap();
        let media = tempfile::tempdir().unwrap();
        let camera = media.path().join("DCIM");
        std::fs::create_dir_all(camera.join("Camera")).unwrap();
        std::fs::write(
            camera.join("Camera/a.jpg"),
            testdata::jpeg_at_location("2024:05:01 10:00:00", 48.8584, 2.2945),
        )
        .unwrap();
        std::fs::write(camera.join("Camera/b.heic"), b"not decodable here").unwrap();
        let options = || Options {
            data_dir: data.path().join("db"),
            thumbnail_dir: data.path().join("thumbs"),
            thumbnail_cache_mb: 16,
            preview_cache_mb: 16,
            default_roots: vec![camera.clone(), media.path().join("Missing")],
        };
        let library = Library::start(options()).unwrap();
        // The first start indexes the default folders in the background.
        let mut status = String::new();
        for _ in 0..200 {
            let (code, body) = library.request("GET", "/api/library/status", None).unwrap();
            assert_eq!(code, 200);
            status = String::from_utf8_lossy(&body).into_owned();
            if status.contains("\"images\":2") && status.contains("\"scanning\":false") {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(20));
        }
        assert!(status.contains("\"images\":2"), "{status}");

        let (code, roots) = library.request("GET", "/api/settings/roots", None).unwrap();
        assert_eq!(code, 200);
        let roots: serde_json::Value = serde_json::from_slice(&roots).unwrap();
        assert_eq!(
            roots.as_array().unwrap().len(),
            1,
            "missing folders are skipped"
        );

        let id_of = |name: &str| -> i64 {
            library
                .state
                .db
                .conn()
                .unwrap()
                .query_row("SELECT id FROM media WHERE name = ?1", [name], |r| r.get(0))
                .unwrap()
        };
        let (a, heic) = (id_of("a.jpg"), id_of("b.heic"));
        assert!(matches!(
            library.thumbnail(heic, "thumb", false).unwrap(),
            Some(Thumbnail::Platform(_))
        ));
        library
            .store_thumbnail(heic, "thumb", false, b"made by the app".to_vec())
            .unwrap();
        match library.thumbnail(heic, "thumb", false).unwrap() {
            Some(Thumbnail::Jpeg(jpeg)) => assert_eq!(jpeg, &b"made by the app"[..]),
            _ => panic!("stored thumbnail not used"),
        }
        assert!(library.thumbnail(9999, "thumb", false).unwrap().is_none());
        assert!(
            library
                .path(a, false)
                .unwrap()
                .unwrap()
                .ends_with("Camera/a.jpg")
        );

        let out = data.path().join("shared.jpg");
        library.copy_without_location(a, &out).unwrap();
        let original = std::fs::read(camera.join("Camera/a.jpg")).unwrap();
        let shared = std::fs::read(&out).unwrap();
        assert_eq!(original.len(), shared.len());
        assert_ne!(original, shared, "GPS was blanked");
        drop(library);

        // Later starts keep the same account, folders and stored thumbnails.
        let again = Library::start(options()).unwrap();
        let (code, _) = again.request("GET", "/api/albums", None).unwrap();
        assert_eq!(code, 200);
        assert!(matches!(
            again.thumbnail(heic, "thumb", false).unwrap(),
            Some(Thumbnail::Jpeg(_))
        ));
    }
}
