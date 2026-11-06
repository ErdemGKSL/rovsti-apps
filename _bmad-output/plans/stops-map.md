# Rovsti Stops: vector map tiles under the radar map (plan, round 1)

Base: `f763d18` (nothing tile-related exists yet: no `tiles` module, no MVT code,
no tile setting; `docs/roadmap.md` still says "decided: vector radar map, no tiles").

Checks after every step (all must pass):

```
cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace
cargo check -p rovsti_android --target aarch64-linux-android
cargo check -p rovsti_wa_client --target aarch64-linux-android
# from step 6 on, also:
cargo check -p rovsti_tiles --target aarch64-linux-android
```

If `df` shows under 6 GB free: `rm -rf target/debug/incremental` before building.

## Facts gathered for this plan (2026-11-06)

- `https://tiles.versatiles.org/tiles/osm/{z}/{x}/{y}` answers `200`,
  `content-type: application/vnd.mapbox-vector-tile`, and honours
  `Accept-Encoding` (identity, gzip or br). Empty tiles may be 37-42 byte tiles.
  `https://maps.maltepe.edu.tr/tiles/osm/...` answers `204 No Content` for tiles it
  has no data for (treat 204 and 404 as "empty tile", not an error).
- `0/0/0` is ~60 KB (versatiles, gzip) / ~110 KB (maltepe): good "test the server" tile.
- Small real fixture: `14/9520/6130` (Istanbul, 6 508 bytes uncompressed). Layers
  (name, feature count, extent 4096): `buildings 124`, `land 3`, `mountain_peaks 1`,
  `pois 3`, `street_labels 1`, `streets 6` (keys `kind, service, surface, oneway`),
  `water_lines 1`.
- GPUI at the pinned rev (`crates/gpui/src/path_builder.rs`): `PathBuilder::fill()` uses
  lyon `FillOptions::default()` (even-odd rule, so holes work as extra subpaths in the
  same path); `PathBuilder::stroke(px(w))`, `add_polygon(&[Point<Pixels>], closed)`,
  `move_to/line_to/close`, `build()`. Vertex buffers are `u16`: keep each built path
  under ~8 000 input points (chunk). `gpui::rgba(0xRRGGBBAA) -> Rgba`, `Hsla: From<Rgba>`.
  `window.with_content_mask(Some(ContentMask { bounds }), |w| ..)` exists.
- `apps/stops/src/main.rs` `render` calls `rovsti_ui::current_theme(window)` every frame
  and `map_card(theme, ..)` builds `Palette::new(theme)`, so a theme-keyed map style
  switches instantly with the system theme (FOLLOW_SYSTEM_THEME) for free.
- `rovsti_wa_client::http::build_agent` is the TLS template: `TlsConfig` with
  `TlsProvider::Rustls`, `unversioned_rustls_crypto_provider(Arc::new(rustls_graviola::default_provider()))`,
  `RootCerts::WebPki`; `Config::builder().http_status_as_error(false)` + timeouts.
  ureq 3.5 has a `gzip` feature (flate2, pure Rust backend).
- The Stops Android manifest already has `INTERNET`.

### VersaTiles style colours (source: versatiles-style, as served at
`https://tiles.versatiles.org/assets/styles/{colorful,eclipse}/style.json`; layer ids in brackets)

