//! Per-server tile cache on disk: `<root>/<source.key()>/<z>/<x>/<y>.mvt`.
//!
//! Tiles are stored as the server sent them (decompressed). A tile the server
//! has no data for is a zero-byte file, so it is not asked for again. Recency
//! and sizes live in `index.json` (an `LruIndex`); a missing or unreadable
//! index is rebuilt from the files.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use rovsti_geo::tiles::{LruIndex, TileGeometry, TileId, TileSource};

use crate::http::{Fetched, TileClient, TileError};

pub const DEFAULT_CAP_BYTES: u64 = 128 * 1024 * 1024;

const INDEX_FILE: &str = "index.json";
const EXT: &str = "mvt";

/// The index key. A string, because JSON object keys must be strings.
fn key(id: TileId) -> String {
    format!("{}/{}/{}", id.z, id.x, id.y)
}

fn parse_key(k: &str) -> Option<TileId> {
    let mut it = k.split('/');
    let z = it.next()?.parse().ok()?;
    let x = it.next()?.parse().ok()?;
    let y = it.next()?.parse().ok()?;
    if it.next().is_some() {
        return None;
    }
    TileId::new(z, x, y)
}

#[derive(Debug)]
pub struct DiskCache {
    dir: PathBuf,
    cap: u64,
    index: LruIndex<String>,
    dirty: bool,
}

impl DiskCache {
    /// Opens (creating) the cache directory of `src` under `root`.
    pub fn open(root: &Path, src: &TileSource, cap: u64) -> io::Result<Self> {
        let dir = root.join(src.key());
        fs::create_dir_all(&dir)?;
        let loaded = fs::read(dir.join(INDEX_FILE))
            .ok()
            .and_then(|b| serde_json::from_slice::<LruIndex<String>>(&b).ok());
        let (index, dirty) = match loaded {
            Some(i) => (i, false),
            None => (rebuild(&dir), true),
        };
        let mut cache = Self {
            dir,
            cap,
            index,
            dirty,
        };
        cache.evict();
        Ok(cache)
    }

    fn path(&self, id: TileId) -> PathBuf {
        self.dir
            .join(id.z.to_string())
            .join(id.x.to_string())
            .join(format!("{}.{EXT}", id.y))
    }

    pub fn contains(&self, id: TileId) -> bool {
        self.index.contains(&key(id))
    }

    pub fn total_bytes(&self) -> u64 {
        self.index.total()
    }

    pub fn len(&self) -> usize {
        self.index.len()
    }

    pub fn is_empty(&self) -> bool {
        self.index.is_empty()
    }

    /// The cached bytes (empty for a known-empty tile), marking the tile as
    /// recently used. A tile whose file vanished is forgotten.
    pub fn get(&mut self, id: TileId) -> Option<Vec<u8>> {
        let k = key(id);
        if !self.index.contains(&k) {
            return None;
        }
        match fs::read(self.path(id)) {
            Ok(bytes) => {
                self.index.touch(&k);
                self.dirty = true;
                Some(bytes)
            }
            Err(_) => {
                self.index.remove(&k);
                self.dirty = true;
                None
            }
        }
    }

    /// Stores a tile (empty `bytes` for "no data here"), then evicts the least
    /// recently used tiles until the cache fits its cap.
    pub fn put(&mut self, id: TileId, bytes: &[u8]) -> io::Result<()> {
        let path = self.path(id);
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        let tmp = path.with_extension("tmp");
        fs::write(&tmp, bytes)?;
        fs::rename(&tmp, &path)?;
        self.index.insert(key(id), bytes.len() as u64);
        self.dirty = true;
        self.evict();
        Ok(())
    }

    fn evict(&mut self) {
        for k in self.index.evict_to(self.cap) {
            if let Some(id) = parse_key(&k)
                && let Err(e) = fs::remove_file(self.path(id))
            {
                log::debug!("tile cache: cannot delete {k}: {e}");
            }
            self.dirty = true;
        }
    }

    /// Writes `index.json` if anything changed.
    pub fn flush(&mut self) -> io::Result<()> {
        if !self.dirty {
            return Ok(());
        }
        let json = serde_json::to_vec(&self.index).map_err(io::Error::other)?;
        let tmp = self.dir.join("index.json.tmp");
        fs::write(&tmp, json)?;
        fs::rename(&tmp, self.dir.join(INDEX_FILE))?;
        self.dirty = false;
        Ok(())
    }
}

