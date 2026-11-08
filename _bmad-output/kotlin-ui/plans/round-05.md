# Round 05 plan: E1 R5 = finish E1.22 (contact sheet, overlay-state goldens, checklist pass), fix the R4 screenshot findings, close E1

Planned 2026-11-08 at base `86c994f` (branch `ui-overhaul`). Epic: **E1 design system** (the first epic not done:
E1.22 is only partly delivered). After this round E1 is done and the next planner starts E2 R1 / E3 R1.

Owner request relayed for this workflow: "make our project GPL, port the code with very high similarity". That was
delivered in R3 (E1.23-E1.29, verified again below); this round keeps every rule from `round-03.md` and `round-04.md`
(Convx ported close to verbatim with its header plus `Ported from Convx (...), GPL-3.0, <path>`; glisse header for glisse
ports; `LicenceHeaderTest`; no Material3; facades keep our public API).

## State found (verified in code, not trusted)

- `git log`: R4 complete as commits `8bd4989`..`86c994f`. Working tree clean. `stash@{0}` is the deprecated GPUI layout
  WIP: leave it, never pop it.
- Relicensing (owner decision 1) holds: root `LICENSE` is GPL-3.0, `Cargo.toml:9` `license = "GPL-3.0-or-later"`,
  README "Licence" section (`README.md:84-91`), `CLAUDE.md:29-30` licence line, `android/THIRD_PARTY_NOTICES.md` lists
  glisse, Convx, Kyant0 backdrop/AndroidLiquidGlass/Capsule, compose-miuix-ui, compose-hig, compose-floating-tab-bar,
  Haze, Coil, Navigation 3, with the Apache-2.0 text.
- Glass (owner decision 2) holds: the Convx backdrop stack is the only engine; `GlassRealPathTest`,
  `GlassFallbackTest`, `NoFlatInTestsTest` exist. Catalog APK: `android/catalog/build/outputs/apk/debug/catalog-debug.apk`
  (37.5 MB, built 16:41 by `86c994f`).
- E1.18-E1.21: done, evidence lines in epics.md match the files (`design/transition/*`, `design/list/AlphabetScrollBar.kt`,
  `SwipeRow.kt`, `media/*`, `Recomposition.kt`, `ConventionTest`, `checkComposeStability`), and goldens `e1.18-*`,
  `e1.19-*`, `e1.20-*` exist in `android/catalog/screenshots/` (218 files).
- **E1.22 is only partly done** although `86c994f` says "close E1":
  - done: `catalog/.../OverlayStatesPage.kt` (one page that opens every overlay in every state from buttons),
    `CatalogPage.OverlayStates`, `CatalogNavigationTest` opens every page, `docs/kotlin-ui/design-system.md`,
    `ContactSheetTask` (`build-logic/convention/.../ContactSheetTask.kt`, task `:catalog:contactSheet`), notices, APK.
  - **missing**: `docs/kotlin-ui/screenshots/design.png` does not exist (the task was registered but never run or the
    output was not committed; `docs/kotlin-ui/screenshots/` is absent). No `e1.22-*` goldens of the states page. No
    written record of the checklist (SKILL `references/checklist.md` A-H) pass over the catalog; epics.md has no
    E1.22 evidence line and its status still says `todo`.
  - Status set to `in-progress` this round.

## Screenshots of R4 re-judged (opened with Read this round)

- `e1.7-tabbar-rest-light`: glass capsule with the lens puck on Keypad. "Contact 10" shows doubled and smeared through
  the capsule. Convx's default glass blur is 3 dp (`$CX/ui/component/GlassEffect.kt:57`, side panels 2 dp `:91`), and our
  port uses the same number (`design/glass/GlassEffect.kt:61`), so this is Convx (and iOS 26) parity: the labels on the
  bar stay legible. **No change**; written down in the checklist record as a judged deviation from "frosted".
