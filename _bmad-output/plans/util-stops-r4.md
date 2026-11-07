# util-stops: everyday utilities for Rovsti Stops (round 4)

baseSha: 4df8c8f8cb988cb79b084445b6b8135dcc7d53d0

## Audit at baseSha

- R1-R3 (`util-stops.md`, `util-stops-r2.md`, `util-stops-r3.md`; last commits 2caadb6..4df8c8f) shipped
  S1-S18, S20-S23, S25, S26 (see `docs/stops-utilities.md`). No stash entries; working tree clean.
- Disk had 2.3 GB free at planning time: run `rm -rf target/debug/incremental` before every build.
- Checked in code for the R4 backlog:
  - S28 map scale bar: **already exists** (`map.rs::scale_bar`, drawn in the map card, test
    `the_scale_bar_uses_round_lengths`). Mark done (pre-existing), no work.
  - S29: the Saved row subtitle already has "Gate 120 m, 1.2 km away" (`saved_ui.rs::stop_detail`);
    only "Open in maps" is missing for a saved stop (pin menu has it via `map.rs::maps_url`).
  - S24: `SavedStop { id, name, a, b, created_ms }` has no favourite flag; `saved_stops::sorted`
    knows Saved / Name / Nearest only. Colour tags: low value next to favourites, moved to backlog.
  - S30: nothing copies the Saved list as text (only GPX/JSON export).
  - S27: after arrival the only feedback is the pill "Alarm dismissed" (+ Re-arm). The record is in
    `History.records[0]` (`TripRecord { label, a, b, started_ms, ended_ms, outcome, travelled_m,
    snoozes, .. }`), but the idle Trip tab (`trip_ui.rs::trip_tab`) shows nothing about it.
  - History record (Settings > History > open) has Start again / Save as stop / Use / Delete, no Share
    or Copy.
  - No backup: saved stops and history can be exported separately, prefs cannot, and nothing restores
    everything on a new phone.
  - **Bug (user-visible):** `apps/stops/src/settings_ui.rs` has double-encoded UTF-8 (mojibake) since
    04879d7: `â€“` instead of `–` (U+2013) and `Â·` instead of `·` (U+00B7) at lines ~214-251, 389,
    and the tests ~2218-2402. History rows render "5 Nov  Â·  18 min". Only this file is affected
    (`grep -rln $'\xc3\xa2\xe2\x82\xac\|\xc3\x82\xc2\xb7' apps crates`).
- Reusable: `map::maps_url`, `cx.open_url`, `saved_ui::{share_out, stop_detail}`, `settings_ui::{
  distance_text, duration_text, start_again, save_record_as_stop, record_pair, android_notice}`,
  `session::format_distance`, `coord_format::{format, CoordFormat::OsmLink}`, `backend::{export_text,
  pick_import, read_import, save_stops, save_prefs}`, `save_history_in_background`, `use_tile_source`,
  `map_options::set_appearance`, `MapTiles::set_cap`, `pending_export: Option<ExportKind>`,
  `rovsti_core::contact_dates::local_ymd`, `PillAction`, `SheetMode`.
- Document routing: places import uses `backend::pick_import` and arrives as
  `DocumentOp::ImportMessages` (`main.rs::on_event` -> `on_places_picked`). A second importer (backup)
  needs an explicit `pending_import` like `pending_export`.

## Utility backlog (ordered by user value)

| # | Utility | Status |
|---|---------|--------|
| S1-S9 | Copy as, share stop, QR, open in maps, export/import stops, Saved search/sort, transit stop sheet, measure, bearing | done R1 |
| S10-S16 | Offline area, appearance, vibration pattern, alarm sound, volume, history search/filter/start again/save, history export | done R2 |
| S17, S18, S20-S23, S25, S26 | Follow-me, home screen shortcut, live status share, re-arm, tile cache size, paste DMS/Plus Code, share my location, measure actions | done R3 |
| S28 | Map scale bar | done (pre-existing, `map.rs::scale_bar`) |
| S31 | Fix garbled separators in History rows and stats (mojibake) | R4 step 4 |
| S24 | Saved stops: favourites (star, listed first in every sort, kept in JSON export/import) | R4 steps 1, 4 |
| S29 | Saved stop: Open in maps (distance from me already in the row) | R4 step 4 |
| S30 | Saved stops: Copy list as plain text (visible stops, respects search and sort) | R4 steps 1, 4 |
| S27 | Last trip card on the idle Trip tab: "Arrived at Home – Work", "18 min · 3.4 km · 1 snooze", Start again / Save as stop / Share / Close | R4 steps 2, 5 |
| S33 | History record: Share and Copy trip summary | R4 steps 2, 5 |
| S32 | Backup and restore everything (saved stops, history, settings) as one JSON file, confirm + Undo | R4 steps 3, 6 |
| S34 | Pin menu: "1.2 km from you, NE" line and "Measure from here" | backlog R5 |
| S35 | Saved stops: colour tag | backlog R5 |
| S36 | History: delete trips older than 30 / 90 days (two-tap) | backlog R5 |
| S37 | Trip tab idle: "Nearest saved stop" quick-start chip when within 2 km | backlog R5 |
| S19 | Reverse / Duplicate a saved stop | dropped (gate is direction-agnostic) |

