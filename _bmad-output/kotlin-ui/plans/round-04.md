# Round 04 plan: E1 R4 = finish the Convx chrome port (E1.29 rest), screenshot fixes, then E1.18-E1.22 (close E1)

Planner: PLAN agent, round 4. Base: `d0ea763` (branch `ui-overhaul`; never push to `main`).
Epic file: `_bmad-output/kotlin-ui/epics.md`. Previous plans: `round-01.md`, `round-02.md`, `round-03.md` (Convx port),
`round-03-e1.17-e1.22.md` (its Steps 4-8 are the detail source for E1.18-E1.22; read them, but every "ORIGINAL" /
"never copy Convx" line there is void: port the Convx file named below instead).

Owner decisions in force (user message: "tamam okey projemizi gpl yap kodları çok yüksek benzerlikte taşı" = "OK, make
our project GPL and port the code with very high similarity"): Rovsti is GPL-3.0-or-later; Convx code is ported
**close to verbatim** with the original header plus `Ported from Convx (https://github.com/cosmictaserdev-creator/Convx),
GPL-3.0, <original path>`; files Convx vendored (Kyant0 backdrop/AndroidLiquidGlass/Capsule, compose-miuix-ui,
compose-floating-tab-bar, compose-hig) keep their Apache-2.0 attributions; glisse (MIT) stays portable with its header.
Round-03 "Rules for this round" (header format, package map, no forced flat in tests, real-path glass in goldens) apply
unchanged.

`$CX` = `/tmp/claude-0/-home-user-rovsti-apps/fec9d351-b8cd-5c7f-af54-f98d09255a4a/scratchpad/ref/Convx`,
`$CXK` = `$CX/app/src/main/kotlin/com/convx/music`, `D` = `android/design/src/main/kotlin/dev/rovsti/design`,
`DT` = `android/design/src/test/kotlin/dev/rovsti/design`, `CAT` = `android/catalog/src/{main,test}/kotlin/dev/rovsti/catalog`,
`SHOTS` = `android/catalog/screenshots`.

## State found (verified in code, not trusted)

- `git log`: R3 complete, `542e271` (E1.23) .. `d0ea763` (E1.29 + APK). Working tree clean. `stash@{0}` = deprecated
  GPUI layout WIP: leave it, never pop it.
- Ported and **used**: backdrop engine (`D/backdrop/**`, `D/shapes/**`), `D/glass/{GlassEffect,TopFadeBlur,RovstiGlass,
  GlassSettings}.kt`, `D/ios/foundation/*`, menus (`IosMenu`, `IosContextMenu`, `AppleContextMenu`, `OverlayMenu`; our
  `ButtonMenu`/`ContextMenuEntry` are facades), sheets (`SheetMath.kt` takes Convx numbers, `IosPageSheetEntry` +
  `presentPageSheet` used by `SheetsPage`), `IosToggle`/`IosSlider`/`SegmentedControl`, `IosSwipeableRow` (`SwipePage`).
  `GlassRealPathTest` (API 35, 16 cases), `GlassFallbackTest`, `NoFlatInTestsTest` exist.
- **E1.29 is not finished** (its own evidence says so, and grep confirms): `IosTabBar`, `IosNavigationBar`, `IosList`
  have **no caller** outside their own file; `IosButton` is used only by `SwipePage`. `D/chrome/RovstiTabBar.kt` (647
  lines, glisse geometry on `rovstiGlass`), `LargeTitleScaffold.kt` (326), `Toolbar.kt` (167), `controls/Buttons.kt`
  (252), `list/ListRow.kt` / `InsetGroupedSection.kt` are still our hand-written versions (only `ListRowChevron` draws
  `IosChevron`). `overlay/Popover.kt` still uses our `MenuMorph` + `panelGlass`.
- What Convx actually ships on device is not `ios/component/IosTabBar` (used only by `$CXK/ui/ios/IosCatalog.kt:396`)
  but `$CXK/ui/component/floatingtabbar/**` (11 files, ~3.3k lines: vendored compose-floating-tab-bar v1.0.1 by Elyes
  Mansour, Apache-2.0, plus Convx's LiquidBottomTabs puck, folding and minimise) and, for the top chrome,
  `$CXK/ui/component/{FrostedTopBar (326), ScreenChrome (301), NavigationTitle (150), ChromeScrim (67),
  GlassCircleButton (270)}.kt` + `$CXK/ui/utils/IosOverscroll.kt` (376). None of these is ported yet. They are the
  device-proven chrome; this round ports them and makes our chrome API facades over them.
- Orphan goldens: `SHOTS/e1.5-glass-{flat,frost,liquid}-over-stripes-*`, `e1.5-rim-cpu-twin-*` are produced by no test
  (the old engine and its tests were deleted in `2077536`). Roborazzi verify ignores them; they mislead reviewers.
- Catalog APK exists: `android/catalog/build/outputs/apk/debug/catalog-debug.apk` (34.8 MB, 10:36).
- Disk: 8.8 GB free, `~/.gradle` 6.5 GB. Two new dependency trees come this round (Navigation 3, Coil 3).
- No Convx navigation library to port: Convx uses `androidx.navigation` `NavHost` (`$CXK/AppNavHost.kt:50-98`). ARCH 5.1
  keeps Navigation 3 (app-owned back stack, per-tab stacks); we port Convx's **transition specs and freeze wiring**
  verbatim into `NavDisplay` specs. Our `RovstiMotion` already has Convx's push numbers (`PushMs = 400`, `PushEasing
  (0.32, 0.72, 0, 1)`, `PushParallax 0.30`, `PushDimAlpha 0.85`, `RovstiMotion.kt:97-134`).
- Convx has no photo viewer/zoom component (grep `detectTransformGestures|transformable` hits only
  `ui/screens/settings/diy/DiyEditorScreen.kt`). E1.20 stays our own code (it may follow `crates/rovsti_core` zoom
  behaviour). Convx does have `$CXK/ui/component/AlphabetScrollBar.kt` (523) + `$CX/app/src/test/kotlin/com/convx/music/
  ui/component/AlphabetScrollBarTest.kt` (82): E1.19's rail is a port.

## Screenshots of R3 re-judged (opened with Read this round)

- `e1.26-overlays-over-stripes-light`: real glass: chips and pills bend the stripes, the menu and popover are frosted
  with readable labels. Good. But at the menu's trigger (right side, ~730,880 in the 923-wide view) only a ghost blob
  with a tiny square dot is left. **F5.**
- `e1.27-menu-open-light`: menu panel, separator, destructive row fine. The trigger "..." at top left shows a stray
  ~8 dp grey square under its first dot while the menu is open; the bottom and right triggers do not. **F5** (same bug:
  a highlight/press layer of the trigger survives while it is hidden behind the menu morph).
- `e1.7-tabbar-rest-light`: glass capsule with lens puck on Keypad; refracted avatar colour at the puck edge is
  expected. Row "Contact 10" is doubled/smeared *through* the capsule while row 11 shows below it: the capsule does not
  frost enough (blur too low for text). Convx's bar (`floatingtabbar/FloatingTabBarBars.kt`) uses its own blur+vibrancy
  numbers: the port in Step 2 fixes this; judge again.
- `e1.8-title-collapsed-dark`: the inline title "Contacts" and the toolbar capsules sit directly over rows 01/02 which
  stay readable behind them (only a weak dark fade). Text over text. Convx puts `TopFadeBlur` (progressive blur + fade)
  behind `FrostedTopBar`. **F6**, fixed by the Step 3 port.
- `e1.28-page-sheet-light`: the sheet's demo row "Name / Weekend stops" floats at a 72 dp inset with no group card,
  while the paragraph above sits at 36 dp: two inset rhythms (owner complaint "inconsistent spacing"). **F7**: put the
  row in an `IosList` inset-grouped card (Step 3, catalog only).
- `e1.6-buttons-dark`: glass circle buttons and capsules fine; over-backdrop glass buttons bend stripes. OK.

## Rules for this round (in addition to round-03 rules)

- Facade rule: keep our public API (`RovstiTabBar`, `TabBarMinimizer`, `ChromeMetrics`, `LocalContentInsets`,
  `LargeTitleScaffold`, `Toolbar*`, `BackButton`, `GlassIconButton`/`CapsuleButton`/`TextButton` in `Buttons.kt`,
  `ListRow*`, `InsetGroupedSection`) so the catalog and future apps do not churn; the drawing and motion move into the
  ported Convx files. Where a Convx number differs from ours, Convx wins and the test is updated with a citation.
- Music specifics are removed, not stubbed: `PlayerMorph`, `LocalPlayerAwareWindowInsets` (-> our `LocalContentInsets`),
  `LocalAmbientBackgroundColor` (-> `RovstiTheme.colors.page`), `rememberPreference(...)` keys (-> parameters or
  `LocalGlassSettings`), `ios.compat.*` (-> `IosText`/`IosGlyph`/`RovstiIcon`, no Material3), `R.string` (-> parameters).
- Commit prefix: `Kotlin UI (E1 R4): ...` with the session attribution lines.
- Checks per step: `bash scripts/kotlin-ui-check.sh` prints `ALL GREEN` (record new goldens with `RECORD=1`, then verify
  without it). Disk: if `df -h .` shows < 8 GB before Steps 4 and 6, run `rm -rf android/*/build android/build
  target/debug/incremental` first.
- Every new or changed PNG is opened with Read and judged; what was seen goes into the epics.md Evidence line.

---

## Step 1 (R3 carry-over): screenshot fixes F5, F7 catalog part, orphan goldens

- **F5 stray square on the hidden trigger.** Find which layer of the trigger survives while the menu morph hides it
  (candidates: `ButtonMenu.kt` trigger alpha vs the `InteractiveHighlight` / `glassPress` point highlight in
  `D/press/GlassPress.kt` or `D/backdrop/catalog/utils/InteractiveHighlight.kt`, which keeps the last press position
  drawn at full alpha). Fix so the whole trigger (glass, glyph, highlight) hides together or the highlight resets on
  `PressInteraction.Release/Cancel`. Test: `ButtonMenuTest.triggerLeavesNoResidueWhileMenuIsOpen` (capture the trigger
  bounds with `captureToImage()` while open; every pixel equals the scrimmed page colour within tolerance 6).
- **Orphan goldens**: delete `SHOTS/e1.5-*`. Add `scripts/orphan-goldens.sh` (called by `kotlin-ui-check.sh` after the
  Gradle step, skipped when `SKIP_GRADLE`): list the images Roborazzi compared in this run (check what the 1.77.0 plugin
  writes, e.g. `android/*/build/test-results/roborazzi/results-summary.json` or `build/outputs/roborazzi`; verify the
  file before relying on it) and fail on any `screenshots/*.png` not in that list, printing the names.
- Screenshots re-recorded and OPENED: `e1.27-menu-open-{light,dark}`, `e1.26-overlays-over-stripes-{light,dark}`,
  `e1.10-menu-open-below-*`. Judge: no residue at the trigger, menu unchanged otherwise.

Commit: `Kotlin UI (E1 R4): fix trigger residue under open menus; prune orphan goldens`.

## Step 2 (E1.29 rest, part A): port Convx's floating tab bar; `RovstiTabBar` becomes a facade

Port close to verbatim into `D/chrome/floatingtabbar/` (package `dev.rovsti.design.chrome.floatingtabbar`):
`$CXK/ui/component/floatingtabbar/{FloatingTabBar, FloatingTabBarBars, FloatingTabBarModel, FloatingTabBarMotion,
FloatingTabBarTabs, FoldGeometry, FoldingTabBar, FoldingTabGroup, GooeyTransition, BarMotion, AccessoryHost}.kt`,
keeping the compose-floating-tab-bar Apache-2.0 header block and Convx's modification notes, plus our "Ported from Convx"
line. Helpers they import that are not ported yet go to `D/ios/foundation/ConvxUtils.kt` or their own file with the
original path: `elasticStretchLayerBlock` (`$CXK/ui/component/GlassCircleButton.kt:63`), `asPuckTint`, `isHueless`
(`$CXK/ui/theme/`), `additiveChrome` (already in `ConvxUtils`/`ModifierExt` port: reuse).
- `AccessoryHost` loses the mini player: it becomes a generic `accessory: (@Composable () -> Unit)?` slot above/inside
  the bar (the Phone in-call pill and the Stops trip pill will use it). Keep its shared-transition morph between inline
  and expanded bars.
- `RovstiTabBar(...)` keeps its signature and delegates: tabs -> `FloatingTabBarModel`, selection/drag -> the ported
  puck (`LiquidBottomTabs` press-drag, release snaps to nearest tab), minimise -> Convx's inline/expanded collapse driven
  by our `TabBarMinimizer`, fold -> `FoldingTabBar`. Keep `ChromeMetrics`, `LocalContentInsets` (bar height + accessory
  height feed the content inset), IME hides the bar, haptic on tab change. Glass via the root `LocalAppBackdrop` exactly as
  Convx (`rememberCombinedBackdrop` where the bar layers its own backdrop).
- glisse code in the old `RovstiTabBar.kt`/`TabBarGeometry.kt`/`SelectionPuck.kt` that is no longer called is deleted
  (keep the glisse header only on files that still contain glisse code).
- `android/THIRD_PARTY_NOTICES.md`: add compose-floating-tab-bar v1.0.1 (Elyes Mansour, Apache-2.0, URL from the header).
- Tests (existing must stay green, numbers updated with citations): `TabBarGestureTest`, `PuckBoundsTest`,
  `TabBarSemanticsTest`, `ListEndAboveBarTest`, `ImeHidesBarTest`, `GlassRealPathTest.tabBar/foldedTabBar`. New:
  `TabBarAccessoryTest` (accessory raises `LocalContentInsets.bottom` by its height; last list row stays above it).
  Port any Convx test for these files if one exists under `$CX/app/src/test/kotlin/com/convx/music/ui/component/`.
- Screenshots re-recorded and OPENED: `e1.7-tabbar-{rest,drag-mid,minimised,folded,fs2,360x640}-{light,dark}`,
  `e1.7-list-end-above-bar-*`, new `e1.29-tabbar-accessory-{light,dark}` (an "On call 02:14" pill in the slot).
  Judge: rows under the capsule are blurred (no legible doubled text, fix for the R3 finding), lens puck refracts,
  labels readable, last row above the bar, accessory aligned with the bar's width.

Commit: `Kotlin UI (E1 R4): port Convx floating tab bar; RovstiTabBar facade; accessory slot`.

## Step 3 (E1.29 rest, part B, closes E1.29): top chrome, glass buttons, list drawing, overscroll

Port close to verbatim: `$CXK/ui/component/{FrostedTopBar, ScreenChrome, NavigationTitle, ChromeScrim,
GlassCircleButton}.kt` -> `D/chrome/` (GlassCircleButton -> `D/controls/`), `$CXK/ui/utils/IosOverscroll.kt` ->
`D/motion/IosOverscroll.kt`, `$CXK/ui/utils/AppBar.kt` pieces they need. Replace music bits per the rules
(`AppBarHeight` constant -> `Sizes`, `LocalPageMetrics` -> our `Spacing`/width cap, `LocalAccentTextColor` ->
`RovstiTheme.colors.accent`).
- `LargeTitleScaffold` delegates to `ScreenLazyPage`/`ScreenScaffold` + `ScreenLargeTitle` + `FrostedTopBar` (inline
  title, `TopFadeBlur` behind it: fixes **F6**); `Toolbar` capsules use `IosToolbarGroup` (already ported in
  `IosNavigationBar.kt`) on `GlassCircleButton`; `BackButton` = `GlassCircleButton` with the chevron. Keep
  `TitleCollapse` maths only if Convx's collapse (`rememberIosNavigationBarCollapse` / FrostedTopBar) lacks it; else
  delete it and update `ScaffoldTest` numbers with citations.
- `Buttons.kt`: `GlassIconButton` -> `GlassCircleButton`; capsule/filled/text buttons -> `IosButton` styles
  (`IosButtonStyle`); keep 48 dp touch targets and `PressNoLayoutShiftTest`.
- `ListRow`/`InsetGroupedSection` draw through `IosList` (`IosList` section card, separator inset, row min height,
  `IosNavigationRow` chevron) keeping our slots (avatar, subtitle, trailing, switch).
- Lists in the catalog get `IosOverscroll` (rubber-band overscroll 0.55, `IosOverscroll.kt:47`) via one modifier in
  `LargeTitleScaffold`.
- **F7**: `SheetsPage` page-sheet demo puts its field row inside an `InsetGroupedSection` (catalog only).
- Tests: `ScaffoldTest`, `ScaffoldWideTest`, `GroupedSectionTest`, `ListRowSemanticsTest`, `PressNoLayoutShiftTest`,
  `GlassRealPathTest.largeTitle*/buttonsOverBackdrop` green. New `CollapsedBarLegibilityTest`: at collapsed state the
  region behind the inline title has luminance variance below the value measured for an unblurred row (proves the
  blur/fade), and the title contrast vs the bar is >= 4.5:1 (use `Contrast.kt`).
- Screenshots re-recorded and OPENED: `e1.8-*` (all 10 variants), `e1.6-{buttons,rows,grouped-section}*`,
  `e1.28-page-sheet-*`, new `e1.29-overscroll-top-{light,dark}` (list pulled 80 dp). Judge: no text-over-text under the
  collapsed bar, one 16 dp gutter rhythm, buttons unchanged in size, page sheet row in a card at the same inset.
- Close E1.29: epics.md evidence (files, the facade list, PNGs opened).

Commit: `Kotlin UI (E1 R4): port Convx top chrome, glass circle button, IosList drawing, overscroll; close E1.29`.

## Step 4 (E1.18): Navigation 3 scaffold with Convx push/pop specs and nav freeze

Follow `round-03-e1.17-e1.22.md` Step 4 (dependencies `navigation3 = "1.2.1"` runtime + ui, kotlinx-serialization;
`api-notes.md` rows checked with javap; `TabNavigator`, `RovstiNavHost`, `SharedBounds`, `ChromeSpec`/`chromeFor`,
`docs/kotlin-ui/shell-usage.md`, catalog converted to two tabs "Components"/"Demo" with avatar -> detail ->
sub-detail). Changes from that plan:
- `D/transition/NavTransitions.kt` ports `$CXK/AppNavHost.kt:55-98` verbatim as `NavDisplay` `transitionSpec` /
  `popTransitionSpec` / `predictivePopTransitionSpec`: enter `slideInHorizontally(push) { it }`; exit
  `slideOutHorizontally(push) { parallaxOffset(it) } + fadeOut(push, PushDimAlpha)`; pop enter
  `slideInHorizontally(push) { parallaxOffset(-it) }` (Convx: incoming stays opaque); pop exit
  `slideOutHorizontally(push) { it } + fadeOut(push)`; the morph-route branch (`isMorphRoute`) becomes our
  shared-bounds route predicate (`fadeOut(targetAlpha = 0.7f)` under a growing tile). Header: Convx + `AppNavHost.kt`.
  Tab switch: Convx `AppRootContent.kt:990` `fadeIn(tween(220)) togetherWith fadeOut(tween(160))`.
- Wire the already-ported `rememberNavTransitionFreeze(currentRoute, lastNavWasPop)` (`D/backdrop/backdrops/
  NavTransitionFreeze.kt:37`) into `AppBackdropState` from `RovstiNavHost`, exactly as `$CXK/AppBackdropState.kt:66-74`,
  so chrome glass freezes during a push instead of re-recording every frame.
- Shared bounds: `Motion.sharedArtwork()` 500 ms (`$CXK/ui/utils/Motion.kt:26-31`) for the large-avatar flight;
  port `$CXK/ui/utils/SharedArtwork.kt` helpers where they are not music-specific.
- Tests as in the old Step 4 (`TabNavigatorTest`, `ChromeSpecTest`, `NavTest` with manual clock and
  `dispatchOnBackStarted/Progressed`, overlay closes before the stack pops, reselect scrolls to top) plus
  `NavFreezeTest` (the backdrop reports frozen for the push window and unfrozen after).
- Screenshots OPENED: `e1.18-push-mid`, `e1.18-predictive-back-50pct`, `e1.18-shared-avatar-mid`, `e1.18-tab-switch-mid`
  light/dark. Judge: outgoing page dimmed to 0.85 and offset ~0.3 W, no blank band, avatar unclipped, tab bar per
  `chromeFor`, glass chrome shows a settled blur (no smeared stale frame).

Commit: `Kotlin UI (E1 R4): Navigation 3 scaffold with Convx push/pop, predictive back, nav freeze, shared bounds`.

## Step 5 (E1.19, closes it): alphabet rail from Convx (swipe half already in E1.29)

- Port `$CXK/ui/component/AlphabetScrollBar.kt` -> `D/list/AlphabetScrollBar.kt` close to verbatim (scrubbing with
  `scrollToItem`, letter bubble, haptic per letter; music sort keys -> a `sections: ImmutableList<Char>` +
  `indexOf(Char)` parameter) and its test `$CX/app/src/test/.../AlphabetScrollBarTest.kt` -> `DT/list/`. Add what
  Convx lacks: TalkBack adjustable semantics (`setProgress`, `stateDescription` = letter), hidden while the IME is up,
  stays above `LocalContentInsets.bottom`.
- `SwipeRow` facade name over `IosSwipeableRow` (`D/list/SwipeRow.kt`, our `SwipeAction` model) if apps need it; one
  open row per list (`SwipeRowGroup`), tap on an open row closes it; extend `SwipeRowTest`.
- Catalog `ListsPage`: 120 contacts A-Z with sticky headers, swipe actions (Pin; Mute, Delete + undo toast) and the rail.
- Tests: ported `AlphabetScrollBarTest`, Robolectric `AlphabetRailTest` (scrub A->M puts M's header first, one haptic per
  letter, TalkBack setProgress, rail above the tab bar), `SwipeRowTest` additions.
- Screenshots OPENED: `e1.19-alphabet-rail-scrubbing-{light,dark}`, `e1.19-lists-page-{light,dark}`; the swipe goldens
  stay `e1.29-swipe-*` (E1.19 AC "swipe" screenshots are those; note it in epics.md).

Commit: `Kotlin UI (E1 R4): alphabet rail ported from Convx, swipe row group; close E1.19`.

## Step 6 (E1.20): Coil 3 loader and PhotoViewer

As `round-03-e1.17-e1.22.md` Step 6 (Coil 3.6.3 `coil-compose` + `coil-core`, `media/RovstiImages.kt`,
`media/ZoomMath.kt` pure and tested, `media/PhotoViewer.kt` as an `OverlayEntry`, own pager, fake image loader in
`ShotTest` replacing `TODO(E1.20)` at `android/testing/src/main/kotlin/dev/rovsti/testing/ShotTest.kt:46`, catalog
`PhotosPage`). Differences: the viewer's chrome uses the ported `FrostedTopBar` + `GlassCircleButton` (Step 3) and
the bottom toolbar uses `IosToolbarGroup`; open/close uses the shared-bounds helper from Step 4 (500 ms
`sharedArtwork` spec) when inside `RovstiNavHost`; background dims with `1 - dismissProgress`. Convx has no viewer, so
this is Rovsti code (GPL, our header). Add Coil (Apache-2.0) to `THIRD_PARTY_NOTICES.md` as a dependency note.
Tests: `ZoomMathTest`, `PhotoViewerTest`, `ImageSlotTest`. Screenshots OPENED: `e1.20-viewer-{fit,zoomed,dismiss-drag,
paging}` (dark background in both themes; light variant proves the chrome tint), `e1.20-photos-grid-{light,dark}`.

Commit: `Kotlin UI (E1 R4): Coil loader, slot-sized images, PhotoViewer`.

## Step 7 (E1.21): performance harness and convention tests

As `round-03-e1.17-e1.22.md` Step 7 (`RecompositionCounter`, `RecompositionTest` for tab-bar drag, menu open, sheet
drag, slider drag, swipe drag; `ConventionTest` with positive/negative fixtures; `enableAccessibilityChecks` helper and
`screenTest {}`; `:design:checkComposeStability` in `kotlin-ui-check.sh`). Changes: the `Popup(`/`Dialog(` rule allows
only `D/overlay/**` (Convx files were moved onto `OverlayEntry`, so `D/ios/component` must have none: verify);
the Material3 rule also covers ported files (`ios.compat` was replaced); hot composables for the stability gate are
the Convx ones now used: `FloatingTabBar` tabs, `FrostedTopBar`, `IosList` rows, `IosSwipeableRow`, `IosMenu` rows,
`IosToggle`, `IosSlider`, `SegmentedControl`, `GlassTextField`, `AlphabetScrollBar`. Add a Convx-specific perf check
from `$CXK/ui/utils/Motion.kt:148` notes: while `NavTransitionFreeze` is frozen the root layer is not re-recorded
(`BackdropDebug` record count stays flat across the push window).

Commit: `Kotlin UI (E1 R4): recomposition counter, convention tests, a11y checks, stability gate`.

## Step 8 (E1.22, closes E1): catalog complete, contact sheet, design-system doc, checklist, APK

As `round-03-e1.17-e1.22.md` Step 8 (states pages per overlay, `CatalogNavigationTest` opens every page, `contactSheet`
JVM task -> `docs/kotlin-ui/screenshots/design.png` opened and judged as a whole, `docs/kotlin-ui/design-system.md`,
checklist A-H over the catalog with fixes). Changes: the licence pass checks both header kinds (`LicenceHeaderTest`
already enforces Convx/glisse headers; extend it to the files added this round) and that
`android/THIRD_PARTY_NOTICES.md` lists Convx, Kyant0 backdrop/AndroidLiquidGlass/Capsule, compose-miuix-ui,
compose-floating-tab-bar, Haze (if used), glisse, Navigation 3, Coil. Rebuild the catalog APK
(`android/catalog/build/outputs/apk/debug/catalog-debug.apk`, path and size in the evidence). epics.md: E1.18-E1.22
`done` with evidence; E1 marked done; next planner starts E2 R1 / E3 R1.

Commit: `Kotlin UI (E1 R4): catalog complete, design contact sheet, design-system doc, checklist, APK; close E1`.

---

## Order and dependencies

1 (fixes, orphan check) -> 2 (tab bar: the accessory slot and insets are used by every later page) -> 3 (top chrome and
buttons: the nav host and viewer use them) -> 4 (navigation: catalog moves onto it; Lists/Photos pages are added through
it) -> 5 (rail, lists page) -> 6 (images, viewer: shared bounds from 4) -> 7 (perf and conventions over everything) ->
8 (close E1 + APK). Each step ends with `bash scripts/kotlin-ui-check.sh` = `ALL GREEN` and its own commit.
