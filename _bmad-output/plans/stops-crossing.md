# Plan: Rovsti Stops, diameter-crossing alarm (round 1)

- Base: `7acac595199fb54b854f9fa3a711734e61f7bcb5` ("WIP: carry-over before sync")
- Unit: Rovsti Stops. Architect: PLAN agent. No source changed by this plan.
- Earlier plans: `stops.md` (8 steps, done), `stops-r2.md` (done). No earlier
  `stops-crossing*.md`, no stash entries, nothing of the crossing trigger exists in the tree.
- Checks for every step (all must stay green):
  `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android`
  (if `df` shows under 6 GB free: `rm -rf target/debug/incremental` first). No device.

## Owner's spec (binding)

Two points A and B (any point source: map / `geo:` / shared link, saved places, history, GTFS
stop). Circle with AB as diameter (centre = midpoint, r = |AB|/2). Line AB splits the plane;
facing A -> B, "left" and "right" halves, shown on the map. While inside the circle, crossing
from one half-disc to the other fires the alarm **in either direction** (left->right or
right->left). Fire once, then the trip is Arrived like today (notification, full-screen alarm,
optional WhatsApp "arriving" message). Left/right is still shown so the user understands the line.

## What the code has at the base

- `rovsti_geo::trip`: `AlarmRule::{Radius, Eta, BeforeStop}`, `Fix`, `TripState`,
  `TripUpdate { state, distance_m, ring, next_fix_in, eta }`, `Trip { destination, rule, .. }`,
  `MAX_ACCURACY_M = 150`, polling `(slack / 60 m/s / 2).clamp(3, 300)` s.
- `rovsti_geo::session`: `ActiveTrip { id, label, trip, started_ms, snoozes, ring_at_ms, track,
  notice }`, `NoticeState { rule: AlarmRule, .. }`, `FixIn`, `FixOut { state, distance_m,
  next_fix_ms, high_accuracy, ring, title, text, notice_sent, at }`, `FixPos`.
- `rovsti_geo::{history (TripRecord.destination/rule, recent_destinations), places (Place.rule),
  prefs (RuleChoice, NoticeWhen, NoticePrefs::to_notice_state(alarm)) }`.
- `apps/stops`: `backend.rs` (`build_trip(id, label, destination, rule, started, notice)`,
  `sim_fix`), `headless.rs` (`trip_fix/trip_alarm/trip_end` over `trip.json`, under
  `store::lock()`), `store.rs` (`load_trip` = `storage::load_json`, returns `None` on parse
  failure), `picker.rs` (`Destination { label, coord, rule, .. }`, `StopsApp.dest`), `transit.rs`
  (`StopWake`, `stop_wake`, `wake_options`: the line-aware BeforeStop rule), `map.rs`
  (`alarm_ring`, `fit_view`, `Scene { ring, destination, .. }`, `SceneInput { destination,
  rule, .. }`), `trip_ui.rs` (`rule_text`, `rule_chips`, `Live`, live card, alarm screen).
- Kotlin (`TripService`, `TripAlarm`) only reads `FixOut` fields `state, ring, next_fix_ms,
  high_accuracy, title, text`; it is trigger-agnostic. Extra JSON fields are ignored.
  `TripAlarm.kt:196` has the fallback text "You are close to your destination".

## Decisions (AD-SX)

- **AD-SX1 Geometry is local metres.** Equirectangular projection around the midpoint M:
  `x = wrap180(lon - M.lon).to_radians() * R * cos(M.lat)`, `y = (lat - M.lat).to_radians() * R`
  (R = `EARTH_RADIUS_M`). Valid for city-scale circles; the diameter is capped.
- **AD-SX2 Side.** `offset(P) = cross(B - A, P - A) / |AB|` in local metres; `> 0` is **left**
  (facing A -> B, counter-clockwise), `< 0` right. Dead band `max(10 m, fix.accuracy_m)`:
  `|offset| <= band` reads as `OnLine` (unknown) and never changes the remembered side.
