# UX completeness pass: Rovsti Stops (round 5)

Base: `fc7e72a7439d76f5debd995a74eea22ea6b841aa` (main). No stash, clean tree.
Rounds 1-4 (`ux-stops.md` .. `ux-stops-r4.md`) are done and committed (last: `73c35fe`..`fc7e72a`).
- The owner's gap is closed. Any pin or picked point can be saved from the pin menu, with a name
  field prefilled with the snapped stop name.
- Saved places can be renamed, reordered, deleted with undo, and shared.
- Saved places are drawn on the map as rings. A tap on one picks it exactly, and the pin shows
  its name.
- Other gaps closed: share any pin, paste into the picker, delete single History trips, the
  downloaded-maps size with Clear, a searchable chat picker that refreshes, and picker empty
  states.

This round re-audits the whole app from the phone user's side. The one management gap left on
saved items is moving a place (U5, deferred twice). The owner asked for every gap of this kind,
so it is planned now. The audit also found silent failures around the arrival message and the
snooze limit.

Scope: `apps/stops`, `crates/rovsti_geo`, `crates/rovsti_tiles`. No new product features and no
backends. Pure logic goes first, with tests. GPUI glue stays thin. Only GPUI APIs already used in
this app are allowed: `glass_pill`/`list_row` with `.id(..).on_click(cx.listener(..))`,
`empty_state`, `self.tabs.jump_to`, `self.map.take_over()`, `Viewport::new(.., STREET_MPP)` as in
`locate_me`, `show_pill` with a `PillAction`.

## Re-audit: gaps

| # | Sev | Gap | Where |
|---|-----|-----|-------|
| V1 | **Medium** | A saved place cannot be moved (round-4 U5). Rename is the only edit. To fix a misplaced "Home", the user must delete it, find the spot, save again under the same name, and lose its list position. Items created in one place cannot be fully managed elsewhere. | `saved_ui.rs`, `rovsti_geo::places` |
| V2 | **Medium** | A failed arrival message is silent. After `MAX_NOTICE_ATTEMPTS`, or a `GiveUp` (no link, bad chat), `NoticeState` stops retrying. The live card still says "Will text when arriving", and the alarm screen says nothing. The user thinks the chat was told and does not text them by hand. | `rovsti_geo::session::{NoticeState, FixOut}`, `trip_ui.rs::{Live, live_card, alarm_screen}` |
| V3 | Low | The alarm keeps a "Snooze N min" button after the 5th snooze (`MAX_SNOOZES`). Tapping it, or System Back (which snoozes), ends the trip with no warning: headless refuses the snooze and Kotlin dismisses. | `trip_ui.rs::alarm_screen`, `main.rs::on_back` |
| V4 | Low | "Swap" shows and is tappable with no point set, and it does nothing (no feedback). | `trip_ui.rs::setup_cards` |
| V5 | Low | The Saved empty state explains how to save a place but has no way there. The user has to find the Trip tab on their own. | `saved_ui.rs::saved_tab` |
| V6 | Info | The pin menu of a saved place offers Rename but not Delete. The Saved tab has Delete with undo. No change. | `map.rs::pin_menu` |
| V7 | Info | The "Text X when arriving" toggle resets to On at launch. By design (round-3 T8): a per-trip choice. No change. | `trip_ui.rs` |
| V8 | Info | Statistics rows ("Top places") are not tappable. They are statistics, and their places are reachable from Saved and History. No change. | `settings_ui.rs` |
| V9 | Info | `rovsti_tiles`: no new gap. Test before save, reset with undo, size and Clear are all in place. | |

## Plan (ordered by user impact, pure logic first; each step leaves all checks green)

### S1. rovsti_geo pure logic, with tests (no UI change)

`crates/rovsti_geo/src/places.rs`:
- Factor the coordinate check of `add` into `fn valid_coord(coord: Coord) -> bool` and reuse it.
- New method:
  `pub fn relocate(&mut self, id: u64, coord: Coord, gtfs_stop: Option<String>) -> Result<(Coord, Option<String>), PlaceError>`.
  It moves place `id` to `coord` with the new stop and returns the old `(coord, gtfs_stop)` for
  undo. Name, id and list position stay. The checks run in this order:
  1. `InvalidCoord` when the coordinate is not on Earth.
  2. `NotFound` when the id is missing.
  3. `Duplicate(other)` when another place (`p.id != id`) is within `DUPLICATE_RADIUS_M`.
  The place itself being near `coord` is fine: that is a small nudge.
- Tests:
  - `relocate_moves_and_keeps_name_id_and_position`
  - `relocate_returns_the_old_point_for_undo`
  - `relocate_refuses_a_spot_taken_by_another_place`, which also checks that a 20 m nudge of
    the same place is allowed
  - `relocate_refuses_bad_coords_and_unknown_ids`

