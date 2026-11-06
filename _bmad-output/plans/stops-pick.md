# Plan: Rovsti Stops, pick A and B on the map (round 1)

- Base: `3878bcf99d8efe64fd45d4ca67ffe9a14ca1908e` (HEAD "WIP: carry-over before sync"; it only adds
  `_bmad-output/plans/stops-alarm-r3.md`). `git stash list` is empty. No earlier `stops-pick*.md`.
- Architect: PLAN agent. No source changed by this plan.
- Verdict: **not done**. 7 steps below.

## Owner's complaint

"We should be able to pick the 2 points on the map. Am I supposed to type coordinates by hand?"

## What exists at the base

- `apps/stops/src/map.rs`: pure `Gestures` (touch -> `Pan` / `Zoom` / `DoubleTap`, single tap
  delayed by `DOUBLE_TAP_MS` = 300 and delivered by `due_tap`; `click` for desktop with
  `CLICK_ECHO_MS` echo guard), pure `pick_stop`, `fit_view`, `Scene` (`diameter` only when the
  pair is valid; no pins without a valid diameter), `MapState` (`viewport`, `follow`, `position`
  set only by `map_fix` during a trip), `input_surface` (TouchEvent + MouseDownEvent through
  `window.on_mouse_event`, `over_controls` exclusion), `map_card` (`MAP_HEIGHT` 340, hidden while
  `map_has_content()` is false, i.e. on a fresh install), controls `+`, `-`, `Fit`.
- `map_tap` (idle): GTFS stop under the finger -> stop sheet; empty ground ->
  `confirm_destination(Destination::new(coord_text(coord), coord))` = sheet titled
  "Use 41.0370, 28.9850" with Use as A / Use as B. This is the hidden, slow flow.
- `apps/stops/src/picker.rs`: `Destination {label, coord, gtfs_stop, pattern}`, `Slot {A, B}`,
  `Ends {a, b}` (`next_slot`, `set`, `set_pair`, `swap`, `diameter`), `coord_text`, `offer`
  (unnamed pins labelled with coordinates), `SheetMode {Pick, Confirm, Rename, Stop}`.
- `apps/stops/src/trip_ui.rs` `setup_cards`: A/B list rows with `coords_text()` subtitles, Swap,
  trigger text or `DiameterError::message`, Start (enabled when the diameter is Ok).
- `StopsApp` (`main.rs`): `ends: picker::Ends`, `active_slot: picker::Slot` (63 uses across
  `picker.rs`, `trip_ui.rs`, `map.rs`, `saved_ui.rs`, `main.rs`).
- `rovsti_geo`: `MIN_DIAMETER_M` 20, `MAX_DIAMETER_M` 50 000, `DiameterError::{InvalidCoord,
  SamePoint, TooShort, TooLong}`, `Viewport` (`project`, `unproject`, `zoom_about`, `pan`,
  `nearest_on_screen`), `Feed::nearest`, `History::recent_pairs`, `SavedPlaces`.
- `rovsti_android::trip`: start/stop/alarm only. There is **no idle location**: Rust never sees a
  position before a trip starts. Kotlin `TripService` already uses
  `LocationManager.getCurrentLocation`; `permissions::Group::Location` and
  `backend::Step::RequestLocation` exist.
- gpui-fusion at `d6c8412` (checked): `TouchEvent {id, phase, position, force}`,
  `MouseDownEvent`, `MouseUpEvent`, `MouseMoveEvent {position, pressed_button, modifiers}`,
  `Div::on_pinch`, `on_long_press` exist. Nothing else is assumed.

## Decisions (become AD-ST9 in `docs/architecture.md`)

1. **The map is the picker.** Idle Trip tab = tall map first, a pick bar under it, sheet sources
   (paste/link, saved, recent, stop search) behind one secondary row. Tap 1 drops A, tap 2 drops
   B, the circle shows at once. No sheet, no coordinates in the flow.
2. **Snap rule.** A tap within `PICK_RADIUS_PX` (40) of a drawn GTFS stop (`pick_stop`) places
   the stop itself (its name, `gtfs_stop` id, its coord); otherwise the raw point, unnamed.
   Dropping a dragged pin uses the same rule.