## Round 4 plan

Steps 1-3 pure (unit-tested, `crates/rovsti_geo`), steps 4-6 thin GPUI, step 7 docs. No new Android
seam is needed (reuses `export_text`, `pick_import`, `share`, `open_url`). Every step leaves all checks
green: `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test
--workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p
rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target
aarch64-linux-android`.

### Step 1. Pure: favourites and list text (S24, S30)
- `crates/rovsti_geo/src/saved_stops.rs`:
  - `SavedStop.favourite: bool` with `#[serde(default, skip_serializing_if = "std::ops::Not::not")]`;
    `FORMAT_VERSION` stays 2 (old files load, files without favourites are byte-identical). Update the
    ~14 `SavedStop { .. }` literals in the workspace (`grep -rn "SavedStop {" apps crates`), `add`
    sets `false`.
  - `pub fn toggle_favourite(&mut self, id: u64) -> Option<bool>` (new state; None for unknown id).
  - `sorted(..)`: after the chosen sort, a stable partition puts favourites first (all three sorts).
  - `move_by(id, delta)`: moves only among stops of the same favourite group (swaps with the nearest
    neighbour in that direction having the same flag; false at the group edge), so Up/Down match what
    the user sees.
  - `pub fn list_text(stops: &[&SavedStop], here: Option<Coord>) -> String`: first line
    `"Saved stops (3)"`, then one line per stop
    `"★ Kadikoy - Moda: 40.99000, 29.02000 (1.2 km away)"` (star only for favourites, centre via
    `coord_format::format(center, CoordFormat::Decimal)`, distance via `session::format_distance` when
    `here` is Some), then the OSM link (`CoordFormat::OsmLink`) on the next line indented two spaces.
    Empty slice -> `""`.
- `crates/rovsti_geo/src/places_io.rs`: `Incoming.favourite: bool`; JSON import reads `favourite`
  (default false), GPX import false; `merge_plan`/apply keeps it (set it on the stop returned by
  `SavedStops::add`). JSON export already serialises `SavedStops`, so it carries the flag.
- Tests: old JSON without `favourite` loads; serialising a non-favourite has no `favourite` key;
  toggle twice; `sorted` favourites first for Saved/Name/Nearest with stable order inside groups;
  `move_by` stays inside its group and returns false at the edge; `list_text` for 0 / 2 stops with
  and without `here`, multibyte name; import of a JSON with a favourite keeps it, duplicate skipped.

### Step 2. Pure: trip summary text and last-trip lookup (S27, S33)
- `crates/rovsti_geo/src/history.rs`:
  - `pub fn format_duration(ms: u64) -> String` (move the body of `settings_ui::duration_text` here:
    "under 1 min", "18 min", "1 h 05 min"; `duration_text` becomes a thin call so its tests still pass).
  - `pub fn snoozes_text(n: u8) -> Option<String>` (0 -> None, "1 snooze", "3 snoozes").
  - `impl TripRecord { pub fn title(&self) -> String }`: "A – B" (U+2013) from `a`/`b` labels (empty
    label -> "Point A" / "Point B"), legacy -> `label`, empty -> "Trip".
  - `pub fn summary_line(&self) -> String`: `"18 min · 3.4 km · 1 snooze"` (U+00B7, snoozes part
    omitted at 0, distance via `session::format_distance`).
  - `pub const LAST_TRIP_CARD_MS: i64 = 12 * 3_600_000;`
    `impl History { pub fn last_finished(&self, now_ms: i64) -> Option<&TripRecord> }`: the newest
    record when `now_ms - ended_ms` is in `0..=LAST_TRIP_CARD_MS`, else None.
