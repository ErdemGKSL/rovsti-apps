//! `tiles.*`: the map tile source of Stops (E2.13).
//!
//! `tiles.open {server_url?, cache_cap?, dir?}` answers `{handle, ...}`: a [`TileSource`], a [`TileClient`] and the
//! per-server [`DiskCache`] under `dir` (default `<cache dir>/tiles`). The `tile(handle, z, x, y, flags)` export reads
//! that handle: the cached tile or a download (stored for next time), decoded by `rovsti_geo`, styled and written
//! as the binary buffer of `docs/kotlin-ui/tile-buffer.md`. A failure there is an ordinary error envelope; the
//! caller tells the two apart by the buffer magic (`RVT1`, a JSON envelope starts with `{`).
//!
//! The cache ops (`usage`, `usage_by_source`, `clear_all`, `clear_except`, `set_cap`) and `test_server` take the
//! handle or a URL. `tiles.offline_start` plans an area and downloads it on a background thread (four tiles at a
//! time, like the GPUI app's queue); progress arrives as `tiles.offline` events and `tiles.offline_cancel` stops it.

use super::registry::{Case, OpSpec, PLACEHOLDER_HANDLE as H};
use crate::envelope::{Answer, ErrorKind, FfiError, encode};
use crate::tile_buffer::{self, FLAG_CACHE_ONLY};
use crate::{events, handles, op, state};
use base64::{Engine, engine::general_purpose::STANDARD};
use rovsti_geo::Coord;
use rovsti_geo::tiles::{
    AREA_MIN_ZOOM, AREA_TILE_BUDGET, AreaDownload, MAX_TILE_ZOOM, TileGeometry, TileId, TileSource,
    estimate_bytes, stand_in, tiles_for_area, tiles_for_circle,
};
use rovsti_tiles::{DEFAULT_CAP_BYTES, DiskCache, TileClient, TileError};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::VecDeque;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

pub(super) const OPS: &[OpSpec] = &[
    op!("tiles.open", OpenReq, OpenAns, open, open_cases),
    op!("tiles.usage", HandleReq, UsageAns, usage, usage_cases),
    op!(
        "tiles.usage_by_source",
        HandleReq,
        SourcesAns,
        usage_by_source,
        sources_cases
    ),
    op!(
        "tiles.clear_all",
        HandleReq,
        FreedAns,
        clear_all,
        clear_all_cases
    ),
    op!(
        "tiles.clear_except",
        ClearExceptReq,
        FreedAns,
        clear_except,
        clear_except_cases
    ),
    op!("tiles.set_cap", SetCapReq, UsageAns, set_cap, set_cap_cases),
    op!(
        "tiles.test_server",
        TestReq,
        TestAns,
        test_server,
        test_cases
    ),
    op!(
        "tiles.stand_in",
        StandInReq,
        StandInAns,
        stand_in_op,
        stand_in_cases
    ),
    op!(
        "tiles.encode_mvt",
        EncodeReq,
        EncodeAns,
        encode_mvt,
        encode_cases
    ),
    op!(
        "tiles.offline_start",
        OfflineReq,
        OfflineAns,
        offline_start,
        offline_cases
    ),
    op!(
        "tiles.offline_cancel",
        CancelReq,
        CancelAns,
        offline_cancel,
        cancel_cases
    ),
];

/// The event kind of offline progress.
pub const OFFLINE_EVENT: &str = "tiles.offline";
/// Tiles downloaded at the same time by an offline job.
const WORKERS: usize = 4;
/// Progress events are at most this often (the last one is always sent).
const PROGRESS_EVERY: Duration = Duration::from_millis(100);

/// What a `tiles.open` handle holds.
pub struct TileState {
    source: TileSource,
    client: TileClient,
    root: PathBuf,
    cap: AtomicU64,
    cache: Mutex<DiskCache>,
}

impl TileState {
    fn cache(&self) -> MutexGuard<'_, DiskCache> {
        self.cache
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Opens the cache directory again (after the files under it were deleted).
    fn reopen(&self) -> Result<(), FfiError> {
        let fresh = DiskCache::open(&self.root, &self.source, self.cap.load(Ordering::Relaxed))?;
        *self.cache() = fresh;
        Ok(())
    }
}

