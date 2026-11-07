# util-stops: everyday utilities for Rovsti Stops (round 1)

baseSha: ae71621ab0a127390d9c52267016166e83a68cf5

## Audit (what already exists at baseSha)

Earlier Stops plans (`stops*.md`, `stops-alarm*`, `stops-crossing*`, `stops-map*`, `stops-pick*`,
`ux-stops*.md`, `util-stops-pair*.md`) are done. No `util-stops*.md` existed before this one; no stash
entries; working tree clean. Disk had 2.7 GB free at planning time: run `rm -rf target/debug/incremental`
before building.

Already shipped, so not planned again:
- Pin menu on the map (`map.rs::pin_menu`): Share (text with an OSM link via `rovsti_geo::share_text`,
  clipboard fallback through `saved_ui::share_outcome`), Move, Remove (pill with Undo), Lines (GTFS stop).
- Map controls: Me (locate, permission flow), Fit (follow = true), +/-; scale bar; breadcrumb trail.
- Paste a shared location in the picker (`dest::parse_location`: geo:, OSM, Google, `lat, lon`).
- Saved tab (`saved_ui.rs`): Rename, Up/Down, Show on map, Start, two-tap Delete with Undo.
  `SavedStops::search` exists but is only used by the picker; the Saved tab has no search, no sort,
  no share, no export/import.
- Trip tab: live card with side, distance to the line, ETA (`eta_text`), snooze with count, alarm screen.
- Settings: sound / vibration toggles (last one cannot be turned off), snooze length, Test alarm,
  battery / autostart / exact alarms, History page (stats, per-record Use / Delete with Undo, two-tap
  Clear with Undo), Map tiles page (Test and save, Reset, Paste, Downloaded maps size + two-tap clear),
  Arriving soon (WhatsApp, through `rovsti_wa_client`).
- Tiles: `MapTiles::prefetch` is used only for the trip circle at trip start; no user prefetch.

Reusable pieces (do not re-create):
- `rovsti_core::location_link::{geo_url, osm_url, coords_text, is_valid}` (rovsti_geo already depends on
  rovsti_core).
- `rovsti_wa_ui::qr_png(payload, min_px)` (Stops already depends on `rovsti_wa_ui`); pattern in
  `apps/contacts/src/qr_sheet.rs` and `apps/contacts/src/backend.rs::{save_png, export_ics}`
  (write to cache, then `media::save_image_to_gallery` / `media::share_image` / `documents::export_file`).
- `rovsti_android::{telecom::{share_text, copy_text}, media::{save_image_to_gallery, share_image,
  share_file}, documents::{export_file, import_file}}`: all exist with desktop `Error::Unsupported`.
  `export_file` answers `document_result` op `export_messages`, `import_file` op `import_messages`
  (Contacts reuses `export_file` the same way for `.ics`). Stops handles only `ImportGtfs` today
  (`main.rs` ~line 472), so these ops are free for Stops. No new Kotlin is needed for round 1.
- Stops' manifest has **no** `dev.rovsti.bridge.RovstiShareProvider` (Phone's manifest lines 119-123
  have it); `media::share_image` / `share_file` need it.
- `rovsti_geo::gtfs::fold` for search, `SavedStops::{search, find_gate, restore}`, `rovsti_ui::haptics`,
  `rovsti_ui::set_theme_override` (app-wide; Phone uses it).
- GPUI: use only APIs already used in `apps/stops` (sheets via `SheetMode`, `glass_pill`, `list_row`,
  `cx.write_to_clipboard`, `cx.read_from_clipboard`, `cx.open_url`) or `apps/contacts/src/qr_sheet.rs`
  (`gpui::Image::from_bytes(ImageFormat::Png, ..)`). Grep the pinned checkout before anything new.

## Utility backlog (ordered by user value)

