# Rovsti Stops: everyday utilities (rounds 1-6)

Everything here is local. Pure logic lives in `crates/rovsti_geo` (unit-tested); the GPUI code in
`apps/stops/src` is thin glue, and Android calls go through `rovsti_android` (desktop returns
`Error::Unsupported`). Backlog and status: `_bmad-output/plans/util-stops.md` (R1) and `_bmad-output/plans/util-stops-r2.md` (R2), `_bmad-output/plans/util-stops-r3.md` (R3), `_bmad-output/plans/util-stops-r4.md` (R4), `_bmad-output/plans/util-stops-r5.md` (R5), `_bmad-output/plans/util-stops-r6.md` (R6 and later backlog).

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
| S17 | Follow-me | The Me button cycles Off, Centred (one jump to your position), Following (live fixes keep the map centred, zoom unchanged). Button tinted while following. Pills "Following you" / "Stopped following"; desktop answers "Following works on the phone only.", denied permission "Allow location to follow you." | `rovsti_geo::view::FollowMode::{on_me_tap, on_pan, watches}`; `map.rs` (`follow_mode`, `sync_follow`, `pause_follow`), `location::watch_location`; reach: Me button on the map |
| S18 | Home screen shortcut for a saved stop | Pins a launcher shortcut that opens the app and starts that stop's trip. Pills "Added to the home screen" or "The launcher did not add it"; desktop shows the Android-only notice. | `rovsti_geo::saved_stops::shortcut_spec`; `rovsti_android::shortcuts::pin_stop`; `backend::{pin_stop_shortcut, can_pin_shortcut}`; `saved_ui.rs` (`pin_pill`); reach: Saved tab > open a stop > "Home screen" |
| S20 | Copy / share live trip status | "On my way to Kadikoy - Moda: 1.2 km to the line, about 4 min, heading NE." plus a geo link line; "Arrived at ..." once crossed. Pills "Status copied"; share opens the system share sheet. | `rovsti_geo::session::{status_share_text, format_distance}`; `trip_ui.rs` (copy / share status); reach: Trip tab live card, "Copy status" and "Share status" |
| S21 | Re-arm after dismiss | Dismissing the alarm shows the pill "Alarm dismissed" with a "Re-arm" action that starts the same A/B trip again (an explicit tap; no pill action when the ends are unknown). | `trip_ui.rs::dismiss_pill`, `PillAction::Rearm` in `main.rs`; reach: alarm Dismiss |
| S22 | Tile cache size and other servers | "Cache size" cycles 128 / 256 / 512 / 1024 MB, applied live. Lowering below current usage needs a second tap ("Tap again to shrink to 128 MB"), then pill "Cache limit 128 MB". "Other servers" (only when > 0) shows their usage; two taps clear it, pill "Freed 12 MB". | `rovsti_tiles::cache::{DiskCache::set_cap, usage_by_source, clear_except}`; `rovsti_geo::prefs::{TILE_CACHE_CHOICES_MB, next_tile_cache, tile_cache_bytes}`; `settings_ui.rs` (`cache_size_text`, `needs_shrink_confirm`, `other_servers_bytes`); Settings > Map tiles |
| S23 | Paste DMS / DDM / Plus Code | The picker accepts `41°01'32.2"N 28°58'36.0"E`, `41° 1.537' N, 28° 58.600' E`, `N 41.0256 E 28.9767` and full Plus Codes (not short ones), alongside links and plain decimals. Out-of-range values give the usual error. | `rovsti_geo::coord_format::{parse_coord, plus_code_decode}`, `dest::parse_location`; `picker.rs` paste field and search box |
| S25 | Share my current location | Tapping the blue dot (idle map, not measuring) opens "My location" ("Accurate to 15 m") with Copy as, Share, QR code, Use as A and Use as B instead of placing a pin. | `map.rs::hits_position`; `map_actions.rs::open_my_location`; reuses `open_copy_as`, `open_qr`, `share_point`; reach: tap the dot |
| S26 | Measure actions | "More" on the measure card (2 or more points) opens Copy result (pill "Measurement copied"), Share as GPX (save dialog, pill "Measurement exported") and Save as stop (first and last point, only when they form a valid gate; pill "Saved as <name>" with Undo, or "Already saved as <name>"). | `rovsti_geo::measure::{summary_text, to_gpx, as_gate, export_file_name}`; `map_actions.rs` (`measure_actions`, `copy_measurement`, `export_measurement`, `save_measurement_as_stop`); reach: Ruler > More |
| S24 | Favourite saved stops | A star marks a stop and lists it first in every sort (Saved / Name / Nearest). Up/Down reordering stays inside the favourites or the other group, and is disabled at the group edge. Kept in JSON export/import and backup. Pills "Added to favourites" / "Removed from favourites"; the same tap undoes it, so no confirm. | `rovsti_geo::saved_stops::{SavedStop::favourite, toggle_favourite, sorted, move_by}`, `places_io`; `saved_ui.rs` (`row_title`, `favourite_pill`, `can_move`); reach: Saved tab > open a stop > "Favourite" / "Unfavourite" |
| S27 | Last trip card | The idle Trip tab shows the newest finished trip for 12 hours: "Arrived at Home – Work" (or "Trip stopped: ..."), "18 min · 3.4 km · 1 snooze", with Start again, Save as stop, Share and Close. Close only hides the card. | `rovsti_geo::history::{History::last_finished, TripRecord::{title, summary_line}, format_duration, snoozes_text}`; `trip_ui.rs` (`last_trip_title`, `show_last_trip`); reach: Trip tab after a trip |
| S28 | Map scale bar | Round-length scale bar in the map card. Pre-existing before R4, no change. | `map.rs::scale_bar` |
| S29 | Open a saved stop in maps | Opens the stop's `geo:` link in the user's maps app. Pill "Opening in maps". | `saved_ui.rs` (opened row), `map::maps_url`; reach: Saved tab > open a stop > "Open in maps" |
| S30 | Copy saved stops as text | Copies the visible stops (search and sort respected) as lines "★ Name: 40.99000, 29.02000 (1.2 km away)" plus an OpenStreetMap link. Pill "Copied 12 stops" / "Copied 1 stop". | `rovsti_geo::saved_stops::list_text`; `saved_ui.rs::copy_list_pill`; reach: Saved tab header "Copy list" (shown when a stop is visible) |
| S31 | History text fix | Garbled separators in History rows and stats (double-encoded "–" and "·") are fixed; no behaviour change. | `settings_ui.rs` |
| S32 | Backup and restore | One JSON file with saved stops, trip history and settings. Restore asks first and can be undone. Pills "Backup saved", "Backup restored" (Undo). See Backup below. | `rovsti_geo::backup::{Backup, to_json, parse, summary, export_file_name}`; `saved_ui.rs` (`on_backup_picked`, `restore_blocked`, `restore_sheet_body`); `backend::{export_text, pick_import, read_import}`; reach: Settings > Backup |
| S33 | Share and copy a trip | A history record offers Share (system share sheet) and Copy (pill "Trip copied"): "Arrived at Home – Work on 5 Nov 2026, 18:42. 18 min · 3.4 km · 1 snooze." plus a geo link of B. | `rovsti_geo::history_io::share_text`; `settings_ui.rs`; reach: Settings > History > open a trip |

