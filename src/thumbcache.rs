//! Optional thumbnail cache on disk.
//!
//! Off by default on the server (`PIXELGROVE_THUMBNAIL_CACHE`), where previews live only
//! in memory; always on in the PixelG Android app. Each preview is one small JPEG,
//! named after a hash of the file's path, size and mtime (so an edited file simply gets
//! a new entry) and read back memory-mapped, so a hit costs an `open` and an `mmap` and
//! the operating system's page cache does the rest. The total size is capped: when it
//! goes over, the entries used longest ago are deleted.

use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::{Duration, SystemTime};

use anyhow::{Context, Result};
use bytes::Bytes;
use sha2::{Digest, Sha256};

use crate::db::Db;
use crate::preview::{Key, Previews, Variant, render};

/// A hit refreshes an entry's mtime (which eviction goes by) at most this often, so
/// reading a grid doesn't turn into a stream of metadata writes.
const TOUCH_AFTER: Duration = Duration::from_secs(24 * 3600);
/// Eviction deletes down to this share of the capacity, so it doesn't run on every put.
const EVICT_TO: f64 = 0.9;

pub struct DiskCache {
    dir: PathBuf,
    capacity: u64,
    /// Bytes on disk, as far as we know (worked out at start, then kept up to date).
    bytes: AtomicU64,
    evicting: AtomicBool,
    /// Makes temporary file names unique between threads.
    counter: AtomicU64,
}

