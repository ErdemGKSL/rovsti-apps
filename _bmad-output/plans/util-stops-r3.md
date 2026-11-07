# util-stops: everyday utilities for Rovsti Stops (round 3)

baseSha: 290550b818cc1a25913d9feab9f9f6edd431245a

## Audit at baseSha

- R1 (`util-stops.md`) shipped S1-S9, R2 (`util-stops-r2.md`, commits 29aea3e..290550b) shipped S10-S16
  (see `docs/stops-utilities.md`). No stash entries; working tree clean.
- Disk had 2.3 GB free at planning time: run `rm -rf target/debug/incremental` before every build.
- Checked in code, still missing:
  - S17 follow-me: `MapState::follow` only means "auto-fit"; `locate_me` (`map.rs` ~2573) jumps once to
    `STREET_MPP` and sets `follow = false`. Location outside a trip is one-shot only
    (`StopsLocation.once`, `location::request_location`); no continuous updates.
  - S18 shortcut: `rovsti_android::shortcuts` only has `can_pin` / `pin_contact`
    (`Shortcuts.pinContact`, `BirthdayReminders.EXTRA_CONTACT_ID`, `RovstiBridge.deliverOpenContact`).
    Stops `MainActivity.kt` has no `onNewIntent`.
  - S19 Reverse / Duplicate: dropped. A gate rings when crossed in either direction and
    `SavedStops::add` already treats A/B and B/A as the same stop (`same_gate`), so neither has value.
  - S20: the live card (`trip_ui.rs::live_card`) has only Stop. `session.rs::format_distance` is private.
  - S21: Dismiss (`alarm_action` -> `trip_over`) shows no pill and offers no way back.
    `PillAction::RestartTrip(a, b)` exists (used by "Trip stopped").
  - S22: `DiskCache::open(.., DEFAULT_CAP_BYTES)` (128 MB, fixed) in `apps/stops/src/tiles.rs`; no
    `set_cap`; per-server dirs are `root.join(src.key())`; Settings > Map tiles shows total size + Clear.
  - S23: `dest::parse_location` falls back to `parse_pair` (plain decimals only); no DMS / DDM /
    Plus Code parsing (`coord_format::plus_code` encodes only).
  - S25: the blue dot has no menu; nothing shares the device position.
  - S26: the measure card (`map.rs::measure_card`) has Undo / Clear / Done only.
- Reusable: `coord_format::{format, CoordFormat, cardinal}`, `dest::{share_text, point_qr_payload}`,
  `picker.rs::{open_copy_as, open_qr}`, `places_io` XML escaping (`pub(crate)`), `backend::export_text`,
  `SavedStops::add` + `PillAction::UndoSave(u64)`, `saved_stops::suggest_name`, `request_start`,
  `PillAction::RestartTrip`, `settings_ui::{bytes_text, android_notice}`, `run_android`,
  `cx.write_to_clipboard(gpui::ClipboardItem::new_string(..))`, `postOneShot` mailbox events,
  `kotlin_contract.rs` string checks.

## Utility backlog (ordered by user value)

| # | Utility | Status |
|---|---------|--------|
| S1-S9 | Copy as, share stop, QR, open in maps, export/import stops, Saved search/sort, transit stop sheet, measure, bearing | done R1 |
| S10-S16 | Offline area, appearance, vibration pattern, alarm sound, volume, history search/filter/start again/save, history export | done R2 |
| S23 | Paste DMS / DDM / Plus Code (and `N 41 1.5 E 28 58.6`) in the picker | R3 step 1, 5 |
| S25 | Share my current location: tap the blue dot -> Copy as / Share / QR / Use as A / Use as B | R3 step 5 |
| S18 | Home screen shortcut for a saved stop that starts its trip | R3 steps 2, 4, 7 |
| S20 | Copy / share the live trip status ("Kadikoy - Moda: 1.2 km to the line, about 4 min, NE") | R3 steps 2, 6 |
| S21 | Re-arm after dismiss: pill "Alarm dismissed" with "Re-arm" (same A/B trip again) | R3 step 6 |
| S26 | Measure: Copy result, Share as GPX, Save first + last point as a stop | R3 steps 2, 5 |
| S17 | Follow-me: second tap on Me keeps the map centred on you (live fixes), pan or tap ends it | R3 steps 2, 4, 7 |
| S22 | Tile cache size choice (128 / 256 / 512 / 1024 MB) and "Other servers" usage with Clear | R3 steps 3, 6 |
| S19 | Reverse / Duplicate a saved stop | dropped (gate is direction-agnostic) |
| S24 | Saved stops: pin favourites to top, colour tag | backlog R4 |
| S27 | Trip summary after arrival (time, distance, snoozes) with Share and Save as stop | backlog R4 |
| S28 | Map scale bar (metric, updates with zoom) | backlog R4 |
| S29 | Saved stop: Open in maps and distance from me in the row | backlog R4 |
| S30 | Saved stops: copy all as a plain text list | backlog R4 |

