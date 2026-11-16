# E7 checklist record (Stops, E7.14)

This record runs `.claude/skills/convx-motion-compose/references/checklist.md`, sections A to H, over every Stops screen
built in E7 (`android/stops`). The screens covered:

- **Map (E7.1 to E7.4):** the map home and its chrome (E7.1), the tile renderer (E7.2), the radar layers (E7.3), and
  picking A and B with the pin menu (E7.4).
- **Trip and alarm (E7.5, E7.6):** the trip with its preflight, live card and line choice (E7.5), and the in-app alarm
  with the last-trip card (E7.6).
- **Lists and sheets (E7.7, E7.8):** the Saved sheet and stop sheet (E7.7), and the pick sheets, Confirm sheet and stop
  sheet (E7.8).
- **Map tools (E7.9):** follow-me, My location, the layers and map options popovers, and the measure tool.
- **Settings (E7.10):** the Settings pages (alarm, map tiles, offline area, transit data) and the arriving-soon /
  WhatsApp pages.
- **History and files (E7.11, E7.12):** History with its statistics, export, backup and restore (E7.11), and the saved
  stops import / export (E7.12).

Each item is marked `pass`, `fixed` or `deviation: <reason>`.

Evidence:
- **Goldens:** `android/stops/screenshots/`, 74 shots, light and dark (148 PNGs). Each was opened when it landed; the
  R2 fs2 / Turkish sweep reopened them.
- **Approach notes:** `_bmad-output/kotlin-ui/plans/e7-r1-approach-{app,map}.md` and
  `e7-r2-approach-{app,settings,fable}.md`.
- **Device check (E7.13):** `docs/kotlin-ui/acceptance.md` ("The map at 120 Hz") and
  `docs/kotlin-ui/decisions/stops-map-renderer.md`.

## A. Licence and provenance

- **pass:** Stops holds no Convx or glisse code of its own. Glass, menus, sheets, toasts, the tab bar and the text fields
  come from `:design` / `:shell`, whose headers are checked there (`LicenceHeaderTest`). The Stops files are Rosty code
  (GPL-3.0-or-later). Parts that follow the GPUI app name it in their KDoc (`locate_me`, `measure_card`,
  `map_options_body`, `history_page`, `Preflight`).
- **pass:** no GPUI and no Material. The design `ConventionTest` (no `material3`, `Popup` / `Dialog`, `FlowRow`, raw `.dp`
  outside `Spacing.kt` / `Sizes.kt`, literal text or wall clock) passes on the module.
- **pass:** every rule lives in Rust and is reached through `StopsOps` / `TilesOps`: pick, snap, crossing, trip, alarm
  plan, saved, history, stats, measure, follow, coordinate formats, places and backup files, the notice, prefs edits,
  tiles and offline plan.
- **deviation: four GPUI app-side rules have no op.** Kotlin leaves a hook or chooses a plain Undo instead:
  - `looks_like_link` has the hook `PickRules.showRefusal`, which is off;
  - `clear_step` and the prune / clear two-tap confirms became Undo;
  - `history_field_visible`: the search field always shows.

## B. Layout

- **pass:** the map is full screen, edge to edge, under both system bars, and every control floats on top of it:
  - the search pill, the hint and chip row, and the measure card at the top;
  - the button column with locate, layers and map options;
  - the folded tab bar.

  The map sheet is non-modal: the map pans and takes taps at peek and medium (`HomeGestureTest`, `e7.1-home-idle`,
  `e7.1-home-sheet-peek`, `e7.1-home-sheet-medium`).
- **pass:** the bottom chrome rides the sheet up to the medium detent, then fades (`SheetRideTest`). The camera padding
  follows the settled detent, not the finger. Toasts rise above the riding block but not past the window middle.
