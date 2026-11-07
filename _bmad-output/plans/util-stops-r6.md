# util-stops: everyday utilities for Rovsti Stops (round 6)

baseSha: fda322eff78818bd3794e2ae2730da3285fe3a87

## Audit at baseSha

- R1-R5 (`util-stops.md` .. `util-stops-r5.md`, last commits 5c4b892..fda322e) shipped S1-S18,
  S20-S34, S36-S39 (see `docs/stops-utilities.md`). No stash entries; working tree clean.
- Disk had 2.3 GB free at planning time: run `rm -rf target/debug/incremental` before every build.
- Checked in code for the R6 backlog:
  - S40 (saved stops on the map): the rings ARE already drawn (`Scene::saved`, `SceneInput::saved`
    built in `map.rs` ~2035 from `SavedStop::center`, painted in `scene_paths` with `pal.saved =
    c.orange`, cache key `saved_key`). Missing: a way to hide them, and a tap on a ring does nothing
    special (`map_tap` drops a pin there). `Viewport::nearest_on_screen(&[Coord], at, max_px)`
    already exists in `rovsti_geo::view`.
  - S35 (colour tag): `SavedStop` has `favourite` (serde default, skipped when false) but no colour.
    `places_io::Incoming` carries `favourite`; backup embeds `SavedStops` as is. Theme tokens
    available (`liquid_glass_kit::tokens::Colors`): `accent`, `red`, `green`, `orange` only, so the
    tag set is limited to those four (no invented colours).
  - S41 (new): the live card says "About 4 min to the line" (`trip_ui::eta_text`) and the status
    share says "about 4 min" (`session::status_share_text`), but never the clock time. The app has
    `StopsApp.tz_offset_s`; `history_io::date_time` already formats HH:MM privately.
  - S42 (new): Map options (`map_options.rs::map_options_body`) has Appearance and Download this
    area only. No way to share or copy the current map view or its centre.
    `tiles::slippy::display_zoom(&Viewport) -> f64` exists; `picker.rs::open_copy_as(label, coord)`
    and `saved_ui.rs::share_out(name, text)` are reusable.
  - Already present, not re-planned: rename, show on map, map fit button, keep-screen-on, recents.

## Utility backlog (ordered by user value)

| # | Utility | Status |
|---|---------|--------|
| S1-S9 | Copy as, share stop, QR, open in maps, export/import stops, Saved search/sort, transit stop sheet, measure, bearing | done R1 |
| S10-S16 | Offline area, appearance, vibration pattern, alarm sound, volume, history search/filter/start again/save, history export | done R2 |
| S17, S18, S20-S23, S25, S26 | Follow-me, home screen shortcut, live status share, re-arm, tile cache size, paste DMS/Plus Code, share my location, measure actions | done R3 |
| S24, S27-S33 | Favourites, last trip card, scale bar, open saved stop in maps, copy list, mojibake fix, backup/restore, share/copy trip | done R4 |
| S34, S36-S39 | Pin distance + Measure from here, delete old trips, nearest saved stop, usage on saved rows, reset alarm settings | done R5 |
| S41 | Arrival clock: "About 4 min to the line, around 18:42" on the live card and in the status share | R6 |
| S40 | Saved stops on the map: tap a ring opens a saved-stop sheet (Start, Share, Copy as, Open in maps, Open in Saved, Place a pin here); "Saved stops on map" toggle in Map options | R6 |
| S42 | Map options: Copy map link, Share this view, Copy centre as | R6 |
| S35 | Saved stops: colour tag (Orange / Red / Green / Blue), shown on the row and on the map ring | R6 |
| S43 | Distance units (metric / imperial) | backlog R7 (touches every distance string; needs its own pass) |
| S44 | Saved stop note (free text shown in the row and share text) | backlog R7 |
| S19 | Reverse / Duplicate a saved stop | dropped (gate is direction-agnostic) |

## Round 6 plan

Steps 1-2 pure (unit-tested, `crates/rovsti_geo`), steps 3-6 thin GPUI, step 7 docs. No new Android
seam is needed (share / clipboard / maps go through the existing `backend` helpers). Every step
leaves all checks green: `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings
&& cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo
check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target
aarch64-linux-android`.