/// Walks `<dir>/<z>/<x>/<y>.mvt`; the oldest files are treated as least
/// recently used.
fn rebuild(dir: &Path) -> LruIndex<String> {
    let mut found: Vec<(std::time::SystemTime, String, u64)> = Vec::new();
    let subdirs = |p: &Path| -> Vec<PathBuf> {
        fs::read_dir(p)
            .map(|rd| rd.filter_map(|e| e.ok().map(|e| e.path())).collect())
            .unwrap_or_default()
    };
    let num = |p: &Path| -> Option<u32> { p.file_name()?.to_str()?.parse().ok() };
    for zp in subdirs(dir) {
        let Some(z) = num(&zp).and_then(|z| u8::try_from(z).ok()) else {
            continue;
        };
        for xp in subdirs(&zp) {
            let Some(x) = num(&xp) else { continue };
            for file in subdirs(&xp) {
                if file.extension().and_then(|e| e.to_str()) != Some(EXT) {
                    continue;
                }
                let Some(y) = file
                    .file_stem()
                    .and_then(|s| s.to_str())
                    .and_then(|s| s.parse().ok())
                else {
                    continue;
                };
                let Some(id) = TileId::new(z, x, y) else {
                    continue;
                };
                if let Ok(meta) = fs::metadata(&file) {
                    let when = meta.modified().unwrap_or(std::time::UNIX_EPOCH);
                    found.push((when, key(id), meta.len()));
                }
            }
        }
    }
    found.sort();
    let mut index = LruIndex::new();
    for (_, k, size) in found {
        index.insert(k, size);
    }
    index
}

/// One tile as drawable geometry: disk cache first, then the network (stored
/// for next time). With `offline_only` the network is never touched, and a
/// tile that is not cached is an error. The cache lock is not held during the
/// download.
pub fn load(
    cache: &Mutex<DiskCache>,
    client: &TileClient,
    src: &TileSource,
    id: TileId,
    offline_only: bool,
) -> Result<TileGeometry, TileError> {
    let lock = || {
        cache
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    };
    if let Some(bytes) = lock().get(id) {
        match to_geometry(id, &bytes) {
            Ok(geometry) => return Ok(geometry),
            // A damaged file: download it again (which overwrites it).
            Err(e) if offline_only => return Err(e),
            Err(e) => log::warn!("tile cache: {id:?} is damaged, fetching again: {e}"),
        }
    }
    if offline_only {
        return Err(TileError::Http("tile is not cached".to_owned()));
    }
    let bytes = match client.fetch(src, id)? {
        Fetched::Tile(b) => b,
        Fetched::Empty => Vec::new(),
    };
    // Decode before storing, so a broken tile is never cached.
    let geometry = to_geometry(id, &bytes)?;
    let mut c = lock();
    if let Err(e) = c.put(id, &bytes).and_then(|()| c.flush()) {
        log::warn!("tile cache: cannot store {id:?}: {e}");
    }
    Ok(geometry)
}

/// Total size in bytes of every file under `root` (tiles and index files of
/// all servers). 0 when `root` does not exist.
pub fn disk_usage(root: &Path) -> u64 {
    let Ok(entries) = fs::read_dir(root) else {
        return 0;
    };
    entries
        .flatten()
        .map(|e| match e.metadata() {
            Ok(m) if m.is_dir() => disk_usage(&e.path()),
            Ok(m) => m.len(),
            Err(_) => 0,
        })
        .sum()
}

/// Deletes the whole tile cache under `root`. A missing directory is fine.
/// Any open `DiskCache` on `root` should be dropped or reopened afterwards.
pub fn clear_all(root: &Path) -> io::Result<()> {
    match fs::remove_dir_all(root) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
        _ => Ok(()),
    }
}