## Round 3 plan

Steps 1-3 pure (unit-tested), step 4 Android seam, steps 5-7 thin GPUI, step 8 docs. Every step leaves all
checks green: `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test
--workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p
rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target
aarch64-linux-android`.

### Step 1. Pure: coordinate parsing (S23)
- `crates/rovsti_geo/src/coord_format.rs`:
  - `pub fn plus_code_decode(code: &str) -> Option<Coord>`: full codes only (8 digits + `+` + 0..=3
    more, padding `0` allowed before `+`, case-insensitive), returns the centre of the code area;
    short codes (no 8 leading digits) -> None.
  - `pub fn parse_coord(text: &str) -> Option<Coord>`: trims; accepts DMS (`41°01'32.2"N 28°58'36.0"E`,
    also `'`/`′`, `"`/`″`/`''`), DDM (`41° 1.537' N, 28° 58.600' E`), hemisphere-prefixed
    (`N 41.0256 E 28.9767`, `S 33 52.1 E 151 12.5`), signed degrees with `°`, and a full Plus Code.
    Rules: minutes/seconds < 60, lat <= 90, lon <= 180, hemisphere letters N/S/E/W (lat first or
    detected by letter), comma or whitespace separator. Plain `lat, lon` stays in `dest::parse_pair`.
- `crates/rovsti_geo/src/dest.rs::parse_location`: after the existing `parse_pair` line loop, try
  `coord_format::parse_coord` per line (label None); an out-of-range result -> `DestError` as today.
- Tests: round-trip `format(c, Dms|Ddm|PlusCode)` -> `parse_coord` within 1 m for Istanbul, Sydney (S/E),
  Lima (S/W), Reykjavik (N/W); the spec example code; `S 33 52.1 E 151 12.5`; rejects `61'`, `91°N`,
  short Plus Code `9G8F+6X`, empty, plain words; `parse_location("41°01'32.2\"N 28°58'36.0\"E")` is Ok;
  existing dest tests unchanged.

### Step 2. Pure: status text, measure export, stop shortcut spec, follow mode (S17, S18, S20, S26)
- `crates/rovsti_geo/src/session.rs`: make `format_distance` `pub` (re-export as
  `rovsti_geo::format_distance`) and add `pub fn status_share_text(label: &str, line_m: Option<f64>,
  eta_min: Option<u32>, here: Option<Coord>, target: Coord, crossed: bool) -> String`:
  `"On my way to Kadikoy - Moda: 1.2 km to the line, about 4 min, heading NE."`, crossed ->
  `"Arrived at Kadikoy - Moda."`, missing parts omitted (`"On my way to X."` + geo link line from
  `dest::point_qr_payload(target, Some(label))` always appended on a second line).
- `crates/rovsti_geo/src/measure.rs`: `pub fn summary_text(&self) -> String`
  (`"1.4 km, 3 legs"`, "1 leg", empty -> ""); `pub fn to_gpx(&self, name: &str) -> String` (one `<trk>`
  with `<trkseg>` points, name escaped with the places_io helper); `pub fn as_gate(&self) ->
  Option<(Coord, Coord)>` (first and last point when `Diameter::new` accepts them); `pub fn
  export_file_name(today: &str) -> String` -> `rovsti-stops-measure-2026-11-07.gpx`.
- `crates/rovsti_geo/src/saved_stops.rs`: `#[derive(Serialize)] pub struct StopShortcut { id: String,
  short_label: String, long_label: String, stop_id: u64 }` and `pub fn shortcut_spec(stop: &SavedStop)
  -> StopShortcut` (id `stop-<id>`, short <= 10 chars on a char boundary with an ellipsis-free cut,
  long <= 25 chars, empty name -> "Stop").
- `crates/rovsti_geo/src/view.rs`: `pub enum FollowMode { Off, Centred, Following }` with
  `pub fn on_me_tap(self, has_fix: bool) -> FollowMode` (Off -> Centred; Centred -> Following;
  Following -> Off; no fix -> Centred) and `pub fn on_pan(self) -> FollowMode` (always Off),
  `pub fn watches(self) -> bool` (Following only).