### Step 1. Pure: arrival clock and map view links (S41, S42)
- `crates/rovsti_geo/src/session.rs`:
  - `pub fn arrival_clock(now_ms: i64, eta_min: u32, tz_offset_s: i32) -> String`: local "HH:MM"
    of `now_ms + eta_min.max(1) * 60_000` (24 h, zero padded, `div_euclid`/`rem_euclid` like
    `history_io::date_time`).
  - `status_share_text` gains a last param `arrive: Option<&str>`; when Some and `eta_min` is
    Some the ETA part reads `"about 4 min (around 18:42)"`. Update the 4 existing tests and the one
    caller (`apps/stops/src/trip_ui.rs::status_text`, pass `None` in this step).
- `crates/rovsti_geo/src/coord_format.rs`:
  - `pub fn osm_view_url(center: Coord, zoom: f64) -> String`:
    `https://www.openstreetmap.org/#map={z}/{lat:.5}/{lon:.5}` with `z = zoom.round()` clamped to
    0..=19.
  - `pub fn geo_view_url(center: Coord, zoom: f64) -> String`: `geo:{lat:.5},{lon:.5}?z={z}`
    (same rounding/clamp; z clamped 1..=19 per the geo URI convention).
  - `pub fn map_view_share_text(center: Coord, zoom: f64) -> String`: `"Map view\n{osm}\n{geo}"`.
- Tests: `arrival_clock` plain, `eta 0` counted as 1 min, crossing midnight with +03:00 and with a
  negative offset; `status_share_text` with and without `arrive`; `osm_view_url`/`geo_view_url`
  rounding (14.6 -> 15), clamping (25 -> 19, -3 -> 0 / 1), negative lat/lon formatting;
  `map_view_share_text` exact string.

### Step 2. Pure: stop colour tag and map-ring preference (S35, S40)
- `crates/rovsti_geo/src/saved_stops.rs`:
  - `#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
    #[serde(rename_all = "lowercase")] pub enum StopColour { #[default] Orange, Red, Green, Blue }`
    with `pub const ALL: [StopColour; 4]`, `pub fn label(self) -> &'static str` ("Orange" ...),
    `pub fn next(self) -> StopColour` (cycles), `pub fn is_default(&self) -> bool`.
  - `SavedStop` gains `#[serde(default, skip_serializing_if = "StopColour::is_default")] pub
    colour: StopColour` (old files load as Orange and are written unchanged). Fix every struct
    literal (`add`, tests, `places_io`, `backup` tests).
  - `impl SavedStops { pub fn cycle_colour(&mut self, id: u64) -> Option<StopColour> }` (new
    colour, None when the id is unknown) and `pub fn set_colour(&mut self, id: u64, c:
    StopColour) -> bool` (for Undo).
  - `saved_key` (in `apps/stops/src/map.rs`) must also hash `colour` so the ring layer rebuilds:
    do it in this step and extend `saved_key_changes_with_the_stops_and_either_end`.
- `crates/rovsti_geo/src/places_io.rs`: `Incoming` gains `colour: StopColour`; JSON export writes
  it (only when not default), JSON import reads it (missing -> Orange), GPX -> Orange; the merge
  applies it via `set_colour` next to the favourite.
- `crates/rovsti_geo/src/prefs.rs`: `StopsPrefs` gains `pub show_saved_on_map: bool` (Default
  true; the container `#[serde(default)]` covers old files). Not part of the alarm reset.
- Tests: serde round trip with colour Red, legacy JSON without `colour` -> Orange and re-serialises
  without the key; `next` cycles through all four back to Orange; `cycle_colour` unknown id;
  places_io JSON keeps colour, GPX gives Orange; prefs legacy JSON -> `show_saved_on_map == true`,
  `with_alarm_defaults` keeps it false when it was false; backup round trip keeps the colour.

### Step 3. UI: arrival clock on the live card and in the status share (S41)
- `apps/stops/src/trip_ui.rs`:
  - `eta_text(eta_min: Option<u32>, clock: Option<&str>) -> Option<String>`: "About 4 min to the
    line, around 18:42" (clock omitted when None). Caller in the live card passes
    `session::arrival_clock(now_ms(), m, self.tz_offset_s)`. Update the existing test, add one
    with a clock.
  - `status_text(trip, live, here)` gains `arrive: Option<String>` computed the same way and
    passed to `status_share_text`; both "Copy status" and "Share status" therefore include it.
- No new pill (existing "Status copied" / share sheet are the feedback).

