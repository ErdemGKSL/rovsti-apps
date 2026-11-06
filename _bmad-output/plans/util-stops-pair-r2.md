# Plan: stops-pair, round 2

Base: 1f5520e9d19e51c08054f19638fab4cd5fae5357. Stash list empty, working tree clean.

## Verdict: done, no further steps

Round 1 (`util-stops-pair.md`, commits 116f2eb..1f5520e) implemented the whole goal:

1. Two-point saved stop
   - `crates/rovsti_geo/src/saved_stops.rs`: `SavedStop {id, name, a, b, created_ms}`,
     `SavedStops` (format v2, order = Vec position), add/rename/remove/move_by/restore/find_gate,
     `load_saved_stops` discards old/unknown content, `suggest_name` (nearest GTFS stop to the
     midpoint, else label, else ""). Tests cover all of these.
   - `places.rs` and single-place UI are gone (no `SavedPlaces`, `from_place`, `named_from` or
     `relocat` left). `backend.rs` overwrites a discarded file with an empty v2 list, and a test
     covers it.
   - The pick bar offers "Save stop" (`pick::save_offer`, `SheetMode::SaveStop`, a prefilled name
     field with a "Stop name" placeholder). The pin menu no longer has Save.
   - Saved tab (`saved_ui.rs`): `stop_detail` ("Gate 420 m"), Rename, Up/Down, a confirmed Delete
     with Undo, "Show on map" (restores both points and frames the circle), and "Start".
2. Velocity-aware snapping
   - `apps/stops/src/snap.rs` (pure): `VelocityTracker` (90 ms window), `snap_radius_px`
     (250/900 px/s, linear in between), `decide` with hysteresis and peak-speed near-snap, and
     `tap_snaps`. Tests cover all of these.
   - `map.rs`: the drag follows the finger exactly. The drop uses `snap::decide`, then a spring
     settle (`motion_tokens::release`, reduced motion respected) and a Selection haptic.
     Taps keep tap-to-snap within 40 px.
   - The rule is documented in `docs/stops-saved-and-snapping.md`.

Checks at base: fmt, clippy -D warnings, rovsti_geo + rovsti_stops tests (226 + 228 passed),
aarch64-linux-android checks of rovsti_android, rovsti_wa_client and rovsti_tiles: all pass.