| class | Shortbread filter | min z | colorful | eclipse |
| --- | --- | --- | --- | --- |
| background [background] | - | 0 | 248,244,240 | 40,39,37 |
| ocean [water-ocean] | `ocean` | 0 | 191,217,242 | 12,28,42 |
| water area [water-area*] | `water_polygons` kind water/river/reservoir/basin/dock | 4 | 191,217,242 | 12,28,42 |
| glacier [land-glacier] | `water_polygons` kind glacier | 4 | 255,255,255 | 70,70,70 |
| waterway [water-river/canal] | `water_lines` kind river/canal | 9 | 166,202,237 | 22,44,64 |
| waterway small [water-stream/ditch] | `water_lines` kind stream/ditch | 14 | 166,202,237 | 22,44,64 |
| commercial | `land` commercial, retail | 10 | 248,238,239 | 46,40,41 |
| industrial | `land` industrial, quarry, railway | 10 | 250,244,225 | 43,39,28 |
| residential | `land` garages, residential | 10 | 245,241,237 | 43,40,38 |
| agriculture | `land` farmland, farmyard, greenhouse_horticulture, orchard, plant_nursery, vineyard | 10 | 233,242,206 | 41,45,23 |
| waste | `land` landfill | 10 | 219,214,189 | 62,59,43 |
| park | `land` park, village_green, recreation_ground | 11 | 205,217,169 @0.5 | 56,63,31 @0.5 |
| garden | `land` allotments, garden | 11 | 205,217,169 | 56,63,31 |
| burial | `land` cemetery, grave_yard | 13 | 224,228,221 | 49,51,47 |
| leisure | `land` miniature_golf, playground, golf_course | 11 | 231,237,222 | 43,47,37 |
| rock | `land` bare_rock, scree, shingle | 11 | 242,242,230 | 42,41,34 |
| forest | `land` forest | 7 | 203,229,184 | 43,59,29 |
| grass | `land` grass, grassland, meadow, wet_meadow | 11 | 209,229,195 | 44,57,35 |
| vegetation | `land` heath, scrub | 11 | 230,238,199 | 44,48,23 |
| sand | `land` beach, sand | 10 | 242,242,206 | 44,43,20 |
| wetland | `land` bog, marsh, string_bog, swamp | 11 | 211,230,219 | 42,54,47 |
| pier/dam area | `pier_polygons`, `dam_polygons` | 12 | 248,244,240 | 40,39,37 |
| pedestrian area | `street_polygons` kind pedestrian | 14 | 251,235,255 | 48,39,50 |
| building | `buildings` | 14 | 242,234,226 (outline 223,219,215) | 49,44,39 (outline 57,55,52) |
| motorway (+link) | `streets` kind motorway | 5 | 255,204,136 casing 233,172,119 | 105,71,16 casing 90,49,4 |
| trunk/primary/secondary (+links) | kind trunk, primary, secondary | 8 | 255,238,170 casing 233,172,119 | 105,93,43 casing 90,49,4 |
| tertiary (+link) | kind tertiary | 10 | 255,255,255 casing 207,205,202 | 82,82,82 casing 53,52,50 |
| minor | kind residential, unclassified, living_street | 12 | 255,255,255 casing 207,205,202 | 82,82,82 casing 53,52,50 |
| service / busway | kind service, busway, bus_guideway | 14 | 247,247,247 casing 221,220,218 | 87,87,87 casing 62,61,60 |
| pedestrian | kind pedestrian | 13 | 251,235,255 casing 207,205,202 | 48,39,50 casing 53,52,50 |
| track | kind track | 14 | 255,255,255 casing 207,205,202 | 82,82,82 casing 53,52,50 |
| path | kind footway, steps, path | 13 | 213,200,217 | 79,71,81 |
| cycleway | kind cycleway | 13 | 203,212,217 | 69,73,77 |
| rail | kind rail, light_rail (also subway, tram) | 8 | 200,207,214 casing 177,187,196 | 50,55,59 casing 71,78,84 |
| boundary country | `boundaries` admin_level 2, not maritime | 0 | 166,166,200 | 87,87,112 |
| boundary state | `boundaries` admin_level 4 | 7 | 166,166,200 | 87,87,112 |

Street features with `tunnel = true` are drawn like the class but at 50 % alpha;
`bridge` is drawn like the class (no special handling). Line widths at z14 (px, from the
colorful `line-width` stops): motorway 4, trunk/primary/secondary 5, tertiary 3,
minor 2, service 1.5, pedestrian/track 1.5, path/cycleway 1, rail 1.5, waterway 2,
boundary 1.5. Width at display zoom `dz` = `w14 * 2^(clamp(dz - 14, -3, 2))`, at least
0.5 px. Casing is drawn first at `width + 1.5`.

## Architecture decisions

- **Pure logic in `rovsti_geo::tiles`** (new module dir `crates/rovsti_geo/src/tiles/`):
  slippy math, URL/source, MVT decoder (hand-written, no prost; flate2 is already a dep
  for gzip), Shortbread classification, style tables, clipping and simplification,
  per-tile draw geometry, the LRU index and the fetch queue. All unit-tested.
- **I/O in a new crate `crates/rovsti_tiles`**: blocking HTTP over ureq + rustls-graviola
  (same approach as `rovsti_wa_client`, own small agent builder, not a dependency on the
  WhatsApp client), and the on-disk cache. Platform-free (no GPUI, no JNI), compiles for
  `aarch64-linux-android`.
- **`apps/stops/src/map.rs` stays thin**: picks the tiles for the view, asks a loader,
  transforms cached per-tile geometry to the screen and paints it before the existing
  layers. The tile loading glue (GPUI tasks on the background executor) lives in a new
  `apps/stops/src/tiles.rs` so `map.rs` does not grow further.