fn state_of(handle: i64) -> Result<Arc<TileState>, FfiError> {
    handles::global()
        .with(handle, |s: &mut Arc<TileState>| Arc::clone(s))
        .ok_or_else(|| {
            FfiError::new(
                ErrorKind::NotFound,
                format!("tile handle {handle} is not open"),
            )
        })
}

/// A download failure as an error envelope: the kind to branch on, a sentence for the user, the cause in `detail`.
pub fn tile_error(error: &TileError) -> FfiError {
    match error {
        TileError::Http(cause) => {
            // The cause (an OS error text) is for the log, not for the wire.
            log::debug!("tile server unreachable: {cause}");
            FfiError::new(
                ErrorKind::Network,
                "The map server could not be reached. Check the URL and the connection.",
            )
            .with_detail(json!({"reason": "unreachable"}))
        }
        TileError::Status(code @ (401 | 403)) => FfiError::new(
            ErrorKind::Auth,
            "The map server refused access. Check the URL.",
        )
        .with_detail(json!({"reason": "refused", "status": code})),
        TileError::Status(404) => FfiError::new(
            ErrorKind::NotFound,
            "The map server has no tile there (404). Check the URL.",
        )
        .with_detail(json!({"reason": "no_tile", "status": 404})),
        TileError::Status(code) => FfiError::new(
            ErrorKind::Network,
            format!("The map server answered with error {code}."),
        )
        .with_detail(json!({"reason": "status", "status": code})),
        TileError::TooLarge => {
            FfiError::invalid("The tile is too large.").with_detail(json!({"reason": "too_large"}))
        }
        TileError::Decode(cause) => {
            FfiError::new(ErrorKind::Parse, "The server does not send vector tiles.")
                .with_detail(json!({"reason": "not_vector", "cause": cause.to_string()}))
        }
        TileError::Io(cause) => {
            FfiError::new(ErrorKind::Io, "The tile could not be stored on the phone.")
                .with_detail(json!({"reason": "storage", "cause": cause.to_string()}))
        }
    }
}

fn source_of(url: &Option<String>) -> Result<TileSource, FfiError> {
    match url {
        None => Ok(TileSource::default_source()),
        Some(text) => TileSource::parse(text).map_err(|error| {
            let reason = match error {
                rovsti_geo::tiles::TileUrlError::Empty => "empty",
                rovsti_geo::tiles::TileUrlError::Scheme => "scheme",
                rovsti_geo::tiles::TileUrlError::Missing(_) => "placeholder",
                rovsti_geo::tiles::TileUrlError::Whitespace => "whitespace",
                rovsti_geo::tiles::TileUrlError::TooLong => "too_long",
            };
            FfiError::invalid(error.to_string()).with_detail(json!({"reason": reason}))
        }),
    }
}

// ---------------------------------------------------------------- tiles.open

#[derive(Deserialize)]
struct OpenReq {
    #[serde(default)]
    server_url: Option<String>,
    /// Cap of the per-server cache in bytes (default 128 MB).
    #[serde(default)]
    cache_cap: Option<u64>,
    /// The cache root; default `<cache dir>/tiles`.
    #[serde(default)]
    dir: Option<String>,
}

#[derive(Serialize)]
struct OpenAns {
    handle: i64,
    /// The directory name of the server's cache.
    key: String,
    template: String,
    is_default: bool,
    cap_bytes: u64,
    /// Bytes already cached for this server.
    cached_bytes: u64,
    cached_tiles: usize,
}

fn open(req: OpenReq) -> Result<OpenAns, FfiError> {
    let source = source_of(&req.server_url)?;
    let root = match req.dir {
        Some(dir) => PathBuf::from(dir),
        None => state::dirs()
            .map(|d| d.cache_dir.join("tiles"))
            .ok_or_else(|| FfiError::invalid("init was not called"))?,
    };
    let cap = req.cache_cap.unwrap_or(DEFAULT_CAP_BYTES);
    let cache = DiskCache::open(&root, &source, cap)?;
    let answer = |handle| OpenAns {
        handle,
        key: source.key(),
        template: source.template().to_owned(),
        is_default: source.is_default(),
        cap_bytes: cap,
        cached_bytes: cache.total_bytes(),
        cached_tiles: cache.len(),
    };
    let preview = answer(0);
    let tile_state = Arc::new(TileState {
        source,
        client: TileClient::new(),
        root,
        cap: AtomicU64::new(cap),
        cache: Mutex::new(cache),
    });
    Ok(OpenAns {
        handle: handles::global().insert(tile_state),
        ..preview
    })
}