3. **Unnamed points.** A raw point has an empty `label`. It is shown as "Point A"/"Point B" by the
   slot it is in (so Swap stays right) and frozen to that text only when the trip starts
   (`Endpoint.label`, history). `coord_text` survives only in the pin menu's details line and the
   shared-link confirm sheet's details line.
4. **Third tap.** With A and B set, a tap on empty map places nothing (accidental resets are
   worse than one more gesture) and shows the pill "Drag a pin to adjust it, or tap Clear."
   "Move" in the pin menu arms that slot so the next tap relocates it.
5. **Pins drag directly** (no long-press: it would delay every pan). A one-finger touch that
   starts within `PIN_GRAB_PX` (32) of a pin grabs it; past `TAP_SLOP` it drags (no pan); release
   drops (snap rule); no movement = pin tap -> pin menu at once (no double-tap wait). A second
   finger during a grab cancels the drag (pin returns) and the sequence becomes a pinch. The pin
   keeps the finger's grab offset so it does not jump under the finger.
6. **Map taps never move the view.** Picks from the sheet (saved, recent, link) still refit.
7. **Where the map opens.** Ends set -> fit them (existing `fit_view`); else the current position
   at street level (`STREET_MPP` = 1.5 m/px, tiles visible); else the last trip's midpoint or the
   first saved place at street level; else Istanbul centre (41.0082, 28.9784) at `CITY_MPP` = 12.
8. **Idle location** is a new `rovsti_android` seam: last known location (sync) + one fresh fix
   on request, delivered as a `stops_location` event. No background tracking, nothing leaves
   the phone.
9. **Pure core** in `apps/stops/src/pick.rs` (no `gpui` import) as the spec allows; gesture and
   scene additions stay in the pure sections of `map.rs`. Apps are binary crates, so no item may
   be left unused at the end of a step (`-D warnings` dead_code): each step wires what it adds.

## Steps

### S1. Pure picking state machine and labels; StopsApp moves to it

Files: new `apps/stops/src/pick.rs` (declared in `main.rs`), `picker.rs`, `trip_ui.rs`,
`main.rs`, `map.rs`, `saved_ui.rs`.

- `picker.rs`:
  - `Destination::dropped(coord: Coord) -> Self` (empty label), `Destination::is_named(&self) ->
    bool` (`!label.trim().is_empty()`). `Destination::title()` returns "Use this point" when
    unnamed. `offer()` keeps an unnamed pin unnamed (no `coord_text` label); update test
    `a_pin_without_a_name_is_named_by_its_coordinates` -> `a_pin_without_a_name_stays_unnamed`.
  - `Ends` stays (it is the data); `next_slot` stays.