| # | Utility | Status |
|---|---------|--------|
| S1 | Copy coordinates as: decimal, DMS, degrees + decimal minutes, Plus Code, geo: URI, OSM link (pin menu, saved stop, transit stop) | R1 |
| S2 | Share a saved stop (both gate points) as text; pasting that text in the picker fills A and B together | R1 |
| S3 | QR code of a point / saved stop (geo: URI or stop share text) with Save to gallery and Share image | R1 |
| S4 | Open a point in another maps app (`geo:` via `cx.open_url`) | R1 |
| S5 | Export saved stops as GPX and JSON; import GPX / JSON with a merge summary, duplicates skipped, Undo | R1 |
| S6 | Search field and sort (saved order / name / nearest) on the Saved tab | R1 |
| S7 | Transit stop sheet: Copy name, Copy stop code, Share stop, distance from here | R1 |
| S8 | Measure distance on the map: tap points, total + per-leg distance, Undo last, Clear | R1 |
| S9 | Bearing to the gate on the live card ("NE, 1.2 km") with an arrow | R1 |
| S10 | Download this area for offline use: tile count estimate, progress, cancel, zoom range cap | backlog R2 |
| S11 | Appearance quick toggle Auto / Light / Dark (map style follows), persisted in `StopsPrefs` | backlog R2 |
| S12 | Vibration pattern choice (Standard / Pulse / Long / Heartbeat) feeding `AlarmPlan.vibrate_pattern_ms`, with a preview buzz | backlog R2 |
| S13 | Alarm sound picker (system ringtone picker, Kotlin glue like `ContactOptions` REQUEST_RINGTONE) stored as a URI, `SoundSource::UserAlarm` | backlog R2 |
| S14 | Alarm volume level with a short "Test volume" preview | backlog R2 |
| S15 | History: Start again directly, Save as stop from a record, filter by outcome, search | backlog R2 |
| S16 | Export history as CSV / GPX | backlog R2 |
| S17 | Follow-me mode on the map (keep centred on the live fix, heading-up off), cycling Me button | backlog R3 |
| S18 | Home screen shortcut for a saved stop that starts its trip (`rovsti_android::shortcuts`, new Kotlin spec) | backlog R3 |
| S19 | Reverse a saved stop's direction and Duplicate a saved stop | backlog R3 |
| S20 | Copy / share the live trip status text ("Arriving at X, 1.2 km, about 4 min") | backlog R3 |
| S21 | Re-arm after dismiss: "Ring again in N min" on the alarm screen and a re-arm pill after Dismiss | backlog R3 |
| S22 | Tile cache: cache size cap choice (100 / 250 / 500 MB) and per-server usage | backlog R3 |
| S23 | Coordinates entry: type or paste DMS / Plus Code in the picker (parse the S1 formats back) | backlog R3 |
| S24 | Saved stops: pin favourites to top, colour tag | backlog R4 |

## Round 1 plan (S1-S9)

Steps 1-3 are pure (rovsti_geo, unit-tested). Step 4 is Android packaging. Steps 5-8 are thin GPUI glue.
Every step must leave all checks green:
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace &&
cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target
aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.