fn open_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"cache_cap": 1_048_576, "dir": "$DIR/tiles"})).seeded("stops_files"),
        Case::named(
            "custom",
            json!({"server_url": "https://tiles.example.org/{z}/{x}/{y}.pbf",
                   "cache_cap": 268_435_456, "dir": "$DIR/tiles"}),
        )
        .seeded("stops_files"),
        Case::named(
            "bad_url",
            json!({"server_url": "https://tiles.example.org/{z}/{x}"}),
        ),
        Case::named(
            "no_scheme",
            json!({"server_url": "tiles.example.org/{z}/{x}/{y}"}),
        ),
    ]
}

fn open_setup() -> (&'static str, Value) {
    (
        "tiles.open",
        json!({"cache_cap": 1_048_576, "dir": "$DIR/tiles"}),
    )
}

// ------------------------------------------------------------- cache ops

#[derive(Deserialize)]
struct HandleReq {
    handle: i64,
}

#[derive(Serialize)]
struct UsageAns {
    /// Bytes of every server under the cache root.
    total_bytes: u64,
    /// Bytes and tiles of the open server.
    source_bytes: u64,
    source_tiles: usize,
    cap_bytes: u64,
}

fn usage_of(h: &TileState) -> UsageAns {
    let (source_bytes, source_tiles) = {
        let cache = h.cache();
        (cache.total_bytes(), cache.len())
    };
    UsageAns {
        total_bytes: rovsti_tiles::disk_usage(&h.root),
        source_bytes,
        source_tiles,
        cap_bytes: h.cap.load(Ordering::Relaxed),
    }
}

fn usage(req: HandleReq) -> Result<UsageAns, FfiError> {
    let h = state_of(req.handle)?;
    Ok(usage_of(&h))
}

fn usage_cases() -> Vec<Case> {
    let (op, req) = open_setup();
    vec![
        Case::new(json!({"handle": H}))
            .after(op, req)
            .seeded("stops_files"),
    ]
}

#[derive(Serialize)]
struct SourceRow {
    key: String,
    bytes: u64,
    /// The open server.
    current: bool,
}

#[derive(Serialize)]
struct SourcesAns {
    sources: Vec<SourceRow>,
}

fn usage_by_source(req: HandleReq) -> Result<SourcesAns, FfiError> {
    let h = state_of(req.handle)?;
    let own = h.source.key();
    let sources = rovsti_tiles::usage_by_source(&h.root)
        .into_iter()
        .map(|(key, bytes)| SourceRow {
            current: key == own,
            key,
            bytes,
        })
        .collect();
    Ok(SourcesAns { sources })
}

fn sources_cases() -> Vec<Case> {
    let (op, req) = open_setup();
    vec![
        Case::new(json!({"handle": H}))
            .after(op, req)
            .seeded("stops_files"),
    ]
}

#[derive(Serialize)]
struct FreedAns {
    freed_bytes: u64,
}

fn clear_all(req: HandleReq) -> Result<FreedAns, FfiError> {
    let h = state_of(req.handle)?;
    let freed_bytes = rovsti_tiles::disk_usage(&h.root);
    rovsti_tiles::clear_all(&h.root)?;
    h.reopen()?;
    Ok(FreedAns { freed_bytes })
}

fn clear_all_cases() -> Vec<Case> {
    let (op, req) = open_setup();
    vec![
        Case::new(json!({"handle": H}))
            .after(op, req)
            .seeded("stops_files"),
    ]
}

#[derive(Deserialize)]
struct ClearExceptReq {
    handle: i64,
    /// The server directory to keep; default the open server's.
    #[serde(default)]
    keep_key: Option<String>,
}

fn clear_except(req: ClearExceptReq) -> Result<FreedAns, FfiError> {
    let h = state_of(req.handle)?;
    let keep = req.keep_key.unwrap_or_else(|| h.source.key());
    let freed_bytes = rovsti_tiles::clear_except(&h.root, &keep)?;
    // The open server's cache is untouched unless a different one was kept.
    if keep != h.source.key() {
        h.reopen()?;
    }
    Ok(FreedAns { freed_bytes })
}

fn clear_except_cases() -> Vec<Case> {
    let (op, req) = open_setup();
    vec![
        Case::new(json!({"handle": H}))
            .after(op, req)
            .seeded("stops_files"),
    ]
}

#[derive(Deserialize)]
struct SetCapReq {
    handle: i64,
    cap_bytes: u64,
}

