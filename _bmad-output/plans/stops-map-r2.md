# Rovsti Stops: map tiles, plan round 2

Base: `2491aed` (merge of the round-1 work: commits `dc16e7e`..`058d65a`).

## Audit against the owner's spec

All checks pass at the base (fmt, clippy `-D warnings`, `cargo test --workspace`,
`cargo check` for `rovsti_android`, `rovsti_wa_client` and `rovsti_tiles` on
`aarch64-linux-android`).

| Spec item | Where | State |
| --- | --- | --- |
| Slippy math, cover, overzoom, circle prefetch set | `crates/rovsti_geo/src/tiles/slippy.rs` | done, tested |
| Tile URL setting, default VersaTiles, parse/validate | `tiles/source.rs`, `prefs.rs` (`tile_url`) | done, tested |
| MVT decoder (hand-written) + real fixture `14/9520/6130` | `tiles/mvt.rs`, `tiles/testdata/` | done, tested |
| Shortbread -> draw classes | `tiles/shortbread.rs` | done, tested |
| colorful / eclipse style table, source cited | `tiles/style.rs` | done, tested |
| Clipping, simplification per zoom, per-tile geometry | `tiles/clip.rs`, `tiles/geometry.rs` | done, tested |
| LRU index, bounded fetch queue | `tiles/lru.rs`, `tiles/queue.rs` | done, tested |
| HTTP over ureq + rustls-graviola, User-Agent, 204/404 = empty | `crates/rovsti_tiles/src/http.rs` | done, tested |
| Disk cache per server key, size cap, LRU | `crates/rovsti_tiles/src/cache.rs` | done, tested |
| Background loading, never on UI thread | `apps/stops/src/tiles.rs` | done |
| Drawing under circle/diameter/breadcrumb/pins, theme-keyed style | `apps/stops/src/map.rs` | done |
| OSM attribution on the map | `map.rs` (`ATTRIBUTION`) | done |
| Settings: text field, reset to default, test (download+decode 0/0/0) before save | `apps/stops/src/settings_ui.rs`, `TileClient::test_server` | done |
| Prefetch around the A-B circle on trip start | `apps/stops/src/trip_ui.rs` (`tiles_for_circle`) | done |
| CLAUDE.md "Everything is local" line | `CLAUDE.md` | done |
| docs/roadmap.md Stops section | `docs/roadmap.md` | done |
| docs/architecture.md | Map tiles bullet and storage note done, **but `AD-ST4 Map: no tiles` still says the opposite** | open |

The only gap is a stale architecture decision record.

## Steps

### Step 1. Replace the stale AD-ST4 in docs/architecture.md

File: `docs/architecture.md` (docs only, no code).

Replace the bullet `**AD-ST4 Map: no tiles.** ...` (around line 91) with a decision that
matches the code, keeping the AD id:

- **AD-ST4 Map: vector tiles under a vector radar.** No bundled region (it bloats the APK
  and goes stale). Stops downloads Shortbread 1.0 vector tiles (MVT, zoom 0-14, overzoomed
  beyond) from a user-set VersaTiles-compatible server (default
  `https://tiles.versatiles.org/tiles/osm/{z}/{x}/{y}`), decodes and styles them in
  `rovsti_geo::tiles` (colorful in light, eclipse in dark), fetches and caches them in
  `rovsti_tiles`, and paints them with `canvas` and `PathBuilder` under the radar layers
  (A and B, the circle with the diameter line and its halves, position, breadcrumb, GTFS
  stops and line) over `rovsti_geo::view`. Where a tile is missing the radar look shows.
  Tiles around the trip circle are prefetched when a trip starts. Text labels are not drawn.
  The map shows "(c) OpenStreetMap contributors".

Also grep `docs/` once more for "no tiles" / "no tile renderer" and fix any other hit.

Acceptance: `grep -rni "no tiles\|no tile renderer" docs/ CLAUDE.md` returns nothing;
all checks still green (docs-only change).