- **AD-SX3 Detection by segment intersection.** The watcher remembers the last confident
  `(side, position)`. When a confident fix on the other side arrives (either direction), it
  fires iff segment `last -> now` intersects segment `AB` (parameter along AB in `[0, 1]`).
  Sparse fixes still fire; passing around the circle (outside AB's ends) never fires. Either
  way the remembered side/position becomes the new one. Fixes with accuracy > 150 m are ignored.
- **AD-SX4 Input limits.** `MIN_DIAMETER_M = 20.0`, `MAX_DIAMETER_M = 50_000.0`; non-finite or
  off-Earth coordinates are rejected. `DiameterError::message()` gives the user sentence.
- **AD-SX5 Polling.** `slack = distance_to_segment_AB - accuracy - band`, next fix in
  `(slack / 60 / 2).clamp(3, 300)` s while tracking, 60 s otherwise (same curve as today).
  `high_accuracy` stays `next_fix_in <= 15 s`.
- **AD-SX6 Notice.** `NoticeWhen::WithAlarm` sends with the crossing.
  `NoticeWhen::MinutesBefore(m)` sends when ETA to the circle edge (`distance_to_circle / speed`,
  speed >= 1.5 m/s) is <= m min, or when the fix is inside the circle (fallback for no speed).
- **AD-SX7 Superseded code is removed.** `AlarmRule` (Radius / Eta / BeforeStop), `RuleChoice`,
  the "Wake me" chips, `Place.rule`, `TripRecord.rule`, `transit::{StopWake, stop_wake,
  wake_options, BEFORE_STOP_METRES}`, `map::alarm_ring`. GTFS import, stop search, lines through
  a stop and drawing a chosen line stay (GTFS is a point source). `Feed::stops_before` is removed
  only if nothing calls it any more.
- **AD-SX8 Old state.** `trip.json` without the new fields fails to parse; `store::load_trip`
  then renames it to `trip.legacy.json`, logs, and returns `None` (the service is answered
  `{"state":"ended"}` and stops). Old `history.json` records (with `destination`, no `a`/`b`)
  still load: they are shown and offered as single recent points, never as pairs. Unknown keys
  (`rule` in places/prefs/history) are ignored by serde.

## Steps

### 1. Pure diameter geometry and crossing watcher (`rovsti_geo`, additive)

New `crates/rovsti_geo/src/crossing.rs`, exported from `lib.rs`
(`pub use crossing::{Diameter, DiameterError, Side, SideReading, CrossingWatch, Crossing,
MIN_DIAMETER_M, MAX_DIAMETER_M, DEAD_BAND_M}`). Nothing else changes.

- `pub const DEAD_BAND_M: f64 = 10.0; MIN_DIAMETER_M = 20.0; MAX_DIAMETER_M = 50_000.0;`
- `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum DiameterError { InvalidCoord,
  SamePoint, TooShort, TooLong }` with `message(self) -> &'static str` ("A and B are the same
  place.", "A and B must be at least 20 m apart.", "A and B must be at most 50 km apart.",
  "These coordinates are not on Earth."), `Display`, `std::error::Error`.
- `#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
  #[serde(try_from = "DiameterRaw", into = "DiameterRaw")] pub struct Diameter { a, b }`
  (private fields; `DiameterRaw { a: Coord, b: Coord }`, so invalid persisted data is refused).
  Methods: `new(a, b) -> Result<Self, DiameterError>`, `a()`, `b()`, `center() -> Coord`,
  `length_m()`, `radius_m()`, `swapped() -> Self`, `local(c) -> (f64, f64)` (AD-SX1, lon wrapped
  to [-180, 180)), `from_local((x, y)) -> Coord`, `offset_m(c) -> f64` (AD-SX2, signed),
  `distance_to_segment_m(c)`, `distance_to_circle_m(c)` (`max(0, |c - M| - r)`),
  `contains(c) -> bool`, `read_side(c, accuracy_m) -> SideReading`,
  `segment_crosses(p, q) -> bool` (AD-SX3, endpoints inclusive),
  `outline(n) -> Vec<Coord>` (circle polygon), `half_disc(side, n) -> Vec<Coord>` (A, arc on
  that side, B; for map shading).
- `#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
  #[serde(rename_all = "snake_case")] pub enum Side { Left, Right }` with `other()` and
  `label() -> &'static str` ("Left", "Right").
  `pub enum SideReading { Side(Side), OnLine }`.
- `#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)] pub struct CrossingWatch
  { last: Option<(Side, Coord)> }` with `side() -> Option<Side>` and
  `observe(&mut self, d: &Diameter, fix: &Fix) -> Option<Crossing>`;
  `pub struct Crossing { pub from: Side, pub to: Side }`. Ignores fixes with accuracy > 150 m
  (`trip::MAX_ACCURACY_M`).
- Tests (fixtures built with `Diameter::from_local` around Istanbul 41.0, 29.0, plus one near
  lon 179.99 and one at lat 60): `new` rejects same point / 19 m / 60 km / NaN / lat 91 and
  accepts 20 m and 50 km; centre and radius; `local`/`from_local` round trip < 0.01 m; left of
  A->B is positive for an east-pointing AB and a north-pointing AB; `swapped` flips sides;
  `read_side` dead band uses `max(10, accuracy)`; `segment_crosses` true through the middle, at
  an end point, false beyond A's end and B's end, false for a parallel segment;
  `distance_to_segment_m` / `distance_to_circle_m` / `contains`; watcher: left->right through
  the circle fires `Crossing { from: Left, to: Right }`; right->left fires too; sparse jump
  (2 km left to 1 km right through AB) fires; going round outside the circle (left to right past
  B's end) does not fire and updates the side; jitter around the line (+5/-5/+8 m, 10 m
  accuracy) never fires; an OnLine fix in between does not lose the last side
  (left, on-line, right -> fires); a 2 km accuracy fix is ignored; `half_disc(Left)` points all
  have `offset >= 0`; serde round trip; deserialising `{a, b}` with a == b is an error.

### 2. Pure crossing trip machine (`rovsti_geo`, additive)

In `crossing.rs` (or `crossing/trip.rs` if the file passes ~600 lines):

- `#[derive(Clone, Debug, Serialize, Deserialize)] pub struct CrossingTrip { pub diameter:
  Diameter, state: TripState, watch: CrossingWatch, last_line_m: Option<f64> }` with
  `new(diameter)`, `state()`, `side()`, `last_line_m()`, `dismiss()`,
  `update(&mut self, fix: Fix) -> CrossingUpdate`, private `next_fix_in(line_m, accuracy)`
  (AD-SX5).
- `#[derive(Clone, Copy, Debug, PartialEq)] pub struct CrossingUpdate { state, ring: bool,
  crossing: Option<Crossing>, side: Option<Side>, line_m: f64 /* to segment AB */,
  circle_m: f64, inside: bool, next_fix_in: Duration, eta: Option<Duration> /* line_m / speed,
  speed >= 1.5 m/s */ }`. `ring` is true exactly once (Tracking -> Arrived); a Dismissed or
  Arrived trip never rings again.
- `pub enum NoticeRule { WithAlarm, BeforeCircle { before: Duration } }` (serde) with
  `is_met(&self, update: &CrossingUpdate, fix: &Fix) -> bool` (AD-SX6).
- Make `MIN_SPEED_MPS`/`MAX_SPEED_MPS` in `trip.rs` `pub(crate)` and reuse them.
- Tests: rings once either direction (two tests), never rings for a pass outside the circle,
  dismissed stays quiet, inaccurate fix ignored, `next_fix_in` = 300 s at 80 km and 3 s at
  100 m from AB, `eta` = line distance / speed, `NoticeRule` both variants (ETA hit, inside
  fallback, stopped outside = false), serde round trip keeps state and side.

### 3. Switch the trip session and stored data to the crossing trigger (`rovsti_geo` + `apps/stops` non-GPUI)

The breaking step; it removes the old rule and keeps the app compiling with a minimal UI.

`rovsti_geo`:
- `trip.rs`: delete `AlarmRule`, old `Trip`, `TripUpdate` and their tests; keep `Fix`,
  `TripState`, the constants. `lib.rs` exports updated.
- New `pub struct Endpoint { pub label: String, pub coord: Coord }` (serde, in `crossing.rs`).
- `session.rs`: `ActiveTrip { id, a: Endpoint, b: Endpoint, trip: CrossingTrip, started_ms,
  snoozes, ring_at_ms, track, notice }`; `ActiveTrip::new(id, a, b, started_ms) ->
  Result<Self, DiameterError>`; `label()` = `"{a} – {b}"`; `swap` not needed on a running trip.
  `NoticeState.rule: NoticeRule`; `on_fix` uses `CrossingUpdate`; the notice renders with
  `b.label` as the stop. `FixOut` keeps all fields (`distance_m` = metres to the line) and adds
  `#[serde(default)] side: Option<Side>`, `#[serde(default)] inside: bool`. Texts:
  tracking `"Left side, 120 m to the line A – B"` (`"On the line"` when unknown, `"Outside the
  circle, 1.2 km away"` when `!inside`), title `label()`; ringing title `"Crossed A – B"`.
- `history.rs`: `TripRecord` drops `rule`, gets `a: Option<Endpoint>`, `b: Option<Endpoint>`
  and keeps `destination: Option<Coord>` for old records only (`skip_serializing_if`).
  `from_session` fills `a`/`b`. Replace `recent_destinations` with
  `recent_pairs(limit) -> Vec<(Endpoint, Endpoint)>` and `recent_points(limit) -> Vec<Endpoint>`
  (both ends of new records plus legacy destinations, deduped by `RECENT_DEDUPE_M`). Stats
  `top` counts by trip label as today.
- `places.rs`: remove `Place.rule` (old files still load). `prefs.rs`: remove `RuleChoice` and
  `StopsPrefs.rule`; `NoticePrefs::to_notice_state(&self) -> NoticeState` maps `WithAlarm` ->
  `NoticeRule::WithAlarm`, `MinutesBefore(m)` -> `BeforeCircle`. Update all tests; add tests:
  an old `trip.json`-shaped `ActiveTrip` JSON (destination + rule) fails to parse; an old
  history record JSON loads with `destination` and `a == None`; an old `saved.json` place with
  `rule` loads; old prefs with `rule` load.

`apps/stops` (no visual redesign yet):
- `store.rs`: `load_trip` renames an unparsable `trip.json` to `trip.legacy.json` (AD-SX8);
  test with an old-format file. Test helper `trip(id)` builds two endpoints 1 km apart.
- `backend.rs`: `build_trip(id, a: Endpoint, b: Endpoint, started_ms, notice) ->
  Result<ActiveTrip, DiameterError>`; `sim_fix(d: &Diameter, elapsed_s, at_ms)` starts 2 km
  left of the midpoint on the perpendicular, moves right at `SIM_SPEED_MPS`, parks 200 m right;
  `simulate_tick` uses `trip.trip.diameter`. Update tests (simulated trip reaches the ring).
- `headless.rs`: fixtures via endpoints; tests: a left->right fix pair rings, a right->left pair
  rings, notice WithAlarm goes out with the ring; doc table unchanged.
- `transit.rs`: remove `StopWake`, `stop_wake`, `wake_options`, `BEFORE_STOP_METRES` and tests.
- `picker.rs`: `Destination` loses `rule`; add `pub struct Ends { pub a: Option<Destination>,
  pub b: Option<Destination> }` with `set(slot, dest)`, `next_slot() -> Slot` (A until set,
  then B), `swap()`, `diameter() -> Option<Result<Diameter, DiameterError>>`, and
  `pub enum Slot { A, B }`; unit tests. `StopsApp.dest` -> `ends: Ends` and `active_slot: Slot`;
  `choose_destination` fills `active_slot` then advances. Delete `rule_chips`, `set_rule`,
  `StopsApp.rule`, `rule_text`; the setup card shows "A: …" / "B: …" rows and Start, enabled only
  when `ends.diameter()` is `Some(Ok(_))`, else the error sentence. The GTFS stop sheet's
  "N stops before" rows become "Use as A" / "Use as B". Map: `SceneInput` takes
  `diameter: Option<Diameter>` instead of `destination`/`rule`; `ring` = circle (centre,
  radius); `fit_view` fits the circle. `Live::from_trip` uses `last_line_m()`.
  Kotlin `TripAlarm.kt:196` fallback text -> "You crossed the line".

### 4. Picking UI: A, B, swap (`apps/stops`)

- Trip tab setup card: two glass rows "A  <label or Choose A>" and "B  <label or Choose B>";
  tapping a row sets `active_slot` and opens the picker titled "Choose A"/"Choose B"; a Swap
  button (`Icon` already in `rovsti_ui`; grep the icon list, do not invent) calls `ends.swap()`;
  under the rows: "Circle 1.2 km across. Crossing the line A – B either way wakes you."
  (pure `trigger_text(&Diameter) -> String` in `trip_ui.rs`, tested) or the `DiameterError`
  sentence.
- Confirm sheet for a shared location / `geo:` link: "Use as A" and "Use as B" buttons
  (default highlighted = `next_slot()`); Save toggle unchanged.
- Picker lists: saved places and `recent_points(5)` fill the active slot; a "Recent trips"
  section from `recent_pairs(3)` fills both ends at once.
- `request_start`/`begin_trip`/`resume_pending_start` take `(Destination, Destination)`;
  `pending_start` holds both. Saving a confirmed place stays per point.
- Tests: pure helpers only (`Ends` transitions, `trigger_text`, picker titles).
- Follow `.claude/skills/liquid-glass-gpui/SKILL.md`; grep gpui-fusion at `d6c8412` for any API.

### 5. Map preview: circle, diameter, left/right halves (`apps/stops/src/map.rs`)

- `Scene` gains `diameter: Option<DiameterScene>`: `{ a: (f32, f32), b: (f32, f32),
  centre: (f32, f32), radius_px: f32, left: Vec<(f32, f32)>, right: Vec<(f32, f32)>,
  left_label: (f32, f32), right_label: (f32, f32) }`, built from `Diameter::half_disc(.., 48)`
  projected with the viewport; label anchors at `from_local` of the half-radius point on the
  perpendicular. Remove the old `ring`/`destination` fields if nothing else uses them.
- Paint: both half-discs filled (`PathBuilder::fill` + `add_polygon`, as already used) with two
  palette tints (`Palette.left`, `Palette.right`, low alpha from theme colours), circle outline,
  the diameter line A-B stroked thicker, A and B markers with "A"/"B" text, "L"/"R" labels.
  Shown while picking (both ends set) and during the trip.
- `fit_view(position, diameter, size)` fits the circle plus your position.
- Tests: `Scene::build` with a known viewport puts A/B where expected, the left polygon lies on
  the left of the projected A->B (screen y down: check sign carefully), label anchors on the
  correct side, `fit_view` contains the whole circle.

### 6. Live status, alarm and history texts (`apps/stops`)

- `Live` gains `side: Option<Side>`, `inside: bool`, `line_m: f64` from `FixOut`
  (`from_trip` uses `trip.trip.side()`); live card shows "You are on the left side" /
  "right side" / "on the line", "120 m to the line", "inside the circle" or "1.2 km to the
  circle", ETA when present, the side chip coloured like the map halves.
- Alarm screen (`alarm_screen`, `show_alarm`): "Crossed A – B" and the side it went to.
- Settings history rows (`record_subtitle`) show "A – B" (legacy records: the single label).
  Notice settings labels: "When you cross the line", "N min before the circle".
- Pure tests for the new text helpers and `Live::from_out` with and without `side`.

### 7. Docs

- `docs/roadmap.md` Stops section: replace the "pick destination" / "line-aware alarms" lines
  with the diameter-crossing trigger (two points from any source, circle on AB, either-way
  crossing, dead band, segment test), GTFS as a point source; keep the device check open.
- `docs/architecture.md` AD-ST block: add AD-SX1..8 (or rewrite AD-ST4/ST5/ST6 accordingly),
  `docs/android.md` Stops host: simulator now walks across the diameter; `FixOut` new fields.
- Add a status table to this file (commit per step).

## Done when

All seven steps landed with checks green, `rg -n "AlarmRule|RuleChoice|BeforeStop|stop_wake"`
over `crates/ apps/` finds nothing, and the roadmap describes the crossing trigger.

## Status

| Step | What | Status |
| --- | --- | --- |
| 1 | Diameter geometry and crossing watcher | done |
| 2 | Crossing trip machine | done |
| 3 | Session, stored data and non-GPUI app switch | done |
| 4 | Picking UI: A, B, swap | done |
| 5 | Map preview: circle, diameter, halves | done |
| 6 | Live status, alarm and history texts | done |
| 7 | Docs | done |

The device check in `docs/roadmap.md` stays open (no device).