fn set_cap(req: SetCapReq) -> Result<UsageAns, FfiError> {
    let h = state_of(req.handle)?;
    h.cap.store(req.cap_bytes, Ordering::Relaxed);
    {
        let mut cache = h.cache();
        cache.set_cap(req.cap_bytes);
        cache.flush()?;
    }
    Ok(usage_of(&h))
}

fn set_cap_cases() -> Vec<Case> {
    let (op, req) = open_setup();
    vec![
        Case::new(json!({"handle": H, "cap_bytes": 33_554_432}))
            .after(op, req)
            .seeded("stops_files"),
    ]
}

// ------------------------------------------------------------ test_server

#[derive(Deserialize)]
struct TestReq {
    server_url: String,
}

#[derive(Serialize)]
struct TestAns {
    /// Layers of tile 0/0/0 (at least one is a Shortbread layer).
    layers: usize,
}

/// Downloads tile 0/0/0 of the server and decodes it, so a wrong URL is caught before it is saved.
fn test_server(req: TestReq) -> Result<TestAns, FfiError> {
    let source = source_of(&Some(req.server_url))?;
    let layers = TileClient::new()
        .test_server(&source)
        .map_err(|e| tile_error(&e))?;
    Ok(TestAns { layers })
}

fn test_cases() -> Vec<Case> {
    // The main case needs a server; an address nothing listens on answers `network` at once, offline too.
    vec![
        Case::new(json!({"server_url": "http://127.0.0.1:1/{z}/{x}/{y}"})),
        Case::named("bad_url", json!({"server_url": "https://a.b/{z}/{x}"})),
        Case::named("empty", json!({"server_url": "  "})),
    ]
}

// --------------------------------------------------------------- stand_in

#[derive(Deserialize)]
struct StandInReq {
    want: TileId,
    have: Vec<TileId>,
}

#[derive(Serialize)]
struct StandInAns {
    /// The wanted tile itself, else its nearest loaded ancestor (up to four zooms up), else `null`.
    tile: Option<TileId>,
}

fn stand_in_op(req: StandInReq) -> Result<StandInAns, FfiError> {
    Ok(StandInAns {
        tile: stand_in(req.want, |id| req.have.contains(&id)),
    })
}

fn stand_in_cases() -> Vec<Case> {
    let t = |z, x, y| json!({"z": z, "x": x, "y": y});
    vec![
        Case::new(json!({"want": t(14, 9520, 6130), "have": [t(12, 2380, 1532), t(10, 595, 383)]})),
        Case::named(
            "none",
            json!({"want": t(14, 9520, 6130), "have": [t(8, 148, 95)]}),
        ),
        Case::named(
            "itself",
            json!({"want": t(14, 9520, 6130), "have": [t(14, 9520, 6130), t(13, 4760, 3065)]}),
        ),
    ]
}

// ------------------------------------------------------------- encode_mvt

#[derive(Deserialize)]
struct EncodeReq {
    /// A Mapbox vector tile (gzip is accepted), base64.
    mvt: String,
    z: u8,
    x: u32,
    y: u32,
    #[serde(default)]
    flags: u16,
}

#[derive(Serialize)]
struct EncodeAns {
    buffer: String,
}

/// The pure half of the `tile` export: decode, clip, style, encode. No cache and no network.
fn encode_mvt(req: EncodeReq) -> Result<EncodeAns, FfiError> {
    let id = TileId::new(req.z, req.x, req.y)
        .ok_or_else(|| FfiError::invalid("that tile is outside the map"))?;
    let bytes = STANDARD
        .decode(req.mvt.as_bytes())
        .map_err(|e| FfiError::new(ErrorKind::Parse, format!("mvt is not base64: {e}")))?;
    let geometry = if bytes.is_empty() {
        TileGeometry::empty(id)
    } else {
        TileGeometry::from_bytes(id, &bytes).map_err(|e| tile_error(&TileError::Decode(e)))?
    };
    Ok(EncodeAns {
        buffer: STANDARD.encode(tile_buffer::encode(&geometry, req.flags)),
    })
}

