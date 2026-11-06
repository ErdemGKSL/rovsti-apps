# Stops: pin snapping

Implemented in `apps/stops/src/snap.rs` (pure, unit-tested) and wired in
`apps/stops/src/map.rs` (`Gesture::PinDrop`, `drop_destination`).

A stop is the gate AB. A and B are its two points; snapping only ever moves a point.

## Rule

- While the finger moves slowly the pin follows it exactly. Nothing snaps during a drag.
- On release the speed is the displacement over the last 90 ms of pointer samples
  (`VELOCITY_WINDOW_MS`). A pause before lifting reads as about 0 px/s.
- Snap radius from release speed: 0 below `SLOW_PX_S` (250 px/s), `FAST_RADIUS_PX`
  (56 px) at or above `FAST_PX_S` (900 px/s), linear in between.
- Fast motion within the last 300 ms, then a stop within `NEAR_RADIUS_PX` (16 px) of a
  candidate, also snaps.
- Hysteresis: a pin that started snapped and moved no more than `HOLD_PX` (10 px) stays
  snapped. Dragging slowly away unsnaps it.
- Taps on the map keep tap-to-snap within `TAP_RADIUS_PX` (40 px).
