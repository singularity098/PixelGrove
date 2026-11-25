//! JNI bindings for `app.pixelg.core.Native`: PixelGrove's library engine
//! (`pixelgrove::embed`) running inside the PixelG app.
//!
//! Every entry point catches panics (unwinding into the JVM is undefined behaviour)
//! and turns errors into a `null`, an empty array or an error message.

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::PathBuf;
use std::sync::OnceLock;

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring};
use pixelgrove::embed::{Library, Options, Thumbnail};

static LIBRARY: OnceLock<Library> = OnceLock::new();

fn library() -> Option<&'static Library> {
    LIBRARY.get()
}

/// Runs `f`, logging errors and panics and returning `fallback` for them.
fn guarded<T>(what: &str, fallback: T, f: impl FnOnce() -> anyhow::Result<T>) -> T {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(value)) => value,
        Ok(Err(err)) => {
            tracing::warn!("{what} failed: {err:#}");
            fallback
        }
        Err(_) => {
            tracing::error!("{what} panicked");
            fallback
        }
    }
}

fn string(env: &mut JNIEnv, value: &JString) -> anyhow::Result<String> {
    Ok(env.get_string(value)?.into())
}

fn strings(env: &mut JNIEnv, array: &JObjectArray) -> anyhow::Result<Vec<String>> {
    let len = env.get_array_length(array)?;
    let mut out = Vec::with_capacity(len as usize);
    for i in 0..len {
        let item = JString::from(env.get_object_array_element(array, i)?);
        out.push(string(env, &item)?);
    }
    Ok(out)
}

fn bytes(env: &mut JNIEnv, data: &[u8]) -> jbyteArray {
    env.byte_array_from_slice(data)
        .map(JByteArray::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

fn new_string(env: &mut JNIEnv, value: &str) -> jstring {
    env.new_string(value)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

/// Starts the engine (once per process). Returns `null`, or an error message.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_pixelg_core_Native_start(
    mut env: JNIEnv,
    _: JClass,
    data_dir: JString,
    thumbnail_dir: JString,
    default_roots: JObjectArray,
    thumbnail_cache_mb: jint,
) -> jstring {
    logging::init();
    let result = guarded(
        "starting",
        Some("PixelG's engine crashed while starting.".to_string()),
        || {
            if LIBRARY.get().is_some() {
                return Ok(None);
            }
            let options = Options {
                data_dir: PathBuf::from(string(&mut env, &data_dir)?),
                thumbnail_dir: PathBuf::from(string(&mut env, &thumbnail_dir)?),
                thumbnail_cache_mb: thumbnail_cache_mb.max(64) as u64,
                preview_cache_mb: 96,
                default_roots: strings(&mut env, &default_roots)?
                    .into_iter()
                    .map(PathBuf::from)
                    .collect(),
            };
            Ok(match Library::start(options) {
                Ok(library) => {
                    let _ = LIBRARY.set(library);
                    None
                }
                Err(err) => Some(format!("{err:#}")),
            })
        },
    );
    match result {
        None => std::ptr::null_mut(),
        Some(message) => new_string(&mut env, &message),
    }
}

/// Calls the JSON API. Returns the status as three ASCII digits, then the body.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_pixelg_core_Native_request(
    mut env: JNIEnv,
    _: JClass,
    method: JString,
    uri: JString,
    body: JByteArray,
) -> jbyteArray {
    let response = guarded("request", b"500".to_vec(), || {
        let library = library().ok_or_else(|| anyhow::anyhow!("not started"))?;
        let method = string(&mut env, &method)?;
        let uri = string(&mut env, &uri)?;
        let body = if body.is_null() {
            None
        } else {
            Some(env.convert_byte_array(&body)?)
        };
        let (status, bytes) = library.request(&method, &uri, body)?;
        let mut out = Vec::with_capacity(3 + bytes.len());
        out.extend_from_slice(format!("{status:03}").as_bytes());
        out.extend_from_slice(&bytes);
        Ok(out)
    });
    bytes(&mut env, &response)
}

/// A preview as JPEG; `null` when the app should decode the file itself (see
/// `path`); an empty array when there's no such item.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_pixelg_core_Native_thumbnail(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    variant: JString,
    trash: jboolean,
) -> jbyteArray {
    let found = guarded("thumbnail", None, || {
        let library = library().ok_or_else(|| anyhow::anyhow!("not started"))?;
        let variant = string(&mut env, &variant)?;
        Ok(match library.thumbnail(id, &variant, trash != 0)? {
            None => Some(Vec::new()),
            Some(Thumbnail::Jpeg(jpeg)) => Some(jpeg.to_vec()),
            Some(Thumbnail::Platform(_)) => None,
        })
    });
    match found {
        Some(data) => bytes(&mut env, &data),
        None => std::ptr::null_mut(),
    }
}