fn encode_cases() -> Vec<Case> {
    let mvt = STANDARD.encode(include_bytes!(
        "../../../rovsti_geo/src/tiles/testdata/osm-14-9520-6130.mvt"
    ));
    vec![
        Case::new(json!({"mvt": mvt, "z": 14, "x": 9520, "y": 6130, "flags": 0})),
        Case::named(
            "dark",
            json!({"mvt": mvt, "z": 14, "x": 9520, "y": 6130, "flags": 1}),
        ),
        Case::named(
            "empty",
            json!({"mvt": "", "z": 3, "x": 1, "y": 2, "flags": 0}),
        ),
        Case::named(
            "garbage",
            json!({"mvt": STANDARD.encode(b"not a tile"), "z": 3, "x": 1, "y": 2}),
        ),
        Case::named("outside", json!({"mvt": "", "z": 3, "x": 8, "y": 0})),
    ]
}

// ------------------------------------------------------------ tile export

/// The `tile` export: a tile buffer, or an error envelope (kind `not_found` for a tile that is not cached while
/// `FLAG_CACHE_ONLY` is set). Never panics across the boundary.
pub fn tile_export(handle: i64, z: i32, x: i32, y: i32, flags: i32) -> Vec<u8> {
    let run = || tile_buffer_of(handle, z, x, y, flags);
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(run)) {
        Ok(Ok(bytes)) => bytes,
        Ok(Err(error)) => encode(&Answer::Err(error)),
        Err(_) => encode(&Answer::Err(FfiError::new(
            ErrorKind::Panic,
            "the tile could not be built",
        ))),
    }
}

fn tile_buffer_of(handle: i64, z: i32, x: i32, y: i32, flags: i32) -> Result<Vec<u8>, FfiError> {
    let h = state_of(handle)?;
    let id = u8::try_from(z)
        .ok()
        .zip(u32::try_from(x).ok())
        .zip(u32::try_from(y).ok())
        .and_then(|((z, x), y)| TileId::new(z, x, y))
        .ok_or_else(|| FfiError::invalid("that tile is outside the map"))?;
    let flags = (flags & 0xFFFF) as u16;
    let cache_only = flags & FLAG_CACHE_ONLY != 0;
    if cache_only && !h.cache().contains(id) {
        return Err(
            FfiError::new(ErrorKind::NotFound, "The tile is not cached.")
                .with_detail(json!({"reason": "not_cached"})),
        );
    }
    let geometry = rovsti_tiles::load(&h.cache, &h.client, &h.source, id, cache_only)
        .map_err(|e| tile_error(&e))?;
    Ok(tile_buffer::encode(&geometry, flags))
}

// ----------------------------------------------------------------- offline

/// A running or finished offline download. Dropping the handle cancels it.
struct Job {
    cancel: Arc<AtomicBool>,
    area: Arc<Mutex<AreaDownload>>,
}

impl Drop for Job {
    fn drop(&mut self) {
        self.cancel.store(true, Ordering::Relaxed);
    }
}

#[derive(Deserialize)]
struct Bbox {
    south: f64,
    west: f64,
    north: f64,
    east: f64,
}

#[derive(Deserialize, Default)]
struct Zooms {
    #[serde(default)]
    min: Option<u8>,
    #[serde(default)]
    max: Option<u8>,
}

/// The tiles around a crossing, for a trip that must work offline.
#[derive(Deserialize)]
struct Circle {
    center: Coord,
    radius_m: f64,
    /// Extra metres around the circle (default 500).
    #[serde(default)]
    margin_m: Option<f64>,
    /// Most tiles to download (default 600).
    #[serde(default)]
    budget: Option<usize>,
}

/// An area is a box (`bbox`) or a circle; exactly one of them.
#[derive(Deserialize)]
struct OfflineReq {
    handle: i64,
    #[serde(default)]
    bbox: Option<Bbox>,
    #[serde(default)]
    circle: Option<Circle>,
    #[serde(default)]
    zooms: Zooms,
}

#[derive(Serialize)]
struct OfflineAns {
    /// The job: pass it to `tiles.offline_cancel` and release it when done.
    handle: i64,
    total: usize,
    /// The highest zoom the budget allowed.
    top_zoom: u8,
    /// The area was cut short of the wanted zoom by the 2 500-tile budget.
    capped: bool,
    estimate_bytes: u64,
}

type Emit = Arc<dyn Fn(&Value) + Send + Sync>;

fn offline_start(req: OfflineReq) -> Result<OfflineAns, FfiError> {
    let emit: Emit = Arc::new(|payload| {
        events::emit(OFFLINE_EVENT, payload);
    });
    start_job(req, emit)
}

