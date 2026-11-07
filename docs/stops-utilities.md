# Rovsti Stops: everyday utilities (rounds 1 and 2)

Everything here is local. Pure logic lives in `crates/rovsti_geo` (unit-tested); the GPUI code in
`apps/stops/src` is thin glue, and Android calls go through `rovsti_android` (desktop returns
`Error::Unsupported`). Backlog and status: `_bmad-output/plans/util-stops.md` (R1) and `_bmad-output/plans/util-stops-r2.md` (R2 and later).

| # | Utility | What it does | Where |
|---|---------|--------------|-------|
| S1 | Copy coordinates as | Decimal, DMS, degrees + decimal minutes, Plus Code, geo: link, OpenStreetMap link. Tap a format to copy, a pill confirms. | Logic `rovsti_geo::coord_format`; sheet `picker.rs::open_copy_as`; entries: pin menu "Copy", saved stop, transit stop sheet |
| S2 | Share a saved stop | Name plus both gate points as text; pasting that text in the picker fills A and B together. | `rovsti_geo::dest::{stop_share_text, parse_stop_share}`; `saved_ui.rs::share_point`; picker paste |
| S3 | QR code of a point or stop | QR sheet with Save to gallery and Share image. | `qr_sheet.rs`, `picker.rs::open_qr`, `backend::{save_png, share_png}` |
| S4 | Open in maps | Opens a `geo:` link in the user's maps app. | Pin menu "Open in maps" (`map.rs::maps_url`) |
| S5 | Export / import saved stops | GPX and JSON export; import GPX / JSON with a merge summary and a confirm sheet, duplicates skipped, Undo pill. | `rovsti_geo::places_io`; Saved tab pills in `saved_ui.rs` (`export_places`, `on_places_picked`); `backend::{export_text, pick_import, read_import}` |
| S6 | Search and sort saved stops | Search field (more than 5 stops) and a Saved / Name / Nearest sort. | `saved_ui.rs::visible_stops`, `rovsti_geo::saved_stops::{SavedSort, sorted}` |
| S7 | Transit stop sheet | Copy name, Copy stop code, Share, Copy as, distance from the last fix. | `picker.rs` stop sheet |
| S8 | Measure distance | Ruler button on the map; taps add points to a polyline; a glass card shows the total and leg count with Undo, Clear and Done. See below. | `rovsti_geo::measure`; `map.rs` (`MapState::measure*`, `measure_card`) |
| S9 | Bearing to the gate | The live card shows "NE, 1.2 km" with an arrow. | `trip_ui.rs::{bearing_text, octant_arrow}`, `rovsti_geo::coord_format::cardinal` |
| S10 | Download this map area | Estimates the tiles on screen ("900 tiles, about 27 MB, up to zoom 14", plus "Zoom in for street detail" when capped at 2500 tiles). First tap on "Download this area" arms a confirm row "Download 900 tiles", the second starts it. Progress ("Downloading 120 of 900 tiles") with Cancel; the end pill says "Map area ready offline. ..." or "Download cancelled. ...". | Logic `rovsti_geo::tiles::{slippy::tiles_for_area, offline::AreaDownload, queue::drop_prefetch}`; `map_options.rs` (`tap_area_download`, `area_progress_row`), `tiles.rs` (`download_area`, `cancel_area`); reach: map "Map" button, progress also on Settings > Map tiles |
| S11 | Appearance Auto / Light / Dark | Segmented row; the whole app and the map palette follow, saved in prefs. Pill "Appearance: Dark". | `rovsti_geo::prefs::Appearance`; `map_options.rs` (`theme_override`, `set_appearance`); reach: Map options sheet or Settings > Appearance |
| S12 | Vibration pattern | Standard / Pulse / Long / Heartbeat. Each change buzzes a preview and a haptic tick; the row is hidden while vibration is off. | `rovsti_geo::alarm::VibrationPattern`; `settings_ui.rs` ("Vibration pattern", `pattern_row_visible`); Settings > Alarm |
| S13 | Alarm sound picker | Opens the system alarm picker; the pick is stored as uri + title and used by the trip alarm. Pill "Alarm sound: <title>" with Undo (restores the previous sound). Cancelling does nothing. | `rovsti_geo::prefs::AlarmSound`; `trip::pick_alarm_sound`; `main.rs::on_event` (`alarm_sound_picked`); Settings > Alarm > Alarm sound |
| S14 | Alarm volume and Test volume | Volume cycles 80 / 90 / 100 % ("Never quieter than this."). Test volume plays the chosen sound for 3 seconds, pill "Playing for 3 seconds". | `alarm::{VOLUME_CHOICES, next_volume}`; `settings_ui.rs::volume_text`; `trip::preview_alarm_sound`; Settings > Alarm |
| S15 | History search, filter, Start again, Save as stop | Search field (more than 5 trips, accent-insensitive on label and A/B), All / Arrived / Cancelled filter, "No trips match" empty state. An open trip offers Start again (pill "Not while a trip is running" during a trip), Save as stop (pill "Saved as <name>" with Undo, or "Already saved as <name>"), Use and Delete. | `rovsti_geo::history::{OutcomeFilter, matching, TripRecord::pair}`; `settings_ui.rs` (`history_controls`, `start_again`, `save_record_as_stop`), `saved_ui.rs::history_field_visible`; Settings > History |
| S16 | Export history as CSV / GPX | Header pills "Export CSV" / "Export GPX" open the system save dialog; pill "History exported". CSV: RFC 4180, CRLF, local ISO times; GPX: one route per trip with A and B. | `rovsti_geo::history_io::{to_csv, to_gpx, export_file_name}`; `saved_ui.rs` (`ExportKind::History`), `backend::export_text`; Settings > History |

