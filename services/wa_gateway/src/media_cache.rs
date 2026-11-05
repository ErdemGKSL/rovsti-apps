//! A disk cache of downloaded (decrypted) media, bounded by total size.
//! Eviction is least recently used by file modification time: reading a
//! file touches it.

use std::fs;
use std::io::Write as _;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::SystemTime;

use sha2::{Digest as _, Sha256};

/// Per-instance cache budget.
pub const DEFAULT_MAX_BYTES: u64 = 256 * 1024 * 1024;

static TMP_COUNTER: AtomicU64 = AtomicU64::new(0);

#[derive(Clone, Debug)]
pub struct MediaCache {
    dir: PathBuf,
    max_bytes: u64,
}

/// A file name for a message id: ids come from remote parties, so they never
/// touch the file system as they are.
fn file_name(key: &str) -> String {
    Sha256::digest(key.as_bytes())
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

impl MediaCache {
    pub fn new(dir: PathBuf, max_bytes: u64) -> Self {
        Self { dir, max_bytes }
    }

    fn path(&self, key: &str) -> PathBuf {
        self.dir.join(file_name(key))
    }

    /// The cached bytes, marking them as just used.
    pub fn get(&self, key: &str) -> Option<Vec<u8>> {
        let path = self.path(key);
        let bytes = fs::read(&path).ok()?;
        touch(&path);
        Some(bytes)
    }

    /// Stores `bytes` (atomically) and evicts the least recently used files
    /// until the cache fits. Media larger than the whole budget is not kept.
    pub fn put(&self, key: &str, bytes: &[u8]) -> std::io::Result<()> {
        if bytes.len() as u64 > self.max_bytes {
            return Ok(());
        }
        fs::create_dir_all(&self.dir)?;
        let path = self.path(key);
        let tmp = self.dir.join(format!(
            ".tmp-{}-{}",
            std::process::id(),
            TMP_COUNTER.fetch_add(1, Ordering::Relaxed)
        ));
        let written = fs::File::create(&tmp).and_then(|mut f| {
            f.write_all(bytes)?;
            f.sync_all()
        });
        if let Err(e) = written.and_then(|()| fs::rename(&tmp, &path)) {
            let _ = fs::remove_file(&tmp);
            return Err(e);
        }
        self.evict(&path)
    }

    /// Deletes the oldest files until the total is within budget. `keep` (the
    /// file just written) is never the victim.
    fn evict(&self, keep: &Path) -> std::io::Result<()> {
        let mut files: Vec<(SystemTime, PathBuf, u64)> = Vec::new();
        for entry in fs::read_dir(&self.dir)? {
            let entry = entry?;
            let name = entry.file_name();
            if name.to_string_lossy().starts_with('.') {
                continue; // in-flight temp file
            }
            let meta = entry.metadata()?;
            if meta.is_file() {
                let used = meta.modified().unwrap_or(SystemTime::UNIX_EPOCH);
                files.push((used, entry.path(), meta.len()));
            }
        }
        let mut total: u64 = files.iter().map(|f| f.2).sum();
        files.sort();
        for (_, path, len) in files {
            if total <= self.max_bytes {
                break;
            }
            if path == keep {
                continue;
            }
            if fs::remove_file(&path).is_ok() {
                total -= len;
            }
        }
        Ok(())
    }
}

fn touch(path: &Path) {
    if let Ok(file) = fs::OpenOptions::new().write(true).open(path) {
        let _ = file.set_modified(SystemTime::now());
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    fn set_age(cache: &MediaCache, key: &str, secs_ago: u64) {
        let file = fs::OpenOptions::new()
            .write(true)
            .open(cache.path(key))
            .unwrap();
        file.set_modified(SystemTime::now() - Duration::from_secs(secs_ago))
            .unwrap();
    }

    fn cache(max: u64) -> (tempfile::TempDir, MediaCache) {
        let dir = tempfile::tempdir().unwrap();
        let cache = MediaCache::new(dir.path().join("media"), max);
        (dir, cache)
    }

    #[test]
    fn round_trip_and_miss() {
        let (_dir, cache) = cache(100);
        assert_eq!(cache.get("a"), None);
        cache.put("a", b"hello").unwrap();
        assert_eq!(cache.get("a").as_deref(), Some(&b"hello"[..]));
        cache.put("a", b"bye").unwrap();
        assert_eq!(cache.get("a").as_deref(), Some(&b"bye"[..]));
    }

    #[test]
    fn keys_never_reach_the_file_system_raw() {
        let (_dir, cache) = cache(100);
        cache.put("../../etc/passwd", b"x").unwrap();
        let names: Vec<String> = fs::read_dir(&cache.dir)
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        assert_eq!(names.len(), 1);
        assert_eq!(names[0].len(), 64);
        assert!(names[0].bytes().all(|b| b.is_ascii_hexdigit()));
    }

    #[test]
    fn the_least_recently_used_file_goes_first() {
        let (_dir, cache) = cache(10);
        cache.put("old", b"1234").unwrap();
        cache.put("mid", b"1234").unwrap();
        set_age(&cache, "old", 300);
        set_age(&cache, "mid", 200);
        // Reading `old` makes `mid` the least recently used.
        assert!(cache.get("old").is_some());
        cache.put("new", b"1234").unwrap();
        assert!(cache.get("mid").is_none());
        assert!(cache.get("old").is_some());
        assert!(cache.get("new").is_some());
    }

    #[test]
    fn oversized_media_is_not_kept_and_the_new_file_survives() {
        let (_dir, cache) = cache(4);
        cache.put("big", b"12345").unwrap();
        assert_eq!(cache.get("big"), None);
        cache.put("a", b"1234").unwrap();
        cache.put("b", b"1234").unwrap();
        assert!(cache.get("a").is_none());
        assert!(cache.get("b").is_some());
    }
}
