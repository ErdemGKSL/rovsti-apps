# Plan: Rovsti Stops, round 2

- Base: `254a0fe00e1f94e53ca159c57e54ffffd2eefc48` ("Rovsti Stops: review fixes")
- Unit: Rovsti Stops. Continues `stops.md` (round 1, 8 steps).
- Result: **done**. No steps remain that can be verified without a device.

## Status of round 1

All eight steps landed, none blocked, no stash entries:

| Step | Commit |
| --- | --- |
| 1. Pure trip session and notice (`rovsti_geo`) | `5f0c489` |
| 2. Pure destinations, places, history, prefs | `2deda8c` |
| 3. Pure GTFS feed, CSV, map viewport, zip | `8e1a34a` |
| 4. Android seam: trip service, alarm, location permission, GTFS pick | `92df117` |
| 5. Stops Android host and headless trip handler | `25b8186` |
| 6. Stops UI: destination picking, start/stop, live status, alarm screen | `1f9702d` |
| 7. Vector map, GTFS import UI, line-aware "N stops before" alarm | `0dee398` |
| 8. History/stats, Settings, WhatsApp arriving-soon, docs | `5edf20d` |
| Review fixes | `254a0fe` |

## Goal coverage at the base

- Foreground location service: `rovsti_android/kotlin/.../TripService.kt` (type `location`,
  fused/GPS/network provider, `Headless.call("trip_fix")`, exact watchdog alarm) driving
  `apps/stops/src/headless.rs` -> `rovsti_geo::session`.
- Full-screen alarm: `AlarmActivity.kt` + `TripAlarm.kt` (show-when-locked, sound, vibration,
  snooze capped in Rust, `USE_FULL_SCREEN_INTENT`, `USE_EXACT_ALARM`).
- Destination picking: `geo:` and maps-link intent filters plus `ACTION_SEND` in the manifest,
  `on_receive_share` in `main.rs`, `rovsti_geo::dest::parse_location`, saved places and recent
  trips in `picker.rs`.
- Map: decided vector radar map, no tiles (`apps/stops/src/map.rs`, `rovsti_geo::view`).
- GTFS import: `rovsti_geo::{gtfs, csv}`, `apps/stops/src/transit.rs`, own document op.
- Trip history and stats: `rovsti_geo::history`, Settings in `settings_ui.rs`.
- Shared WhatsApp settings: `rovsti_wa_ui::WhatsAppSettings` (`AppId::Stops`), link first time
  or connect with key; `INTERNET` permission present.
- Arriving-soon message: `rovsti_geo::notice`, sent through the gateway by
  `rovsti_wa_ui::headless::send_text_in` from the headless fix handler, with retry/give-up.

## Checks run at the base

`cargo fmt --all --check`, `cargo clippy --workspace --all-targets -- -D warnings`,
`cargo test --workspace` (1421 passed, 0 failed),
`cargo check -p rovsti_android --target aarch64-linux-android`,
`cargo check -p rovsti_wa_client --target aarch64-linux-android`: all green.

## Remaining (out of scope here)

- Roadmap item "Check the trip, alarm and Doze on a phone" needs a physical device and stays open.
