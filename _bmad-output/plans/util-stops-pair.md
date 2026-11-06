# Plan: stops-pair, round 1

Base: c7844d0d92ad556ec58ac3de4aa17121520c5062. No earlier util-stops-pair plan, no stash.
Nothing of the goal is implemented yet: Saved still holds single `rovsti_geo::places::Place`
(`SavedPlaces`, `saved.json`), the pin menu offers Save/Rename per point, and pin drops always
run `map::snap_destination` (saved place, then GTFS stop within `PICK_RADIUS_PX` = 40 px).

Domain words (code, UI, docs): a **stop** / **saved stop** is the pair; its two ends are
**point A** and **point B**. Never call A or B a stop. Crossing logic in
`crates/rovsti_geo/src/crossing.rs` is not touched.

Checks after every step:
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace`
`&& cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`
(if `df` < 6 GB free: `rm -rf target/debug/incremental` first).

## Step 1. Pure saved-stop model in rovsti_geo
New `crates/rovsti_geo/src/saved_stops.rs` (`pub mod saved_stops;` in lib.rs, re-export
`SavedStop, SavedStops, SavedStopError, LoadedStops`). `places.rs` stays until step 3.
- `pub const FORMAT_VERSION: u32 = 2; MAX_STOPS: usize = 200; MAX_NAME_CHARS: usize = 60;`
  `DUPLICATE_GATE_M: f64 = 25.0;` `NAME_SEARCH_M: f64 = 300.0`.
- `#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)] pub struct SavedStop { pub id: u64,
  pub name: String, pub a: Endpoint, pub b: Endpoint, pub created_ms: i64 }` with
  `fn diameter(&self) -> Result<Diameter, DiameterError>`, `fn center(&self) -> Coord`
  (midpoint), `fn length_m(&self) -> f64`. Order = position in the Vec.
- `pub struct SavedStops { pub version: u32 /* no serde default: required */, pub next_id: u64,
  pub stops: Vec<SavedStop> }`; `Default` sets `version = FORMAT_VERSION`.
- `SavedStopError { InvalidName, InvalidGate(DiameterError), Duplicate(u64), Full, NotFound }`
  + `message()`/Display/Error (texts say "stop", e.g. "You already saved this stop.").
- Methods: `add(name, a: Endpoint, b: Endpoint, now_ms) -> Result<u64,_>` (validates name 1..=60
  trimmed chars and `Diameter::new(a,b)`; duplicate = an existing stop whose ends match in either
  order within `DUPLICATE_GATE_M`, because direction does not matter); `get`, `rename`,
  `remove -> bool`, `move_by(id, delta) -> bool` (clamped, same semantics as old
  `SavedPlaces::move_by`), `restore(stop, index)` (undo delete), `find_gate(a, b) -> Option<&SavedStop>`
  (either order, `DUPLICATE_GATE_M`), `search(query)` (fold, like old places).
- `pub struct LoadedStops { pub stops: SavedStops, pub discarded: bool }` and
  `pub fn load_saved_stops(text: Option<&str>) -> LoadedStops`: `None` -> empty, not discarded;
  text that parses as `SavedStops` with `version == FORMAT_VERSION` -> it; anything else (old
  `{"next_id":..,"places":[..]}`, garbage, other version, empty string) -> empty with
  `discarded = true`. Never panics.
- `pub fn suggest_name(feed: Option<&Feed>, a: &Endpoint, b: &Endpoint) -> String`: the name of
  the GTFS stop nearest the gate midpoint within `NAME_SEARCH_M` (`Feed::nearest`), else the
  shared label when a and b carry the same non-generic label, else the first non-generic label
  ("", "Point", "Point A", "Point B" are generic), else `""` (UI shows a placeholder).
Tests (in-file): add/ids not reused; name limits; invalid gate (too short/long/same point);
duplicate in both orders; full; rename/remove/move_by/restore; `load_saved_stops` on None, valid
v2 round trip, old single-point JSON from `places.rs` test `old_saved_place_with_a_rule_still_loads`,
`{}`, `"garbage"`, `{"version":1,...}` -> empty + discarded; `suggest_name` with feed stop near
midpoint, stop too far (falls back to label), no feed and generic labels -> "".

## Step 2. Pure snap decision in apps/stops/src/snap.rs (no gpui imports)
`mod snap;` in main.rs. All px are screen px, times ms.
- Constants: `VELOCITY_WINDOW_MS: u64 = 90; SLOW_PX_S: f32 = 250.0; FAST_PX_S: f32 = 900.0;
  FAST_RADIUS_PX: f32 = 56.0; NEAR_RADIUS_PX: f32 = 16.0; PEAK_WINDOW_MS: u64 = 300;
  HOLD_PX: f32 = 10.0; TAP_RADIUS_PX: f32 = 40.0` (= current `PICK_RADIUS_PX`).
- `#[derive(Default)] pub struct VelocityTracker` with fixed `[(u64, f32, f32); 16]` ring:
  `reset()`, `push(t_ms, (x, y))`, `speed_px_s(now_ms) -> f32` (least-squares or first/last over
  samples within the last `VELOCITY_WINDOW_MS`; 0 with < 2 samples or zero dt; samples older than
  the window ignored so a pause before release reads as slow), `peak_px_s(now_ms) -> f32` (max
  windowed speed seen within `PEAK_WINDOW_MS`).
