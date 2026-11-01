//! Containing panics in third-party decoders.
//!
//! The JPEG, PNG/GIF/WebP, JPEG XL and EXIF/MP4 metadata parsers all read whatever is
//! in the library, and fuzzing has shown that a crafted or corrupt file can make some of
//! them panic. Release builds unwind (see `Cargo.toml`) and every call into them goes
//! through [`contain`], so a bad file loses its preview or metadata instead of taking
//! the server down (and, since scans start at boot, keeping it down).

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;

/// Runs `f`, turning a panic into `None` (the panic message is still printed by the
/// panic hook). `what` and `path` say what was being done, for the log.
pub fn contain<T>(what: &str, path: &Path, f: impl FnOnce() -> T) -> Option<T> {
    #[cfg(fuzzing)]
    let _guard = fuzzing::Guard::enter();
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(value) => Some(value),
        Err(_) => {
            tracing::error!(path = %path.display(), "{what} crashed on this file; skipped it");
            None
        }
    }
}

/// Lets the fuzz targets tell contained panics from real ones.
#[cfg(fuzzing)]
pub mod fuzzing {
    use std::cell::Cell;

    thread_local! {
        static DEPTH: Cell<u32> = const { Cell::new(0) };
    }

    pub struct Guard;

    impl Guard {
        pub fn enter() -> Self {
            DEPTH.with(|d| d.set(d.get() + 1));
            Guard
        }
    }

    impl Drop for Guard {
        fn drop(&mut self) {
            DEPTH.with(|d| d.set(d.get() - 1));
        }
    }

    /// Whether this thread is inside [`super::contain`].
    pub fn contained() -> bool {
        DEPTH.with(Cell::get) > 0
    }
}
