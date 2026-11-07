# util-stops: everyday utilities for Rovsti Stops (round 5)

baseSha: 20d7c96b83cb7c7a92eb8886c5e26e56c98a07ac

## Audit at baseSha

- R1-R4 (`util-stops.md` .. `util-stops-r4.md`, last commits 9361eda..20d7c96) shipped S1-S18,
  S20-S33 (see `docs/stops-utilities.md`). No stash entries; working tree clean.
- Disk had 2.3 GB free at planning time: run `rm -rf target/debug/incremental` before every build.
- Checked in code for the R5 backlog:
  - S34: the pin menu (`map.rs::pin_menu`) has Share / Copy / QR code / Open in maps / Move / Remove
    (+ Lines for a transit stop) and a footer `pick::details_text(dest)`. No distance/bearing from
    the user, no "Measure from here". Height is `PIN_MENU_H = 248.0` (7 rows of 32 + padding), the
    non-stop menu is one `PIN_MENU_ROW_H` shorter; test helper `pin_menu_rows(is_stop)` (6 / 7).
    Measuring API: `MapState::{start_measure, end_measure, measure: Option<Measure>}`,
    `Measure::push(Coord) -> bool`; the map's `toggle_measure(window, cx)` is the ruler button.
  - S36: History only has "Clear history" (two taps via `confirm_clear`, Undo via
    `PillAction::RestoreHistory(History)`). No age-based clean-up.
  - S37: idle Trip tab (`trip_ui.rs::trip_tab`) shows map, `setup_cards`, `last_trip_card`. No
    "nearest saved stop" shortcut; `saved_stops::sorted(.., SavedSort::Nearest, here)` exists.
  - S38 (new): Saved row subtitle is `saved_ui::stop_detail` ("Gate 120 m, 1.2 km away"). The
    history knows how often a gate was used (`TripRecord::pair()`), but the Saved tab never says it.
  - S39 (new): Settings > Alarm has sound, vibration, pattern, volume, snooze, sound picker, but no
    way back to the defaults after experimenting. `StopsPrefs::default()` holds them.
  - S35 colour tags: still low value next to favourites; stays in backlog.
  - S40 (new, backlog R6): saved stops as markers on the map (needs Scene/painter work in `map.rs`).
- Reusable: `session::format_distance`, `coord_format::cardinal`, `Coord::{distance_m,
  bearing_deg}` (check exact name in `crates/rovsti_geo/src/coord.rs`; `trip_ui.rs::bearing_text`
  already computes a bearing), `History::{take, put_back, records}`, `PillAction::RestoreHistory`,
  `PillAction::RestoreEnds`, `request_start`, `show_pill`, `save_prefs`, `history_io::share_text`.

## Utility backlog (ordered by user value)

| # | Utility | Status |
|---|---------|--------|
| S1-S9 | Copy as, share stop, QR, open in maps, export/import stops, Saved search/sort, transit stop sheet, measure, bearing | done R1 |
| S10-S16 | Offline area, appearance, vibration pattern, alarm sound, volume, history search/filter/start again/save, history export | done R2 |
| S17, S18, S20-S23, S25, S26 | Follow-me, home screen shortcut, live status share, re-arm, tile cache size, paste DMS/Plus Code, share my location, measure actions | done R3 |
| S24, S27-S33 | Favourites, last trip card, scale bar (pre-existing), open saved stop in maps, copy list, mojibake fix, backup/restore, share/copy trip | done R4 |
| S37 | Trip tab idle: "Nearest saved stop" quick-start row when within 2 km | done R5 |
| S34 | Pin menu: "1.2 km from you, NE" line and "Measure from here" | done R5 |
| S38 | Saved stop row: "Used 4 times, last 5 Nov" from history | done R5 |
| S36 | History: "Delete trips older than 30 / 90 days" (two taps, Undo) | done R5 |
| S39 | Settings > Alarm: "Reset alarm settings" (two taps, Undo) | done R5 |
| S40 | Show saved stops on the map (toggle in Map options, tap marker opens the stop) | backlog R6 |
| S35 | Saved stops: colour tag | backlog R6 |
| S19 | Reverse / Duplicate a saved stop | dropped (gate is direction-agnostic) |

## Round 5 plan