- **pass:** in a wide window the chrome stays put beside the centred sheet (`e7.1-home-landscape`).
- **pass:** the lists clear the floating bar and the last row scrolls above it (`e7.7-saved-end-above-bar`).
- **pass:** filter chips are one non-wrapping row: the S37 nearest chip and History's All / Arrived / Cancelled
  (`e7.8-nearest-chip`, `e7.11-history-filter`).
- **pass:** spacing uses the tokens only. Stops sizes sit in `Sizes.kt` (`StopsSizes`) and the per-package `Spacing.kt`
  files (`ConventionTest`).
- **fixed (R2, font scale 2):**
  - The pick field now stacks above Paste instead of cutting its hint (`e7.8-pick-sheet-fs2`).
  - The map footer puts the attribution under the scale bar instead of clipping it (`e7.1-home-fs2`).
  - The measure card's buttons keep their widths, one under the other (`e7.9-measure-two-legs-fs2`).
  - History rows give the pair and its details more lines (`e7.11-history-fs2`).
- **pass:** the other fs2 shots show no clipping: `e7.9-my-location-sheet-fs2`, `e7.12-import-summary-fs2`,
  `e7.10-settings-fs2`, `e7.10-arriving-soon-fs2`.
- **pass:** Turkish fits on every `-tr` shot: home, pick sheet, measure card, history, import summary, settings and
  arriving-soon.
- **fixed (R2):** the measure card cut "Done" to "D…". Done now sits beside the summary and the edit buttons share the
  row (`e7.9-measure-two-legs`).
- **fixed (R2):** the map's first view depended on when the hint and chip row arrived, because the camera's top padding
  included them. It now clears the search pill only. Fable's held-answer test harness makes the timing deterministic.
- **deviation: the sheet subtitle is cut at the peek detent.** The detent shows the title and one line only (by design,
  ARCH 8.4).