- `crates/rovsti_geo/src/history_io.rs`: `pub fn share_text(record: &TripRecord, tz_offset_s: i32) ->
  String`: Arrived -> `"Arrived at Home – Work on 5 Nov 2026, 18:42. 18 min · 3.4 km · 1 snooze."`;
  Cancelled -> `"Trip to Home – Work stopped on 5 Nov 2026, 18:42. ..."`; second line the geo link of
  B (`dest::point_qr_payload(b.coord, Some(title))`) when the record has B. Local date and time via
  `rovsti_core::contact_dates::local_ymd` and the existing local-time helper used by `to_csv`.
- Tests: `format_duration` edges (0, 59 s, 60 min, 125 min); `snoozes_text`; `title` for pair, empty
  labels, legacy, empty; `summary_line` with 0 and 2 snoozes; `last_finished` (fresh, 13 h old,
  future `ended_ms`, empty history); `share_text` arrived / cancelled / legacy (no link line) with a
  +03:00 offset.

### Step 3. Pure: backup format (S32)
- New `crates/rovsti_geo/src/backup.rs` (`pub mod backup;` in `lib.rs`):
  - `pub const BACKUP_APP: &str = "rovsti-stops"; pub const BACKUP_VERSION: u32 = 1;`
  - `#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)] pub struct Backup { pub app: String,
    pub version: u32, pub created_ms: i64, pub saved: SavedStops, pub history: History, pub prefs:
    StopsPrefs }` and `impl Backup { pub fn new(saved, history, prefs, now_ms) -> Self }`.
  - `pub fn to_json(backup: &Backup) -> String` (pretty).
  - `#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)] pub enum BackupError { NotJson,
    NotStops, Newer, BadStops }` with `pub fn message(self) -> &'static str` ("That file is not a
    backup", "That backup is from another app", "That backup is from a newer version", "The saved
    stops in that backup are damaged").
  - `pub fn parse(text: &str) -> Result<Backup, BackupError>`: JSON error -> NotJson; `app` mismatch
    -> NotStops; `version > BACKUP_VERSION` -> Newer; `saved.version != saved_stops::FORMAT_VERSION` ->
    BadStops; then drops stops whose `diameter()` fails, truncates to `MAX_STOPS`, history to
    `MAX_RECORDS`, and fixes `saved.next_id` to exceed the highest id. Prefs fields use their serde
    defaults when missing.
  - `pub fn summary(backup: &Backup, tz_offset_s: i32) -> String`: `"12 stops, 48 trips and settings
    from 5 Nov 2026"` (singulars "1 stop", "1 trip").
  - `pub fn export_file_name(today: &str) -> String` -> `rovsti-stops-backup-2026-11-07.json`.
  - The WhatsApp connect key is not in `StopsPrefs` (it lives with `rovsti_wa_client`) and is never
    written; add a doc comment saying so.
- Tests: round trip equals; a prefs object missing new fields loads with defaults; wrong `app`,
  version 2, garbage, `saved.version` 1 give the right errors; an invalid gate is dropped and
  `next_id` fixed; summary singular/plural; file name.

### Step 4. UI: Saved tab favourites, Open in maps, Copy list; fix mojibake (S24, S29, S30, S31)
- Mojibake: in `apps/stops/src/settings_ui.rs` replace the byte sequences `â€“` -> `–` and `Â·` -> `·`
  everywhere (code, doc comments, tests), e.g. with a Python byte replace; confirm with the grep
  above that no file in `apps` or `crates` still has them. Existing tests then assert the right text.
- `apps/stops/src/saved_ui.rs`:
  - Row title: `"★ {name}"` for favourites (pure `row_title(&SavedStop) -> String`).
  - Opened row, first pill row becomes Show on map / Open in maps / Favourite (or "Unfavourite") /
    Start. Favourite -> `self.saved.toggle_favourite(id)`, `backend::save_stops` in the background
    as the other edits do, refresh `saved_visible`, pill `favourite_pill(state)` ("Added to
    favourites" / "Removed from favourites"); no confirm (reversible by the same tap).
  - Open in maps -> `cx.open_url(&crate::map::maps_url(stop.center(), &stop.name))`, pill "Opening
    in maps".
  - Saved header: pill "Copy list" next to the export pills when at least one stop is visible;
    copies `saved_stops::list_text` of `saved_visible` (search + sort respected) with
    `cx.write_to_clipboard(gpui::ClipboardItem::new_string(..))`, pill `copy_list_pill(n)` ("Copied 1
    stop" / "Copied 12 stops").
  - `reorderable` unchanged; Up/Down now use the grouped `move_by`; disable Up/Down at group edges
    (pure `can_move(&SavedStops, id, delta) -> bool` mirrors `move_by` without mutating).
- Tests (saved_ui): `row_title`, `favourite_pill`, `copy_list_pill`, `can_move` at group edges, and
  `visible_stops` puts favourites first under a query.

### Step 5. UI: Last trip card and history Share / Copy (S27, S33)
- `StopsApp.last_trip_hidden: Option<u64>` (in memory, record id hidden by Close).
- `trip_ui.rs::trip_tab` idle branch: after `setup_cards`, when `self.history.last_finished(now)` is
  Some and `show_last_trip(record.id, self.last_trip_hidden)`: a `section` with `list_row` title
  `last_trip_title(record)` ("Arrived at Home – Work" / "Trip stopped: Home – Work"), subtitle
  `record.summary_line()`, and a pill row: Start again (reuse `start_again(a, b)`; only with `pair()`),
  Save as stop (reuse `save_record_as_stop(0)`; only with `pair()`), Share (system share of
  `history_io::share_text` through `share_out`, desktop -> `android_notice` pill as today), Close
  (sets `last_trip_hidden`, no pill). Make `start_again` / `save_record_as_stop` `pub(crate)` if needed.
- Settings > History, opened record: add pills "Share" and "Copy" (clipboard, pill "Trip copied")
  with the same `share_text`.
- Pure helpers in `trip_ui.rs` with tests: `last_trip_title(&TripRecord) -> String`,
  `show_last_trip(id, hidden) -> bool`.

### Step 6. UI: Settings > Backup (S32)
- `saved_ui::ExportKind::Backup` with `export_pill` text "Backup saved"; new `pub enum ImportKind {
  #[default] Places, Backup }` and `StopsApp.pending_import: Option<ImportKind>`. `import_places` sets
  `Some(Places)`; `main.rs::on_event` `ImportMessages` branch routes on `pending_import.take()
  .unwrap_or_default()`: Places -> `on_places_picked`, Backup -> `on_backup_picked`.
- Settings main page, new section "Backup" with rows:
  - "Export backup" (subtitle "Saved stops, trip history and settings"): `Backup::new(saved.clone(),
    history.clone(), prefs.clone(), now)` -> `backend::export_text(backup::export_file_name(today),
    "application/json", &backup::to_json(..))`, `pending_export = Some(ExportKind::Backup)`; Err ->
    pill with the error / `android_notice`.
  - "Restore backup": trip running -> pill "Not while a trip is running" (pure
    `restore_blocked(trip_running) -> Option<&'static str>`); else `pending_import = Some(Backup)` and
    `backend::pick_import()`.
- `on_backup_picked`: `read_import(path)` -> `backup::parse` -> Err pill `BackupError::message`; Ok ->
  `SheetMode::RestoreBackup(Box<Backup>)` (new) with title "Restore backup", body
  `backup::summary(..)` plus "This replaces your saved stops, trip history and settings.", buttons
  Cancel and red "Replace everything".
- Confirm: keep `(saved, history, prefs)` for `PillAction::UndoRestore(Box<(SavedStops, History,
  StopsPrefs)>)`; apply via one `apply_snapshot(saved, history, prefs, cx)` used by both confirm and
  Undo: assign, `backend::save_stops`, `save_history_in_background`, `save_prefs`, then side effects:
  `set_appearance(prefs.appearance)`, `use_tile_source(prefs.tile_source())`,
  `map_tiles.set_cap(prefs.tile_cache_bytes())`, refresh `saved_visible`, reset `expanded`,
  `confirm_delete`, `last_trip_hidden`. Pill "Backup restored" with Undo.
- Tests: `restore_blocked`, `export_pill(ExportKind::Backup, ..)`, ImportKind default is Places, and a
  pure `restore_sheet_body(&Backup, tz) -> String`.

### Step 7. Docs and backlog
- `docs/stops-utilities.md`: title "rounds 1-4", link `util-stops-r4.md`; add rows S24, S27, S29,
  S30, S32, S33 (what, where, how to reach, feedback) and a short "Backup" section (file contents,
  what is not included: tile cache, GTFS feed, WhatsApp link; restore is blocked during a trip and
  has Undo). Note S28 pre-existing and the S31 text fix.
- This file: mark shipped rows "done R4"; keep S34-S37 in order for R5.
- Acceptance: all checks green; every R4 utility reachable from the UI with a pill; destructive
  actions confirmed (restore needs the "Replace everything" sheet and has Undo; favourite toggles are
  reversible by the same tap; Close only hides the card).