- Projection: tiles are Web Mercator; the map's `Viewport` is a local equirectangular
  projection. Each tile is mapped to the screen with a per-tile affine transform built
  from projecting its NW and SE corners through `Viewport::project` (error < 0.5 px at
  city scale; tested). Geometry is decoded once into tile units and kept in memory.

## Steps

### Step 1. Slippy tile math and the tile source setting (rovsti_geo, pure)

Files: `crates/rovsti_geo/src/tiles/mod.rs`, `tiles/slippy.rs`, `tiles/source.rs`,
`crates/rovsti_geo/src/lib.rs` (`pub mod tiles;`), `crates/rovsti_geo/src/prefs.rs`.

`tiles/slippy.rs`:
```rust
pub const MAX_TILE_ZOOM: u8 = 14;          // server max; overzoom above
pub const TILE_DISPLAY_PX: f64 = 512.0;    // vector tiles are designed for 512 px
pub const MAX_MERCATOR_LAT: f64 = 85.051_128_78;
const EQUATOR_M: f64 = 40_075_016.686;
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
pub struct TileId { pub z: u8, pub x: u32, pub y: u32 }
impl TileId {
    pub fn new(z: u8, x: u32, y: u32) -> Option<Self>;   // z <= 24, x,y < 2^z
    pub fn parent(self) -> Option<Self>;
    pub fn ancestor(self, z: u8) -> Option<Self>;        // None if z > self.z
    pub fn nw(self) -> Coord;  pub fn se(self) -> Coord;
}
pub fn tile_frac(c: Coord, z: u8) -> (f64, f64);         // clamps lat, wraps lon
pub fn frac_to_coord(z: u8, fx: f64, fy: f64) -> Coord;
pub fn tile_containing(c: Coord, z: u8) -> TileId;
pub fn display_zoom(vp: &Viewport) -> f64;               // log2(EQUATOR_M*cos(lat)/(TILE_DISPLAY_PX*mpp))
pub fn fetch_zoom(display_zoom: f64) -> u8;              // round, clamp 0..=MAX_TILE_ZOOM
pub struct TileCover { pub zoom: u8, pub tiles: Vec<TileId> }
/// Tiles at fetch_zoom covering the widget (unprojected corners + 1 tile margin),
/// nearest the centre first; drops one zoom level while more than max_tiles.
pub fn tiles_for_view(vp: &Viewport, size: (f32, f32), max_tiles: usize) -> TileCover;
/// Prefetch: tiles covering the circle's bbox + margin at every zoom from min_zoom up to
/// the highest zoom (<= 14) whose cumulative count stays within budget; coarse first.
pub fn tiles_for_circle(center: Coord, radius_m: f64, margin_m: f64, min_zoom: u8, budget: usize) -> Vec<TileId>;
```
x wraps modulo 2^z across the antimeridian; y is clamped.

`tiles/source.rs`:
```rust
pub const DEFAULT_TILE_URL: &str = "https://tiles.versatiles.org/tiles/osm/{z}/{x}/{y}";
pub const ATTRIBUTION: &str = "© OpenStreetMap contributors";
#[derive(Clone, Debug, PartialEq, Eq)] pub struct TileSource { template: String }
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum TileUrlError { Empty, Scheme /* not http(s):// */, Missing(&'static str) /* {z},{x},{y} once each */, Whitespace, TooLong /* > 512 */ }
impl TileSource {
    pub fn parse(text: &str) -> Result<Self, TileUrlError>;  // trims
    pub fn default_source() -> Self;
    pub fn template(&self) -> &str;  pub fn is_default(&self) -> bool;
    pub fn url(&self, id: TileId) -> String;
    /// 16 lowercase hex chars, FNV-1a 64 of the template (stable across Rust versions;
    /// never std's DefaultHasher). Names the per-server cache directory.
    pub fn key(&self) -> String;
}
```
`StopsPrefs` gains `pub tile_url: Option<String>` (`None` = default; `#[serde(default)]`
already on the struct) and `pub fn tile_source(&self) -> TileSource` (an invalid stored
value falls back to the default) and `pub fn set_tile_url(&mut self, s: &TileSource)`
(stores `None` when it is the default).