### Step 1. Pure: coordinate formats, Plus Code, bearing (S1, S9)
- New `crates/rovsti_geo/src/coord_format.rs` (`pub mod coord_format;` in lib.rs):
  - `pub enum CoordFormat { Decimal, Dms, Ddm, PlusCode, GeoUri, OsmLink }` with
    `pub const ALL: [CoordFormat; 6]`, `fn label(self) -> &'static str` ("Decimal", "Degrees, minutes,
    seconds", "Degrees and minutes", "Plus Code", "geo: link", "OpenStreetMap link").
  - `pub fn format(c: Coord, f: CoordFormat, label: Option<&str>) -> String`:
    Decimal -> `location_link::coords_text`; Dms -> `41°00'29.5"N 28°58'42.2"E` (one decimal second,
    carry 60.0 -> next minute); Ddm -> `41°00.492'N 28°58.704'E` (three decimals); PlusCode ->
    `plus_code(c)`; GeoUri -> `location_link::geo_url`; OsmLink -> `location_link::osm_url`.
  - `pub fn plus_code(c: Coord) -> String`: Open Location Code, 10 digits + "+" (alphabet
    `23456789CFGHJMPQRVWX`, lat clipped below 90, lon normalised).
- In `coord.rs`: `pub fn bearing_deg(self, to: Coord) -> f64` (initial great-circle bearing, 0..360).
  New `pub fn cardinal(deg: f64) -> &'static str` (8 points: N NE E SE S SW W NW) in `coord_format`.
- Tests: DMS/DDM of N/E, S/W and rounding carry (59.99995"); Plus Code spec example
  (47.365590, 8.524997) -> `8FVC9G8F+6X` and a southern/western point; poles and lon 180 do not panic;
  bearing due north/east/south/west and across the antimeridian; cardinal boundaries (22.4 -> N,
  22.6 -> NE, 359 -> N).
- Acceptance: `cargo test -p rovsti_geo` passes with the new tests.

### Step 2. Pure: saved-stop share text, QR payloads, measure tool (S2, S3, S8)
- `crates/rovsti_geo/src/dest.rs`:
  - `pub fn stop_share_text(name: &str, a: Coord, b: Coord) -> String`: name line, then
    `A: <osm link>` and `B: <osm link>` lines.
  - `pub fn parse_stop_share(text: &str) -> Option<(String, SharedPlace, SharedPlace)>`: two location
    links in the text (first = A, second = B), name = first non-link line; `None` for zero or one link.
    `parse_location` behaviour stays unchanged (first link wins).
  - `pub fn point_qr_payload(c: Coord, label: &str) -> String` = `geo_url` (label when not blank).
- New `crates/rovsti_geo/src/measure.rs` (`pub mod measure;`): `pub struct Measure { points: Vec<Coord> }`
  with `MAX_POINTS = 50`, `push(&mut self, c) -> bool` (false when full), `undo(&mut self) -> Option<Coord>`,
  `clear`, `points(&self) -> &[Coord]`, `legs_m(&self) -> Vec<f64>`, `total_m(&self) -> f64`.
- Tests: share -> parse round trip keeps the name and both coords to 5 decimals; a single-link text is
  `None` for `parse_stop_share` and still read by `parse_location`; a text with blank name parses with an
  empty name; measure totals equal the sum of legs, undo on empty is `None`, push past the cap is false.

### Step 3. Pure: saved stops export / import (GPX and JSON) and Saved-tab sort (S5, S6)
- New `crates/rovsti_geo/src/places_io.rs` (`pub mod places_io;`):
  - `pub fn to_json(stops: &SavedStops) -> String` (the v2 file format, pretty).
  - `pub fn to_gpx(stops: &SavedStops) -> String`: GPX 1.1, creator "Rovsti Stops", one `<rte>` per stop
    with `<name>` (XML-escaped) and two `<rtept lat lon><name>A|B</name></rtept>`.
  - `pub struct Incoming { pub name: String, pub a: Coord, pub b: Coord }`.
  - `pub enum ImportError { Empty, NotRecognised, TooLarge }` with `message()`.
  - `pub fn parse_import(text: &str) -> Result<Vec<Incoming>, ImportError>`: JSON (our v2 file, or a
    bare array of `SavedStop`) or GPX. Minimal hand-written GPX reader (no new dependency): `<rte>` ->
    first and last `rtept`, `<trk>` -> first and last `trkpt` of the track, consecutive `<wpt>` pairs whose
    names end in "A"/"B" (or plain pairs) -> one stop. Attributes in either quote style; entities
    `&amp; &lt; &gt; &quot; &apos;` decoded; invalid coords (`location_link::is_valid`) skipped. Text over
    2 MB -> `TooLarge`.
  - `pub struct MergePlan { pub add: Vec<Incoming>, pub duplicates: usize, pub invalid: usize,
    pub over_cap: usize }` and `pub fn merge_plan(existing: &SavedStops, incoming: Vec<Incoming>) ->
    MergePlan` (duplicate = `find_gate` or a repeat inside the file; invalid = `Diameter::new` fails;
    cap `saved_stops::MAX_STOPS`). `pub fn summary(plan: &MergePlan) -> String`
    ("Add 3 stops. 1 already saved." etc).
  - `pub fn export_file_name(kind: &str, today: &str) -> String` -> `rovsti-stops-2026-11-07.gpx`.
- `saved_stops.rs`: `pub enum SavedSort { Saved, Name, Nearest }` and
  `pub fn sorted<'a>(hits: Vec<&'a SavedStop>, sort: SavedSort, here: Option<Coord>) -> Vec<&'a SavedStop>`
  (Nearest without `here` falls back to Saved order; Name uses `fold`).
- Tests: GPX and JSON round trips of three stops (names with `&`, `<`, non-ASCII); a GPX from another app
  with a `trk` and with `wpt` pairs; garbage -> `NotRecognised`; empty -> `Empty`; merge skips a
  duplicate gate in either order, an in-file repeat, an invalid (too short) gate and respects the cap;
  summary texts; sort orders including the Nearest fallback.

### Step 4. Android packaging and backend wrappers (S3, S5)
- `apps/stops/platforms/android/app/src/main/AndroidManifest.xml`: add the
  `dev.rovsti.bridge.RovstiShareProvider` provider exactly as in Phone's manifest (authorities
  `${applicationId}.rovstishare`, not exported, grantUriPermissions).
- `apps/stops/src/kotlin_contract.rs`: test that the manifest declares the provider and that
  `MediaShare.kt` uses the `.rovstishare` authority.
- `apps/stops/src/backend.rs`: `pub(crate) fn save_png(name, bytes) -> Result<(), String>`,
  `pub(crate) fn share_png(name, bytes, title) -> Result<(), String>`,
  `pub(crate) fn export_text(name, mime, text) -> Result<(), String>` (cache file + `documents::export_file`,
  mime `application/gpx+xml` or `application/json`), `pub(crate) fn pick_import() -> Result<(), String>`
  (`documents::import_file(2 MB)`), `pub(crate) fn read_import(path) -> Result<String, String>` (reads and
  deletes the cache copy). Follow `apps/contacts/src/backend.rs` (`write_temp`, `safe_name`, `done`).
  Tests: desktop returns `Err` for the Android ones; `read_import` reads and removes a temp file.
- `main.rs` document_result dispatch: `DocumentOp::ExportMessages` -> pill "Saved stops exported" (or the
  error), `DocumentOp::ImportMessages` with `path` -> `on_places_picked` (stub that logs until step 6).

### Step 5. UI: Copy as, Open in maps, QR sheet (S1, S3, S4)
- `picker.rs` `SheetMode`: add `CopyAs { label: String, coord: Coord }` and `Qr(QrCard)` (new app-local
  `apps/stops/src/qr_sheet.rs` modelled on Contacts: `QrCard { image, png, name }`, `make_card(payload,
  name)`), titles "Copy as" / "QR code".
- CopyAs body: one `list_row` per `CoordFormat::ALL` showing the formatted value as subtitle; tap writes
  to the clipboard (`cx.write_to_clipboard`), closes the sheet, pill "Copied <format label>", haptic tick.
- QR body: the image (white card in dark mode too), name caption, pills Save (`backend::save_png`, pill
  "Saved to Photos" / error text) and Share (`backend::share_png`, clipboard fallback not applicable: pill
  "Could not share" on error).
- Pin menu (`map.rs::pin_menu`): add rows "Copy", "QR code", "Open in maps" (`cx.open_url(geo_url)`,
  pill "No maps app" when it fails is not observable, so no pill); keep `pin_menu_rect` sized for the
  extra rows and update its test.
- Acceptance: tests for any new pure helpers (`qr_sheet::file_name`, menu row count -> rect height).

### Step 6. UI: Saved tab utilities (S2, S5, S6)
- `saved_ui.rs`: a search field above the list when there are more than 5 stops (reuse the picker's field
  pattern), sort pill cycling Saved / Name / Nearest (nearest uses `self.map.position`), "No stops match".
  Store `saved_query: String`, `saved_sort: SavedSort` on `StopsApp` (not persisted).
- Per-stop pills add Share (`dest::stop_share_text` via `share_point`-style flow with clipboard fallback),
  Copy as (opens CopyAs for the gate midpoint), QR (payload = stop share text).
- Header menu row on the Saved tab: "Export GPX", "Export JSON", "Import". Import: `backend::pick_import`;
  `on_places_picked` reads, `places_io::parse_import`, `merge_plan`, then a confirm sheet
  (`SheetMode::ImportStops(MergePlan)`) with the summary and "Add N stops"; on confirm add them, save,
  pill "Added N stops" with `PillAction::RestoreSaved(SavedStops)` (new variant) as Undo. Errors -> pill
  with `ImportError::message()`.
- Picker paste (`picker.rs::paste_into_picker`): try `parse_stop_share` first; when it returns a pair,
  fill A and B (`choose_pair`) and pill "Filled A and B from <name>".
- Tests: pure helpers extracted in `saved_ui.rs` (`visible_stops(query, sort, here)`, field visibility
  threshold, import pill texts).

### Step 7. UI: transit stop sheet and live bearing (S7, S9)
- `picker.rs::stop_body`: rows "Copy name", "Copy stop code" (when the GTFS stop has a code, else id),
  "Share" (`share_text(name, coord)`), "Copy as"; subtitle with the distance from the last fix
  (`settings_ui::distance_text`). Pills after copying.
- `trip_ui.rs::live_card`: a bearing line `"{cardinal}, {distance}"` from the last fix to the gate centre,
  plus a small arrow rotated by the bearing (use a glyph/rotation API only if present in the pinned
  gpui-fusion; otherwise text only, e.g. arrow characters chosen by octant). Pure helper
  `pub fn bearing_text(here: Coord, target: Coord) -> String` and `octant_arrow(deg) -> &'static str`
  with tests.

### Step 8. UI: measure tool on the map, docs and backlog (S8)
- `map.rs`: a "Ruler" control button; while active, map taps add `Measure` points (no pin placement),
  the scene draws the polyline with dots (`stroke_line`, `fill_circle`), a glass card shows
  "Total 1.4 km, 3 legs" with Undo and Clear (Clear asks to tap again if more than 2 points, pill after);
  leaving the tab or starting a trip ends measuring. Gesture tests: a tap in measure mode adds a point and
  never places a pin.
- `docs/stops-utilities.md`: what each R1 utility does and where it is. Update this file's backlog
  statuses to "done R1" for what shipped.
- Acceptance: all checks green; every R1 utility reachable from the UI with a pill, destructive ones
  confirmed (import confirm, Clear measure, existing deletes).