- `pub fn snap_radius_px(release_speed: f32) -> f32`: 0 below `SLOW_PX_S`, `FAST_RADIUS_PX`
  above `FAST_PX_S`, linear in between.
- `pub struct DropInput { pub release_px_s: f32, pub peak_px_s: f32, pub candidate_px: Option<f32>,
  pub travel_px: f32, pub started_on_candidate: bool }`, `pub enum DropSnap { Snap, Free }`,
  `pub fn decide(input: &DropInput) -> DropSnap`: Snap when (a) `started_on_candidate &&
  travel_px <= HOLD_PX` (hysteresis: a slow nudge never unsnaps or jumps), or (b) the candidate
  is within `snap_radius_px(release)`, or (c) `peak_px_s >= FAST_PX_S` and candidate within
  `NEAR_RADIUS_PX`. Otherwise Free (slow drag away = unsnap).
- `pub fn tap_snaps(candidate_px: Option<f32>) -> bool` (`<= TAP_RADIUS_PX`).
Tests: speed of evenly spaced samples (e.g. 10 px / 10 ms = 1000 px/s); old samples outside the
window ignored; fewer than two samples = 0; radius 0 at 200, full at 1000, half-way linear at
575; slow release next to a stop -> Free; fast flick 40 px away -> Snap; fast then stop 10 px away
-> Snap via peak; started snapped and moved 6 px slowly -> Snap; moved 30 px slowly -> Free;
tap_snaps at 39/41 px.

## Step 3. Switch the app to SavedStops and delete the single-place model
Single compiling change across apps/stops; remove `crates/rovsti_geo/src/places.rs` and its
lib.rs exports.
- `store.rs`: keep `SAVED_FILE = "saved.json"` (doc: saved stops, format v2).
  `backend.rs`: `Data.saved: SavedStops`; in `load` read the text
  (`std::fs::read_to_string(dir.join(SAVED_FILE)).ok()`), call `load_saved_stops`, and when
  `discarded` immediately `storage::save_json(dir, SAVED_FILE, &SavedStops::default())`
  (log a warning) so the old file is overwritten. `save_places` -> `save_stops(dir, &SavedStops)`.
  Backend tests: old single-point file loads as empty and the file on disk is now v2.
- `main.rs`: `saved: SavedStops`; drop `relocating`, `PillAction::UndoRelocate`;
  `PillAction::UndoDelete(SavedStop, usize)`; `save_places` -> `save_stops`.
- `picker.rs`: delete `Destination::from_place`, `named_from` (+ its tests), the SavePoint/Rename
  sheet modes for places, "Save this place"/"Save this stop" toggles (`save_place` flag) in
  `confirm_body`/`stop_body`, `save_named`, `rename_place`. The picker's "Saved" group lists saved
  stops (`PickItem::SavedStop { id, name, detail, at }`, detail = "Stop, 420 m gate") and choosing
  one calls `choose_pair(a, b)`. Copy: "Search stops, saved stops, or paste a link".
- `pick.rs`: `dest_from_endpoint(ep)` without `saved`; delete `save_result_text`,
  `relocate_hint`, `relocate_result_text`, `save_prefill`; update `no_match_text`; fix callers in
  trip_ui.rs and settings_ui.rs.
- `map.rs`: `snap_destination(feed, viewport, size, at)` snaps to GTFS stops only (saved stops are
  not snap targets: A and B are not stops); delete `pick_saved`, `begin_relocate`,
  `relocate_tap`; `saved_key(&SavedStops)` hashes id + both ends; scene draws each saved stop as
  a faint ring at its `center()` (rename `SceneInput.saved` doc accordingly);
  `fallback_centre` uses `saved.stops.first().map(SavedStop::center)`. Pin menu: remove the
  Save/Rename row (Share, Move, Remove, Lines stay; update `the_menu_rows_fit_the_menu_box`).
- `trip_ui.rs`: drop the relocate branch of `setup_cards`; list row text
  "Saved stops, recent trips, links".