Tests: lon/lat -> tile for known points (Istanbul `41.0082, 28.9784` z14 ->
`14/9510/6140`; any point z0 -> 0/0/0; `(0.0001, 0.0001)` z1 -> 1/1/0), frac round trip < 1e-9,
clamping at ±89°, lon 180 wraps to x=0, `parent`/`ancestor`, `display_zoom` vs a known
mpp (z14 at the equator = 4.89 m/px), `tiles_for_view` for a 370x340 widget at
`Viewport::new(IST, 2.0)` returns a handful of z14 tiles all intersecting the view and
sorted by distance, `max_tiles` forces a lower zoom, overzoom (mpp 0.5) still gives
z14; `tiles_for_circle` for a 1 km and a 50 km diameter stays within budget, includes
the centre tile at its top zoom and is coarse-first; `TileSource::parse` accepts both
owner URLs, rejects empty, `ftp://`, missing `{y}`, duplicate `{z}`, spaces;
`url()` fills placeholders; `key()` is stable (hard-coded expected hex for the default
URL) and differs for the maltepe URL; prefs round trip with and without `tile_url`, an
old prefs JSON without the field loads with `None`.

Acceptance: checks green; only `crates/rovsti_geo` changes.

### Step 2. Mapbox Vector Tile decoder with a real fixture (rovsti_geo, pure)

Files: `crates/rovsti_geo/src/tiles/mvt.rs`,
`crates/rovsti_geo/src/tiles/testdata/osm-14-9520-6130.mvt` (download once with
`curl -sS -o ... https://tiles.versatiles.org/tiles/osm/14/9520/6130`, uncompressed,
~6.5 KB; mention source + licence "© OpenStreetMap contributors, ODbL" in a
`testdata/README.md`).

Hand-written protobuf reader (no prost): varint (max 10 bytes), zigzag, wire types 0/1/2/5,
unknown fields skipped. Types:
```rust
pub struct MvtTile { pub layers: Vec<MvtLayer> }
pub struct MvtLayer { pub name: String, pub extent: u32 /* default 4096 */, pub keys: Vec<String>, pub values: Vec<MvtValue>, pub features: Vec<MvtFeature> }
pub enum MvtValue { String(String), Float(f32), Double(f64), Int(i64), UInt(u64), SInt(i64), Bool(bool) }
pub enum GeomType { Unknown, Point, LineString, Polygon }
pub struct MvtFeature { pub id: Option<u64>, pub tags: Vec<u32>, pub geom_type: GeomType, pub geometry: Vec<u32> }
impl MvtLayer { pub fn get(&self, f: &MvtFeature, key: &str) -> Option<&MvtValue>; pub fn str_of(&self, f, key) -> Option<&str>; }
impl MvtValue { pub fn as_str(&self) -> Option<&str>; pub fn as_i64(&self) -> Option<i64>; pub fn as_bool(&self) -> Option<bool>; }
impl MvtFeature { pub fn decode_geometry(&self) -> Result<Vec<Vec<(i32, i32)>>, MvtError>; } // MoveTo/LineTo/ClosePath; one Vec per part/ring; ClosePath repeats the first point
pub fn ring_area2(ring: &[(i32, i32)]) -> i64;   // signed doubled area; > 0 = exterior (MVT 2.1, y down)
pub fn decode(bytes: &[u8]) -> Result<MvtTile, MvtError>;
pub fn decode_any(bytes: &[u8]) -> Result<MvtTile, MvtError>; // gunzips (flate2) when it starts 1f 8b, inflate cap 16 MiB
#[derive(Debug, thiserror::Error)] pub enum MvtError { Truncated, Varint, WireType(u8), Utf8, Geometry, TooLarge }
```
Tests: the fixture decodes to exactly the layers and counts listed in "Facts" above,
every layer extent 4096, `streets` keys include `kind`, every `buildings` feature is a
Polygon whose first ring has positive `ring_area2`, every decoded vertex is within
`-extent..2*extent`; gzip of the fixture (compressed in the test with flate2) decodes
identically; a hand-built tile (small test-only encoder) covers multi-part MoveTo,
ClosePath, a polygon with a hole (negative ring), packed tags, unknown fields, all value
kinds; truncated buffers at every prefix length of the fixture return `Err` and never
panic; an 11-byte varint is `Err(Varint)`; a geometry command count larger than the data
is `Err(Geometry)`.

### Step 3. Shortbread classes and the colorful/eclipse style table (rovsti_geo, pure)

Files: `crates/rovsti_geo/src/tiles/shortbread.rs`, `tiles/style.rs`.

