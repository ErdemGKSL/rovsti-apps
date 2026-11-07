# Rovsti Stops: everyday utilities (round 1)

Everything here is local. Pure logic lives in `crates/rovsti_geo` (unit-tested); the GPUI code in
`apps/stops/src` is thin glue, and Android calls go through `rovsti_android` (desktop returns
`Error::Unsupported`). Backlog and status: `_bmad-output/plans/util-stops.md`.

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