`crates/rovsti_geo/src/session.rs`:
- `impl NoticeState { pub fn gave_up(&self) -> bool { !self.sent && self.attempts >= MAX_NOTICE_ATTEMPTS } }`
- `FixOut` gains `#[serde(default)] pub notice_failed: bool`. `fix_out` sets it to
  `self.notice.as_ref().is_some_and(NoticeState::gave_up)`. Older JSON keeps loading.
- `impl ActiveTrip { pub fn snoozes_left(&self) -> u8 { MAX_SNOOZES.saturating_sub(self.snoozes) } }`
- Tests:
  - `a_given_up_notice_is_reported_as_failed`: after `notice_result(GiveUp)`, `fix_out(..)`
    has `notice_failed` true and `notice_sent` false. After `RetryLater` three times it is
    also true. After `Sent` it is false.
  - `snoozes_left_counts_down_to_zero`, which also checks that it saturates at 0.
  - A `FixOut` JSON without `notice_failed` deserialises to false. Add it to the
    old-answer test if one exists.
- Fix every `FixOut { .. }` struct literal in the workspace (grep `FixOut {`).

Acceptance: `cargo test -p rovsti_geo` passes with the new tests, and nothing in the UI changes.

### S2. Stops pure helpers, with tests (no UI change)

`apps/stops/src/pick.rs`:
- `pub fn relocate_hint(name: &str) -> String`: `Tap the map to move {name}`. An empty name
  gives `Tap the map to move the place`.
- `pub fn relocate_result_text(result: &Result<(Coord, Option<String>), PlaceError>, name: &str, saved: &SavedPlaces) -> String`:
  - `Ok` → `Moved {name}`
  - `Duplicate(id)` → `{other name} is already saved there`
  - other errors → `error.message()`
- Tests cover each branch.

`apps/stops/src/trip_ui.rs`:
- `pub fn notice_line(sent: bool, failed: bool, planned: bool) -> Option<&'static str>`:
  - sent → `Arrival message sent`
  - failed → `Arrival message not sent. Text them yourself.`
  - planned → `Will text when arriving`
  - otherwise None
  Sent wins over failed. Test each case.
- `pub fn snooze_label(minutes: u8, left: u8) -> Option<String>`: None when `left == 0`, else
  `Snooze {m} min` with `m = minutes.clamp(1, 15)`. Test 0 left, 1 left and clamping.
- `Live` gains `pub notice_failed: bool`:
  - `from_out` takes `out.notice_failed`.
  - `from_trip` takes `trip.notice.as_ref().is_some_and(NoticeState::gave_up)`.
  Update the `Live { .. }` literals in tests (around line 969) and the JSON fixtures if they
  assert the whole struct.

Acceptance: the new tests pass. Only the `Live` field is new; the UI does not read it yet.

### S3. Show a failed arrival message (V2)

`trip_ui.rs::live_card`:
- Replace the `notice_sent` / `trip.notice.is_some()` branch with
  `notice_line(l.notice_sent, l.notice_failed, trip.notice.is_some())`.
- When the line is the failed one, colour it `colors.red`, not `tertiary_label`. Keep the
  details as `(String, bool /*warn*/)`, or push the failed line as a separate child.

`trip_ui.rs::alarm_screen`:
- When `self.live.as_ref().is_some_and(|l| l.notice_failed && !l.notice_sent)`, add one
  `TITLE2`-size line under the distance: `Arrival message not sent`, in `colors.red`.

`on_status_json` / `Live::from_out`: when the old `Live` had `notice_failed == false` and the
new one is true, also `show_pill("Arrival message not sent. Text them yourself.", None, cx)`
once. This is the feedback when the app is open on another tab.

Acceptance:
- A trip whose notice gave up shows the red line on the live card and on the alarm screen.
- The pill shows once when the failure happens.
- A sent message still shows "Arrival message sent".

### S4. Move a saved place (V1)

State:
- `StopsApp` gains `pub(crate) relocating: Option<u64>`, initialised to `None`.
- `PillAction` gains `UndoRelocate { id: u64, coord: Coord, gtfs_stop: Option<String> }`.
  `pill_fired` handles it by calling `self.saved.relocate(id, coord, gtfs_stop)`. Ignore an
  error: it is the old spot, so it was free. Then `save_places` and `cx.notify()`.

`saved_ui.rs::saved_item`:
- Add a "Move" pill with id `("place-move", id)` to the second pill row, after Rename.
- When `self.trip.is_some()`, the tap shows the pill "Not while a trip is running", as
  `clear_tiles` does. Otherwise it calls `self.begin_relocate(id, cx)`.
- Update the module doc to include Move in the action list.

`map.rs` (glue):
- `pub(crate) fn begin_relocate(&mut self, id: u64, cx)`:
  1. Look up the place and return if it is gone.
  2. Set `self.expanded = None` and `self.relocating = Some(id)`.
  3. Call `self.pick.close_menu()`.
  4. Call `self.map.take_over()` and set
     `self.map.viewport = Viewport::new(place.coord, STREET_MPP)`.
  5. Call `self.tabs.jump_to(0)` and `cx.notify()`.