fn start_job(req: OfflineReq, emit: Emit) -> Result<OfflineAns, FfiError> {
    let h = state_of(req.handle)?;
    let min = req.zooms.min.unwrap_or(AREA_MIN_ZOOM);
    let max = req.zooms.max.unwrap_or(MAX_TILE_ZOOM);
    if min > max {
        return Err(
            FfiError::invalid("the zoom range is empty").with_detail(json!({"reason": "zooms"}))
        );
    }
    let (tiles, top_zoom, capped) = match (&req.bbox, &req.circle) {
        (Some(b), None) => {
            let sane = [b.south, b.west, b.north, b.east]
                .iter()
                .all(|v| v.is_finite())
                && b.south.abs() <= 90.0
                && b.north.abs() <= 90.0
                && b.south < b.north
                && b.west < b.east
                && b.east - b.west <= 360.0;
            if !sane {
                return Err(FfiError::invalid("that map area is not valid")
                    .with_detail(json!({"reason": "bbox"})));
            }
            let corners = [
                Coord::new(b.north, b.west),
                Coord::new(b.north, b.east),
                Coord::new(b.south, b.east),
                Coord::new(b.south, b.west),
            ];
            let plan = tiles_for_area(&corners, min, max, AREA_TILE_BUDGET);
            (plan.tiles, plan.top_zoom, plan.capped)
        }
        (None, Some(c)) => {
            let sane = c.center.lat.is_finite()
                && c.center.lon.is_finite()
                && c.center.lat.abs() <= 90.0
                && c.radius_m.is_finite()
                && c.radius_m >= 0.0;
            if !sane {
                return Err(FfiError::invalid("that map area is not valid")
                    .with_detail(json!({"reason": "circle"})));
            }
            let budget = c.budget.unwrap_or(600).min(AREA_TILE_BUDGET);
            let mut tiles = tiles_for_circle(
                c.center,
                c.radius_m,
                c.margin_m.unwrap_or(500.0).max(0.0),
                min,
                budget,
            );
            // `tiles_for_circle` always goes up to the last zoom; the request may stop sooner.
            tiles.retain(|t| t.z <= max);
            let top = tiles.iter().map(|t| t.z).max().unwrap_or(min);
            (tiles, top, top < max.min(MAX_TILE_ZOOM))
        }
        _ => {
            return Err(FfiError::invalid("name either bbox or circle")
                .with_detail(json!({"reason": "area"})));
        }
    };
    let plan = rovsti_geo::tiles::AreaPlan {
        tiles,
        top_zoom,
        capped,
    };
    if plan.tiles.is_empty() {
        return Err(
            FfiError::invalid("that map area has no tiles").with_detail(json!({"reason": "empty"}))
        );
    }
    let total = plan.tiles.len();
    let area = Arc::new(Mutex::new(AreaDownload::new(plan.tiles.iter().copied())));
    let cancel = Arc::new(AtomicBool::new(false));
    let job = handles::global().insert(Job {
        cancel: Arc::clone(&cancel),
        area: Arc::clone(&area),
    });
    let queue: VecDeque<TileId> = plan.tiles.into();
    let spawned = std::thread::Builder::new()
        .name("tiles-offline".to_owned())
        .spawn(move || run_job(job, &h, queue, &area, &cancel, &emit));
    if let Err(error) = spawned {
        handles::global().release(job);
        return Err(error.into());
    }
    Ok(OfflineAns {
        handle: job,
        total,
        top_zoom: plan.top_zoom,
        capped: plan.capped,
        estimate_bytes: estimate_bytes(total),
    })
}

fn progress(job: i64, area: &AreaDownload, finished: bool) -> Value {
    let handled = area.done + area.failed;
    json!({
        "job": job,
        "done": handled,
        "saved": area.done,
        "failed": area.failed,
        "total": area.total,
        "fraction": area.fraction(),
        "text": if finished { area.finished_text() } else { area.progress_text() },
        "finished": finished,
        "cancelled": area.cancelled,
    })
}

