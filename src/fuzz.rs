//! Entry points for the fuzz targets in `fuzz/` (built only with `--cfg fuzzing`, which
//! `cargo fuzz` sets). Each takes raw fuzzer bytes, runs them through the same code a
//! photo on disk or an HTTP request would reach, and panics if an invariant breaks.
//!
//! Panics that `guard::contain` catches are survivable in the server, so they don't
//! fail a run; set `PIXELGROVE_FUZZ_STRICT=1` to have them fail it too (to hunt for
//! bugs in the decoders themselves).
//!
//! Inputs that the server reads from a file are written to a scratch file first (in
//! `/dev/shm` when there is one), so the real path-based code is what gets exercised.

use std::path::{Path, PathBuf};
use std::sync::{Arc, LazyLock};

use bytes::Bytes;

use crate::location::{self, Patch};
use crate::metadata;
use crate::preview::{self, Variant};

/// Keeps libFuzzer's abort-on-panic hook for panics outside `guard::contain`.
fn init() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        if std::env::var_os("PIXELGROVE_FUZZ_STRICT").is_some() {
            return;
        }
        let abort = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            if !crate::guard::fuzzing::contained() {
                abort(info);
            }
        }));
    });
}

fn scratch_dir() -> &'static Path {
    static DIR: LazyLock<PathBuf> = LazyLock::new(|| {
        let base = Path::new("/dev/shm");
        let base = if base.is_dir() {
            base.to_path_buf()
        } else {
            std::env::temp_dir()
        };
        let dir = base.join(format!("pixelgrove-fuzz-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    });
    &DIR
}

/// Writes `data` to a scratch file named `input.<ext>` and returns its path.
fn scratch_file(data: &[u8], ext: &str) -> PathBuf {
    let path = scratch_dir().join(format!("input.{ext}"));
    std::fs::write(&path, data).unwrap();
    path
}

/// File extensions the server handles, picked by the input's first byte.
const EXTENSIONS: &[&str] = &[
    "jpg", "png", "webp", "gif", "avif", "jxl", "mp4", "mov", "m4v", "webm", "mkv",
];

fn split_ext(data: &[u8]) -> Option<(&'static str, &[u8])> {
    let (&first, rest) = data.split_first()?;
    Some((EXTENSIONS[first as usize % EXTENSIONS.len()], rest))
}

// ---- Location removal (share links) ----

/// Finds the location patches for a file and checks they are safe to lay over it:
/// inside the file (patches never change its length) and the same whether the file
/// is sent whole or in arbitrary ranges.
pub fn location(data: &[u8]) {
    init();
    let Some((&first, file)) = data.split_first() else {
        return;
    };
    let kind = if first & 1 == 0 { "image" } else { "video" };
    let path = scratch_file(file, if kind == "image" { "jpg" } else { "mp4" });
    let Ok(patches) = location::find(&path, kind) else {
        return;
    };
    let len = file.len() as u64;
    for patch in &patches {
        assert!(
            patch.offset + patch.bytes.len() as u64 <= len,
            "patch {}+{} outside a {len}-byte file",
            patch.offset,
            patch.bytes.len()
        );
    }
    assert!(patches.is_sorted_by_key(|p| p.offset));
    let whole = location::apply(&patches, 0, Bytes::copy_from_slice(file));
    assert_eq!(whole.len(), file.len());
    // Ranges cut at points the input chooses (its first bytes double as cut sizes).
    let mut pieces = Vec::with_capacity(file.len());
    let mut offset = 0;
    let mut sizes = file.iter().map(|&b| usize::from(b) * 7 + 1).cycle();
    while offset < file.len() {
        let end = (offset + sizes.next().unwrap()).min(file.len());
        let chunk = Bytes::copy_from_slice(&file[offset..end]);
        pieces.extend_from_slice(&location::apply(&patches, offset as u64, chunk));
        offset = end;
    }
    assert_eq!(pieces, whole, "ranges and the whole file disagree");
}

/// `location::apply` against a byte-by-byte model, with arbitrary sorted patches.
pub fn location_apply(data: &[u8]) {
    init();
    let Some((header, rest)) = data.split_at_checked(4) else {
        return;
    };
    let file_len = usize::from(u16::from_le_bytes([header[0], header[1]])) % 4096;
    let offset = (usize::from(header[2]) * 17) % (file_len + 1);
    let chunk_len = (usize::from(header[3]) * 13).min(file_len - offset);
    // Each patch: 2 bytes of offset, 1 byte of length.
    let mut patches: Vec<Patch> = rest
        .chunks_exact(3)
        .map(|p| {
            let at = usize::from(u16::from_le_bytes([p[0], p[1]])) % (file_len + 1);
            let len = usize::from(p[2]).min(file_len - at);
            Patch {
                offset: at as u64,
                bytes: vec![!p[2]; len],
            }
        })
        .collect();
    patches.sort_by_key(|p| p.offset);
    let file: Vec<u8> = (0..file_len).map(|i| (i % 251) as u8).collect();
    let mut model = file.clone();
    for patch in &patches {
        let at = patch.offset as usize;
        model[at..at + patch.bytes.len()].copy_from_slice(&patch.bytes);
    }
    let chunk = Bytes::copy_from_slice(&file[offset..offset + chunk_len]);
    let got = location::apply(&patches, offset as u64, chunk);
    assert_eq!(&got[..], &model[offset..offset + chunk_len]);
}

// ---- Reading files: JPEG XL boxes, metadata, previews ----

/// The JPEG XL box walkers and header reader.
pub fn jxl(data: &[u8]) {
    init();
    let _ = crate::jxl::metadata_boxes(data);
    let path = scratch_file(data, "jxl");
    let _ = crate::jxl::exif(&path);
    let _ = crate::jxl::dimensions(&path);
}

/// What the scanner reads from every file: EXIF, track metadata and sizes.
pub fn metadata(data: &[u8]) {
    init();
    let Some((ext, file)) = split_ext(data) else {
        return;
    };
    let path = scratch_file(file, ext);
    let kind = metadata::kind_for_path(&path).unwrap();
    let mut parser = nom_exif::MediaParser::new();
    let info = metadata::read(&mut parser, &path, kind);
    if let (Some(lat), Some(lon)) = (info.latitude, info.longitude) {
        let _ = crate::places::lookup(lat, lon);
    }
}

/// Preview rendering for the decoded image formats.
pub fn preview(data: &[u8]) {
    init();
    let Some((&first, file)) = data.split_first() else {
        return;
    };
    let ext = ["jpg", "png", "webp", "gif", "jxl"][usize::from(first & 7) % 5];
    let variant = if first & 8 == 0 {
        Variant::Thumb
    } else {
        Variant::Large
    };
    let orientation = u16::from(first >> 4) % 9;
    let path = scratch_file(file, ext);
    if let Ok(Some(jpeg)) = preview::render(&path, variant, orientation) {
        assert!(jpeg.starts_with(&[0xFF, 0xD8]), "preview is not a JPEG");
    }
}

// ---- HTTP API ----

/// A small library and accounts, set up once; every request runs against a fresh
/// copy so one request's changes (trash, deleted users) don't leak into the next.
struct Template {
    admin: String,
    viewer: String,
    share: String,
}

/// Where every HTTP run happens. Runs reuse the template's directory, so the paths
/// stored in its database stay valid.
fn run_dir() -> PathBuf {
    scratch_dir().join("run")
}

fn template_db() -> PathBuf {
    scratch_dir().join("template.db")
}

static RUNTIME: LazyLock<tokio::runtime::Runtime> =
    LazyLock::new(|| tokio::runtime::Runtime::new().unwrap());

static TEMPLATE: LazyLock<Template> = LazyLock::new(|| RUNTIME.block_on(build_template()));

/// The test images every HTTP run starts with, relative to the media root.
fn template_media() -> Vec<(&'static str, Vec<u8>)> {
    vec![
        ("a.png", PNG_3X2.to_vec()),
        ("trip/b.png", PNG_3X2.to_vec()),
        ("trip/day 2/c.png", PNG_3X2.to_vec()),
        (
            "hidden/d.jxl",
            include_bytes!("../tests/fixtures/alpha.jxl").to_vec(),
        ),
    ]
}

const PNG_3X2: &[u8] = &[
    0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, b'I', b'H', b'D', b'R', 0, 0, 0,
    3, 0, 0, 0, 2, 8, 2, 0, 0, 0, 0x12, 0x16, 0xF1, 0x4D, 0, 0, 0, 0, b'I', b'E', b'N', b'D', 0xAE,
    0x42, 0x60, 0x82,
];

fn write_media(root: &Path) {
    for (relative, bytes) in template_media() {
        let path = root.join(relative);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, bytes).unwrap();
    }
}

struct App {
    router: axum::Router,
    db: crate::db::Db,
}

fn app(dir: &Path) -> App {
    let media = dir.join("media");
    let mut config = crate::config::Config::with_config_dir(dir.to_path_buf());
    config.media_roots = vec![media];
    let db = crate::db::Db::open(&dir.join("t.db")).unwrap();
    let indexer = crate::indexer::Indexer::new(db.clone(), &config).unwrap();
    let state = crate::server::AppState {
        config: Arc::new(config),
        db: db.clone(),
        indexer,
        login_throttle: Default::default(),
        previews: Arc::new(preview::Previews::new(1 << 20)),
        locations: Default::default(),
    };
    App {
        router: crate::server::router(state),
        db,
    }
}

async fn send(
    router: &axum::Router,
    method: &str,
    uri: &str,
    cookie: Option<&str>,
    body: Vec<u8>,
) -> (axum::http::StatusCode, Bytes, Option<String>) {
    use tower::ServiceExt;
    let mut request = axum::http::Request::builder()
        .method(method)
        .uri(uri)
        .header(crate::api::CSRF_HEADER, "1")
        .header(axum::http::header::CONTENT_TYPE, "application/json");
    if let Some(cookie) = cookie {
        request = request.header(axum::http::header::COOKIE, cookie);
    }
    let Ok(request) = request.body(axum::body::Body::from(body)) else {
        return (axum::http::StatusCode::BAD_REQUEST, Bytes::new(), None);
    };
    let response = router.clone().oneshot(request).await.unwrap();
    let status = response.status();
    let cookie = response
        .headers()
        .get(axum::http::header::SET_COOKIE)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.split(';').next())
        .map(str::to_string);
    let body = axum::body::to_bytes(response.into_body(), usize::MAX)
        .await
        .unwrap();
    (status, body, cookie)
}

async fn build_template() -> Template {
    let dir = run_dir();
    let _ = std::fs::remove_dir_all(&dir);
    write_media(&dir.join("media"));
    let App { router, db } = app(&dir);
    let root = dir.join("media");
    let roots = crate::scanner::sync_roots(&db, std::slice::from_ref(&root)).unwrap();
    let excludes = crate::scanner::Excludes::new(&[]).unwrap();
    crate::scanner::scan(&db, roots[0].0, &root, &root, &excludes).unwrap();

    let json = |v: serde_json::Value| v.to_string().into_bytes();
    let (_, _, admin) = send(
        &router,
        "POST",
        "/api/setup",
        None,
        json(serde_json::json!({"username": "admin", "password": "admin-password"})),
    )
    .await;
    let admin = admin.expect("setup gave no session");
    let (status, ..) = send(
        &router,
        "POST",
        "/api/users",
        Some(&admin),
        json(serde_json::json!({
            "username": "viewer", "password": "viewer-password", "role": "viewer",
            "folders": [root.join("trip").to_str().unwrap()]
        })),
    )
    .await;
    assert!(status.is_success(), "creating the viewer: {status}");
    let (_, _, viewer) = send(
        &router,
        "POST",
        "/api/auth/login",
        None,
        json(serde_json::json!({"username": "viewer", "password": "viewer-password"})),
    )
    .await;
    let viewer = viewer.expect("viewer login gave no session");
    // Hide a folder and share another, so those code paths have data.
    let folder_id = |name: &str| -> i64 {
        db.conn()
            .unwrap()
            .query_row(
                "SELECT id FROM folders WHERE path = ?1",
                [root.join(name).to_str().unwrap()],
                |r| r.get(0),
            )
            .unwrap()
    };
    let (hidden_folder, trip) = (folder_id("hidden"), folder_id("trip"));
    send(
        &router,
        "PUT",
        &format!("/api/folders/{hidden_folder}/hidden"),
        Some(&admin),
        Vec::new(),
    )
    .await;
    let (status, body, _) = send(
        &router,
        "POST",
        "/api/shares",
        Some(&admin),
        json(serde_json::json!({"folder_id": trip, "allow_download": true})),
    )
    .await;
    let share = serde_json::from_slice::<serde_json::Value>(&body)
        .ok()
        .and_then(|v| v["token"].as_str().map(str::to_string))
        .unwrap_or_else(|| panic!("creating a share: {status} {body:?}"));
    db.conn()
        .unwrap()
        .execute_batch("PRAGMA wal_checkpoint(TRUNCATE)")
        .unwrap();
    drop((router, db));
    std::fs::copy(dir.join("t.db"), template_db()).unwrap();
    Template {
        admin,
        viewer,
        share,
    }
}

const METHODS: &[&str] = &["GET", "POST", "PATCH", "DELETE", "PUT", "HEAD"];

/// One HTTP request against a fresh copy of the template library.
///
/// Input: one byte choosing the method (low bits) and the session (admin, viewer or
/// none), then the request target up to the first newline, then the body. `{share}`
/// in the target becomes the share link's token. A 500 fails the run: bad input must
/// be rejected with a 4xx, never break a handler. (503 is fine: the web UI may not be
/// built into a fuzz binary.)
pub fn http(data: &[u8]) {
    init();
    let Some((&first, rest)) = data.split_first() else {
        return;
    };
    let (target, body) = match rest.iter().position(|&b| b == b'\n') {
        Some(i) => (&rest[..i], rest[i + 1..].to_vec()),
        None => (rest, Vec::new()),
    };
    let Ok(target) = std::str::from_utf8(target) else {
        return;
    };
    let template = &*TEMPLATE;
    let target = target.replace("{share}", &template.share);
    let method = METHODS[usize::from(first) % METHODS.len()];
    let cookie = match (first >> 4) % 3 {
        0 => Some(template.admin.as_str()),
        1 => Some(template.viewer.as_str()),
        _ => None,
    };

    let dir = run_dir();
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::copy(template_db(), dir.join("t.db")).unwrap();
    write_media(&dir.join("media"));
    let app = app(&dir);
    let (status, body, _) = RUNTIME.block_on(send(&app.router, method, &target, cookie, body));
    assert!(
        status != axum::http::StatusCode::INTERNAL_SERVER_ERROR,
        "{method} {target} -> {status}: {}",
        String::from_utf8_lossy(&body)
    );
}
