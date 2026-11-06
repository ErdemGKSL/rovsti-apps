# Rovsti Stops: map tiles, plan round 3

Base: `848eefa67467f182cbcb617e66ed1cae52756a1c` (round 2's AD-ST4 fix on top of the round-1 work).

## Verdict: done

Every item in the owner's spec and the engineering requirements is implemented. The checks
pass at the base:

- `cargo fmt --all -- --check`: clean
- `cargo clippy --workspace --all-targets -- -D warnings`: clean
- `cargo test --workspace`: all suites ok (`rovsti_geo` tiles modules included)
- `cargo check --target aarch64-linux-android` for `rovsti_android`, `rovsti_wa_client`
  and `rovsti_tiles`: ok

## Audit

| Spec item | Where | State |
| --- | --- | --- |
| Slippy math, viewport cover, overzoom > 14, circle prefetch set | `crates/rovsti_geo/src/tiles/slippy.rs` | done, tested |
| Tile URL setting, default `tiles.versatiles.org`, persisted in Stops prefs | `tiles/source.rs`, `apps/stops/src/store.rs` / prefs | done, tested |
| MVT decoder (hand-written) + real captured fixture | `tiles/mvt.rs`, `tiles/testdata/` | done, tested |
| Shortbread layer -> draw class | `tiles/shortbread.rs` | done, tested |
| colorful / eclipse style table, source cited | `tiles/style.rs` | done, tested |
| Clipping and per-zoom simplification | `tiles/clip.rs`, `tiles/geometry.rs` | done, tested |
| LRU index, bounded fetch queue | `tiles/lru.rs`, `tiles/queue.rs` | done, tested |
| ureq + rustls-no-provider + rustls-graviola, Rovsti Stops User-Agent | `crates/rovsti_tiles/src/http.rs` | done, tested |
| Per-server disk cache, size cap, LRU eviction | `crates/rovsti_tiles/src/cache.rs` | done, tested |
| Background loading off the UI thread | `apps/stops/src/tiles.rs` | done |
| Tiles under circle/diameter/breadcrumb/pins, style keyed by theme | `apps/stops/src/map.rs` (`Palette::new`) | done |
| "(c) OpenStreetMap contributors" on the map | `map.rs` (`ATTRIBUTION`) | done |
| Settings: text field, reset to default, download+decode test before save | `apps/stops/src/settings_ui.rs`, `TileClient::test_server` | done |
| Prefetch around the A-B circle on trip start | `apps/stops/src/trip_ui.rs` (`tiles_for_circle`) | done |
| Missing tiles fall back to the radar look | `map.rs` | done |
| CLAUDE.md "Everything is local" line | `CLAUDE.md` | done |
| docs/roadmap.md Stops section | `docs/roadmap.md` | done |
| docs/architecture.md (diagram, crate notes, AD-ST4) | `docs/architecture.md` | done |

## Steps

None. The open roadmap item "Check tile drawing speed on a phone" needs a physical device and
is outside what cargo-only verification can close.