- `e1.8-title-collapsed-dark`: rows under the bar are blurred and faded (F6 fix holds). But the inline title "Contacts"
  sits at x ~ 284 of 923 instead of the centre. Cause: `design/chrome/FrostedTopBar.kt:120-122,192` pads the title
  `start = titleSideReserve, end = titleEndReserve` (an R4 deviation, KDoc line 10) and `LargeTitleScaffold.kt:131` passes
  the toolbar width as the end reserve, so the centred text box is shifted left. Convx pads symmetrically,
  `.padding(horizontal = 96.dp)` (`$CX/ui/component/FrostedTopBar.kt:175-179`), which keeps the title on the screen centre
  like iOS. **F9**.
- `e1.19-lists-page-light`: sticky headers, white rows, rail A-Z # over the right edge, tab bar with puck on Contacts,
  the row under the bar fades. OK.
- `e1.27-menu-open-light`: F5 fixed (no stray square under the top-left trigger). Menu panel, separator, destructive row
  fine. OK.
- `e1.18-push-mid-light`: detail page 0.1 W from the left over the list shifted left; back button glass, tab bar kept.
  The outgoing list is barely dimmed (fade to 0.85 alpha on a light page), which is Convx's spec
  (`design/transition/NavTransitions.kt:75`, ported). OK, no change.
- `e1.20-viewer-fit-dark`: image fitted, "3 of 7" centred, back circle and glass toolbar with five actions. OK.

## Rules for this round

- Same as `round-04.md` "Rules for this round". Commit prefix `Kotlin UI (E1 R5): ...` with the session attribution lines.
- Checks per step: `bash scripts/kotlin-ui-check.sh` prints `ALL GREEN` (record new goldens with `RECORD=1`, then verify
  without it). Every new or changed PNG is opened with Read and judged; what was seen goes into the epics.md Evidence line.
- Disk: keep >= 6 GB free (`df -h .`, 9.7 GB at planning time); before Step 4 run `rm -rf android/*/build android/build`
  if it is lower than 8 GB.

---

## Step 1 (fix F9): centre the inline title like Convx

- `android/design/src/main/kotlin/dev/rovsti/design/chrome/FrostedTopBar.kt`: go back to Convx's symmetric reserve. Keep
  the two parameters for callers (`ScreenChrome.kt:196,277,442`, `LargeTitleScaffold.kt:131`) but apply
  `padding(horizontal = max(titleSideReserve, titleEndReserve))` so the text box is centred on the bar and a long title
  still truncates before the wider side. Update the KDoc deviation note (line 10) and `docs/kotlin-ui/api-notes.md`
  (the deviation is now only "reserve is max(leading, trailing) instead of a fixed 96 dp").
- Test (`:catalog`, extend `CollapsedBarLegibilityTest` or new `InlineTitleCentreTest`): with a back button and with a
  two-capsule toolbar (asymmetric sides), the `inline-title` node's text centre is within 1 dp of the window centre; a
  40-character title is truncated (`TextLayoutResult.hasVisualOverflow`) and its bounds do not overlap the
  `toolbar` capsules.
- Goldens re-recorded and opened: `e1.8-title-collapsed-{light,dark}`, `e1.8-title-mid-scroll-*`, `e1.8-title-360x640-*`,
  `e1.8-title-landscape-*`, `e1.18-push-mid-*` (title in the pushed page bar). Expect the title on the vertical centre line.
- Commit: `Kotlin UI (E1 R5): F9 centre the inline title (Convx symmetric reserve)`.

## Step 2 (E1.22 AC1): goldens of the overlay states page

- New `catalog/src/test/kotlin/dev/rovsti/catalog/E122ShotTest.kt` (extends `ShotTest`, `screenTest {}` so the
  accessibility checks run, `mainClock.autoAdvance = false` for the mid-motion shots). Drive `OverlayStatesPage` with its
  buttons, one golden per state, light and dark:
  `e1.22-states-page` (closed), `e1.22-states-menu-below`, `e1.22-states-menu-above`, `e1.22-states-popover`,
  `e1.22-states-context-preview`, `e1.22-states-sheet-medium`, `e1.22-states-sheet-large`, `e1.22-states-alert`,
  `e1.22-states-action-sheet`, `e1.22-states-toast`, `e1.22-states-undo`, `e1.22-states-photo-viewer`.
  If the page lacks a button for one of these, add it to `OverlayStatesPage.kt` (catalog code only).