fn run_job(
    job: i64,
    h: &TileState,
    queue: VecDeque<TileId>,
    area: &Mutex<AreaDownload>,
    cancel: &AtomicBool,
    emit: &Emit,
) {
    let queue = Mutex::new(queue);
    let last_sent = Mutex::new(Instant::now() - PROGRESS_EVERY);
    let lock_area = || {
        area.lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    };
    let lock_queue = || {
        queue
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    };
    std::thread::scope(|scope| {
        for _ in 0..WORKERS {
            scope.spawn(|| {
                while !cancel.load(Ordering::Relaxed) {
                    let Some(id) = lock_queue().pop_front() else {
                        break;
                    };
                    let cached = h.cache().contains(id);
                    let ok = cached
                        || rovsti_tiles::load(&h.cache, &h.client, &h.source, id, false).is_ok();
                    let payload = {
                        let mut area = lock_area();
                        area.record(id, ok);
                        let mut last = last_sent
                            .lock()
                            .unwrap_or_else(std::sync::PoisonError::into_inner);
                        if last.elapsed() < PROGRESS_EVERY || area.is_finished() {
                            None
                        } else {
                            *last = Instant::now();
                            Some(progress(job, &area, false))
                        }
                    };
                    if let Some(payload) = payload {
                        emit(&payload);
                    }
                }
            });
        }
    });
    let last = {
        let mut area = lock_area();
        // Every tile that was in flight is recorded by now; what is still queued is dropped.
        if cancel.load(Ordering::Relaxed) && !area.pending.is_empty() {
            area.cancel();
        }
        progress(job, &area, true)
    };
    emit(&last);
}

#[derive(Deserialize)]
struct CancelReq {
    job: i64,
}

#[derive(Serialize)]
struct CancelAns {
    /// Tiles saved so far (they stay in the cache).
    saved: usize,
    total: usize,
}

/// Stops the download; tiles already saved are kept. The last `tiles.offline` event (`finished`, `cancelled`)
/// follows when the downloads in flight have ended.
fn offline_cancel(req: CancelReq) -> Result<CancelAns, FfiError> {
    handles::global()
        .with(req.job, |job: &mut Job| {
            job.cancel.store(true, Ordering::Relaxed);
            let area = job
                .area
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            CancelAns {
                saved: area.done,
                total: area.total,
            }
        })
        .ok_or_else(|| {
            FfiError::new(
                ErrorKind::NotFound,
                format!("download {} is not open", req.job),
            )
        })
}

fn offline_cases() -> Vec<Case> {
    let setup = (
        "tiles.open",
        json!({"server_url": "http://127.0.0.1:1/{z}/{x}/{y}", "dir": "$DIR/tiles"}),
    );
    // Istanbul, about 2 km a side, zooms 9-11: a handful of tiles that nothing serves.
    let area = json!({"south": 41.00, "west": 28.96, "north": 41.02, "east": 28.99});
    vec![
        Case::new(json!({"handle": H, "bbox": area, "zooms": {"min": 9, "max": 11}}))
            .after(setup.0, setup.1.clone())
            .seeded("stops_files"),
        Case::named(
            "circle",
            json!({"handle": H, "zooms": {"min": 10, "max": 12},
                   "circle": {"center": {"lat": 41.0, "lon": 28.994}, "radius_m": 600.0,
                              "margin_m": 300.0, "budget": 40}}),
        )
        .after(setup.0, setup.1.clone())
        .seeded("stops_files"),
        Case::named("no_area", json!({"handle": H}))
            .after(setup.0, setup.1.clone())
            .seeded("stops_files"),
        Case::named(
            "bad_bbox",
            json!({"handle": H, "bbox": {"south": 42.0, "west": 28.0, "north": 41.0, "east": 29.0}}),
        )
        .after(setup.0, setup.1.clone())
        .seeded("stops_files"),
        Case::named(
            "bad_zooms",
            json!({"handle": H, "bbox": area, "zooms": {"min": 12, "max": 9}}),
        )
        .after(setup.0, setup.1)
        .seeded("stops_files"),
    ]
}

fn cancel_cases() -> Vec<Case> {
    let open = (
        "tiles.open",
        json!({"server_url": "http://127.0.0.1:1/{z}/{x}/{y}", "dir": "$DIR/tiles"}),
    );
    let start = (
        "tiles.offline_start",
        json!({"handle": H,
               "bbox": {"south": 41.00, "west": 28.96, "north": 41.02, "east": 28.99},
               "zooms": {"min": 9, "max": 11}}),
    );
    vec![
        Case::new(json!({"job": H}))
            .after(open.0, open.1)
            .after(start.0, start.1)
            .seeded("stops_files"),
        Case::named("unknown", json!({"job": 4_294_967_300_i64})),
    ]
}

#[cfg(test)]
mod tests;
