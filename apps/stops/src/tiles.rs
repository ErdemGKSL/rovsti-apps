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
    AreaDownload, FetchQueue, LruIndex, Priority, TileCover, TileGeometry, TileId, TileSource,
    stand_in,
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
    /// Disk cap per server, bytes (from the prefs).
    cap: u64,
    /// Bumped whenever the server changes, so late results are ignored.
    generation: u64,
    client: TileClient,
    queue: FetchQueue,
    memory: TileMemory,
    /// The offline area download under way (or the last one, finished).
    area: Option<AreaDownload>,
    /// What the app should say about an area download that just finished.
    area_report: Option<String>,
}

impl Default for MapTiles {
    fn default() -> Self {
        Self {
            source: TileSource::default(),
            root: None,
            cache: None,
            cap: DEFAULT_CAP_BYTES,
            generation: 0,
            client: TileClient::new(),
            queue: FetchQueue::new(),
            memory: TileMemory::default(),
            area: None,
            area_report: None,
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
        self.area = None;
        self.area_report = None;
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
    pub fn attach(
        &mut self,
        data_dir: &Path,
        source: TileSource,
        cap: u64,
        cx: &mut Context<StopsApp>,
    ) {
        self.root = Some(cache_root(data_dir));
        self.cap = cap;
        self.set_source(source, cx);
    }

    /// Switches server: forgets the queue and the memory cache, opens the
    /// other server's disk cache (on the background executor).
    pub fn set_source(&mut self, source: TileSource, cx: &mut Context<StopsApp>) {
        self.source = source;
        self.queue.clear();
        self.memory.clear();
        self.area = None;
        self.area_report = None;
        self.cache = None;
        self.generation += 1;
        cx.notify();
        let Some(root) = self.root.clone() else {
            return;
        };
        let (src, generation, cap) = (self.source.clone(), self.generation, self.cap);
        cx.spawn(async move |this, cx| {
            let opened = cx
                .background_executor()
                .spawn(async move { DiskCache::open(&root, &src, cap) })
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

    /// Applies a new disk cap now: the open cache evicts on the background
    /// executor; a cache still opening picks the cap up when it is (re)opened.
    pub fn set_cap(&mut self, cap: u64, cx: &mut Context<StopsApp>) {
        self.cap = cap;
        let Some(cache) = self.cache.clone() else {
            return;
        };
        cx.background_executor()
            .spawn(async move {
                if let Ok(mut cache) = cache.lock() {
                    cache.set_cap(cap);
                    if let Err(error) = cache.flush() {
                        log::warn!("cannot write the tile index: {error}");
                    }
                }
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

    /// Whether the disk cache is open, so downloads can start.
    pub fn is_ready(&self) -> bool {
        self.cache.is_some()
    }

    /// The offline area download, running or finished.
    pub fn area(&self) -> Option<&AreaDownload> {
        self.area.as_ref()
    }

    /// Whether an offline area download is running.
    pub fn area_running(&self) -> bool {
        self.area.as_ref().is_some_and(|a| !a.is_finished())
    }

    /// Starts downloading `ids` as an offline area (queued as prefetch).
    /// `false` when the cache is not open or a download is already running.
    pub fn download_area(&mut self, ids: Vec<TileId>, cx: &mut Context<StopsApp>) -> bool {
        if self.cache.is_none() || self.area_running() {
            return false;
        }
        self.area = Some(AreaDownload::new(ids.iter().copied()));
        self.queue.want(ids, Priority::Prefetch, now_ms());
        self.pump(cx);
        cx.notify();
        true
    }

    /// Stops the area download; tiles already saved are kept. Returns what to
    /// tell the user, or `None` when nothing was running.
    pub fn cancel_area(&mut self, cx: &mut Context<StopsApp>) -> Option<String> {
        if !self.area_running() {
            return None;
        }
        self.queue.drop_prefetch();
        let area = self.area.as_mut()?;
        area.cancel();
        let text = area.finished_text();
        cx.notify();
        Some(text)
    }

    /// The text for an area download that just finished, once.
    pub fn take_area_report(&mut self) -> Option<String> {
        self.area_report.take()
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
                    if let Some(text) = app.map_tiles.take_area_report() {
                        app.on_area_finished(text, cx);
                    }
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
        self.record_area(id, result.is_ok());
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

    /// Notes a handled tile in the area download; remembers the closing text
    /// when it was the last one.
    fn record_area(&mut self, id: TileId, ok: bool) {
        if let Some(area) = self.area.as_mut()
            && !area.is_finished()
            && area.record(id, ok)
            && area.is_finished()
        {
            self.area_report = Some(area.finished_text());
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
    fn the_area_download_reports_once_when_its_last_tile_is_handled() {
        let mut tiles = MapTiles::default();
        let (a, b) = (TileId::new(9, 1, 0).unwrap(), TileId::new(9, 2, 0).unwrap());
        tiles.area = Some(AreaDownload::new([a, b]));
        assert!(tiles.area_running());
        tiles.record_area(a, true);
        assert!(tiles.take_area_report().is_none());
        tiles.record_area(b, false);
        let text = tiles.take_area_report().unwrap();
        assert!(
            text.contains("1 tiles") && text.contains("1 failed"),
            "{text}"
        );
        assert!(!tiles.area_running());
        // Later results change nothing and say nothing.
        tiles.record_area(b, true);
        assert!(tiles.take_area_report().is_none());
        assert_eq!(tiles.area().unwrap().done, 1);
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