- Open every PNG: each overlay sits above the page without moving it (owner complaint "buttons flow into the content"),
  glass panels show the page through them, no trigger residue (F5), toast and undo pill above the tab bar, the sheet's
  grabber and corner radius, the alert's buttons readable in dark. Any defect becomes a fix in this step or a written
  finding in Step 4.
- Commit: `Kotlin UI (E1 R5): E1.22 overlay states goldens`.

## Step 3 (E1.22 AC2): contact sheet

- Run `./gradlew :catalog:contactSheet` after Steps 1-2 so it tiles the current light goldens into
  `docs/kotlin-ui/screenshots/design.png`. Check `ContactSheetTask` keeps the file reasonable (<= 4 MB: if larger,
  downscale tiles to 360 px wide and write PNG; one caption per tile with the golden name). Exclude `-dark`, `-fs2`,
  `-rtl`, `-landscape`, `-360x640` variants so the sheet shows one tile per state.
- Add the task to `scripts/kotlin-ui-check.sh` as a separate, non-failing line only if it takes < 20 s; otherwise
  document `./gradlew :catalog:contactSheet` in `docs/kotlin-ui/design-system.md` (it is already mentioned) and in the
  E1.22 evidence.
- Open `design.png` with Read and judge it as a whole: one visual language (same gutter, same type scale, glass
  everywhere chrome floats, no wrapping chip rows, no overlapping avatar stacks, no oversized pills). Write what was seen.
- Commit the PNG: `Kotlin UI (E1 R5): E1.22 design contact sheet`.

## Step 4 (E1.22 AC3): checklist pass over the catalog, record and fixes

- Go through SKILL `references/checklist.md` sections A (licence), B (layout), C (motion), D (overlays), E (glass),
  F (controls and input), G (performance), H (tests and evidence) for every `CatalogPage`. Record the result in
  `docs/kotlin-ui/checklist-e1.md`: one table per section, each item `pass`, `fixed (<commit>)` or `deviation: <reason>`
  (for example the tab-bar blur parity from the screenshot review above, the light push dim).
- Fix the small findings in this step (catalog or `:design`, no new components); anything larger is written down with a
  story it belongs to (E3.11 `:shell`, E4.16 polish, ...).
- Tests touched by fixes must stay green; re-record and open any golden a fix changes.
- Commit: `Kotlin UI (E1 R5): E1.22 checklist pass over the catalog`.

## Step 5 (close E1): APK, epics, hand-off

- `bash scripts/kotlin-ui-check.sh` = `ALL GREEN`; rebuild the catalog APK (`./gradlew :catalog:assembleDebug`) and put its
  path and size (`android/catalog/build/outputs/apk/debug/catalog-debug.apk`) in the evidence.
- epics.md: E1.22 `done` with an Evidence line (commits, the `e1.22-*` PNGs and `design.png` opened and what was seen,
  `checklist-e1.md`); F9 noted under E1.8/E1.29; E1 header line "E1 done (R5)"; the round-plans paragraph points the next
  planner to E2 R1 and E3 R1 (E3 `:shell`/`:wa` need E1.1-E1.8 and E1.18, both done).
- Commit: `Kotlin UI (E1 R5): close E1, catalog APK`.

---

## Order and dependencies

1 (title fix changes goldens the sheet will show) -> 2 (states goldens feed the sheet) -> 3 (contact sheet from final
goldens) -> 4 (checklist uses the sheet and goldens; its fixes may re-record goldens; if so re-run step 3's task) ->
5 (close). Each step ends with `bash scripts/kotlin-ui-check.sh` = `ALL GREEN` and its own commit.