```rust
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum DrawClass {  // declaration order = paint order
    Ocean, Water, Glacier, Land(LandKind), PierArea, PedestrianArea, Building,
    Waterway { small: bool }, Street { kind: StreetKind, tunnel: bool }, Rail { tunnel: bool },
    Boundary { country: bool },
}
pub enum LandKind { Commercial, Industrial, Residential, Agriculture, Waste, Park, Garden, Burial, Leisure, Rock, Forest, Grass, Vegetation, Sand, Wetland }
pub enum StreetKind { Path, Cycleway, Track, Pedestrian, Service, Minor, Tertiary, Arterial /* trunk,primary,secondary */, Motorway }
pub enum Shape { Fill, Line }
impl DrawClass { pub fn shape(self) -> Shape; pub fn min_zoom(self) -> u8; }
/// Layer name + feature properties -> class, `None` for layers/kinds not drawn
/// (labels, pois, addresses, place_labels, public_transport, aerialways, ferries,
/// bridges, sites, maritime boundaries, unknown kinds).
pub fn classify(layer: &MvtLayer, f: &MvtFeature) -> Option<DrawClass>;
```
Mapping exactly as the table in "Facts" (`link` streets map to their parent kind).

`tiles/style.rs`:
```rust
#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum MapStyle { Colorful, Eclipse }
#[derive(Clone, Copy, Debug, PartialEq)] pub struct Rgba8 { pub r: u8, pub g: u8, pub b: u8, pub a: f32 }
impl Rgba8 { pub fn to_u32(self) -> u32 /* 0xRRGGBBAA for gpui::rgba */ }
pub struct Paint { pub color: Rgba8, pub casing: Option<Rgba8>, pub width14: f32 }
impl MapStyle {
    pub fn background(self) -> Rgba8;
    pub fn paint(self, class: DrawClass) -> Paint;   // tunnel: color alpha halved
}
pub fn line_width(width14: f32, display_zoom: f64) -> f32;  // w14 * 2^(clamp(dz-14,-3,2)), >= 0.5
```
A header comment cites versatiles-style (github.com/versatiles-org/versatiles-style,
styles `colorful` and `eclipse`, as served at tiles.versatiles.org/assets/styles/*/style.json
on 2026-11-06) and its licence (Unlicense / public domain at the time of writing; the
implementer checks the repo's licence text if network allows, else states "see upstream").

Tests: every class has a paint in both styles; a few spot values equal the table
(background, ocean, motorway, building) for both styles; eclipse background is darker
than colorful (luma); classify on the fixture: `buildings` -> Building, `streets` kinds
map to the expected StreetKinds, `street_labels`/`pois`/`mountain_peaks` -> None,
`land` kinds map; hand-built features: `ocean` -> Ocean, `boundaries` admin_level 2
maritime -> None, admin_level 4 -> state, `tunnel=true` street -> tunnel flag;
paint order sorts Ocean < Land < Building < Street < Boundary; `line_width` clamps.

### Step 4. Clipping, simplification and per-tile draw geometry (rovsti_geo, pure)

Files: `crates/rovsti_geo/src/tiles/clip.rs`, `tiles/geometry.rs`.

`clip.rs` (f32 points):
```rust
pub struct Rect { pub min: (f32, f32), pub max: (f32, f32) }
pub fn clip_polygon(ring: &[(f32, f32)], r: &Rect) -> Vec<(f32, f32)>;       // Sutherland-Hodgman
pub fn clip_polyline(line: &[(f32, f32)], r: &Rect) -> Vec<Vec<(f32, f32)>>; // Liang-Barsky, splits into parts
pub fn simplify(points: &[(f32, f32)], tolerance: f32, closed: bool) -> Vec<(f32, f32)>; // Douglas-Peucker, iterative (no recursion), keeps ends
```
`geometry.rs`:
```rust
/// One tile's drawable features in tile units (0..extent), decoded once.
pub struct TileGeometry { pub id: TileId, pub extent: u32, pub groups: Vec<(DrawClass, ClassGeometry)> /* sorted by class */ , pub vertices: usize }
pub struct ClassGeometry { pub polygons: Vec<Vec<Vec<(f32, f32)>>> /* rings */, pub lines: Vec<Vec<(f32, f32)>> }
impl TileGeometry {
    /// classify + decode + clip to the tile (+ 1/16 extent buffer) + simplify
    /// (tolerance = extent / 1024: half a px at 512-px display) + drop polygons whose
    /// bbox is under 1 px at the tile's own zoom; skips classes whose min_zoom > id.z + 1.
    pub fn from_mvt(id: TileId, tile: &MvtTile) -> Self;
    pub fn from_bytes(id: TileId, bytes: &[u8]) -> Result<Self, MvtError>;  // decode_any + from_mvt
    pub fn empty(id: TileId) -> Self;   // 204/404 tiles: draws only the background
}
/// Tile units -> pixels from the view centre (the map's Scene space).
#[derive(Clone, Copy, Debug, PartialEq)] pub struct TileTransform { pub origin: (f32, f32), pub scale: (f32, f32) }
impl TileTransform {
    pub fn new(id: TileId, extent: u32, vp: &Viewport) -> Self;  // from projecting nw()/se()
    pub fn apply(&self, p: (f32, f32)) -> (f32, f32);
    pub fn screen_rect(&self, extent: u32) -> Rect;
}
/// What to draw this frame: for each visible tile (or a cached ancestor standing in
/// for a missing one, overzoomed), the tile's background rect and its features
/// transformed to Scene space, clipped to the widget rect (+8 px), filtered by
/// `min_zoom <= display_zoom + 0.5`, grouped by class in paint order across tiles.
/// Stops adding the lowest-priority classes (Building first, then Path/Cycleway/Track/
/// Service, then Land) once `max_vertices` would be exceeded.
pub struct TileLayer { pub backgrounds: Vec<[(f32, f32); 4]>, pub groups: Vec<(DrawClass, ClassGeometry)>, pub display_zoom: f64 }
pub fn build_layer(tiles: &[&TileGeometry], vp: &Viewport, size: (f32, f32), max_vertices: usize) -> TileLayer;
/// Nearest loaded stand-in for a wanted tile: itself, else its ancestors up to 4 levels.
pub fn stand_in(want: TileId, have: impl Fn(TileId) -> bool) -> Option<TileId>;
```
Tests: clip a square half outside a rect -> expected polygon; a line crossing a rect twice
gives two parts; fully inside/outside cases; simplify keeps a straight-line's ends only and
a zigzag above tolerance; `TileTransform` vs exact (`frac_to_coord` -> `vp.project`) for 9
points of tile 14/9510/6140 at mpp 1.0 and of a z10 tile at mpp 16: error < 0.5 px;
`from_bytes(fixture)` yields Building, Street and Waterway groups, all points within the
buffered extent, `vertices` equals the sum; `build_layer` on the fixture at a viewport
centred on the tile keeps every point inside the clip rect, puts groups in paint order,
honours `max_vertices` by dropping buildings first; a view far away yields no groups;
`stand_in` picks the nearest ancestor.

### Step 5. LRU index and the bounded fetch queue (rovsti_geo, pure)

File: `crates/rovsti_geo/src/tiles/queue.rs` (and `tiles/lru.rs`).

```rust
/// Size-capped LRU bookkeeping (no I/O): the disk cache and the in-memory tile cache use it.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct LruIndex<K: Ord> { /* entries: BTreeMap<K, (size: u64, last_used: u64)>, total: u64, clock: u64 */ }
impl<K: Ord + Clone> LruIndex<K> {
    pub fn touch(&mut self, k: &K) -> bool;
    pub fn insert(&mut self, k: K, size: u64);     // replaces
    pub fn remove(&mut self, k: &K) -> Option<u64>;
    pub fn total(&self) -> u64;  pub fn len(&self) -> usize;
    /// Keys to delete (least recently used first) so total <= cap; removes them.
    pub fn evict_to(&mut self, cap: u64) -> Vec<K>;
}
pub const MAX_IN_FLIGHT: usize = 4;
pub const RETRY_AFTER_MS: i64 = 30_000;
/// Which tiles to fetch next: dedupes, caps concurrency, remembers failures for
/// RETRY_AFTER_MS, and lets view requests jump ahead of prefetch.
#[derive(Debug, Default)] pub struct FetchQueue { /* wanted: VecDeque<(TileId, Priority)>, in_flight: BTreeSet<TileId>, failed: BTreeMap<TileId, i64> */ }
pub enum Priority { View, Prefetch }
impl FetchQueue {
    pub fn want(&mut self, ids: impl IntoIterator<Item = TileId>, p: Priority, now_ms: i64);
    pub fn set_view(&mut self, ids: &[TileId]);       // drops queued View items not in ids
    pub fn next(&mut self, now_ms: i64) -> Option<TileId>;   // None when MAX_IN_FLIGHT reached
    pub fn done(&mut self, id: TileId, ok: bool, now_ms: i64);
    pub fn clear(&mut self);                           // server changed
    pub fn pending(&self) -> usize;  pub fn in_flight(&self) -> usize;
}
```
Tests: LRU eviction order respects touches, `evict_to(0)` empties, replace updates total,
serde round trip; queue never hands out more than 4 at once, dedupes repeated wants and
in-flight ids, View beats Prefetch, `set_view` discards stale view items but keeps
prefetch, a failed tile is skipped until RETRY_AFTER_MS passes, `clear` resets.

### Step 6. `rovsti_tiles` crate: HTTP fetch and disk cache (platform-free I/O)

New crate `crates/rovsti_tiles` (lib; add to root `[workspace.dependencies]` as
`rovsti_tiles = { path = "crates/rovsti_tiles" }`; `[lints] workspace = true`;
`#![cfg_attr(test, allow(clippy::unwrap_used))]`).
Deps: `rovsti_geo`, `thiserror`, `log`, `serde`, `serde_json`,
`ureq = { version = "3", default-features = false, features = ["rustls-no-provider", "gzip"] }`,
`rustls-graviola = "0.4"` (same versions as rovsti_wa_client; no `ring`/`aws-lc-rs`).