- `pick.rs` (pure, imports only `rovsti_geo`, `crate::picker::{Destination, Ends, Slot}`,
  `crate::trip_ui::distance_text`):
  ```rust
  pub enum Stage { Idle, HasA, HasB, HasAB }
  pub enum TapOutcome { Placed(Slot), MenuClosed, Full }
  pub enum Hint { SetA, SetB, Moving(Slot), Ready { metres: f64 }, Invalid(DiameterError) }
  #[derive(Default)] pub struct Picking {
      pub ends: Ends,
      moving: Option<Slot>,            // "Move" armed: next tap relocates this pin
      menu: Option<Slot>,              // pin menu open
      drag: Option<(Slot, Destination)>, // pin being dragged + its value before the drag
      sheet_slot: Slot,                // slot the sheet fills (was StopsApp::active_slot)
  }
  impl Picking {
      pub fn stage(&self) -> Stage;
      pub fn slot_for_tap(&self) -> Option<Slot>; // moving, else first empty (A then B), else None
      pub fn tap(&mut self, dest: Destination) -> TapOutcome; // menu open -> close only
      pub fn place(&mut self, slot: Slot, dest: Destination);  // clears moving/menu, sheet_slot = next
      pub fn set_pair(&mut self, a: Destination, b: Destination);
      pub fn swap(&mut self);
      pub fn clear(&mut self);
      pub fn remove(&mut self, slot: Slot);
      pub fn begin_move(&mut self, slot: Slot);
      pub fn open_menu(&mut self, slot: Slot); pub fn close_menu(&mut self);
      pub fn menu(&self) -> Option<Slot>; pub fn moving(&self) -> Option<Slot>;
      pub fn drag_start(&mut self, slot: Slot) -> bool;   // false when slot is empty
      pub fn drag_to(&mut self, slot: Slot, coord: Coord); // coord moves, becomes unnamed, no stop/pattern
      pub fn drag_end(&mut self, slot: Slot, dest: Destination);
      pub fn drag_cancel(&mut self);                       // restores the saved value
      pub fn dragging(&self) -> Option<Slot>;
      pub fn sheet_slot(&self) -> Slot; pub fn set_sheet_slot(&mut self, slot: Slot);
      pub fn hint(&self) -> Hint;
      pub fn can_start(&self) -> bool; // diameter Ok and no drag in progress
  }
  impl Hint { pub fn text(&self) -> String }
  pub fn point_label(slot: Slot, dest: Option<&Destination>) -> String; // name, else "Point A"
  pub fn pair_text(ends: &Ends) -> Option<String>;  // "A-B 850 m" / "A-B 1.2 km"
  pub fn details_text(dest: &Destination) -> String; // coord_text, details line only
  pub fn trip_endpoint(slot: Slot, dest: &Destination) -> Endpoint; // label frozen to point_label
  ```
  Hint texts: "Tap the map to set A", "Tap the map to set B", "Tap the map to move A",
  `Ready` -> `pair_text`, `Invalid`: TooShort/SamePoint "A and B are too close. Drag a pin at
  least 20 m away.", TooLong "A and B are more than 50 km apart. Move a pin closer.",
  InvalidCoord -> `DiameterError::message`. Format the 20 / 50 km from `MIN_DIAMETER_M` /
  `MAX_DIAMETER_M` (no duplicated literals).
- Migration (mechanical, behaviour unchanged except labels): `StopsApp.ends` + `active_slot` ->
  `pub(crate) pick: pick::Picking`. `use_as` -> `pick.place`, `choose_pair` -> `pick.set_pair`,
  swap row -> `pick.swap`, `close_sheet` pattern restore reads `pick.sheet_slot()`.
  `setup_cards` rows show `point_label` with no coordinate subtitle; the text under the rows is
  `hint().text()` (trigger text stays as the second line when Ready); Start uses `can_start()` and
  `request_start` builds endpoints with `trip_endpoint`. Any method not yet called by glue
  (`tap`, `drag_*`, `menu`, `begin_move`, `remove`, `clear`) gets
  `#[cfg_attr(not(test), expect(dead_code, reason = "wired in S4/S5"))]`; `expect` (not `allow`)
  so the step that wires it is forced to delete the attribute.
- Tests in `pick.rs` (at least): fresh picking is Idle and hints "set A"; tap, tap -> HasAB, slots
  A then B; third tap -> `Full`, nothing changes; tap while menu open only closes it; remove A
  with B set -> HasB, next tap fills A; begin_move(B) then tap relocates B and disarms; clear ->
  Idle; drag_to clears name/stop/pattern; drag_cancel restores the exact original; can_start
  false during a drag, false under 20 m and over 50 km, true at 850 m; hint texts for each
  stage incl. Moving and both Invalid cases; point_label follows the slot after swap;
  pair_text "A-B 850 m" and "A-B 1.2 km"; trip_endpoint freezes "Point B"; named stop keeps its
  name; no hint or label text ever contains a coordinate (assert no digit-dot-digit pattern for
  unnamed points). Existing `picker.rs` tests keep passing.

Acceptance: all checks green; no `coords_text` in `setup_cards`; `active_slot`/`ends` fields gone
from `StopsApp`.

### S2. Pure gestures: grabbing, dragging and tapping pins; mouse drag on desktop

File: `apps/stops/src/map.rs` (pure section) and its tests.