- Tests: status text for tracking / crossed / no fix / no eta; `format_distance` edges; GPX has one `<trkpt` per
  point and escapes `a & <b>` (`places_io::parse_import` reads routes/waypoints, not tracks); `as_gate` None for 1 point and for a too-short pair; `shortcut_spec`
  truncation on a multibyte name and JSON field names; `FollowMode` transitions table.

### Step 3. Pure: tile cache cap and per-server usage (S22)
- `crates/rovsti_tiles/src/cache.rs`: `pub fn set_cap(&mut self, cap: u64)` (stores, evicts, marks
  dirty); `pub fn usage_by_source(root: &Path) -> Vec<(String, u64)>` (one entry per subdirectory, key =
  dir name, sorted by bytes desc); `pub fn clear_except(root: &Path, keep_key: &str) -> io::Result<u64>`
  (removes every other server dir, returns bytes freed). Re-export in `lib.rs`.
- `crates/rovsti_geo/src/prefs.rs`: `pub const TILE_CACHE_CHOICES_MB: [u32; 4] = [128, 256, 512, 1024]`,
  `StopsPrefs.tile_cache_mb: u32` (`#[serde(default = ..)]` 128), `pub fn next_tile_cache(&self) -> u32`
  (off-list values snap to 128), `pub fn tile_cache_bytes(&self) -> u64`.
- Tests (tempdir under `std::env::temp_dir()` like the existing cache tests): `set_cap` lower evicts LRU
  files; `usage_by_source` with two server dirs; `clear_except` keeps the current dir and returns the
  freed size; missing root -> empty / Ok(0); prefs default 128, old JSON loads, cycle, snap.

### Step 4. Android seam: stop shortcut and live location watch (S17, S18)
- Kotlin (`crates/rovsti_android/kotlin/dev/rovsti/bridge/`):
  - `Shortcuts.kt`: `const val EXTRA_STOP_ID = "dev.rovsti.stops.STOP_ID"`, `fun pinStop(ctx, specJson)`
    (same flow as `pinContact`, app icon, extra `stop_id` as Long).
  - `RovstiBridge.kt`: `@JvmStatic fun pinStopShortcut(json: String): Boolean`,
    `fun deliverOpenStop(intent: Intent?): Boolean` (posts one-shot `open_stop` `{id}`, removes the
    extra), `@JvmStatic fun watchLocation(on: Boolean): Boolean`.
  - `StopsLocation.kt`: `fun watch(ctx, on: Boolean): Boolean` (`requestLocationUpdates` on the first
    enabled provider, 2 s / 5 m, main looper, emits `stops_location` like `once`; `on = false` removes
    the listener; idempotent; false without permission).
  - `apps/stops/platforms/android/app/src/main/kotlin/dev/rovsti/stops/MainActivity.kt`: call
    `RovstiBridge.deliverOpenStop(intent)` in `onCreate` and a new `onNewIntent`; `onPause` calls
    `RovstiBridge.watchLocation(false)` (the app re-arms on resume if following).
- Rust: `crates/rovsti_android/src/shortcuts.rs::pin_stop(spec_json: &str) -> Result<bool>`;
  `crates/rovsti_android/src/location.rs::watch_location(on: bool) -> Result<bool>`;
  `events.rs`: `kind::OPEN_STOP = "open_stop"`, `pub fn parse_open_stop(json) -> Option<u64>`.
  Desktop tests: `Error::Unsupported`; parser tests (ok, extra keys, negative / missing / bad JSON).
- `apps/stops/src/backend.rs`: `pin_stop_shortcut(stop: &SavedStop) -> Result<bool, String>` (JSON of
  `shortcut_spec`), `can_pin_shortcut() -> bool`.
- `apps/stops/src/kotlin_contract.rs`: bridge has `pinStopShortcut`, `watchLocation`,
  `deliverOpenStop`; `Shortcuts.kt` has `EXTRA_STOP_ID`; Stops `MainActivity.kt` calls
  `deliverOpenStop` twice and `watchLocation(false)`; `StopsLocation.kt` has `requestLocationUpdates`
  and `removeUpdates`.

### Step 5. UI: picker paste formats, my location sheet, measure actions (S23, S25, S26)
- Picker: paste field placeholder/notice mentions "link, coordinates or Plus Code"; no code change for
  parsing (step 1). Add a `picker.rs` test that `offer` accepts a DMS string and a Plus Code.
- My location (S25): in `map.rs` a tap within 24 px of the blue dot (idle map, not measuring) opens
  `SheetMode::MyLocation(Coord, f64)` (new) instead of placing a pin: header "My location", subtitle
  "Accurate to 15 m"; rows Copy as (`open_copy_as`), Share (`dest::share_text("My location", c)` via the
  existing share path), QR code (`open_qr`), Use as A, Use as B (`pick.set_end`). Pure helper
  `hits_position(tap, dot, radius) -> bool` with a test. Pills reuse the existing texts.