### Step 4. UI: Map options view utilities (S42)
- `apps/stops/src/map_options.rs::map_options_body`, a new `section` "This view" after Download:
  - "Copy map link" (`id "map-copy-link"`): `cx.write_to_clipboard(gpui::ClipboardItem::new_string(
    coord_format::osm_view_url(centre, zoom)))` exactly as `saved_ui.rs::copy_list` does; close
    the sheet, pill "Map link copied".
  - "Share this view" (`id "map-share-view"`): `self.share_out("Map view".into(),
    map_view_share_text(centre, zoom), cx)` (closes the sheet first, like other share rows).
  - "Copy centre as" (`id "map-copy-centre"`): `self.open_copy_as("Map centre".into(), centre,
    cx)` (sheet switches to CopyAs).
  - centre = `self.map.viewport.center`, zoom = `tiles::slippy::display_zoom(&self.map.viewport)`
    (re-exported as `rovsti_geo::tiles::display_zoom`).
  - Also in this section: toggle row "Saved stops on map" (`id "map-saved-toggle"`) bound to
    `prefs.show_saved_on_map`: flips, `save_prefs`, `forget_scene` / `cx.notify`, pill "Saved
    stops shown" / "Saved stops hidden" (pure `saved_toggle_pill(bool) -> &'static str`, tested).
    Hidden when there are no saved stops.

### Step 5. UI: tap a saved-stop ring (S40)
- `apps/stops/src/map.rs`:
  - Pure `pub fn pick_saved(saved: &SavedStops, vp: &Viewport, at: (f32, f32), max_px: f32) ->
    Option<u64>` via `vp.nearest_on_screen(&centres, at, max_px)`; `const SAVED_TAP_RADIUS_PX:
    f32 = 18.0`. Tests: hit, miss, nearest of two, empty list.
  - `SceneInput.saved` gets an empty slice when `!self.prefs.show_saved_on_map` (and the cache
    key includes the flag, e.g. `saved: if show { saved_key(..) } else { 0 }`).
  - `map_tap`: after the my-location check and before the pin logic, when
    `prefs.show_saved_on_map` and `pick_saved(..)` is Some(id) and no pin menu is open:
    `self.open_saved_sheet(id, cx)` and return. Pin taps keep priority (pins are hit earlier in
    the gesture code), measuring and following keep theirs (already earlier in `map_tap`).
- `apps/stops/src/picker.rs`: `SheetMode::SavedStop(u64)`; body: title = `saved_ui::row_title`,
  caption = `saved_ui::stop_detail(stop, here, usage)`; rows Start (`start_saved_stop`), Share
  (`share_stop`), Copy as (`copy_stop_as`), Open in maps (`open_stop_in_maps`), Open in Saved
  (switch to tab 1 and set `self.expanded = Some(id)`), Place a pin here (closes the sheet and runs
  the normal `pick.tap(Destination::dropped(stop.center()))` path). Make the `saved_ui.rs` methods
  used here `pub(crate)`. A deleted id closes the sheet with pill "That stop was deleted".
  Back/close behave like other sheets (no new System Back state).
- Feedback: every row reuses its existing pill; Start during a trip shows "Not while a trip is
  running" (already in `start_saved_stop`).

### Step 6. UI: colour tag (S35)
- `apps/stops/src/saved_ui.rs`: opened row gets a pill "Colour: Orange" (`id ("stop-colour",
  id)`); tap -> `self.saved.cycle_colour(id)`, save in background, pill "Colour: Red" with
  `PillAction::RestoreColour(id, previous)` (new variant in `main.rs`, handler calls `set_colour`
  and saves). The row leading area shows a small dot in the tag colour (only when not Orange, so
  untouched lists look as today).
- `apps/stops/src/map.rs`: `Scene::saved` becomes `Vec<((f32, f32), StopColour)>`
  (`SceneInput::saved: &[(Coord, StopColour)]`); `scene_paths` builds one ring path per colour
  present; pure `pub fn stop_colour_hsla(theme: Theme, c: StopColour) -> Hsla` mapping Orange ->
  `orange`, Red -> `red`, Green -> `green`, Blue -> `accent` (in `map.rs`, shared by `saved_ui`).
  Update `saved_stop_rings_in_view_are_projected`; add a test that colours survive projection.
- Pure test: `stop_colour_hsla` gives distinct colours for the four tags in both themes.

### Step 7. Docs and backlog
- `docs/stops-utilities.md`: title "rounds 1-6", link `util-stops-r6.md`; rows S35, S40, S41, S42
  (what, where, how to reach, feedback, confirmation/Undo; colour has Undo, no destructive action
  in this round).
- This file: mark shipped rows "done R6"; keep S43, S44 for R7.
- Acceptance: all checks green; every R6 utility reachable from the UI with a pill or sheet; old
  saved-stop, places and backup files still load unchanged.