- `pub const PIN_GRAB_PX: f32 = 32.0;` `const MOUSE_ID: u64 = u64::MAX;`
- `Gestures::set_handles(&mut self, handles: &[(Slot, (f32, f32))])`: the pins' positions in the
  same space as touches, replaced every frame by the glue.
- New `Gesture` variants: `PinTap { slot }`, `PinDrag { slot, at }`, `PinDrop { slot, at }`,
  `PinCancel { slot }`. `at` = finger minus the grab offset taken at Start.
- Rules in `touch` (signature unchanged): Start of the first finger within `PIN_GRAB_PX` of the
  nearest handle grabs it; Move past `TAP_SLOP` emits `PinDrag` (never `Pan`); End emits `PinDrop`
  after a drag, else `PinTap` immediately and leaves no pending single tap; a second finger
  emits `PinCancel` and continues as the existing pinch; `Cancel` emits `PinCancel`. With no
  handles, every existing behaviour and test is unchanged.
- Replace `click` with `mouse(&mut self, phase: Phase, at, now_ms) -> Option<Gesture>`: feeds
  `touch(MOUSE_ID, ..)`, ignores a Start within `CLICK_ECHO_MS` of the last real touch (and the
  rest of that sequence). Desktop gains mouse pan and pin drag; taps still go through
  `register_tap`/`due_tap`.
- Glue in this step only: `input_surface` also listens to `MouseMoveEvent` (only while
  `pressed_button == Some(MouseButton::Left)` after a down on the map) and `MouseUpEvent`, mapping
  to `MapInput::Mouse { phase, at }`; `apply_gesture` handles the four Pin variants by calling
  `pick.open_menu` / `drag_start`+`drag_to` / `drag_end` (raw unnamed point for now; snapping is
  S5) / `drag_cancel`. Handles are set from `pick.ends` projected through `viewport` and shifted
  to window space (`bounds.center()`), so pins already drag even before S5 draws them as pins.
- Tests (pure): a touch on a pin then a move drags it and never pans; the drag keeps the grab
  offset; a still touch on a pin is a `PinTap` at once and `due_tap` stays empty; a touch 40 px
  away pans as before; a second finger cancels the pin and pinches; `Cancel` cancels; the nearest
  of two overlapping handles wins; mouse down/move/up pans and drags; the mouse echo right after
  a touch is ignored (replaces `a_click_right_after_a_touch_is_its_echo`); all existing gesture
  tests unchanged.

### S3. Pure scene and view: pins, invalid segment, opening view, menu rect

File: `apps/stops/src/map.rs` (pure section) and tests.

- `SceneInput` gains `pins: [Option<(Coord, bool)>; 2]` (A, B; `bool` = snapped to a stop).
  `Scene` gains `pins: Vec<PinScene>` (`slot`, `at`, `snapped`) for every set end, and
  `segment: Option<((f32, f32), (f32, f32))>` when both ends are set but the diameter is invalid
  (drawn as a plain warning line instead of a circle). `diameter` is unchanged.
- `pub const STREET_MPP: f64 = 1.5; pub const CITY_MPP: f64 = 12.0;`
  `pub const DEFAULT_CENTRE: Coord = Coord::new(41.0082, 28.9784);`
- `pub fn idle_view(ends: &Ends, position: Option<Coord>, fallback: Option<Coord>, size) ->
  Viewport` per decision 7 (one end set -> centre on it at `STREET_MPP`; both valid -> `fit_view`;
  both invalid -> fit the two points).
- `pub fn fallback_centre(history: &History, saved: &SavedPlaces) -> Option<Coord>`: last recent
  pair's midpoint, else the first saved place.
- `pub fn pin_menu_rect(pin: (f32, f32), size: (f32, f32)) -> (f32, f32, f32, f32)`: a
  `PIN_MENU_W` x `PIN_MENU_H` (e.g. 180 x 132) box above the pin (below it when there is no room),
  clamped inside the map with an 8 px margin, in map-local pixels from the top-left.
  `pub fn over_rect(rect, at) -> bool`. `over_controls` stays.
