# UX completeness pass: Rovsti Stops (round 1)

Base: `7af5352a6096c553453c63c7f8202a867856dd8d` (main). No stash, clean tree.
Scope: `apps/stops`, `crates/rovsti_geo`, `crates/rovsti_tiles`. No new product features, no
backends. Pure logic first, tested; GPUI glue thin; only GPUI APIs already used in the app.

## Audit: gaps found (from the point of view of a user holding the phone)

| # | Sev | Gap | Where |
|---|-----|-----|-------|
| G1 | **Critical** | A pin dropped on the map cannot be saved. The pin menu has Move, Remove, Lines only. `SavedPlaces::add` exists, but no UI path reaches it from a pin. | `map.rs::pin_menu` |
| G2 | **High** | The confirmation sheet's "Save this place" toggle has no name field. A pasted point or a geo link without a label has an empty label, so `SavedPlaces::add` fails with "Names need 1 to 60 characters" after the sheet has already closed. A duplicate is swallowed silently, and a successful save gives no feedback. | `picker.rs::confirm_body`, `save_point` |
| G3 | **High** | Re-picking a saved place does not show its name. A map tap or pin drop at a saved place gives an unnamed "Point A", or the GTFS stop name instead of the user's own name. A pasted link at a saved place is also unnamed. After a pin is saved, the pin still reads "Point A". | `map.rs::snap_destination` callers, `picker.rs::offer_text` |
| G4 | Medium | "Save this stop" saves the stop under the feed name at once, with no chance to name it. | `picker.rs::stop_body/save_stop` |
| G5 | Medium | Saved tab: no order control (the plan allows reorder), no detail line (the user cannot tell a stop from a point or see how far it is), and the empty-state text points to a toggle that does not lead anywhere useful. Rename has no Cancel and no confirmation pill. A rename does not update a pin that shows the old name. | `saved_ui.rs`, `picker.rs::rename_body/rename_place` |
| G6 | Medium | History rows are titled "Trip", with the pair buried in the subtitle. Rows are not tappable, so a past trip cannot be reused from History (the picker shows only the last 3 pairs). "Clear history" cannot be undone, and its armed state survives leaving the page. | `settings_ui.rs::history_item/clear_history` |
| G7 | Low | Clear on the Trip tab drops both pins with no undo. | `trip_ui.rs::setup_cards` |
| G8 | Low | The picker field placeholder "Paste a link or coordinates" hides that typing searches stops and saved places. | `picker.rs::pick_body` |
| G9 | Low (deferred) | Sharing or copying a saved place out of the app. No Stops share or clipboard seam exists (`share_text` lives in the Phone telecom bridge). Adding one is a new feature: noted, not planned. |
| G10 | Info | `rovsti_tiles`: tile server settings already have test, save and reset, with pills. No gap found. GTFS import, re-import and remove already exist with pills. |

Duplicated code to fold while fixing: `save_point` and `save_stop` (picker.rs) both do
add + set gtfs_stop + save.

## Plan (ordered by user impact, pure logic first; each step leaves all checks green)

### S1. rovsti_geo: saved-place lookup, save with stop id, reorder (pure + tests)
`crates/rovsti_geo/src/places.rs`:
- `pub fn near(&self, coord: Coord) -> Option<&Place>`: the nearest place within
  `DUPLICATE_RADIUS_M`, or `None`.
- `pub fn save(&mut self, name: &str, coord: Coord, gtfs_stop: Option<String>) -> Result<u64, PlaceError>`:
  `add` followed by setting `gtfs_stop`, with the same errors.
- `pub fn move_by(&mut self, id: u64, delta: isize) -> bool`: moves a place up or down,
  clamped. Returns false when it is missing or does not move.
Tests: `near` picks the nearest of two within 50 m and returns None past 50 m; `save` keeps the
stop id and passes errors through; `move_by` covers up, down, clamp at both ends, an unknown id,
and other ids keeping their order.

### S2. Pure naming and save helpers in apps/stops (pick.rs / picker.rs + tests)
- `Destination::named_from(self, saved: &SavedPlaces) -> Destination`: when `saved.near(coord)`
  is Some, the label becomes the place's name. Keep `coord`, `pattern` and `gtfs_stop`, and fill
  `gtfs_stop` from the place when it is None.
- `pick::save_prefill(dest: &Destination) -> String`: the trimmed label (the snapped stop name),
  else empty.
- `pick::save_result_text(result: &Result<u64, PlaceError>, saved: &SavedPlaces) -> String`:
  Ok gives "Saved as {name}". `Duplicate(id)` gives "Already saved as {name}". Any other error
  gives `error.message()`.
- `Picking::relabel_near(&mut self, coord: Coord, label: &str)`: renames each end within
  `DUPLICATE_RADIUS_M` of `coord`. Used after a save or a rename.
- `Picking::restore(&mut self, ends: Ends)`: brings back cleared ends. Used for the Clear undo.
Tests in `pick.rs`/`picker.rs`: a raw point near a saved place takes its name; a stop near a
saved place takes the user's name and keeps its gtfs id; a point far away stays unnamed; the
prefill and the result texts; `relabel_near` touches only nearby ends; `restore` round-trips.