- `src/http.rs`:
  ```rust
  pub const USER_AGENT: &str = concat!("RovstiStops/", env!("CARGO_PKG_VERSION"), " (offline arrival alarm; map tiles)");
  pub enum Fetched { Tile(Vec<u8>), Empty /* 204, 404 */ }
  #[derive(Debug, thiserror::Error)] pub enum TileError { Http(String), Status(u16), TooLarge, Decode(#[from] MvtError), Io(#[from] std::io::Error) }
  #[derive(Clone)] pub struct TileClient { agent: ureq::Agent }
  impl TileClient {
      pub fn new() -> Self;   // rustls+graviola, WebPKI roots, http_status_as_error(false), connect 10 s, global 30 s
      pub fn fetch(&self, src: &TileSource, id: TileId) -> Result<Fetched, TileError>; // Accept-Encoding gzip (ureq decodes), body cap 4 MiB
      /// The Settings "Test" button: fetch 0/0/0 and decode it; Ok(layer count) when it
      /// has at least one Shortbread layer (ocean, water_polygons, land, boundaries, streets).
      pub fn test_server(&self, src: &TileSource) -> Result<usize, TileError>;
  }
  ```
  Only the User-Agent and Accept-Encoding headers are sent: no cookies, ids or location.
- `src/cache.rs`:
  ```rust
  pub const DEFAULT_CAP_BYTES: u64 = 128 * 1024 * 1024;
  /// <root>/<source.key()>/<z>/<x>/<y>.mvt plus <root>/<key>/index.json (LruIndex<TileId>).
  /// Empty tiles are stored as zero-byte files (so offline knows they are empty).
  pub struct DiskCache { /* root, key, index: Mutex<LruIndex<TileId>>, cap */ }
  impl DiskCache {
      pub fn open(root: &Path, src: &TileSource, cap: u64) -> io::Result<Self>; // rebuilds the index from files if index.json is missing/corrupt
      pub fn get(&self, id: TileId) -> Option<Fetched>;   // touches
      pub fn put(&self, id: TileId, f: &Fetched) -> io::Result<()>; // atomic write (tmp + rename), then evict_to(cap) deleting files
      pub fn flush(&self) -> io::Result<()>;   // writes index.json
  }
  /// cache -> network -> cache, decoded into draw geometry. Blocking; run on a background executor.
  pub fn load(cache: &DiskCache, client: &TileClient, src: &TileSource, id: TileId, offline_only: bool) -> Result<TileGeometry, TileError>;
  ```