| S34 | Distance from you and Measure from here | The pin menu footer adds "1.2 km from you, NE" (or "You are here" under 10 m) when a fix exists. "Measure from here" starts the ruler with that pin as the first point (hidden during a trip). Pill "Tap the map to measure from here". No confirm needed. | `rovsti_geo::coord_format::from_you_text`; `map.rs` (`pin_menu`, `pin_menu_height`, `MapState::measure_from`); reach: tap a pin > "Measure from here" |
| S36 | Delete old trips | History shows "Delete 12 trips older than 30 days" / "90 days" pills (only when some match). First tap arms ("Tap again to delete 12 trips"), second deletes. Pill "Deleted 12 old trips" with Undo (restores the whole history). | `rovsti_geo::history::{older_than, remove_older_than, prune_label, PRUNE_CHOICES_DAYS}`; `settings_ui.rs` (`prune_history`, `prune_step`, `prune_pill`), `PillAction::RestoreHistory`; reach: Settings > History |
| S37 | Nearest saved stop | The idle Trip tab shows "Nearest: Home gate", "350 m away", with Start, when a fix exists, no ends are picked and a saved stop lies within 2 km. Start uses the normal path (pill "Starting <name>" or the usual error). | `rovsti_geo::saved_stops::{SavedStops::nearest, NEAREST_CHIP_M}`; `trip_ui.rs` (`nearest_row_visible`); reach: Trip tab, idle |
| S38 | Usage on saved stops | The Saved row subtitle adds "Used 4 times, last 5 Nov" (or "Used once, today") from the trip history; matches the gate in either direction. Read-only. | `rovsti_geo::history::{History::usage_of, usage_text, Usage}`; `saved_ui.rs::stop_detail`; reach: Saved tab |
| S39 | Reset alarm settings | Last row of Settings > Alarm, shown only when something differs from the defaults. First tap arms ("Tap again to reset"), second resets sound, vibration, pattern, volume, snooze and alarm sound (appearance and tile settings untouched). Pill "Alarm settings reset" with Undo. | `rovsti_geo::prefs::StopsPrefs::{alarm_is_default, with_alarm_defaults}`; `settings_ui.rs` (`reset_alarm_label`), `PillAction::RestorePrefs`; reach: Settings > Alarm |
| S35 | Colour tag on saved stops | Opened row has a "Colour: Orange" pill; each tap cycles Orange, Red, Green, Blue. A dot in the tag colour shows on the row (not for Orange) and the map ring uses it. Pill "Colour: Red" with Undo (restores the previous colour). Kept in JSON export/import and backup; old files load as Orange. Not destructive, no confirm. | `rovsti_geo::saved_stops::{StopColour, SavedStops::{cycle_colour, set_colour}}`, `places_io`; `saved_ui.rs` (`stop-colour`, `restore_colour`), `PillAction::RestoreColour`, `map.rs::stop_colour_hsla`; reach: Saved tab > open a stop > "Colour" |
| S40 | Saved stops on the map | The saved-stop rings can be hidden with "Saved stops on map" in Map options (pill "Saved stops shown" / "Saved stops hidden"; row hidden without saved stops). Tapping a ring (within 18 px) opens a "Saved stop" sheet: Start, Share, Copy as, Open in maps, Open in Saved, Place a pin here. Rows reuse their usual pills (Start during a trip: "Not while a trip is running"); a deleted stop closes the sheet with "That stop was deleted". No confirm needed. | `rovsti_geo::prefs::StopsPrefs::show_saved_on_map`; `map.rs` (`pick_saved`, `SAVED_TAP_RADIUS_PX`), `picker.rs` (`SheetMode::SavedStop`), `map_options.rs` (`saved_toggle_pill`); reach: Map button > Saved stops on map, or tap a ring |
| S41 | Arrival clock | The live card reads "About 4 min to the line, around 18:42", and Copy / Share status add "(around 18:42)" to the ETA. Local time from the device offset. No new pill (existing "Status copied" / share sheet). | `rovsti_geo::session::{arrival_clock, status_share_text}`; `trip_ui.rs` (`eta_text`, `status_text`); reach: Trip tab live card |
| S42 | Map view link, share and centre | Map options > "This view": Copy map link (OpenStreetMap link of the centre and zoom, pill "Map link copied"), Share this view (system share sheet with the OSM and geo links) and Copy centre as (the Copy as sheet). | `rovsti_geo::coord_format::{osm_view_url, geo_view_url, map_view_share_text}`; `map_options.rs` (`map-copy-link`, `map-share-view`, `map-copy-centre`); reach: Map button > This view |