- Measure card (S26): a "More" action opens `SheetMode::MeasureActions` with Copy result
  (clipboard `summary_text()` + legs, pill "Measurement copied"), Share as GPX (`backend::export_text`
  with `application/gpx+xml` and `measure::export_file_name`, pill "Measurement exported"),
  Save as stop (only when `as_gate()` is Some; `SavedStops::add` with `suggest_name`, pill
  "Saved as <name>" + `PillAction::UndoSave(id)`, duplicate -> "Already saved as <name>"; error ->
  `SavedStopError::message`). "More" hidden below 2 points.
- Tests: `hits_position`, measure sheet row visibility helper `measure_actions(&Measure) -> Vec<&str>`.

### Step 6. UI: trip status share, re-arm, tile cache settings (S20, S21, S22)
- Live card: two pills under the details, "Copy status" (clipboard, pill "Status copied") and
  "Share status" (system share of `status_share_text`; desktop -> `android_notice` text).
- Re-arm: in `alarm_action` / `on_alarm_event` Dismissed paths, capture the trip ends before
  `trip_over` (as `stop_trip` does) and pill "Alarm dismissed" with `PillAction::RestartTrip(a, b)`;
  the pill action label for `RestartTrip` from a dismissal reads "Re-arm" (add a label field or a new
  variant `PillAction::Rearm(Destination, Destination)` that calls the same `request_start`).
  Pure helper `dismiss_pill(ends: Option<..>) -> (&str, bool)` with a test.
- Settings > Map tiles: row "Cache size" cycling `next_tile_cache` ("256 MB", subtitle
  "Offline areas stay while they fit"), applied live through `MapTiles::set_cap(bytes)` (calls
  `DiskCache::set_cap` on the background cache) and on open (`DiskCache::open(.., prefs.tile_cache_bytes())`);
  lowering below current usage arms a confirm ("Tap again to shrink to 128 MB") then pills "Cache limit
  128 MB". Row "Other servers" with `bytes_text` from `usage_by_source` minus the current key, shown only
  when > 0, two-tap "Clear" -> `clear_except`, pill "Freed 12 MB".
- Tests: `cache_size_text`, shrink-confirm predicate `needs_shrink_confirm(usage, new_cap)`,
  `other_servers_bytes(&[(key, bytes)], current)`.

### Step 7. UI: Add to home screen and follow-me (S18, S17)
- Saved stop open row: pill "Home screen" (only when `backend::can_pin_shortcut()` on Android; on
  desktop shown and answers with `android_notice`); success pill "Added to the home screen", false ->
  "The launcher did not add it".
- `main.rs::on_event`: `kind::OPEN_STOP` -> `parse_open_stop` -> stop found: same rule as
  `start_saved_stop` (trip running -> pill "Not while a trip is running", else `request_start` and jump
  to the Trip tab, pill "Starting <name>"); stop gone -> pill "That stop was deleted".
- Follow-me: `MapState.follow_mode: FollowMode`; Me button calls `on_me_tap`: Centred = today's
  `locate_me`; Following -> `location::watch_location(true)` via `run_android`, button tinted accent,
  pill "Following you"; Off -> `watch_location(false)`, pill "Stopped following". While Following,
  `set_device_fix` recentres keeping the current mpp. A pan/drag/pinch (`take_over`) calls `on_pan`
  and stops the watch (pill "Stopped following"); trip start, tab change and app background stop it too;
  on resume re-arm if still Following.
- Tests: `map.rs` (a fix while Following moves the centre and keeps the zoom; pan ends following;
  Centred does not move on later fixes), `open_stop` handling helper `open_stop_outcome(saved, id,
  trip_running) -> OpenStop` enum.

### Step 8. Docs and backlog
- `docs/stops-utilities.md`: rounds 1-3 in the title; add S17, S18, S20-S23, S25, S26 rows (what, where,
  how to reach, feedback) and a short "Shortcuts and follow-me" section (`open_stop` one-shot,
  `watchLocation` lifecycle, battery: watch stops on pause and on pan).
- This file: mark shipped rows "done R3"; keep R4 items in order.
- Acceptance: all checks green; every R3 utility reachable from the UI with a pill; destructive or heavy
  actions confirmed (cache shrink and Other servers Clear two-tap, Save as stop has Undo, Re-arm is an
  explicit pill tap).