- In `map_tap`, before `self.pick.tap(..)`, handle `if let Some(id) = self.relocating`:
  1. Snap with `snap_destination(self.gtfs.as_deref(), &SavedPlaces::default(), ..)`, so it
     snaps to stops only and never to the place itself.
  2. Call `self.saved.relocate(id, dest.coord, dest.gtfs_stop.clone())`.
  3. On `Ok(old)`: `save_places`, set `relocating = None`, and show
     `pick::relocate_result_text(..)` with
     `PillAction::UndoRelocate { id, coord: old.0, gtfs_stop: old.1 }`.
  4. On `Err`: show the text with no action and keep `relocating`, so the user can tap
     another spot.
  5. Return early. The pins are not touched.

`trip_ui.rs::setup_cards`:
- When `self.relocating` names a place, the headline shows `pick::relocate_hint(&name)` instead
  of `hint.text()`.
- The buttons row shows only a "Cancel" pill with id `relocate-cancel`. It clears `relocating`
  and returns to the Saved tab with `self.tabs.jump_to(1)`.
- If the place is gone, clear `relocating`.

`main.rs`:
- `can_go_back` adds `|| self.relocating.is_some()`.
- `on_back` handles it right after the pin menu: clear `relocating`, then `cx.notify()`.
- The tab-bar select handler clears `relocating` when the user leaves a tab.
- `map_tap` already returns while a trip runs. Starting a trip clears `relocating` in
  `request_start`.

Acceptance:
- Saved > Home > Move opens the map centred on Home. The hint reads "Tap the map to move Home".
- A tap moves the ring there. The pill says "Moved Home", and Undo moves it back.
- A tap near another saved place says it is taken, and the mode stays.
- Back or Cancel leaves the mode with nothing changed.
- The name, list position and file persist (`places.json` is written).

### S5. Snooze limit is visible (V3)

`trip_ui.rs::alarm_screen`:
- `let left = self.trip.as_ref().map_or(1, ActiveTrip::snoozes_left);`
- When `snooze_label(self.prefs.snooze_min, left)` is None, replace the Snooze pill with a
  `FOOTNOTE` line "No snoozes left", in `secondary_label`.

`trip_ui.rs::on_snoozed`:
- After hiding the alarm, reload the trip in the background with `backend::reload_trip(&dir)`,
  as `refresh_trip` does. Copy only the snooze count into the current trip:
  `if let (Some(t), Some(new)) = (this.trip.as_mut(), trip) { t.snoozes = new.snoozes; }`
- Do not call `set_saved_trip`, so the alarm and live state are not reset.

`main.rs::on_back`:
- With the alarm open, Back snoozes only while
  `self.trip.as_ref().map_or(true, |t| t.snoozes_left() > 0)`. Otherwise Back does nothing, so
  the alarm stays and Dismiss is explicit.

Acceptance: after five snoozes, the alarm shows Dismiss and "No snoozes left". Back no longer
silently ends the trip.

### S6. Small dead ends (V4, V5)

`trip_ui.rs::setup_cards`: add the Swap pill only when `any_end`, as the Clear pill does.

`saved_ui.rs::saved_tab`: the empty state becomes a `div().flex().flex_col().items_center()`
holding:
- the existing `empty_state(..)`
- a `glass_pill(theme, "Open the map")` with id `saved-open-map`, text colour
  `colors.accent`, `.px(px(20.0))`, and a click that does `this.tabs.jump_to(0); cx.notify()`

Acceptance:
- Swap is hidden with no point.
- The Saved empty state has a button to the map.

### S7. Docs and status

`docs/roadmap.md`, Rovsti Stops:
- Replace "[ ] Idea (open, U5): move a saved place to a new location" with "[x] Move a saved
  place on the map, with undo (done without a device)".
- Extend the trip line with "a failed arrival message is shown on the live card and the alarm;
  the alarm shows when no snoozes are left".

`docs/architecture.md`: only if it lists Saved actions or `FixOut` fields, add Move and
`notice_failed`.

Append a Status section to this file listing the fixed, deferred and by-design gaps.

## Checks (every step)
```
cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace
cargo check -p rovsti_android --target aarch64-linux-android
cargo check -p rovsti_wa_client --target aarch64-linux-android
cargo check -p rovsti_tiles --target aarch64-linux-android
```
Disk: 9.3 GB free at audit time. Under 6 GB, run `rm -rf target/debug/incremental` first.
Other people push to main in parallel. Keep one focused commit per step, with the prefix
"Rovsti stops:".

## Status

- Fixed: V1 (move a saved place, with undo), V2 (failed arrival message shown on the live card and
  the alarm), V3 (alarm shows when no snoozes are left), V4 (Swap hidden with no point), V5 (Saved
  empty state links to the map).
- By design, no change: V6 (pin menu of a saved place has Rename only), V7 (arrival-text toggle
  resets per trip), V8 (statistics rows not tappable), V9 (`rovsti_tiles` has no new gap).
- `docs/architecture.md` lists neither Saved actions nor `FixOut` fields in full, so it is unchanged.
