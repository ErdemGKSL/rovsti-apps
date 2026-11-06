# Stops: saved stops and pin snapping

Code: `crates/rovsti_geo/src/saved_stops.rs` (model, file format, name prefill),
`apps/stops/src/snap.rs` (snap decision, pure, no gpui), `apps/stops/src/map.rs`
(`Gesture::PinDrop`, `drop_destination`, `PinSettle`), `apps/stops/src/saved_ui.rs` (Saved tab).

## A stop is the gate AB

A and B are not stops. The two points together define one **stop**: the segment AB (the
diameter) is the gate, and crossing that segment fires the alarm
(`crates/rovsti_geo/src/crossing.rs`, unchanged). Crossing is two-way, so direction does not
matter. UI and docs say "stop" for the pair and "point A" / "point B" only for its two ends.
Pins snap to GTFS stops (which are real stops of a feed); saved stops are never snap targets.

A saved stop is `{ id, name, a, b, created_ms }`. Order is the position in the list (reorder
with Up/Down). Limits: 200 stops, name 1 to 60 characters (trimmed), a valid gate
(`Diameter::new`), and no duplicate: a stop whose two ends match an existing one in either
order within 25 m (`DUPLICATE_GATE_M`) is refused ("Already saved as ...").

UI: with A and B set, the pick bar offers "Save stop" (the only way to save one) and, once
saved, "Saved as {name}". The Saved tab lists name and gate length; tapping a row or "Show on
map" restores both points and frames the circle; "Start" runs the trip directly; Rename, Up,
Down; Delete asks for confirmation and offers Undo.

## saved.json, format v2

```json
{ "version": 2, "next_id": 3, "stops": [ { "id": 1, "name": "...", "a": {..}, "b": {..}, "created_ms": 0 } ] }
```

`version` is required. There is no migration. `load_saved_stops(text)` returns the stops and a
`discarded` flag: a missing file gives an empty list; anything that is not a valid v2 file
(the old `{"next_id":..,"places":[..]}` single-point format, other versions, garbage, an empty
file) gives an empty list with `discarded = true`, and the backend then overwrites the file
with an empty v2 file. Old single-point data is deleted on purpose. Loading never panics.

## Name prefill

`suggest_name(feed, a, b)`, searched within 300 m (`NAME_SEARCH_M`):

1. the name of the GTFS stop nearest the gate midpoint;
2. else the first non-generic label of A or B ("", "Point", "Point A", "Point B" are generic);
3. else empty (the field shows the placeholder "Stop name").

Names are cut to 60 characters.

## Snapping

Taps are unchanged. A drag never snaps while the finger moves: the pin follows exactly.
Snapping is decided once, on release, by `snap::decide`. Distances are screen px.

| Constant | Value | Meaning |
|---|---|---|
| `VELOCITY_WINDOW_MS` | 90 ms | release speed = displacement over the pointer samples of the last 90 ms; a pause before lifting reads as about 0 |
| `SLOW_PX_S` | 250 px/s | below this release speed the radius is 0: never snaps |
| `FAST_PX_S` | 900 px/s | at or above: full radius |
| `FAST_RADIUS_PX` | 56 px | radius at fast release; linear from 0 at 250 px/s (575 px/s gives 28 px) |
| `PEAK_WINDOW_MS` | 300 ms | how far back the peak speed looks |
| `NEAR_RADIUS_PX` | 16 px | peak plus near rule: peak speed >= 900 px/s in the last 300 ms, then stopped within 16 px of a stop, snaps |
| `HOLD_PX` | 10 px | hysteresis: a pin that started on a snapped stop and travelled <= 10 px keeps it, so a slow nudge never jumps or unsnaps |
| `TAP_RADIUS_PX` | 40 px | a tap (not a drag) snaps to a GTFS stop within 40 px (same as `PICK_RADIUS_PX`) |

Release snaps when the nearest candidate is within `snap_radius_px(release speed)`, or the
peak plus near rule holds, or the hold rule holds. Otherwise the pin stays where the finger
left it. To unsnap, drag the pin slowly away (more than `HOLD_PX`), or release slowly.

On a snap the pin glides from the release point to the stop with a spring: the liquid-glass
`motion_tokens::release()` (0 -> 1, stiffness 420, `PinSettle`). Reduced motion jumps. A
light haptic (`HapticFeedback::Selection` through `rovsti_ui::haptics::perform`) plays on
every snap. A free drop has neither settle nor haptic.