fn to_geometry(id: TileId, bytes: &[u8]) -> Result<TileGeometry, TileError> {
    if bytes.is_empty() {
        return Ok(TileGeometry::empty(id));
    }
    Ok(TileGeometry::from_bytes(id, bytes)?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU32, Ordering};

    fn tmp() -> PathBuf {
        static N: AtomicU32 = AtomicU32::new(0);
        let p = std::env::temp_dir().join(format!(
            "rovsti_tiles_test_{}_{}",
            std::process::id(),
            N.fetch_add(1, Ordering::Relaxed)
        ));
        let _ = fs::remove_dir_all(&p);
        p
    }

    fn id(x: u32) -> TileId {
        TileId::new(14, x, 7).unwrap()
    }

    fn open(root: &Path, cap: u64) -> DiskCache {
        DiskCache::open(root, &TileSource::default_source(), cap).unwrap()
    }

    #[test]
    fn round_trip_and_reopen() {
        let root = tmp();
        let mut c = open(&root, 1000);
        assert_eq!(c.get(id(1)), None);
        c.put(id(1), b"hello").unwrap();
        assert_eq!(c.get(id(1)).unwrap(), b"hello");
        c.flush().unwrap();
        let mut again = open(&root, 1000);
        assert_eq!(again.get(id(1)).unwrap(), b"hello");
        assert_eq!(again.total_bytes(), 5);
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn usage_counts_all_sources_and_clear_all_resets() {
        let root = tmp();
        assert_eq!(disk_usage(&root), 0);
        let other = TileSource::parse("https://tiles.example.org/{z}/{x}/{y}").unwrap();
        let mut a = open(&root, 1000);
        let mut b = DiskCache::open(&root, &other, 1000).unwrap();
        a.put(id(1), b"hello").unwrap();
        b.put(id(2), b"abc").unwrap();
        a.flush().unwrap();
        b.flush().unwrap();
        assert!(disk_usage(&root) >= 8);
        clear_all(&root).unwrap();
        assert_eq!(disk_usage(&root), 0);
        let mut c = open(&root, 1000);
        assert!(c.is_empty());
        c.put(id(3), b"xyz").unwrap();
        assert_eq!(c.get(id(3)).unwrap(), b"xyz");
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn clear_all_on_missing_dir_is_ok() {
        let root = tmp();
        assert!(clear_all(&root).is_ok());
    }

    #[test]
    fn servers_have_separate_dirs() {
        let root = tmp();
        let other = TileSource::parse("https://tiles.example.org/{z}/{x}/{y}").unwrap();
        let mut a = open(&root, 1000);
        let mut b = DiskCache::open(&root, &other, 1000).unwrap();
        a.put(id(1), b"a").unwrap();
        assert_eq!(b.get(id(1)), None);
        b.put(id(1), b"bb").unwrap();
        assert_eq!(a.get(id(1)).unwrap(), b"a");
        assert!(root.join(TileSource::default_source().key()).is_dir());
        assert!(root.join(other.key()).is_dir());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn eviction_keeps_cap_and_deletes_lru_files() {
        let root = tmp();
        let mut c = open(&root, 25);
        for x in 1..=3 {
            c.put(id(x), &[0; 10]).unwrap();
        }
        // 30 > 25: tile 1 (least recently used) is gone, file included.
        assert!(!c.contains(id(1)));
        assert!(!c.path(id(1)).exists());
        assert!(c.path(id(2)).exists());
        assert!(c.total_bytes() <= 25);
        // Touch 2, add 4: 3 is the victim.
        c.get(id(2)).unwrap();
        c.put(id(4), &[0; 10]).unwrap();
        assert!(c.contains(id(2)) && c.contains(id(4)) && !c.contains(id(3)));
        assert!(!c.path(id(3)).exists());
        assert!(c.total_bytes() <= 25);
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn corrupt_or_missing_index_is_rebuilt() {
        let root = tmp();
        let mut c = open(&root, 1000);
        c.put(id(1), b"abc").unwrap();
        c.put(id(2), b"").unwrap();
        c.flush().unwrap();
        let index = root
            .join(TileSource::default_source().key())
            .join(INDEX_FILE);
        fs::write(&index, b"{ not json").unwrap();
        let mut again = open(&root, 1000);
        assert_eq!(again.len(), 2);
        assert_eq!(again.total_bytes(), 3);
        assert_eq!(again.get(id(1)).unwrap(), b"abc");
        fs::remove_file(&index).unwrap();
        assert_eq!(open(&root, 1000).len(), 2);
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn empty_tiles_are_zero_byte_files() {
        let root = tmp();
        let mut c = open(&root, 1000);
        c.put(id(5), b"").unwrap();
        assert!(c.contains(id(5)));
        assert_eq!(c.get(id(5)).unwrap(), Vec::<u8>::new());
        assert_eq!(fs::metadata(c.path(id(5))).unwrap().len(), 0);
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn vanished_file_is_forgotten() {
        let root = tmp();
        let mut c = open(&root, 1000);
        c.put(id(1), b"x").unwrap();
        fs::remove_file(c.path(id(1))).unwrap();
        assert_eq!(c.get(id(1)), None);
        assert!(!c.contains(id(1)));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn load_offline_only_with_empty_cache_errs() {
        let root = tmp();
        let cache = Mutex::new(open(&root, 1000));
        let src = TileSource::default_source();
        let r = load(&cache, &TileClient::new(), &src, id(1), true);
        assert!(r.is_err());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn load_reads_cached_tile_and_empty_marker() {
        let root = tmp();
        let fixture = include_bytes!("../../rovsti_geo/src/tiles/testdata/osm-14-9520-6130.mvt");
        let real = TileId::new(14, 9520, 6130).unwrap();
        let mut c = open(&root, DEFAULT_CAP_BYTES);
        c.put(real, fixture).unwrap();
        c.put(id(1), b"").unwrap();
        let cache = Mutex::new(c);
        let src = TileSource::default_source();
        let client = TileClient::new();
        let g = load(&cache, &client, &src, real, true).unwrap();
        assert_eq!(g.id, real);
        assert!(g.vertices > 0);
        let e = load(&cache, &client, &src, id(1), true).unwrap();
        assert_eq!(e.vertices, 0);
        let _ = fs::remove_dir_all(root);
    }
}