## Measure tool (S8)

- The **Ruler** button is the fourth map control (idle map only). Tapping it turns measuring on; the
  button is tinted while it is on and a haptic tick confirms.
- While measuring, a map tap adds a point (up to 50, then a pill "Up to 50 points") and **never
  places a pin**; pins are not grabbable, so touching one pans or adds a point. The view stays put.
- The scene draws the polyline with a dot on each point (`Scene::measure`, `stroke_line`,
  `fill_circle` in `scene_paths`). The scene cache key includes the points.
- The card reads "Total 1.4 km, 3 legs" (`measure_summary`). **Undo** removes the last point.
  **Clear** wipes immediately for 2 points or fewer; for longer measurements the first tap arms it
  ("Tap to clear", red) and the second clears, then a pill "Measurement cleared" shows.
  **Done** turns the tool off (pill "Measuring done").
- Measuring also ends on tab change, System Back (first layer closed), and trip start.
- Tests: `map.rs` (`a_tap_while_measuring_adds_a_point_and_is_no_pin`, point cap, `clear_step`,
  `measure_summary`, scene projection) and `rovsti_geo::measure`.

## Alarm sound and vibration (S12-S14)

- `AlarmPlan` (`rovsti_geo::alarm`) now carries the vibration pattern, the volume floor and an optional
  `user_sound_uri`. `StopsPrefs::alarm_plan()` builds it from the settings and `backend::start_spec`
  puts it into the trip spec, so the Kotlin trip alarm rings with the user's choices. With default prefs
  the plan JSON is unchanged.
- What reaches Kotlin: the plan JSON (pattern in ms, volume, `user_sound_uri`; `AlarmSound.uriFor("user_alarm")`
  uses the URI, else the system default alarm), a comma-separated pattern for the vibration preview, the
  current sound URI for the picker, and plan + milliseconds for the sound preview. The picker result comes
  back as the one-shot event `alarm_sound_picked` (`{uri, title, cancelled}`). Rust side:
  `rovsti_android::trip::{test_alarm, pick_alarm_sound, preview_vibration, preview_alarm_sound}`; all
  return `Error::Unsupported` on desktop, which the UI shows as a pill.
- Test alarm now uses the current settings: it sends `StopsPrefs::test_ring_json()` (the plan plus
  `sound` and `vibrate` flags) to `trip::test_alarm`, instead of the last trip's stored plan, so it also
  works before the first trip and reflects changes at once.
- Tests: `rovsti_geo` (`alarm`, `prefs`), `rovsti_android::trip` (desktop `Unsupported`, picker parser) and
  `apps/stops/src/kotlin_contract.rs` (Kotlin files carry the expected names).