Tests (no internet): cache put/get round trip, separate dirs per source key, eviction
deletes least recently used files and keeps total <= cap, a corrupt index.json is rebuilt
from files, empty tiles round-trip; `fetch` against a local plain-HTTP server on
`std::net::TcpListener` (thread serving canned responses: 200 with the fixture bytes,
200 gzip-encoded, 204, 404, 500) -> Tile/Tile/Empty/Empty/Err(Status(500)), and the
captured request contains the `User-Agent` and no `Cookie`; `test_server` on the fixture
server returns Ok and on a server returning garbage returns Err(Decode); `load` with
`offline_only` and an empty cache returns an error without touching the network.
Add `cargo check -p rovsti_tiles --target aarch64-linux-android` to the checks.

### Step 7. Draw the tiles under the map, attribution, prefetch on trip start (apps/stops)

Files: `apps/stops/Cargo.toml` (+`rovsti_tiles`), new `apps/stops/src/tiles.rs`,
`apps/stops/src/map.rs`, `apps/stops/src/main.rs`, `apps/stops/src/trip_ui.rs`.

- `tiles.rs` (`pub struct MapTiles` in `StopsApp`): holds `TileSource`, `Arc<DiskCache>`
  (root `<data dir>/tiles`), `TileClient`, `FetchQueue`, in-memory
  `HashMap<TileId, Arc<TileGeometry>>` capped by an `LruIndex<TileId>` (by `vertices`,
  cap ~400 k vertices). `fn request_view(&mut self, cover: &TileCover, cx)` and
  `fn prefetch(&mut self, ids: Vec<TileId>, cx)` push into the queue and call `pump`,
  which spawns up to `MAX_IN_FLIGHT` `cx.background_executor().spawn(load(..))` tasks
  (each followed by `this.update(..)`: insert, `queue.done`, `cx.notify()`, pump again).
  Never decodes or does I/O on the UI thread. `fn set_source(&mut self, src)` clears the
  queue and memory cache and opens the other server's cache dir. The cache index is
  flushed after each prefetch batch and on trip end.