- `saved_ui.rs`: minimal list of saved stops (name, detail via a pure
  `stop_detail(stop: &SavedStop, here: Option<Coord>) -> String` = "Gate 420 m" plus ", 1.2 km
  away" when known) with Rename/Up/Down/Delete as before; empty state "No Saved Stops" / "Set
  point A and point B on the map, then tap Save stop." Rename sheet now renames a saved stop.
Update/replace every test that used `SavedPlaces`/`Place`. Grep must show no `SavedPlaces`,
`Place {` (geo), `from_place`, `named_from`, `relocat` left in apps/ and crates/rovsti_geo.

## Step 4. "Save stop" on the pick bar with a prefilled name
- `pick.rs` pure: `pub enum SaveOffer { None, Save, Saved { id: u64, name: String } }` and
  `pub fn save_offer(ends: &Ends, saved: &SavedStops) -> SaveOffer` (None unless both ends set and
  the diameter is valid; Saved when `find_gate` matches); tests.
- `picker.rs`: `SheetMode::SaveStop { a: Destination, b: Destination }`; `open_save_stop(cx)`
  fills the name field with `rovsti_geo::saved_stops::suggest_name(self.gtfs.as_deref(), &a_ep,
  &b_ep)` (placeholder "Stop name" when empty, via the existing field component); Save calls
  `saved.add(name, a, b, now_ms())`, persists, pill "Saved stop {name}" or the error message
  (Duplicate -> "Already saved as {name}").
- `trip_ui.rs::setup_cards`: when `save_offer` is Save, a "Save stop" pill next to Start; when
  Saved, a secondary line "Saved as {name}". This is the only way to save a stop.
Tests: save_offer cases (one end, invalid gate, new, already saved in reverse order).

## Step 5. Saved tab: stop rows, confirmed delete, restore on map, direct Start
- `saved_ui.rs`: each row shows name, `stop_detail` and a small gate preview (a short line glyph
  or the diameter text; no new GPUI APIs, reuse `list_row`/`div` like other rows). Open row
  actions: "Show on map", "Start", "Rename", "Up", "Down", "Delete".
- Delete asks first: pure `pub enum RowConfirm { None, Delete(u64) }` state on `StopsApp`
  (`confirm_delete: Option<u64>`); first tap turns the row's Delete into "Delete {name}?" with
  Cancel; second tap deletes and shows the pill with Undo (`SavedStops::restore`).
- "Show on map": `pick.set_pair(dest_from_endpoint(&a), dest_from_endpoint(&b))`, frame the
  circle with the existing `fit_view`/idle view (set `map.follow = true`), jump to tab 0.
  Tapping the row title does the same.
- "Start": `request_start(a, b, cx)` with the stop's points (the trip label from the stop name
  via the endpoints' labels if `request_start` takes Destinations; set a/b labels to
  "{name} A"/"{name} B" only internally if needed, never shown as stops). While a trip runs,
  show the existing "Not while a trip is running" pill.
- Pure tests for `stop_detail` and the delete-confirm toggle helper.

## Step 6. Velocity-aware pin drops (wire snap.rs into gestures)
- `map.rs` `Grab` gains `tracker: snap::VelocityTracker`, `start_at: (f32, f32)`, `peak` handled
  by the tracker; `Phase::Start` resets and pushes, `Phase::Move` pushes `(now_ms, at)`.
  `Gesture::PinDrop { slot, at, release_px_s: f32, peak_px_s: f32, travel_px: f32 }`.
  Gesture tests: a fast drag reports release speed > FAST_PX_S; a drag that pauses 150 ms before
  lifting reports ~0.
- `apply_gesture` PinDrop: nearest GTFS candidate via `pick_stop` with radius `FAST_RADIUS_PX`
  and its px distance (`viewport.project`); `started_on_candidate` = the pin's
  `Destination.gtfs_stop` equals the candidate's id; `snap::decide`; Snap -> the stop's
  Destination, Free -> `Destination::dropped(unproject(at))`. PinDrag stays exact (no snap while
  moving). Taps keep `snap_destination` gated by `snap::tap_snaps`.
- Pure helper `pub fn drop_destination(feed, viewport, size, at, input_without_candidate, current:
  &Destination) -> Destination` in map.rs so this is unit-tested headlessly (slow drop near stop
  stays raw; fast drop snaps; started snapped + small slow move stays snapped).

## Step 7. Spring settle into the snapped spot and a light haptic
- `MapState.settle: Option<PinSettle>`; `PinSettle { slot, from: Coord, spring: Spring }` using
  `rovsti_ui::spring::Spring` with `rovsti_ui::motion_tokens::release()` (0 -> 1). On a Snap
  drop: set it from the release coord; pins render at `lerp(from, dest.coord, value)`; tick it in
  the `motion.frame_timed` closure in main.rs; skip the scene cache while settling (or include the
  settle value in `SceneKey`). Reduced motion: no settle (jump).
- Haptic: in the drop handler call `rovsti_ui::haptics::perform(window, HapticFeedback::Selection)`
  when the decision is Snap (this is the existing haptic path, GPUI `Window`; no new
  rovsti_android function is needed). The map input listener must pass `window` through; verify
  the closure signature in map.rs `input_surface` and the fork source before changing it.
- Pure test: lerp of the settle at 0/1 and that a Free drop starts no settle.

## Step 8. Docs and final verification
- New `docs/stops-saved-and-snapping.md`: the stop = gate AB model, saved-stop file format v2 and
  the discard of old single-point data, name prefill rule, and the snapping rule with all
  constants from snap.rs (slow never snaps, linear radius, peak+near rule, hold hysteresis,
  tap radius, settle spring, haptic). Link it from `docs/architecture.md` where Stops is
  described; replace any "saved places" wording in docs/ for Stops.
- Grep apps/stops and the Kotlin side for leftover "saved place" copy; run all checks.