## Backup (S32)

- **Contains:** saved stops (with favourites), trip history and the app settings (appearance, alarm, tile
  server and cache size, and so on), as pretty JSON named `rovsti-stops-backup-<date>.json`.
- **Leaves out:** the tile cache, the GTFS feed, and the WhatsApp link (the connect key lives with
  `rovsti_wa_client` and is never written).
- **Restore:** Settings > Backup > Restore backup. Blocked during a trip (pill "Not while a trip is
  running"). A bad file shows a pill (not a backup, another app, newer version, damaged stops). A good one
  opens the confirm sheet "Restore backup" with a summary such as "12 stops, 48 trips and settings from 5
  Nov 2026"; Cancel or the red "Replace everything". Afterwards the pill "Backup restored" offers Undo,
  which puts back the previous stops, history and settings.
- Tests: `rovsti_geo::backup` and the `saved_ui` helpers.

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

## Shortcuts and follow-me (S17, S18)

- **open_stop one-shot.** The pinned shortcut carries the extra `dev.rovsti.stops.STOP_ID` (Long). Stops
  `MainActivity` calls `RovstiBridge.deliverOpenStop(intent)` in `onCreate` and `onNewIntent`; it posts the
  one-shot event `open_stop` (`{id}`) and removes the extra, so a rotation or resume never replays it.
  Rust parses it with `rovsti_android::events::parse_open_stop`; `main.rs::on_event` then applies
  `open_stop_outcome`: a running trip gives the pill "Not while a trip is running", a deleted stop "That stop
  was deleted", otherwise the trip starts through `request_start`, the Trip tab opens and the pill says
  "Starting <name>".
- **watchLocation lifecycle.** `RovstiBridge.watchLocation(true)` registers one listener on the first enabled
  provider (every 2 s or 5 m, main looper) and emits the same `stops_location` events as the one-shot fix;
  `false` removes it. Both are idempotent, and `true` returns false without the location permission. The
  map turns it on only in Following mode (`sync_follow`) and off on: a second Me tap, any pan, drag or
  pinch, trip start, tab change, and the window going inactive (`pause_follow`; Kotlin `onPause` also calls
  `watchLocation(false)`). On resume the app re-arms it if the mode is still Following.
- **Battery.** Continuous updates run only while the map is visibly following in the foreground, so leaving
  the app, panning or starting a trip stops the GPS use. The trip service has its own location handling and
  is unaffected.
- Tests: `rovsti_geo` (`view`, `coord_format`, `session`, `measure`, `saved_stops`, `prefs`), `rovsti_tiles`
  cache, `rovsti_android` (`events`, `shortcuts`, `location` desktop `Unsupported`), and the Stops crate
  (`map`, `picker`, `trip_ui`, `settings_ui`, `kotlin_contract`).