Steps 1-2 pure (unit-tested, `crates/rovsti_geo`), steps 3-6 thin GPUI, step 7 docs. No new Android
seam is needed. Every step leaves all checks green: `cargo fmt --all && cargo clippy --workspace
--all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target
aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo
check -p rovsti_tiles --target aarch64-linux-android`.

### Step 1. Pure: history usage and age pruning (S36, S38)
- `crates/rovsti_geo/src/history.rs`:
  - `pub const DAY_MS: i64 = 86_400_000; pub const PRUNE_CHOICES_DAYS: [u32; 2] = [30, 90];`
  - `impl History { pub fn older_than(&self, now_ms: i64, days: u32) -> usize }`: number of records
    with `ended_ms < now_ms - days * DAY_MS` (saturating; future records never count).
  - `pub fn remove_older_than(&mut self, now_ms: i64, days: u32) -> usize`: `retain`s the rest,
    keeps order, returns how many went.
  - `#[derive(Debug, Clone, Copy, PartialEq, Eq)] pub struct Usage { pub count: u32, pub last_ms:
    i64 }` and `pub fn usage_of(&self, a: Coord, b: Coord) -> Option<Usage>`: counts records whose
    `pair()` matches the gate in either direction within `RECENT_DEDUPE_M` per end (same tolerance
    the recent list uses); `last_ms` = max `ended_ms`; None when count is 0. Legacy records (no
    pair) never match.
  - `pub fn usage_text(u: Usage, now_ms: i64, tz_offset_s: i32) -> String`: "Used once, today",
    "Used 4 times, last yesterday", "Used 4 times, last 5 Nov" (year added when not the current
    year: "5 Nov 2025"). Use `rovsti_core::contact_dates::local_ymd` like `history_io` does, and
    reuse its month-name helper (make it `pub(crate)` if private).
  - `pub fn prune_label(n: usize, days: u32) -> String`: "Delete 12 trips older than 30 days" /
    "Delete 1 trip older than 90 days".
- Tests: `older_than` with 0 records, boundary exactly at the cutoff (not removed), future
  `ended_ms`; `remove_older_than` keeps order and returns the count; `usage_of` matches A/B and
  reversed B/A, ignores a gate 200 m away and legacy records, picks the newest `last_ms`;
  `usage_text` once / today / yesterday / same year / older year with +03:00 offset; `prune_label`
  singular/plural.

### Step 2. Pure: nearest saved stop, pin distance line, alarm defaults (S34, S37, S39)
- `crates/rovsti_geo/src/saved_stops.rs`: `pub const NEAREST_CHIP_M: f64 = 2_000.0;` and
  `impl SavedStops { pub fn nearest(&self, here: Coord, max_m: f64) -> Option<(&SavedStop, f64)> }`:
  smallest `here.distance_m(stop.center())`, only `<= max_m`, skips stops whose `diameter()` fails;
  ties go to the favourite, then to the earlier stop.
- `crates/rovsti_geo/src/coord_format.rs`: `pub fn from_you_text(here: Coord, to: Coord) ->
  String`: `"1.2 km from you, NE"` (distance via `session::format_distance`, direction via
  `cardinal(bearing)` using the same bearing function `trip_ui::bearing_text` uses); under 10 m ->
  `"You are here"`.
- `crates/rovsti_geo/src/prefs.rs`:
  - `impl StopsPrefs { pub fn alarm_is_default(&self) -> bool }`: `sound`, `vibrate`, `snooze_min`,
    `vibration`, `volume_pct`, `alarm_sound` all equal `StopsPrefs::default()`'s.
  - `pub fn with_alarm_defaults(&self) -> StopsPrefs`: a clone with exactly those six fields reset;
    appearance, tile server, cache size, notice untouched.
- Tests: `nearest` empty / out of range / picks closest / tie to favourite / skips an invalid
  gate; `from_you_text` for 1.2 km north-east, 5 m, 450 m south; `alarm_is_default` on default and
  after one change; `with_alarm_defaults` resets the six fields and keeps `appearance`,
  `tile_cache_mb`, `tile_url`, `notice`.