- Tests: pins appear for one end and for an invalid pair; segment only for an invalid pair;
  idle_view priority chain (ends > position > fallback > default) and its zoom levels;
  fallback_centre picks the recent pair midpoint then saved; pin_menu_rect stays inside the map
  at all four corners and flips below a pin near the top.
- Glue in this step: `map_card` uses `idle_view` when idle and `follow` (trip keeps `fit_view`),
  passes `pins` to `SceneInput`; `map_has_content` returns true whenever no trip runs (the map is
  always there on the idle screen). `paint_scene` paints `segment`; pins are painted in S5.

### S4. Android seam: current location while idle

Files: new `crates/rovsti_android/src/location.rs` (or extend `trip.rs`; keep the module doc
style), `lib.rs`, `events.rs` (`kind::STOPS_LOCATION = "stops_location"`), new
`crates/rovsti_android/kotlin/dev/rovsti/bridge/StopsLocation.kt`, `RovstiBridge.kt`,
`apps/stops/src/kotlin_contract.rs`, `apps/stops/src/main.rs`.

- Rust: `pub fn last_location() -> Result<Option<LocationFix>>` (bridge `lastLocation(): String`,
  "" = none), `pub fn request_location() -> Result<bool>` (bridge `locateOnce(): Boolean`; the
  fix arrives as a `stops_location` event; false without permission or provider),
  `#[derive(Clone, Copy, Debug, PartialEq)] pub struct LocationFix { lat, lon, accuracy_m: f64,
  time_ms: i64 }`, pure `pub fn parse_location_fix(json: &str) -> Option<LocationFix>` (rejects
  non-finite, out-of-range, negative accuracy, missing fields). Desktop: `Error::Unsupported`
  through the existing bridge stubs.
- Kotlin `StopsLocation`: `last(ctx)` = newest of `getLastKnownLocation` over fused (API 31+),
  gps, network, passive, guarded by permission check and `SecurityException`; `once(ctx)` =
  `getCurrentLocation` (API 30+) else `requestSingleUpdate`, emitting via `RovstiBridge.emit(
  "stops_location", json)`; pure `fixJson(lat, lon, acc, timeMs)`. `@JvmStatic` wrappers in
  `RovstiBridge` (String/Boolean only, per the bridge rule).
- App: on load (background executor) `last_location()` -> `map.position` when fresher than
  10 min (pure helper `fresh_enough(time_ms, now_ms) -> bool` in `pick.rs` or `map.rs`, tested);
  `on_event` handles `kind::STOPS_LOCATION` -> position + recentre. The trail is not touched.
- Tests: `parse_location_fix` good/bad inputs (rovsti_android unit tests); kotlin_contract:
  `StopsLocation.kt` contains `getLastKnownLocation`, `getCurrentLocation`, `"stops_location"`,
  `SecurityException`, and `RovstiBridge.kt` has `fun lastLocation(): String` and
  `fun locateOnce(): Boolean`.
- Checks include `cargo check -p rovsti_android --target aarch64-linux-android`.

### S5. GPUI glue: tap to place, pins, my-location, pick bar

Files: `apps/stops/src/map.rs` (glue), `apps/stops/src/trip_ui.rs`, `main.rs`. Read
`.claude/skills/liquid-glass-gpui/SKILL.md` first; reuse `flat_glass`, `glass_pill`,
`control_button`, `list_row`, theme tokens; no new GPUI APIs.

- `map_tap` (idle, no sheet, no alarm): snap via `pick_stop` -> `Destination` for the stop
  (`label` = stop name, `gtfs_stop` = stop id, coord) else `Destination::dropped(coord)`; then
  `pick.tap(dest)`: `Placed` -> `cx.notify()` (view does not move), `Full` -> pill "Drag a pin to
  adjust it, or tap Clear.", `MenuClosed` -> notify. Factor the snap into one pure fn
  `snap_destination(feed: Option<&Feed>, viewport, size, at) -> Destination` (tested with a
  two-stop feed: near -> stop name, far -> unnamed). `PinDrop` uses the same fn. The stop sheet is
  no longer opened by a map tap.