/// Keeps a preview the app decoded itself.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_pixelg_core_Native_storeThumbnail(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    variant: JString,
    trash: jboolean,
    jpeg: JByteArray,
) {
    guarded("storing a thumbnail", (), || {
        let library = library().ok_or_else(|| anyhow::anyhow!("not started"))?;
        let variant = string(&mut env, &variant)?;
        let jpeg = env.convert_byte_array(&jpeg)?;
        library.store_thumbnail(id, &variant, trash != 0, jpeg)
    });
}

/// Where an item is on disk, or `null`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_pixelg_core_Native_path(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    trash: jboolean,
) -> jstring {
    let path = guarded("finding a file", None, || {
        let library = library().ok_or_else(|| anyhow::anyhow!("not started"))?;
        library.path(id, trash != 0)
    });
    match path {
        Some(path) => new_string(&mut env, &path.to_string_lossy()),
        None => std::ptr::null_mut(),
    }
}

/// Rescans the folders holding these files or folders, in the background.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_pixelg_core_Native_rescan(
    mut env: JNIEnv,
    _: JClass,
    paths: JObjectArray,
) {
    guarded("rescanning", (), || {
        let library = library().ok_or_else(|| anyhow::anyhow!("not started"))?;
        let paths = strings(&mut env, &paths)?;
        library.rescan(paths.into_iter().map(PathBuf::from).collect());
        Ok(())
    });
}

/// Goes up whenever a scan changed the library; the app polls it to know when to reload.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_pixelg_core_Native_generation(_: JNIEnv, _: JClass) -> jlong {
    guarded("reading the generation", 0, || {
        Ok(library().map_or(0, |l| l.generation() as jlong))
    })
}

/// Rescans every media folder in the background.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_pixelg_core_Native_fullScan(_: JNIEnv, _: JClass) {
    guarded("scanning", (), || {
        library()
            .ok_or_else(|| anyhow::anyhow!("not started"))?
            .full_scan();
        Ok(())
    });
}

/// Writes a copy without location data to `out`. Returns `null`, or an error message.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_pixelg_core_Native_copyWithoutLocation(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    out: JString,
) -> jstring {
    let error = guarded("removing location", Some("Failed.".to_string()), || {
        let library = library().ok_or_else(|| anyhow::anyhow!("not started"))?;
        let out = PathBuf::from(string(&mut env, &out)?);
        Ok(library
            .copy_without_location(id, &out)
            .err()
            .map(|e| format!("{e:#}")))
    });
    match error {
        None => std::ptr::null_mut(),
        Some(message) => new_string(&mut env, &message),
    }
}

/// Engine logs go to logcat (tag `PixelG`).
mod logging {
    use tracing_subscriber::EnvFilter;

    pub fn init() {
        static ONCE: std::sync::Once = std::sync::Once::new();
        ONCE.call_once(|| {
            let filter = EnvFilter::new("info,nom_exif=off");
            let _ = tracing_subscriber::fmt()
                .with_env_filter(filter)
                .with_ansi(false)
                .without_time()
                .with_writer(Logcat::default)
                .try_init();
        });
    }

    /// Collects one formatted event and hands it to logcat when dropped.
    #[derive(Default)]
    struct Logcat(Vec<u8>);

    impl std::io::Write for Logcat {
        fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
            self.0.extend_from_slice(buf);
            Ok(buf.len())
        }

        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    impl Drop for Logcat {
        fn drop(&mut self) {
            if self.0.is_empty() {
                return;
            }
            let line = String::from_utf8_lossy(&self.0);
            let line = line.trim_end();
            #[cfg(target_os = "android")]
            {
                let priority = if line.starts_with("ERROR") {
                    android_log_sys::LogPriority::ERROR
                } else if line.starts_with(" WARN") || line.starts_with("WARN") {
                    android_log_sys::LogPriority::WARN
                } else {
                    android_log_sys::LogPriority::INFO
                };
                let text = std::ffi::CString::new(line.replace('\0', " ")).unwrap_or_default();
                // SAFETY: both strings are NUL-terminated and outlive the call.
                unsafe {
                    android_log_sys::__android_log_write(
                        priority as std::os::raw::c_int,
                        c"PixelG".as_ptr(),
                        text.as_ptr(),
                    );
                }
            }
            #[cfg(not(target_os = "android"))]
            eprintln!("{line}");
        }
    }
}