impl DiskCache {
    /// Opens (creating) the cache in `dir`, and works out its size in the background.
    pub fn open(dir: &Path, capacity_bytes: u64) -> Result<Arc<Self>> {
        fs::create_dir_all(dir).with_context(|| format!("creating {}", dir.display()))?;
        let cache = Arc::new(Self {
            dir: dir.to_path_buf(),
            capacity: capacity_bytes,
            bytes: AtomicU64::new(0),
            evicting: AtomicBool::new(true),
            counter: AtomicU64::new(0),
        });
        let this = cache.clone();
        std::thread::spawn(move || {
            let (total, _) = this.entries(true);
            this.bytes.store(total, Ordering::Relaxed);
            this.evicting.store(false, Ordering::Relaxed);
            this.evict_if_full();
        });
        Ok(cache)
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    /// Bytes currently used on disk.
    pub fn size(&self) -> u64 {
        self.bytes.load(Ordering::Relaxed)
    }

    pub fn capacity(&self) -> u64 {
        self.capacity
    }

    fn file(&self, path: &Path, key: &Key) -> PathBuf {
        let mut hash = Sha256::new();
        hash.update(path.as_os_str().as_encoded_bytes());
        hash.update(format!("\0{:?}\0{}\0{}", key.variant, key.mtime, key.size));
        let name = hex::encode(&hash.finalize()[..16]);
        self.dir
            .join(&name[..2])
            .join(format!("{}.jpg", &name[2..]))
    }

    pub fn contains(&self, path: &Path, key: &Key) -> bool {
        self.file(path, key).is_file()
    }

    /// The cached preview of `path`, memory-mapped.
    pub fn get(&self, path: &Path, key: &Key) -> Option<Bytes> {
        let file = File::open(self.file(path, key)).ok()?;
        let meta = file.metadata().ok()?;
        if meta.len() == 0 {
            return None;
        }
        if meta
            .modified()
            .ok()
            .and_then(|t| t.elapsed().ok())
            .is_some_and(|age| age > TOUCH_AFTER)
        {
            let _ = file.set_modified(SystemTime::now());
        }
        // SAFETY: entries are only ever created whole (written to a temporary file,
        // then renamed into place) and deleted; nothing truncates or rewrites one in
        // place, so the mapped bytes can't change or vanish while the map is alive.
        let map = unsafe { memmap2::Mmap::map(&file) }.ok()?;
        Some(Bytes::from_owner(map))
    }

    /// Stores a preview. Failures (a full disk, say) only cost the cache entry.
    pub fn put(&self, path: &Path, key: &Key, jpeg: &[u8]) {
        let target = self.file(path, key);
        if let Err(err) = self.write(&target, jpeg) {
            tracing::debug!(path = %target.display(), "writing thumbnail cache failed: {err:#}");
            return;
        }
        self.bytes.fetch_add(jpeg.len() as u64, Ordering::Relaxed);
        self.evict_if_full();
    }

    fn write(&self, target: &Path, jpeg: &[u8]) -> Result<()> {
        let parent = target.parent().context("cache entry has no folder")?;
        fs::create_dir_all(parent)?;
        let n = self.counter.fetch_add(1, Ordering::Relaxed);
        let temp = parent.join(format!(".{}-{n}.tmp", std::process::id()));
        let result = (|| {
            let mut file = File::create(&temp)?;
            file.write_all(jpeg)?;
            fs::rename(&temp, target)
        })();
        if result.is_err() {
            let _ = fs::remove_file(&temp);
        }
        Ok(result?)
    }

    /// Deletes everything (Settings → Clear thumbnail cache in the app).
    pub fn clear(&self) {
        let (_, entries) = self.entries(false);
        for (path, _, _) in entries {
            let _ = fs::remove_file(path);
        }
        self.bytes.store(0, Ordering::Relaxed);
    }

    fn evict_if_full(&self) {
        if self.bytes.load(Ordering::Relaxed) <= self.capacity
            || self.evicting.swap(true, Ordering::AcqRel)
        {
            return;
        }
        let (total, mut entries) = self.entries(false);
        // Oldest first: eviction goes by last use (refreshed on hits, see TOUCH_AFTER).
        entries.sort_unstable_by_key(|(_, _, modified)| *modified);
        let target = (self.capacity as f64 * EVICT_TO) as u64;
        let mut total = total;
        for (path, len, _) in entries {
            if total <= target {
                break;
            }
            if fs::remove_file(&path).is_ok() {
                total -= len;
            }
        }
        self.bytes.store(total, Ordering::Relaxed);
        self.evicting.store(false, Ordering::Release);
    }

    /// Every entry as `(path, size, mtime)`, and their total size. With `clean_up`,
    /// temporary files left by an interrupted write are deleted on the way.
    fn entries(&self, clean_up: bool) -> (u64, Vec<(PathBuf, u64, SystemTime)>) {
        let mut entries = Vec::new();
        let mut total = 0;
        let Ok(shards) = fs::read_dir(&self.dir) else {
            return (0, entries);
        };
        for shard in shards.filter_map(Result::ok) {
            let Ok(files) = fs::read_dir(shard.path()) else {
                continue;
            };
            for file in files.filter_map(Result::ok) {
                let path = file.path();
                if file.file_name().as_encoded_bytes().starts_with(b".") {
                    if clean_up {
                        let _ = fs::remove_file(&path);
                    }
                    continue;
                }
                let Ok(meta) = file.metadata() else { continue };
                let modified = meta.modified().unwrap_or(SystemTime::UNIX_EPOCH);
                total += meta.len();
                entries.push((path, meta.len(), modified));
            }
        }
        (total, entries)
    }
}

/// Makes thumbnails for every photo that has none in the disk cache yet, newest
/// first, in the background: after start-up and again whenever the library changes.
/// Decoding runs in parallel (rayon) on half the cores, so browsing stays quick
/// meanwhile; JPEG XL, which needs hundreds of megabytes per decode, one at a time.
pub fn spawn_warmup(db: Db, previews: Arc<Previews>) {
    if previews.disk_cache().is_none() {
        return;
    }
    let workers = std::thread::available_parallelism().map_or(1, |n| (n.get() / 2).max(1));
    let pool = match rayon::ThreadPoolBuilder::new()
        .num_threads(workers)
        .thread_name(|i| format!("thumbnails-{i}"))
        .build()
    {
        Ok(pool) => pool,
        Err(err) => {
            tracing::warn!("thumbnail warm-up unavailable: {err}");
            return;
        }
    };
    std::thread::spawn(move || {
        let mut done_for = None;
        loop {
            match library_version(&db) {
                Ok(version) if Some(version) != done_for => {
                    done_for = Some(version);
                    if let Err(err) = warm(&db, &previews, &pool) {
                        tracing::warn!("making thumbnails failed: {err:#}");
                    }
                }
                Ok(_) => {}
                Err(err) => tracing::debug!("checking the library failed: {err:#}"),
            }
            std::thread::sleep(Duration::from_secs(20));
        }
    });
}

/// Changes whenever media is added, removed or replaced.
fn library_version(db: &Db) -> Result<(i64, i64, i64)> {
    Ok(db.conn()?.query_row(
        "SELECT count(*), coalesce(max(id), 0), coalesce(sum(mtime), 0) FROM media",
        [],
        |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
    )?)
}

fn warm(db: &Db, previews: &Previews, pool: &rayon::ThreadPool) -> Result<()> {
    let disk = previews.disk_cache().context("no disk cache")?;
    let photos: Vec<(i64, PathBuf, i64, i64, Option<u16>)> = db
        .conn()?
        .prepare(
            "SELECT m.id, f.path, m.name, m.mtime, m.size, m.orientation
             FROM media m JOIN folders f ON f.id = m.folder_id
             WHERE m.kind = 'image' ORDER BY m.taken_at DESC, m.id DESC",
        )?
        .query_map([], |r| {
            Ok((
                r.get(0)?,
                Path::new(&r.get::<_, String>(1)?).join(r.get::<_, String>(2)?),
                r.get(3)?,
                r.get(4)?,
                r.get(5)?,
            ))
        })?
        .collect::<rusqlite::Result<_>>()?;
    let key = |id, mtime, size| Key {
        media_id: id,
        variant: Variant::Thumb,
        mtime,
        size,
    };
    let todo: Vec<_> = photos
        .into_iter()
        .filter(|(id, path, mtime, size, _)| !disk.contains(path, &key(*id, *mtime, *size)))
        .collect();
    if todo.is_empty() {
        return Ok(());
    }
    let started = std::time::Instant::now();
    let jxl = std::sync::Mutex::new(());
    let made = AtomicU64::new(0);
    pool.install(|| {
        use rayon::prelude::*;
        todo.par_iter()
            .for_each(|(id, path, mtime, size, orientation)| {
                let _one_at_a_time = crate::jxl::is_jxl(path).then(|| jxl.lock());
                // `None`: small or AVIF/HEIC files that are shown as they are.
                if let Ok(Some(jpeg)) = render(path, Variant::Thumb, orientation.unwrap_or(1)) {
                    disk.put(path, &key(*id, *mtime, *size), &jpeg);
                    made.fetch_add(1, Ordering::Relaxed);
                }
            });
    });
    crate::alloc::release_free_memory();
    tracing::info!(
        photos = made.load(Ordering::Relaxed),
        duration_ms = started.elapsed().as_millis() as u64,
        "thumbnails cached"
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key(mtime: i64) -> Key {
        Key {
            media_id: 1,
            variant: Variant::Thumb,
            mtime,
            size: 10,
        }
    }

    fn wait_for_size(cache: &DiskCache) {
        for _ in 0..200 {
            if !cache.evicting.load(Ordering::Acquire) {
                return;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
    }

    #[test]
    fn stores_and_maps_entries() {
        let dir = tempfile::tempdir().unwrap();
        let cache = DiskCache::open(dir.path(), 1 << 20).unwrap();
        wait_for_size(&cache);
        let photo = Path::new("/photos/a.jpg");
        assert!(cache.get(photo, &key(1)).is_none());
        cache.put(photo, &key(1), b"jpeg bytes");
        assert_eq!(cache.get(photo, &key(1)).unwrap(), &b"jpeg bytes"[..]);
        assert!(cache.contains(photo, &key(1)));
        assert!(cache.get(photo, &key(2)).is_none(), "a changed file misses");
        assert!(cache.get(Path::new("/photos/b.jpg"), &key(1)).is_none());
        assert_eq!(cache.size(), 10);

        // Sizes are worked out again on the next start.
        let reopened = DiskCache::open(dir.path(), 1 << 20).unwrap();
        wait_for_size(&reopened);
        assert_eq!(reopened.size(), 10);
        reopened.clear();
        assert!(reopened.get(photo, &key(1)).is_none());
        assert_eq!(reopened.size(), 0);
    }

    #[test]
    fn evicts_least_recently_used_beyond_capacity() {
        let dir = tempfile::tempdir().unwrap();
        let cache = DiskCache::open(dir.path(), 100).unwrap();
        wait_for_size(&cache);
        let old = Path::new("/old.jpg");
        cache.put(old, &key(1), &[0; 40]);
        let file = File::options()
            .write(true)
            .open(cache.file(old, &key(1)))
            .unwrap();
        file.set_modified(SystemTime::now() - Duration::from_secs(3600))
            .unwrap();
        cache.put(Path::new("/new.jpg"), &key(1), &[0; 40]);
        cache.put(Path::new("/newer.jpg"), &key(1), &[0; 40]);
        assert!(cache.get(old, &key(1)).is_none(), "the oldest went");
        assert!(cache.get(Path::new("/new.jpg"), &key(1)).is_some());
        assert!(cache.get(Path::new("/newer.jpg"), &key(1)).is_some());
        assert_eq!(cache.size(), 80);
    }
}
