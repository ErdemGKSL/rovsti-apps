# util-stops: everyday utilities for Rovsti Stops (round 2)

baseSha: 10f349dcac419c32b66ae7e95a7dc0759752b031

## Audit at baseSha

- Round 1 (`util-stops.md`, commits 0d901ab..10f349d) shipped S1-S9 (see `docs/stops-utilities.md`).
  No stash entries; working tree clean.
- Disk had 2.3 GB free at planning time: run `rm -rf target/debug/incremental` before every build.
- Still missing (checked in code):
  - Alarm: only Sound / Vibration on-off, Snooze length and "Test alarm" (`settings_ui.rs::settings_main`).
    `AlarmPlan` (`rovsti_geo::alarm`) is always `AlarmPlan::default()` (`backend::start_spec`), so
    vibration pattern, volume and sound are fixed. Kotlin `AlarmSound.uriFor("user_alarm")` uses the
    system default alarm. The test ring (`TripAlarm.ring(test = true)`) reads the *last trip's* plan from
    `TripPrefs` and the sound/vibrate flags from `stops-prefs.json` in that trip's dir, so before the
    first trip it ignores the user's settings.
  - Theme: Stops follows the system only; `rovsti_ui::set_theme_override` exists (Phone uses it) and the
    map palette already follows `Theme` (`map.rs::Palette::new`).
  - Tiles: `MapTiles::prefetch` only for the trip circle; `FetchQueue` cannot drop prefetch work;
    `tiles_for_circle` is the only area helper; the Map tiles page shows size + two-tap Clear.
  - History page: Use / Delete per record, Clear all; no search, filter, start-again, save-as-stop or
    export.
- Reusable: `places_io::export_file_name`, `saved_ui::export_stamp`, `backend::{export_text, write_temp}`,
  `transit::date_text`/`civil_from_days` (app-local), `gtfs::fold`, `pick::dest_from_endpoint`,
  `request_start`, `start_saved_stop` rule ("Not while a trip is running"),
  `ContactOptions.kt` ringtone picker (TYPE_RINGTONE, request code `Documents.REQUEST_RINGTONE`,
  one-shot `postOneShot`), `rovsti_android::events::kind`.
- The document_result op `ExportMessages` always says "Saved stops exported": history export needs the
  app to remember what it exported.

## Utility backlog (ordered by user value)

| # | Utility | Status |
|---|---------|--------|
| S1-S9 | Copy as, share stop, QR, open in maps, export/import stops, Saved search/sort, transit stop sheet, measure, bearing | done R1 |
| S10 | Download this map area for offline use: estimate, progress, cancel, zoom cap | R2 (steps 2, 6) |
| S11 | Appearance quick toggle Auto / Light / Dark (map follows), persisted | R2 (steps 1, 6) |
| S12 | Vibration pattern choice (Standard / Pulse / Long / Heartbeat) with a preview buzz | R2 (steps 1, 4, 5) |
| S13 | Alarm sound picker (system alarm picker) stored as uri + title, used as `user_alarm` | R2 (steps 1, 4, 5) |
| S14 | Alarm volume (80 / 90 / 100 %) with a short "Test volume" | R2 (steps 1, 4, 5) |
| S15 | History: search, outcome filter, Start again, Save as stop | R2 (steps 3, 7) |
| S16 | Export history as CSV / GPX | R2 (steps 3, 7) |
| S17 | Follow-me mode on the map (cycling Me button) | backlog R3 |
| S18 | Home screen shortcut for a saved stop that starts its trip | backlog R3 |
| S19 | Reverse a saved stop's direction and Duplicate a saved stop | backlog R3 |
| S20 | Copy / share the live trip status text | backlog R3 |
| S21 | Re-arm after dismiss ("Ring again in N min") | backlog R3 |
| S22 | Tile cache size cap choice (100 / 250 / 500 MB) and per-server usage | backlog R3 |
| S23 | Coordinates entry: paste DMS / DDM / Plus Code in the picker | backlog R3 |
| S25 | Share my current location (Me long-press or pin menu on the blue dot): Copy as / Share / QR | backlog R3 |
| S26 | Measure tool: copy the result, share the polyline as GPX, save two points as a stop | backlog R3 |
| S27 | Trip summary after arrival (time, distance, snoozes) with Share and Save as stop | backlog R4 |
| S24 | Saved stops: pin favourites to top, colour tag | backlog R4 |

## Round 2 plan (S10-S16)

Steps 1-3 pure (unit-tested), step 4 Android seam, steps 5-7 thin GPUI, step 8 docs. Every step leaves all
checks green: `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test
--workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p
rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target
aarch64-linux-android`.

