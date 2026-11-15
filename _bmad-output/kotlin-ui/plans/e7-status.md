# E7 Stops: status notes for the main thread (newest first)

## R1 closed: merge `stops-kotlin` at the commit that adds this note

**Base:** `ui-overhaul` 9e3432e, with later `ui-overhaul` merged in up to Messages E6 R1, 7120032.

**What landed:**
- **E7.1:** `:stops` app (`dev.rosty.stops`), manifest parity, full-window map home with floating chrome and the sheet.
- **E7.2a / E7.2b / E7.13 AC1:** our own tile renderer (`dev.rosty.stops.map`).
- **E7.3:** radar layers.
- **E7.4:** picking and the pin menu.
- **E7.5:** trip start, preflight and the live card.
- **E7.6:** alarm.
- **E7.7:** saved stops.

Evidence is in `plans/e7-r1.md`.

**Shared changes, each in its own commit:**
- `include(":stops")` in `settings.gradle.kts`.
- `:design` `compactLargeOnly` sheet option (13ee603).
- `:design`/`:shell` `TabSelection` on `RostyNavHost` (2dd9779), so the Stops Settings pages keep the full bar (F16).
- `:shell` `SpecialRowTexts` for `SpecialAccessRow` (94f7639, 232dfc9). Phone and Contacts are unchanged.
- Rust and bridge:
  - `rosty_geo::transit`, moved out of `apps/stops`; the GPUI app still builds.
  - New ops `stops.gtfs_view`, `stops.gtfs_lines`, `stops.gtfs_summary`, `stops.store_trip_clear`.
  - `eta_min` on the trip status.
  - S44 `SavedStop.note`.
  - Each comes with fixtures, OPS.md entries and `StopsOps` methods.
- `crates/rosty_app_core/tests/stops_kotlin_contract.rs` (E7.1 AC4).

**Gate on this branch:**
- Rust part of `scripts/kotlin-ui-check.sh`: ALL GREEN.
- Gradle tasks of the script: green. That covers `spotlessCheck`, `lintRelease`, `testReleaseUnitTest`, the phone flavours, `testRustBridge`, `assembleRelease`, `verifyRoborazziRelease`, `checkComposeStability`, the catalog APK check and orphan goldens.
- Gradle was run with a local `-I` init script that swaps Maven Central for Google's mirror. Central answered HTTP 429 here and Gradle then cached the misses; the script is not in the repository.

**Two notes for the main thread:**
- `scripts/kotlin-ui-check.sh` does not name `:messages`' flavoured Roborazzi task. `verifyRoborazziRelease` is ambiguous there, so `orphan-goldens.sh` reports a missing messages summary until `:messages:verifyRoborazziPlainRelease` runs.
- `e7.5-line-choice-in-stop-ui-dark` failed once in a whole-module run and passed twice alone. This is a possible timing flake; it is tracked for R2.

**What to test on a device:**
- The map: pan, pinch, fling, double-tap; tiles from `tiles.versatiles.org` are cached and survive a restart.
- Picking:
  - Tap A, then B; a third tap shows the hint.
  - Drag a pin, and move a second finger in to pinch.
  - Pin menu: copy as, QR, open in maps, save as stop.
- A trip:
  - The preflight rows.
  - A background location grant asked as a separate step.
  - The live card with the arrival clock.
  - The alarm over the lock screen (native `AlarmActivity`), plus the in-app alarm, snooze and dismiss.
- Saved stops: search, sort, reorder, note, home-screen shortcut.
- Settings → About → hold Version for 10 s: developer mode, then the Logs page.
- Turkish phone: Turkish texts. Texts coming from Rust are still English (R2).

**Next:** E7 R2 (E7.8–E7.14). The arriving-soon / WhatsApp parts go to a Fable subagent.