- Pins painted after the scene: filled circle (`side_color` A/B tokens) with a white rim, letter
  label via `map_label`, a ring when `snapped`; the dragged pin drawn 1.2x. Name labels
  (`point_label`) under each pin in `CAPTION`, no coordinates.
- Idle layout (`trip_tab`): map card first, height `IDLE_MAP_HEIGHT` (e.g. 460; trip keeps
  `MAP_HEIGHT`), then a pick bar: `hint().text()` in `HEADLINE`, Clear (shown when any end is
  set, `pick.clear()`), Swap, Start (`can_start()`; disabled colour + the friendly Invalid text
  otherwise). Under it, one secondary row "Saved places, recent trips, links" -> `open_picker`,
  then the existing notice toggle and preflight rows. The old A/B list rows become compact rows
  ("A  Kadikoy Pier" / "B  Point B") that open the sheet for that slot (`set_sheet_slot`).
- Controls: idle shows `+`, `-`, and a my-location button (label "Me" or `Icon` from the
  existing icon set; grep `rovsti_ui` for a locate icon before adding text) instead of `Fit`;
  tap -> preflight location step (existing `backend::Step::RequestLocation`) if needed, else
  `request_location()`; desktop `Unsupported` -> pill "Location is not available here."
  `CONTROL_COUNT` stays 3.
- Remove every `expect(dead_code)` the wiring makes unfulfilled.

### S6. GPUI glue: pin menu (Move / Remove / Lines / details)

Files: `apps/stops/src/map.rs` glue.

- When `pick.menu()` is `Some(slot)`: an absolute `flat_glass` card at `pin_menu_rect`, with rows
  "Move" (`begin_move`), "Remove" (`remove`), "Lines" when the pin is a GTFS stop (`open_stop`
  with that stop's index), and a `CAPTION` details line `details_text` (the only place lat/lon
  shows in the flow). `input_surface` skips touches/clicks inside the menu rect (pass the rect in
  alongside `over_controls`). Any map tap outside closes it (`TapOutcome::MenuClosed`). A drag or
  trip start closes it. Back closes it before anything else (`on_back`).
- The hint reads "Tap the map to move A" while a move is armed; Clear disarms.

### S7. Secondary sources, shared links, docs

Files: `picker.rs`, `saved_ui.rs`, `trip_ui.rs`, `docs/architecture.md`, `docs/roadmap.md`,
`docs/android.md`, `CLAUDE.md` only if a fact there changes (it should not).

- Sheet picks (saved, recent, stop search, stop sheet "Use as A/B", shared `geo:` / maps links via
  `offer_text` and the Confirm sheet) all go through `pick.place` / `pick.set_pair` and refit the
  view; the Confirm sheet's default emphasised slot is `slot_for_tap()` (else A); its title for an
  unnamed point is "Use this point" with `details_text` as a footnote. Recent rows and history
  show `point_label`-style fallbacks for empty labels (old records keep their stored labels).
- Copy pass: empty state "Tap the map to set A" replaces "Where are you going?" copy that
  mentions picking two points; no other main-flow string shows coordinates (grep `coord_text` /
  `coords_text` call sites: only details lines and the Confirm footnote remain).
- Docs: `docs/architecture.md` AD-ST9 (decisions 1-9 above, condensed); Stops crate notes mention
  `pick.rs` and the `stops_location` seam; `docs/android.md` Stops host: `lastLocation`,
  `locateOnce`, `stops_location`; `docs/roadmap.md` Rovsti Stops: "[x] Pick A and B on the map:
  tap to drop, drag to adjust, snap to stops, my location (done without a device)" and
  "[ ] Check map picking one-handed on a phone".
- Final run of the full check list from the task, plus `cargo check -p rovsti_wa_client` and
  `-p rovsti_tiles` for `aarch64-linux-android`.

## Check list (every step)

```
df -h .    # under 6 GB free: rm -rf target/debug/incremental
cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace
cargo check -p rovsti_android --target aarch64-linux-android
cargo check -p rovsti_wa_client --target aarch64-linux-android
cargo check -p rovsti_tiles --target aarch64-linux-android
```