### Step 1. Pure: alarm customisation and appearance prefs (S11-S14)
- `crates/rovsti_geo/src/alarm.rs`:
  - `pub enum VibrationPattern { Standard, Pulse, Long, Heartbeat }` (serde snake_case, Default
    Standard) with `ALL`, `label()`, `next()`, `pattern_ms() -> &'static [u64]` (Standard ==
    `VIBRATION_MS`; Pulse `[0,250,250,250,250,250,1000]`; Long `[0,1500,700]`; Heartbeat
    `[0,120,120,300,900]`) and `preview_ms() -> Vec<u64>` (one cycle, trailing pause dropped).
  - `pub const VOLUME_CHOICES: [u8; 3] = [80, 90, 100]` and `pub fn next_volume(pct: u8) -> u8`
    (values below 80 snap to 80).
  - `AlarmPlan` gains `#[serde(default, skip_serializing_if = "Option::is_none")] pub user_sound_uri:
    Option<String>`; default `None` (default JSON unchanged, so the Kotlin contract test stays green).
- `crates/rovsti_geo/src/prefs.rs`:
  - `pub enum Appearance { Auto, Light, Dark }` (Default Auto) with `ALL`, `label()`, `next()`.
  - `pub struct AlarmSound { pub uri: String, pub title: String }`.
  - `StopsPrefs` new fields (all `#[serde(default)]`): `appearance: Appearance`,
    `vibration: VibrationPattern`, `volume_pct: u8` (default 80), `alarm_sound: Option<AlarmSound>`.
  - `pub fn alarm_plan(&self) -> AlarmPlan` (pattern, volume clamped 80..=100, `user_sound_uri` from
    `alarm_sound`) and `pub fn test_ring_json(&self) -> String` = the plan JSON plus `"sound"` and
    `"vibrate"` booleans (what Kotlin's `alarmChannels` reads).
  - `pub fn alarm_sound_label(&self) -> String` ("Default alarm" or the title).
- Tests: default prefs -> `alarm_plan() == AlarmPlan::default()`; an old prefs JSON without the new keys
  loads with defaults; every pattern has len >= 2, starts with 0, has a non-zero on segment and a cycle
  of at least 1 s; `preview_ms` total under 3 s; `next()` cycles all of ALL; volume 0 / 79 / 255 clamp;
  `test_ring_json` has `sound`, `vibrate`, `vibrate_pattern_ms`, `user_sound_uri` only when set;
  Appearance cycle and serde names.

### Step 2. Pure: offline map area (S10)
- `crates/rovsti_geo/src/tiles/slippy.rs`: `pub struct AreaPlan { pub tiles: Vec<TileId>,
  pub top_zoom: u8, pub capped: bool }` and `pub fn tiles_for_area(corners: &[Coord], min_zoom: u8,
  max_zoom: u8, budget: usize) -> AreaPlan` (reuses `Span::around(.., 0)` / `tiles_if`; coarse first,
  nearest the centre first; stops at the highest zoom whose cumulative count fits `budget`; `capped` when
  it stopped below `max_zoom.min(MAX_TILE_ZOOM)`). Constants `AREA_MIN_ZOOM = 8`,
  `AREA_TILE_BUDGET = 2500`, `AVG_TILE_BYTES = 30_000` and `pub fn estimate_bytes(tiles: usize) -> u64`.
- New `crates/rovsti_geo/src/tiles/offline.rs` (`pub mod offline;`): `pub struct AreaDownload { total,
  done, failed, pending: BTreeSet<TileId>, cancelled: bool }` with `new(ids)`, `record(id, ok) -> bool`
  (true when the id belonged to it), `cancel()`, `is_finished()`, `fraction() -> f32`,
  `progress_text()` ("Downloading 120 of 900 tiles"), `finished_text()` ("Map area ready offline. 897
  tiles, 3 failed." / "Download cancelled. 120 of 900 tiles kept.").
- `queue.rs`: `pub fn drop_prefetch(&mut self) -> usize` (removes queued Prefetch items, in-flight ones
  finish).
- Tests: a city-sized box reaches zoom 14 within the budget, a country-sized box is capped below 14 and
  never exceeds the budget, an area across the antimeridian, a degenerate (single point) area returns tiles,
  no duplicates; `AreaDownload` counting, foreign ids ignored, double record ignored, cancel texts;
  `drop_prefetch` keeps View items and returns the count.

### Step 3. Pure: history search, filter, export (S15, S16)
- `crates/rovsti_geo/src/history.rs`: `pub enum OutcomeFilter { All, Arrived, Cancelled }` with
  `label()`, `next()`; `pub fn matching(&self, query: &str, filter: OutcomeFilter) -> Vec<usize>`
  (indices into `records`, newest first; query folded with `gtfs::fold`, matches the label and the A/B
  labels); `TripRecord::pair(&self) -> Option<(Endpoint, Endpoint)>`.
- New `crates/rovsti_geo/src/history_io.rs` (`pub mod history_io;`):
  - `pub fn to_csv(records: &[TripRecord], tz_offset_s: i32) -> String`: header
    `started,ended,duration_min,outcome,label,a_lat,a_lon,b_lat,b_lon,travelled_m,snoozes,notice_sent`,
    ISO local time `2026-11-07 08:15`, RFC 4180 quoting (comma, quote, newline), CRLF line ends, empty
    cells for legacy records without A/B (use `destination` as B when present).
  - `pub fn to_gpx(records: &[TripRecord]) -> String`: one `<rte>` per record with A/B (reuse the
    places_io XML escaping, make it `pub(crate)`), `<desc>` with outcome and date; records without
    points are skipped.
  - `pub fn export_file_name(ext: &str, today: &str) -> String` ->
    `rovsti-stops-history-2026-11-07.csv`.
  - A small private `civil_from_days` (Howard Hinnant) for the ISO date.
- Tests: CSV header + one row exact text, quoting of `a,"b"` labels, timezone shift across midnight,
  legacy record; GPX parses back through `places_io::parse_import` into the same pairs; `matching`
  with filter, accent-insensitive query, empty query = all.

### Step 4. Android seam: alarm sound picker, previews, test ring with prefs (S12-S14)
- Kotlin (`crates/rovsti_android/kotlin/dev/rovsti/bridge/`):
  - `AlarmSound.kt`: `AlarmPlan` parses `user_sound_uri`; `uriFor("user_alarm")` returns it when set,
    else `getActualDefaultRingtoneUri(TYPE_ALARM)` (a missing file falls through the order as today).
    New `fun preview(ms: Long)` path: start sound, stop + `restoreVolume` after `ms` (main handler).
  - New `AlarmOptions.kt`: `pickAlarmSound(activity, current: String?)` (`ACTION_RINGTONE_PICKER`,
    TYPE_ALARM, show default, no silent, request code `Documents.REQUEST_ALARM_SOUND` new constant),
    `pickedJson(...)` -> `{uri, title, cancelled}` (title via `RingtoneManager.getRingtone(..).getTitle`,
    default uri -> null), `handle(...)` posting the one-shot `alarm_sound_picked`;
    `previewVibration(context, pattern: LongArray)` (one-shot waveform, repeat -1, alarm attributes).
  - `TripAlarm.ring(context, test, planOverride: String? = null)`: test ring parses `planOverride` for the
    plan and `alarmChannels(planOverride)` for sound/vibrate when given.
  - `RovstiBridge.kt`: `tripTestAlarm(json)`, `pickAlarmSound(current, hasCurrent)`,
    `previewVibration(csv)`, `previewAlarmSound(planJson, ms)`; add `AlarmOptions.handle` to the
    `onActivityResult` chain next to `ContactOptions.handle`.
- Rust `crates/rovsti_android/src/trip.rs`: `pub fn test_alarm(spec_json: &str) -> Result<bool>`,
  `pub fn pick_alarm_sound(current: Option<&str>) -> Result<bool>`,
  `pub fn preview_vibration(pattern_ms: &[u64]) -> Result<bool>` (comma-separated string arg),
  `pub fn preview_alarm_sound(plan_json: &str, ms: u32) -> Result<bool>` (string arg);
  `pub fn parse_alarm_sound_picked(json) -> Option<Option<(String, String)>>` (None = malformed,
  Some(None) = cancelled/default). `events.rs`: `kind::ALARM_SOUND_PICKED = "alarm_sound_picked"`.
  Desktop tests: each returns `Error::Unsupported`; parser tests.
- `apps/stops/src/backend.rs`: `start_spec(dir, label, plan: &AlarmPlan)`; callers pass
  `self.prefs.alarm_plan()` (update `start_trip` signature / its callers and tests).
- `apps/stops/src/kotlin_contract.rs`: tests that `AlarmSound.kt` reads `user_sound_uri`, that the bridge
  has the four new `@JvmStatic` functions, `AlarmOptions.kt` uses `TYPE_ALARM` and posts
  `alarm_sound_picked`, and `RovstiBridge` chains `AlarmOptions.handle`.

### Step 5. UI: alarm settings (S12-S14)
- `settings_ui.rs::settings_main`, Alarm section:
  - "Alarm sound" row, value `prefs.alarm_sound_label()`; tap -> `trip::pick_alarm_sound` via
    `run_android`. `main.rs::on_event` handles `kind::ALARM_SOUND_PICKED`: store, `save_prefs`, pill
    "Alarm sound: <title>" (default -> "Alarm sound: Default alarm"), with `PillAction::RestoreAlarmSound(
    Option<AlarmSound>)` (new variant) as Undo; cancelled -> nothing.
  - "Volume" row cycling `next_volume` ("80 %"), subtitle "Never quieter than this."; a "Test volume"
    row -> `trip::preview_alarm_sound(plan, 3000)`, pill "Playing for 3 seconds" (desktop -> the
    existing `android_notice` text).
  - "Vibration pattern" row cycling `VibrationPattern::next`, each change buzzes
    `trip::preview_vibration(&p.preview_ms())` and a haptic tick; hidden while vibration is off.
  - "Test alarm" now calls `trip::test_alarm(&self.prefs.test_ring_json())`.
- Pure helpers with tests: `volume_text(pct)`, `alarm_rows_visible(prefs) -> (bool pattern_row)`.

### Step 6. UI: map options sheet: appearance and offline area (S10, S11)
- Appearance: on prefs load and on change call `rovsti_ui::set_theme_override` (map `Appearance` to
  `Option<Theme>` in an app helper `theme_override(Appearance)` with a test) and `window.refresh()` as
  Phone does (`apps/phone/src/main.rs` ~756; grep it). Settings row "Appearance" cycling Auto/Light/Dark.
- Map: a fifth control button "Map" (idle map only, under Ruler) opens `SheetMode::MapOptions` (new):
  a segmented Auto / Light / Dark row (pill "Appearance: Dark") and "Download this area" with the
  estimate subtitle from `tiles_for_area(corners of self.map.viewport/self.map.size(), AREA_MIN_ZOOM,
  MAX_TILE_ZOOM, AREA_TILE_BUDGET)`: "900 tiles, about 27 MB, up to zoom 14" (`capped` adds "Zoom in
  for street detail"). Tap -> confirm row "Download 900 tiles" (second tap) then start.
- `apps/stops/src/tiles.rs`: `MapTiles { area: Option<AreaDownload> }`, `download_area(ids, cx)` (queue
  as Prefetch), `cancel_area(cx)` (`queue.drop_prefetch`, `area.cancel`), `area()` accessor; `finish`
  calls `area.record(id, ok)` and, when finished, stores the text for the app to pill (callback via
  `StopsApp::on_area_finished`). Refused while the tile cache is not open (pill "Map tiles are still
  loading").
- Progress: the sheet and the Map tiles settings page show `progress_text()` with a Cancel pill while a
  download runs; finishing pills `finished_text()` and refreshes the size (`measure_tiles`).
- Settings > Map tiles note text mentions "Download areas from the map's Map button".
- Tests: `theme_override` mapping, estimate subtitle text helper, `MapTiles` area record unit test.

### Step 7. UI: history utilities and export (S15, S16)
- `settings_ui.rs::history_page`: a search field (more than 5 records) and a filter pill cycling
  All / Arrived / Cancelled above "Trips"; rows come from `History::matching`; "No trips match".
  State on `StopsApp`: `history_query`, `history_filter` (not persisted); `history_open` holds a
  record index from `matching`, reset when the query/filter changes.
- Open record pills: "Start again" (same rule as `start_saved_stop`: pill "Not while a trip is
  running"; else `request_start` and jump to the Trip tab), "Save as stop" (`SavedStops::add` with
  `saved_stops::suggest_name` or the label; Duplicate -> pill "Already saved as <name>"; success pill
  "Saved as <name>" with `PillAction::UndoSave(u64)` new variant), existing Use / Delete.
- Header pills "Export CSV" / "Export GPX" (`backend::export_text`, mime `text/csv` /
  `application/gpx+xml`, name from `history_io::export_file_name`). Add
  `pending_export: Option<ExportKind { SavedStops, History }>` on `StopsApp` so the
  `ExportMessages` result pills "Saved stops exported" or "History exported".
- Pure helpers with tests: `history_field_visible(count)`, `export_pill(kind, ok, error)`.

### Step 8. Docs and backlog
- `docs/stops-utilities.md`: add S10-S16 (what, where, how to reach, feedback) and a short "Alarm sound
  and vibration" section (what reaches Kotlin, test ring now uses the current settings).
- This file: mark shipped rows "done R2"; keep R3 items in order.
- Acceptance: all checks green; every R2 utility reachable from the UI with a pill; destructive or
  heavy actions confirmed (area download two-tap, Clear kept, Save as stop has Undo).