- **deviation: the pin menu can cover its pin** when there is no room above or below (the design system's placement).
  Pin captions and letters use dp, not the font scale (`e7.4-pin-menu-open`).
- **deviation: dates in History rows follow the phone's locale through `DateUtils`.** Robolectric always writes English
  month names, so the Turkish goldens show "Sep".

## C. Motion

- **pass:** the springs come from `RostyMotion` / `:design`: sheets, menus, toasts, the tab bar, the glass press, the
  measure card's `appear`, and the pin lift and drag.
- **pass:** a press changes transform and alpha only. Capsules, glass circles and rows never shift the layout.
- **pass:** the map's pan, fling and pinch run on `MapCamera` with UIKit's deceleration. A pan or pinch recomposes
  nothing (`StopsMapRecompositionTest`, `MapGesturesTest`, `MapCameraTest`).
- **pass:** a dragged pin follows the finger without a Rust call per move. The circle follows a call or two behind
  (`PickGesturesTest`).
- **pass:** the glass freezes while the map moves (`MotionGate`) and thaws 120 ms after.
- **fixed (R2):** the measure card appeared abruptly. It now fades in and grows slightly from the search pill on the
  critically damped `appear` spring, with no overshoot.
- **deviation: no Frost / Liquid tier switch.** `MotionGate` exposes `frozen` only, and `:design` has no tier switch
  keyed on it for arbitrary chrome (R1 map note, AC3 covered up to the freeze).
- **deviation: the theme flip clears the tile bitmaps.** The radar plate shows for the moment the tiles repaint.

## D. Overlays

- **pass:** every popover, menu, sheet and toast is an overlay entry. There is no `Popup` or `Dialog`.
  - **Sheets:** the map sheet, Saved, the stop sheet, pick, Confirm, Copy as, My location, History, backup confirm,
    import, preflight, note and rename, and the QR sheet.
  - **Menus:** the pin menu, the layers and map options popovers, measure More, the history row and header menus, the
    saved row menu, sort, and the line choice.
  - **The in-app alarm** is a full `OverlayEntry` that Back does not close (`e7.6-alarm-in-app`).
- **pass:** menus open from an anchor rect captured at open, are placed and morphed by `:design`, and play their exit on
  a programmatic dismiss too. Evidence: `e7.4-pin-menu-open`, `e7.4-copy-as-menu`, `e7.9-map-options-popover`,
  `e7.9-measure-more-menu`, `e7.11-history-row-menu`, `e7.7-saved-row-menu`.
- **pass:** long press has a TalkBack equivalent. The Me button's `onLongClick` semantics are used by `MapToolsAppTest`
  and `E79ShotTest`.
- **pass:** undoable actions show the undo pill:
  - saved delete and save-as-stop;
  - history delete, prune, clear and restore;
  - measure clear;
  - places import;
  - alarm dismiss (Re-arm);
  - settings resets.
- **deviation: no press-slide-release from the Layers and Map options buttons.** These popovers open on tap.
  `menuTrigger` there would take over the glass button's own press, and the Me button beside them keeps its long press.

## E. Glass

- **pass:** glass is on chrome only: the search pill, hint, chips, button column, tab bar and measure card. Sheets,
  lists and rows use flat fills. Glass buttons inside sheets are flat (`OnSheet` clears the backdrop).
- **pass:** the chrome samples the map through `BackdropScope`. While the map moves the capture is frozen
  (`MapMotionEffect`).
- **fixed (R2):** the follow-mode tint did not read on dark glass (the additive white washed it out). The additive white
  is now off while following (`e7.9-follow-mode`).
- **pass, after the owner's rule:** blur, smear and tint nuances are not reviewed. The `:design` glass and tab-bar fix
  arrived with c010671, and the Stops goldens were re-recorded after it.

## F. Controls and input

- **pass:** the tab bar is `:design`'s `RostyTabBar` (folded on the map). Pushed Settings pages show the full bar with
  Settings selected (F16, `TabSelection`).
- **pass:** text fields are `BasicTextField(TextFieldState)` through `SearchField` / `GlassTextField`: the pick field,
  saved and history search, the note and rename fields, the tile URL and the notice message
  (`e7.10-template-field-selection`).
- **pass:** haptics go through `RostyHaptics`: a snapped pin, menu row changes (`:design`), the reorder drag and the
  pattern preview.
- **pass:** touch targets are at least 48 dp: the map buttons are 44 dp circles in 48 dp boxes, and the "..." targets
  and chips are 48 / 44 dp.
- **pass:** the Me button asks for the location permission through the `:platform` contract. Only a refusal shows the
  toast (`MapToolsAppTest`).
- **deviation: S43 units are not built.** There is no unit preference in Rust (settings approach).

## G. Performance

- **pass:** the map draws 16 to 25 cached bitmaps through one draw node. `TileLayerPainterTest` measures 0 bytes
  allocated over 500 panning and zooming frames.
- **pass:** rasterising runs off the main thread. A dense city tile rasterises in under 25 ms on the host
  (`TileRasterizerTest`, E7.13 AC1).
- **pass:** deferred reads: the sheet offset, ride and fade are read in layers. The radar is drawn from coarse immutable
  state, and the drag position is read in draw.
- **pass:** lazy lists have `key` and `contentType`: pick rows, saved, history and the import list. Models are immutable.
- **pass:** no Rust call on the main thread. The pick controller queues its calls. List work where the latest call wins
  runs through `data/Latest` (`LatestTest`).
- **deviation: frame times on the phone are not measured yet.** That is the owner-device check: `acceptance.md`, "The map
  at 120 Hz", p90 at most 8.3 ms. The MapLibre decision record stays "not opened" until it is run.

## H. Tests and evidence

- **JVM / Robolectric, map:** `MapCameraTest`, `MapCameraFixtureTest`, `MapGesturesTest`, `MapTileStoreTest`,
  `TileMathTest`, `TileRasterizerTest`, `TileLayerPainterTest`, `ByteLruTest`, `RadarPainterTest`, `RadarHitTestTest`,
  `PickControllerTest`, `PickGesturesTest`, `ScaleBarsTest`, `StopsMapRecompositionTest`.
- **JVM / Robolectric, home:** `HomeGestureTest`, `HomeMapLogicTest`, `HomeStateTest`, `SheetRideTest`,
  `LivePositionTest` (E7 R4: during a trip the dot follows the trip's fixes, its own updates only while following).
- **Location cadence (E7 R4, AD-SA10):** Rust `rosty_geo::cadence` tests (invariant sweep and seeded simulation, monotonic,
  never denser than the old curve, ladder, hysteresis, estimator, worked table), `session` cadence tests (a simulated trip
  always rings soon after the crossing, `no_fix`), `headless` `trip_no_fix`, fixtures `stops.cadence.*` and
  `headless.trip_no_fix`; `:platform` `TripServiceTest` (the request settings, `trip_no_fix` on a missing fix),
  `TripFixJsonTest` (the log line), `TripStatusTest`; `:core-bridge` `StopsOpsNativeTest` (`cadence`).
- **JVM / Robolectric, trip and alarm:** `TripViewModelTest`, `TripAppTest`, `PreflightTest`, `AlarmViewModelTest`,
  `AlarmAppTest`.
- **JVM / Robolectric, lists and tools:** `SavedViewModelTest`, `SavedAppTest`, `SavedLogicTest`,
  `PickSheetViewModelTest`, `PickAppTest`, `MeasureToolTest`, `MapToolsAppTest`, `HistoryViewModelTest`,
  `HistoryAppTest`, `BackupTest`, `PlacesTransferTest`, `LatestTest`.
- **JVM / Robolectric, settings and notice:** `SettingsViewModelTest`, `SettingsScreenTest`, `TilesViewModelTest`,
  `OfflineAreaWorkerTest`, `StopsStoreTest`, `StopsTilesTest`, `NoticeLogicTest`, `NoticeViewModelTest`,
  `TripNoticeTest`, `NoticeAppTest`.
- **JVM / Robolectric, app shell:** `StopsAppTest`, `StopsRoutesTest`, `StopsManifestTest`, `StopsMigrationTest`,
  `MainActivityLaunchTest`, `BootReceiverTest`, `TurkishCoverageTest`.
- **Shots:** `E71ShotTest` to `E79ShotTest`, `E710ShotTest`, `E710NoticeShotTest`, `E711ShotTest`, `E712ShotTest`.
  - Light and dark everywhere.
  - fs2 and Turkish where text matters.
  - Landscape for the home.
  - Every sheet at its detent, popovers open, and the end of the list above the bar.
- **pass:** the real-core tests are deterministic.
  - Fable's held answers (`Background.await` in `settleRust`) and the inline tile dispatcher fixed the R1 golden flake.
  - The latest-wins list fix (`Latest`) closed the `noPositionNoDistance` race.
  - Three whole-module runs and one run in a different package order were green (R2).
- **deviation: no RTL shots for Stops** (Stops ships English and Turkish only), and no keyboard-open shot of the pick
  field.
- **deviation: no device runs.** The SAF pickers (backup, import, GPX / CSV export), the alarm's full-screen intent and
  the offline worker's notification are tested on fakes and Robolectric. A phone runs them in E8.7.

## Open items (listed, not fixed here)

- **Rust ops for the GPUI app-side rules:** `looks_like_link`, `clear_step`, `history_field_visible` (section A).
- **S43 units:** a Rust field, an edit and the distance texts.
- **Pick placement and line choice:**
  - Placing ends is blocked while a trip runs (GPUI kept them for the next trip).
  - The chosen line is not kept across a restart (not in `trip.json`).
- **Map limits:** see `e7-r1-approach-map.md` "Known limits".
  - The data zoom follows the raster zoom.
  - `fit` does not handle the antimeridian.
  - The breadcrumb is thinned per frame.
  - The scale bar uses the centre's scale.
- **E7.13 AC2:** the owner-device frame-time check, then the decision record if it misses.
