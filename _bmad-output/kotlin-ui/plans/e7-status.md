# E7 Stops: status notes for the main thread (newest first)

## R2 closed: merge `stops-kotlin` at the commit that adds this note

**Base:** `ui-overhaul` bc047b6 merged (Messages R5, `rosty_core::wa_identity`, gateway host `rosty.erdemdev.tr`).

**What landed:** E7.8 pick sheets, E7.9 map tools (follow-me, my location, Layers / Map options, measure), E7.10
settings (alarm, map tiles, offline area worker, transit feed, WhatsApp arriving-soon message and per-trip switch),
E7.11 history / stats / backup, E7.12 saved stops to and from a file, E7.13 checklist entry and decision record,
E7.14 checklist record `docs/kotlin-ui/checklist-e7.md` and contact sheet `docs/kotlin-ui/screenshots/stops.png`.
Rust sentences now come in Turkish (optional `locale` on the stops ops). Both reported flakes fixed at the root
(`E75ShotTest` real-core timing; `SavedViewModelTest` list race, `data/Latest.kt`). Evidence: `plans/e7-r2.md`.

**Shared changes (own commits):** `:platform` `AlarmPreview`, `map_download` channel, Stops' `FOREGROUND_SERVICE_DATA_SYNC`
permission; `:core-bridge` `TilesOps.offlinePlan`, `StopsOps.storeGtfsRemove` / `noticeChats`, `locale` on requests;
`rosty_ffi` ops `tiles.offline_plan`, `stops.store_gtfs_remove`, `stops.notice_chats` (uses `wa_identity::display_name`),
`prefs_choices.default_templates`; `rosty_geo` / `rosty_app_core` `_in(lang, ..)` text producers; the trip service sends
the phone's locale with each fix.

**Gate:** `scripts/kotlin-ui-check.sh` Rust steps green; its Gradle steps re-run with the Central mirror init script
(Maven Central 429 on one jar) and green: spotless, lint, all unit tests, testRustBridge, assembleRelease, every
verifyRoborazzi, APK check, compose stability, orphan goldens none.

**What to test on a phone:** offline area download (notification, Cancel); arriving-soon message to a linked WhatsApp
chat; Me without location permission; alarm sound picker and volume test; file import/export (GPX/JSON, CSV, backup);
pan and pinch at 120 Hz for E7.13 AC2.

**Open:** see "Open items" in `docs/kotlin-ui/checklist-e7.md` (S43 units, GPUI rules with no Rust op, chosen line across
a restart, placing ends during a trip).

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

## Hand-over from r18-s1 (Stops NoticeViewModel ordering fix, from stash@{0})

Not applied by the UI session (E7 territory). In `NoticeViewModel` (load/apply of the saved notice), set the template field BEFORE publishing the new state, so a page or test that sees the new state finds the field settled. Original diff (paths mapped rovsti to rosty):

```diff
diff --git a/android/stops/src/main/kotlin/dev/rosty/stops/notice/NoticeViewModel.kt b/android/stops/src/main/kotlin/dev/rosty/stops/notice/NoticeViewModel.kt
index 1ebe6b4..860ee6d 100644
--- a/android/stops/src/main/kotlin/dev/rosty/stops/notice/NoticeViewModel.kt
+++ b/android/stops/src/main/kotlin/dev/rosty/stops/notice/NoticeViewModel.kt
@@ -188,6 +188,8 @@ class NoticeViewModel(
         // that is how a trip sends it.
         val saved = notice?.template?.takeIf { it.trim() !in before.defaults } ?: before.defaultTemplate
         val untouched = before.loading || templateField.text.toString() == before.saved
+        // The field first, then the state: whoever sees the new state (a page, a test) finds the field settled too.
+        if (untouched && templateField.text.toString() != saved) templateField.setTextAndPlaceCursorAtEnd(saved)
         mutable.value =
             before.copy(
                 loading = false,
@@ -197,7 +199,6 @@ class NoticeViewModel(
                 sendWhen = notice?.sendWhen ?: NoticeWhen.WithAlarm,
                 dirty = !untouched && templateField.text.toString() != saved,
             )
-        if (untouched && templateField.text.toString() != saved) templateField.setTextAndPlaceCursorAtEnd(saved)
         if (untouched) templateChanged(saved)
     }
 
```

- Round 19 step 3: goldens changed because of the `:design` `onAccent` fix (filled controls now read navy on the dark accent, ink on red/green fills); re-record Stops shots (`:stops:recordRoborazziRelease`) after merging.