- `map.rs`: `Scene` gains `pub tiles: TileLayer` (built in `Scene::build` from
  `SceneInput.tiles: &[&TileGeometry]`, with `max_vertices` = 60 000), `Palette` gains
  `style: MapStyle` (`Theme::Light -> Colorful`, `Theme::Dark -> Eclipse`) and the
  helper `fn style_color(c: Rgba8) -> Hsla` via `gpui::rgba(c.to_u32()).into()`.
  `paint_scene` keeps the plate quad (the radar look stays where tiles are missing),
  then paints tile backgrounds and each class group (fills with holes as subpaths of one
  even-odd fill path; lines with casing first, width from `line_width`), chunked under
  8 000 points per path, all before the existing line/stops/diameter/trail/position
  layers. `map_card` computes `tiles_for_view` (max 24 tiles), calls
  `self.map_tiles.request_view`, and collects loaded geometry (or `stand_in` ancestors)
  for the scene. A caption-size label `ATTRIBUTION` sits bottom-right (secondary label
  colour, small glass-free text, `absolute().right(px(10)).bottom(px(8))`).
- `trip_ui.rs`: on a successful start, `prefetch(tiles_for_circle(diameter.center(),
  diameter.radius_m(), 500.0, 8, 600))`.
- Pure tests in `map.rs`: `Palette` style follows the theme; `Scene::build` with the
  fixture tile puts its features in the same place as projecting its corners; no tiles
  -> empty `TileLayer` (radar look unchanged, existing scene tests still pass);
  `tiles.rs` pure helpers (cache root path, memory eviction) tested where possible.

### Step 8. Map tiles settings page, docs and CLAUDE.md

Files: `apps/stops/src/settings_ui.rs`, `apps/stops/src/main.rs`, `CLAUDE.md`,
`docs/roadmap.md`, `docs/architecture.md`.

- `SettingsPage::MapTiles`; a main-list row "Map tiles" showing the host of the current
  server (`tiles.versatiles.org` or the custom host). The page: a `TextField`
  (`tile_field`, prefilled with the current template), a "Test and save" row (runs
  `TileClient::test_server` on the background executor; on Ok sets
  `prefs.set_tile_url`, `save_prefs`, `map_tiles.set_source`, shows a pill
  "Map server works (N layers)"; on Err keeps the old setting and shows the reason;
  a parse error from `TileSource::parse` shows without a network call), and
  "Reset to default" (sets the default, saves, no test needed). Rows follow the
  existing `list_row` look (liquid-glass skill; no new GPUI APIs).
- Pure helpers in `settings_ui.rs` with tests: `tile_server_summary(&StopsPrefs) -> String`
  (host only), `tile_url_error_text(&TileUrlError) -> &'static str`,
  `tile_test_text(&Result<usize, TileError>) -> String`.
- `CLAUDE.md`: the "Everything is local except WhatsApp" line also says map tiles come
  from the user-chosen tile server (default the public VersaTiles server,
  `tiles.versatiles.org`), cached on the device, with no other backend.
- `docs/roadmap.md` Stops: replace "decided: vector radar map, no tiles" with the tile
  map item (Shortbread vector tiles from a settable VersaTiles-compatible server,
  colorful/eclipse, disk cache with prefetch around the trip circle, radar fallback;
  done without a device) and an open item "check tile drawing speed on a phone".
- `docs/architecture.md`: add `crates/rovsti_tiles` to the layer diagram, a bullet on
  map tiles (pure `rovsti_geo::tiles`, I/O in `rovsti_tiles`, only network traffic
  besides the WhatsApp gateway), and update the Storage bullet (`tiles/<server key>/`
  cache in the data dir, capped at 128 MiB).

Acceptance (whole goal): all checks green; the map paints decoded Shortbread features
under the circle/diameter/breadcrumb/pins in colorful (light) and eclipse (dark), shows
the OSM attribution, falls back to the radar plate where tiles are missing, the tile URL
is a persisted setting with test and reset, tiles are cached per server with LRU and
prefetched around the trip circle.