### S3. Save sheet + pin menu Save (fixes G1, G3)
- `picker.rs`: new `SheetMode::SavePoint { dest: Destination, slot: Option<Slot> }`, with the
  title "Save place". The body has a footnote with `details_text` and a `field_box`
  ("rename-field" style, id `save-field`, placeholder "Name") prefilled with `save_prefill`.
  Pills are Cancel and Save (accent).
  `pub(crate) fn open_save_point(&mut self, dest, slot, cx)` sets the field and opens the sheet.
  `fn commit_save_point(&mut self, cx)`:
  - `self.saved.save(field text, dest.coord, dest.gtfs_stop)`.
  - On Ok: `save_places`, then `pick.relabel_near`, then close, then pill (S2 text).
  - On Duplicate: relabel to the existing name, close, pill.
  - On InvalidName or Full: pill, and the sheet stays open.
- `map.rs::pin_menu`: add a row. When `saved.near(dest.coord)` is Some, the row is "Rename",
  which opens `open_rename(id)`; otherwise it is "Save", which closes the menu and opens
  `open_save_point(dest.clone(), Some(slot))`. Bump `PIN_MENU_H` to 168 and update
  `the_menu_rows_fit_the_menu_box` (4 rows + details).
- Naming on pick: apply `.named_from(&self.saved)` to the `snap_destination` result in
  `map_tap` and on `Gesture::PinDrop`, and to `Offer::Confirm` in `offer_text` and the paste row.
Acceptance:
- A dropped pin shows Save in its menu, and the name is prefilled with the snapped stop name.
- After a save, the pin label and the A/B row show the name, and the pill says "Saved as X".
- The place appears in the Saved tab.
- Tapping that spot again shows the saved name, and the menu offers Rename.

### S4. Confirm sheet and stop sheet use the name field (fixes G2, G4)
- `confirm_body`: when "Save this place" is On, show a name `field_box` under the toggle,
  prefilled from `save_prefill` when the toggle is turned on.
- `use_as(.., save=true, ..)` reads the name from the field and saves through the same path as
  `commit_save_point` (factor out `fn save_named(&mut self, name, dest, slot) -> bool`), then
  shows the pill.
- On InvalidName the sheet stays open with the pill.
- Remove `save_point`.
- `stop_body`: "Save this stop" opens `open_save_point(here, None)`, prefilled with the stop
  name. Remove `save_stop`.
Acceptance: saving a pasted unnamed point asks for a name and never fails silently. Every save
gives a pill.

### S5. Saved tab management (fixes G5)
- `saved_item`: the subtitle is "Transit stop" when `gtfs_stop` is set, else "Place", followed
  by `distance_text` from `self.map.position` when it is known.
  Write this as a pure fn `saved_ui::place_detail(place, here: Option<Coord>) -> String`, with
  tests.
- When a row is open, it shows two rows of pills:
  - Use as A, Use as B
  - Rename, Up, Down, Delete
  Up and Down call `saved.move_by(id, ∓1)`, then `save_places`, then notify. Each is disabled
  (tertiary colour, no handler) at its end.
- Empty state: "Tap a pin on the map and choose Save, or save a stop or a shared place. Saved
  places appear here for one-tap use as A or B."
- `rename_body`: add a Cancel pill. `rename_place` on Ok calls `pick.relabel_near(place.coord,
  new)` and shows the pill "Renamed to X".
Acceptance: places can be renamed, reordered and deleted (with undo), and each action gives
feedback.

### S6. History reuse and undo (fixes G6)
- `settings_ui.rs`:
  - The row title is `record_pair(r)`. Add a pure `record_details(r, now)` for the subtitle
    (date · duration · distance) and keep `record_subtitle` only if it is still used. Update the
    tests.
  - A record with both `a` and `b` is tappable. Tapping it calls `choose_pair` with
    `Destination::new(..).named_from(&saved)`, then `tabs.jump_to(0)`, then
    `open_settings(Main)`. The row shows the trailing text "Use".
- `clear_history`:
  - Keep the cleared `History`.
  - Add `PillAction::RestoreHistory(History)`: Undo restores it and saves through a new
    `backend::save_history(dir, &History)`, which wraps `storage::save_json` with
    `store::HISTORY_FILE`.
  - The pill says "History cleared".
- Reset `confirm_clear` in `open_settings`.
- Test `backend::save_history` round-trip with `store::tests::tmp`.

### S7. Trip-tab Clear undo and the picker placeholder (G7, G8)
- `trip_ui.rs` Clear:
  - Keep `self.pick.ends.clone()`.
  - Add `PillAction::RestoreEnds(Ends)`, which calls `pick.restore(ends)` and sets
    `map.follow = true`.
  - The pill says "Points cleared".
- `pick_body` placeholder: "Search stops and saved places, or paste a link".

### S8. Docs
- `docs/roadmap.md`, Rovsti Stops: add "[x] Save and manage places from map pins, links and
  stops (name, rename, reorder, delete with undo); history reuse (done without a device)".
- Record G9 (share/copy out) as an open idea, not planned.
- Update this plan file's status.

## Checks (every step)
```
cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace
cargo check -p rovsti_android --target aarch64-linux-android
cargo check -p rovsti_wa_client --target aarch64-linux-android
cargo check -p rovsti_tiles --target aarch64-linux-android
```
Disk: 8.7 GB free at audit time. Under 6 GB, run `rm -rf target/debug/incremental` first.
