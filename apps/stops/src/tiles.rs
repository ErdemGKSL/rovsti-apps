//! Map tiles for the Stops map: what is in memory, what is being fetched.
//!
//! Every download, disk read and decode runs on the background executor (at
//! most `MAX_IN_FLIGHT` at a time, from the pure `FetchQueue`); the UI thread
//! only moves finished geometry into the in-memory cache. Missing tiles are
//! simply not drawn, so the map keeps its radar look there.

use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};

use gpui::Context;
use rovsti_geo::tiles::{
    FetchQueue, LruIndex, Priority, TileCover, TileGeometry, TileId, TileSource, stand_in,
};
use rovsti_tiles::{DEFAULT_CAP_BYTES, DiskCache, TileClient, TileError, load};

use crate::{StopsApp, store::now_ms};

/// Decoded vertices kept in memory (about a few MB).
const MEMORY_CAP_VERTICES: u64 = 400_000;

/// Where the per-server tile caches live inside the app's data directory.
pub fn cache_root(data_dir: &Path) -> PathBuf {
    data_dir.join("tiles")
}

/// Decoded tiles, oldest dropped first once over `cap` vertices.
#[derive(Debug, Default)]
pub struct TileMemory {
    tiles: HashMap<TileId, Arc<TileGeometry>>,
    lru: LruIndex<TileId>,
    /// Bumped whenever a tile comes or goes, so the map knows when to rebuild.
    revision: u64,
}

impl TileMemory {
    pub fn contains(&self, id: TileId) -> bool {
        self.tiles.contains_key(&id)
    }

    #[cfg(test)]
    fn len(&self) -> usize {
        self.tiles.len()
    }

    /// Changes whenever the set of tiles in memory does.
    pub fn revision(&self) -> u64 {
        self.revision
    }

    /// The tile, marked as recently used.
    pub fn get(&mut self, id: TileId) -> Option<Arc<TileGeometry>> {
        let tile = self.tiles.get(&id)?.clone();
        self.lru.touch(&id);
        Some(tile)
    }

    /// Stores `tile` and drops the least recently used ones beyond `cap` vertices.
    pub fn insert(&mut self, tile: Arc<TileGeometry>, cap: u64) {
        let id = tile.id;
        self.lru.insert(id, tile.vertices.max(1) as u64);
        self.tiles.insert(id, tile);
        for gone in self.lru.evict_to(cap) {
            self.tiles.remove(&gone);
        }
        self.revision += 1;
    }

    pub fn clear(&mut self) {
        self.tiles.clear();
        self.lru = LruIndex::new();
        self.revision += 1;
    }
}

/// The tile server in use, its disk cache and the downloads under way.
pub struct MapTiles {
    source: TileSource,
    /// `<data dir>/tiles`, once the data directory is known.
    root: Option<PathBuf>,
    /// The open cache of `source`; `None` while it is being opened.
    cache: Option<Arc<Mutex<DiskCache>>>,
    /// Bumped whenever the server changes, so late results are ignored.
    generation: u64,
    client: TileClient,
    queue: FetchQueue,
    memory: TileMemory,
}

impl Default for MapTiles {
    fn default() -> Self {
        Self {
            source: TileSource::default(),
            root: None,
            cache: None,
            generation: 0,
            client: TileClient::new(),
            queue: FetchQueue::new(),
            memory: TileMemory::default(),
        }
    }
}

impl MapTiles {
    /// A handle to the HTTP client, for testing a server off the UI thread.
    pub fn client(&self) -> TileClient {
        self.client.clone()
    }

    /// `<data dir>/tiles`, once the data directory is known.
    pub fn root(&self) -> Option<PathBuf> {
        self.root.clone()
    }

    /// Deletes every server's cached tiles, then reopens the current server's
    /// (empty) cache so the map refetches its view; `done` runs afterwards.
    pub fn clear_disk(
        &mut self,
        cx: &mut Context<StopsApp>,
        done: impl FnOnce(&mut StopsApp, &mut Context<StopsApp>) + 'static,
    ) {
        self.queue.clear();
        self.memory.clear();
        self.cache = None;
        self.generation += 1;
        cx.notify();
        let root = self.root.clone();
        cx.spawn(async move |this, cx| {
            if let Some(root) = root {
                let cleared = cx
                    .background_executor()
                    .spawn(async move { rovsti_tiles::clear_all(&root) })
                    .await;
                if let Err(error) = cleared {
                    log::warn!("cannot clear the tile cache: {error}");
                }
            }
            this.update(cx, |app, cx| {
                let source = app.map_tiles.source.clone();
                app.map_tiles.set_source(source, cx);
                done(app, cx);
            })
            .ok();
        })
        .detach();
    }

    /// The data directory is known and the prefs are read: use their server.
    pub fn attach(&mut self, data_dir: &Path, source: TileSource, cx: &mut Context<StopsApp>) {
        self.root = Some(cache_root(data_dir));
        self.set_source(source, cx);
    }

    /// Switches server: forgets the queue and the memory cache, opens the
    /// other server's disk cache (on the background executor).
    pub fn set_source(&mut self, source: TileSource, cx: &mut Context<StopsApp>) {
        self.source = source;
        self.queue.clear();
        self.memory.clear();
        self.cache = None;
        self.generation += 1;
        cx.notify();
        let Some(root) = self.root.clone() else {
            return;
        };
        let (src, generation) = (self.source.clone(), self.generation);
        cx.spawn(async move |this, cx| {
            let opened = cx
                .background_executor()
                .spawn(async move { DiskCache::open(&root, &src, DEFAULT_CAP_BYTES) })
                .await;
            this.update(cx, |app, cx| {
                let tiles = &mut app.map_tiles;
                if tiles.generation != generation {
                    return;
                }
                match opened {
                    Ok(cache) => {
                        tiles.cache = Some(Arc::new(Mutex::new(cache)));
                        tiles.pump(cx);
                    }
                    Err(error) => log::warn!("cannot open the tile cache: {error}"),
                }
            })
            .ok();
        })
        .detach();
    }