### Step 3. UI: Pin menu distance and Measure from here (S34)
- `apps/stops/src/map.rs::pin_menu`:
  - Footer: when `self.map.position` is Some, a second caption line under `details` with
    `coord_format::from_you_text(here, dest.coord)` (truncate).
  - New row "Measure from here" (id `pin-measure`) after "Open in maps": closes the menu, calls a
    new `MapState::measure_from(&mut self, c: Coord)` (`start_measure()` then push `c`; pure, unit
    test: one point, `measure_armed == false`), haptic tick as the ruler does, pill "Tap the map to
    measure from here". Hidden while a trip runs (the ruler is idle-only too).
  - Grow the menu by one row and, with a position, by one caption line: add
    `const PIN_MENU_CAPTION_H: f32 = 16.0;` and compute the height with a pure
    `pin_menu_height(is_stop: bool, has_here: bool) -> f32`; update `pin_menu_rows` (7 / 8) and the
    existing rect tests; add a test for `pin_menu_height`.

### Step 4. UI: Nearest saved stop on the idle Trip tab (S37)
- `trip_ui.rs::trip_tab` idle branch, between `setup_cards` and `last_trip_card`: when
  `self.map.position` is Some, no ends are picked (`self.pick.ends.is_empty()`) and
  `self.saved.nearest(here, NEAREST_CHIP_M)` is Some, a `section` with one `list_row`: title
  `"Nearest: {name}"`, subtitle `"{distance} away"` (`session::format_distance`), trailing
  "Start". Tap -> `self.request_start(stop.a.clone(), stop.b.clone(), cx)` (same path as Saved >
  Start; that already pills "Starting <name>" / shows errors). Busy -> no-op.
- Pure helper with test: `nearest_row_visible(has_here: bool, ends_empty: bool, trip: bool) ->
  bool`.

### Step 5. UI: Saved row usage and History age clean-up (S38, S36)
- `saved_ui.rs::stop_detail(stop, here)` gains `usage: Option<&str>`: appended as
  `"Gate 120 m, 1.2 km away · Used 4 times, last 5 Nov"`; the row computes it with
  `self.history.usage_of(stop.a.coord, stop.b.coord)` + `usage_text`. Update existing tests and add
  one with usage.
- Settings > History, list view (the `_ =>` branch near "Clear history"): above the clear pill, for
  each `days` in `PRUNE_CHOICES_DAYS` where `history.older_than(now, days) > 0`, a pill with
  `prune_label(n, days)` (red text). Two taps: new `StopsApp.confirm_prune: Option<u32>` (days);
  first tap arms ("Tap again to delete 12 trips"), second calls `prune_history(days, cx)`: keep
  `old = self.history.clone()`, `remove_older_than`, `save_history_in_background`, close
  `history_open`, pill `"Deleted 12 old trips"` with `PillAction::RestoreHistory(old)`. Reset
  `confirm_prune` wherever `confirm_clear` is reset (`main.rs` ~665, ~895, `settings_ui.rs` ~549).
- Pure helpers with tests: `prune_step(armed: Option<u32>, days: u32) -> (Option<u32>, bool)`
  (new armed state, whether to delete) and `prune_pill(n: usize) -> String`.

### Step 6. UI: Reset alarm settings (S39)
- Settings > Alarm section, last row "Reset alarm settings" (red), shown only when
  `!self.prefs.alarm_is_default()`. Two taps via `StopsApp.confirm_reset_alarm: bool` (label "Tap
  again to reset"). Second tap: snapshot `before = self.prefs.clone()`, `self.prefs = self.prefs.with_alarm_defaults()`, `save_prefs`,
  pill "Alarm settings reset" with new `PillAction::RestorePrefs(Box<StopsPrefs>)` whose handler
  assigns the prefs back and saves (no side effects needed: alarm fields drive nothing live).
  Reset `confirm_reset_alarm` with the other confirm flags.
- Pure helper with test: `reset_alarm_label(armed: bool) -> &'static str`.

### Step 7. Docs and backlog
- `docs/stops-utilities.md`: title "rounds 1-5", link `util-stops-r5.md`; rows S34, S36, S37, S38,
  S39 (what, where, how to reach, feedback, confirmation/Undo).
- This file: mark shipped rows "done R5"; keep S40 and S35 for R6.
- Acceptance: all checks green; every R5 utility reachable from the UI with a pill; destructive
  actions (prune, reset alarm) need two taps and offer Undo.