    /// Changes whenever the tiles in memory do: what [`Self::gather`] returns
    /// for a cover only changes with it.
    pub fn revision(&self) -> u64 {
        self.memory.revision()
    }

    /// Wants the tiles on screen now, ahead of any prefetch.
    pub fn request_view(&mut self, cover: &TileCover, cx: &mut Context<StopsApp>) {
        self.queue.set_view(&cover.tiles);
        let missing: Vec<TileId> = cover
            .tiles
            .iter()
            .copied()
            .filter(|&id| !self.memory.contains(id))
            .collect();
        self.queue.want(missing, Priority::View, now_ms());
        self.pump(cx);
    }

    /// Downloads `ids` in the background so the trip works offline.
    pub fn prefetch(&mut self, ids: Vec<TileId>, cx: &mut Context<StopsApp>) {
        self.queue.want(ids, Priority::Prefetch, now_ms());
        self.pump(cx);
    }

    /// The loaded tiles for `cover`, coarse first; a tile that is not loaded
    /// yet is stood in for by a coarser ancestor when there is one.
    pub fn gather(&mut self, cover: &TileCover) -> Vec<Arc<TileGeometry>> {
        let mut ids: Vec<TileId> = Vec::new();
        for &want in &cover.tiles {
            let have = stand_in(want, |id| self.memory.contains(id));
            if let Some(id) = have.filter(|id| !ids.contains(id)) {
                ids.push(id);
            }
        }
        ids.sort_by_key(|id| id.z);
        ids.into_iter()
            .filter_map(|id| self.memory.get(id))
            .collect()
    }

    /// Starts downloads up to the queue's limit.
    fn pump(&mut self, cx: &mut Context<StopsApp>) {
        let Some(cache) = self.cache.clone() else {
            return;
        };
        while let Some(id) = self.queue.next(now_ms()) {
            let (cache, client) = (cache.clone(), self.client.clone());
            let (src, generation) = (self.source.clone(), self.generation);
            cx.spawn(async move |this, cx| {
                let result = cx
                    .background_executor()
                    .spawn(async move { load(&cache, &client, &src, id, false) })
                    .await;
                this.update(cx, |app, cx| {
                    app.map_tiles.finish(id, generation, result, cx);
                })
                .ok();
            })
            .detach();
        }
    }

    fn finish(
        &mut self,
        id: TileId,
        generation: u64,
        result: Result<TileGeometry, TileError>,
        cx: &mut Context<StopsApp>,
    ) {
        if generation != self.generation {
            return;
        }
        self.queue.done(id, result.is_ok(), now_ms());
        match result {
            Ok(tile) => {
                self.memory.insert(Arc::new(tile), MEMORY_CAP_VERTICES);
                cx.notify();
            }
            Err(error) => log::debug!("tile {id:?} failed: {error}"),
        }
        self.pump(cx);
        if self.queue.in_flight() == 0 {
            self.flush(cx);
        }
    }

    /// Writes the cache index (a no-op when nothing changed).
    fn flush(&self, cx: &mut Context<StopsApp>) {
        let Some(cache) = self.cache.clone() else {
            return;
        };
        cx.background_executor()
            .spawn(async move {
                let mut cache = cache
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                if let Err(error) = cache.flush() {
                    log::warn!("tile cache: cannot write the index: {error}");
                }
            })
            .detach();
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    fn tile(z: u8, x: u32, vertices: usize) -> Arc<TileGeometry> {
        let mut t = TileGeometry::empty(TileId::new(z, x, 0).unwrap());
        t.vertices = vertices;
        Arc::new(t)
    }

    #[test]
    fn the_revision_moves_when_tiles_come_or_go() {
        let mut m = TileMemory::default();
        let r0 = m.revision();
        m.insert(tile(5, 1, 40), 100);
        let r1 = m.revision();
        assert_ne!(r0, r1);
        // Reading does not change what is in memory.
        assert!(m.get(TileId::new(5, 1, 0).unwrap()).is_some());
        assert_eq!(m.revision(), r1);
        m.clear();
        assert_ne!(m.revision(), r1);
    }

    #[test]
    fn the_cache_lives_under_the_data_directory() {
        assert_eq!(
            cache_root(Path::new("/data/stops")),
            Path::new("/data/stops/tiles")
        );
    }

    #[test]
    fn memory_drops_the_least_recently_used_tile_over_the_cap() {
        let mut m = TileMemory::default();
        let (a, b, c) = (tile(5, 1, 40), tile(5, 2, 40), tile(5, 3, 40));
        m.insert(a.clone(), 100);
        m.insert(b.clone(), 100);
        assert!(m.get(a.id).is_some()); // a is now newer than b
        m.insert(c.clone(), 100);
        assert!(m.contains(a.id) && m.contains(c.id) && !m.contains(b.id));
        assert_eq!(m.len(), 2);
    }

    #[test]
    fn empty_tiles_still_count_and_clear_forgets_all() {
        let mut m = TileMemory::default();
        m.insert(tile(3, 1, 0), 10);
        assert!(m.contains(TileId::new(3, 1, 0).unwrap()));
        m.clear();
        assert_eq!(m.len(), 0);
        assert!(m.get(TileId::new(3, 1, 0).unwrap()).is_none());
    }
}
