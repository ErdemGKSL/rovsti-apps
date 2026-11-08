# Round 02 plan: E1 design system, round R2 (stories E1.9-E1.16, plus R1 screenshot fixes)

Planner: PLAN agent, round 2. Base: `c46d5dc` (branch `ui-overhaul`). Epic file: `_bmad-output/kotlin-ui/epics.md`.
Previous plan: `_bmad-output/kotlin-ui/plans/round-01.md` (its "Rules for every step" still apply word for word and are
not repeated here, except where this file changes them).

## State found (verified, not trusted)

- `git log`: R1 landed as one commit per story: E1.1 `6cf6b37`, E1.2 `5905fcb`, E1.3 `e43ae0a`, E1.4 `7499f54`,
  E1.5 `8fe7a78`, E1.6 `f930a74`, E1.7 `4792edf`, E1.8 `7959075`, review fixes `c46d5dc`.
- Code checked against the R1 stories: every file named in the E1.1-E1.8 evidence lines exists under
  `android/design/src/main/kotlin/dev/rovsti/design/{theme,motion,press,haptics,glass,controls,list,icons,chrome,time}`,
  `android/testing`, `android/catalog`; 46 test classes under `src/test` (JVM and Robolectric, incl. `ContrastTest`,
  `SpringStateTest`, `PressNoLayoutShiftTest`, `GlassTierTest`, `LensPullTest`, `RimTwinTest`, `ListEndAboveBarTest`,
  `PuckBoundsTest`, `TabBarGestureTest`, `ScaffoldTest`, `ScaffoldWideTest`, `DeterminismTest`); 90 goldens in
  `android/catalog/screenshots/`. E1.1-E1.8 stay `done`; their evidence lines now name the commits instead of
  "uncommitted".
- **No overlay or text code exists**: no `overlay/` or `text/` package in `:design`. The only forward reference is
  `TODO(E1.10)` in `android/catalog/src/main/kotlin/dev/rovsti/catalog/LargeTitlePage.kt:67` (overflow anchor is stored
  but nothing opens).
- `:design` does **not** depend on `activity-compose` (only `:catalog` does, `android/catalog/build.gradle.kts:26`);
  the overlay host needs `BackHandler`/`PredictiveBackHandler` (present in `activity-compose-1.13.0`:
  `androidx/activity/compose/BackHandlerKt`, `PredictiveBackHandlerKt`).
- Compose 1.12.1 `BasicTextField` already owns a text toolbar with menu items
  (`foundation-android-1.12.1`, `BasicTextFieldKt$BasicTextField$textToolbarHandler...$menuItem$1..5`): the platform
  cut/copy/paste/select-all menu comes for free; E1.16 must not replace it.
- `git stash@{0}` is the deprecated GPUI layout WIP: leave it, do not pop it.
- Hard-dependency table: E1 R2 has no prerequisites. First not-done epic = **E1**, next round = **R2** = E1.9-E1.16
  (exactly 8 stories). Glisse sources for this round: `src/menu.tsx` (330 lines), `src/input.tsx` (62),
  `src/search-field.tsx` (20), `src/styles.css:571-651, 912-1083`.

## Screenshots of R1 re-judged (opened with Read this round)

| PNG | Seen | Verdict |
|---|---|---|
| `e1.8-title-expanded-light` | title 34 sp on the left, search/+ capsule and "..." disc level with the title line, 16 dp gutters, rows on a 72 dp text column | good |
| `e1.8-title-collapsed-dark` | inline "Contacts" centred, but rows "Contact 01/02" and the red avatar are still readable **through the top band** beside and under the inline title | **fix F1** |
| `e1.8-title-mid-scroll-light` | grey large title "Contacts" at the top **and** the inline "Contacts" below it, both at ~50 %: two titles at once reads as a glitch (iOS shows the inline title only once the large title is almost gone) | **fix F2** |
| `e1.7-tabbar-rest-light` | bar and puck inside the gutters, labels fit; but "Contact 10 / Row 10 of 50" and an avatar are **clearly legible through the Flat bar**, crossing the Keypad and Messages labels | **fix F1** |
| `e1.7-tabbar-drag-mid-dark` | puck between Messages and Contacts inside the bar, tint clip splits "Contacts" (spec), bar tugged ~4 dp right (spec: navigation-layout 4.2 "tugs up to 4 dp"); same see-through as above, "Row 10 of 50" under "Messages" | **fix F1** |

F1 and F2 are fixed in step 1 before any overlay work (overlays will sit on the same Flat glass).

## Rules changed or added for this round

- Package for overlays: `dev.rovsti.design.overlay`; text: `dev.rovsti.design.text` (ARCH 4.1). Skill snippets say
  `dev.rovsti.ui.overlay`: rename.
- `Popup(` and `Dialog(` stay forbidden (ARCH 4.5). Every overlay is an `OverlayEntry`.
- Overlay screenshots render in `:catalog` through a real `OverlayHost` placed as the last child of the page root, with
  `mainClock.autoAdvance = false` and `mainClock.advanceTimeBy(ms)` per frame. Frame shots use the names in the
  stories (`e1.10-menu-+40ms`, ...). Every overlay shot is light + dark; add `-rtl` and `-fs2` where the story lists it.
- Each step adds a catalog page (enum entry in `CatalogPage.kt`, route in `CatalogApp.kt`) and a `CatalogNavigationTest`
  line that opens it.
- `docs/kotlin-ui/api-notes.md`: add a row for every new API used (checked by `unzip -l`/`javap` against the cached
  1.12.1 / activity 1.13.0 jars): `MutableTransitionState`, `BackHandler`, `PredictiveBackHandler` (+ `BackEventCompat`
  progress), `SemanticsPropertyReceiver.paneTitle`, `isTraversalGroup`, `clearAndSetSemantics`, `onLongClick`,
  `CustomAccessibilityAction`, `LiveRegionMode.Polite`, `BlurEffect`, `AwaitPointerEventScope.withTimeoutOrNull`,
  `PointerEventPass.Initial/Main`, `NestedScrollConnection` + `NestedScrollSource.UserInput`, `TextFieldState`,
  `BasicTextField(state=...)`, `TextFieldDecorator`, `TextFieldLineLimits.MultiLine(maxHeightInLines=)`,
  `KeyboardActionHandler`/`onKeyboardAction`, `LocalTextSelectionColors`, `LocalTextToolbar`/`TextToolbar` (list the
  1.12 overloads), `performTextInputSelection`, `WindowInsets.ime`/`imePadding`, `FocusRequester`, `focusProperties`.

---

## Step 1 (R1 fixes F1, F2 + E1.9): Flat legibility, title hand-off, overlay host and placement

### 1a. F1: nothing readable through Flat chrome

Cause: `GlassSpec.FLAT_ALPHA = 0.85f` (`glass/GlassSpec.kt:56`) is used for every preset, and Flat has no blur, so 15 %
of high-contrast row text shows through; the collapsed top scrim peaks at 0.94 (`ScaffoldSpec.ScrimPeakCollapsed`,
`chrome/LargeTitleScaffold.kt:73`) and the screenshot still shows rows in the band.
- Replace the single constant with a per-preset `flatAlpha: Float` field on `GlassSpec`: `bar()` 0.96, `popover()` 0.97,
  `sheet()` 0.98, thumb unchanged 0.85 (thumbs sit over their own track). Keep `FLAT_ALPHA` as the thumb default.
- In `LargeTitleScaffold.TopScrim`, when the resolved tier is `GlassTier.Flat` (or reduce transparency), the scrim over
  the bar band (status bar + 52 dp) goes to **1.0** once collapsed, then falls along the existing curve below the band;
  Frost/Liquid keep 0.94 (they blur).
- Test (Robolectric, `:design`, new `FlatLegibilityTest`): draw the tab bar over a black/white 4 dp stripe backdrop,
  capture the bar region bitmap (`captureToImage()`), assert the luminance spread inside the bar (excluding label and
  puck pixels: sample a 6x6 dp patch between two cells) is < 0.05. Same for the collapsed top band in `ScaffoldTest`.
- Re-record and open `e1.7-tabbar-rest-*`, `e1.7-tabbar-drag-mid-*`, `e1.7-list-end-above-bar-*`,
  `e1.8-title-collapsed-*`: no row text may be readable under a label.

### 1b. F2: one title at a time

- New pure functions in `chrome/TitleCollapse.kt`: `inlineTitleAlpha(f) = smoothstep((f - 0.65) / 0.35)` and
  `largeTitleAlpha(f) = 1 - smoothstep(f / 0.65)`; use them at `LargeTitleScaffold.kt:206,252,310` instead of the raw
  fraction. JVM test: both are 0..1, monotone, and `inlineTitleAlpha(f) * largeTitleAlpha(f) == 0` for all f (never
  both visible). Update `ScaffoldTest`'s "strictly between 0 and 1 at half scroll" to probe at f = 0.8.
- Re-record and open `e1.8-title-mid-scroll-*`: exactly one title visible (large title partly faded, no inline title).

### 1c. E1.9 Overlay host and placement

Files (`android/design/src/main/kotlin/dev/rovsti/design/overlay/`, all ORIGINAL, no glisse header):
- `OverlayHost.kt`: `OverlayController` (`present(entry)`, `dismiss(entry)`, `dismissTop(): Boolean`, internal
  `entries: SnapshotStateList`, `removeFinished()`), `abstract class OverlayEntry(val modal: Boolean, val title: String,
  val dismissOnBack: Boolean = true)` with `state = MutableTransitionState(false)` and `@Composable abstract fun
  Content(host: OverlayScope)`, `LocalOverlay`, `OverlayHost(controller, content)` exactly as SKILL overlays 1 (page gets
  `clearAndSetSemantics {}` while any modal entry is up; each entry is a full-size box with `paneTitle` +
  `isTraversalGroup`; `BackHandler` dismisses only the top entry; finished entries removed via `snapshotFlow`).
  `OverlayScope` exposes `windowSize`, `safe: Rect` (window minus `WindowInsets.safeDrawing` and `ime`), `density`,
  `controller`.
- `Scrim.kt`: `OverlayScrim(progress: () -> Float, dark/light alpha, onTap)`; alpha read in `drawBehind`; eats all
  pointer input (`pointerInput { awaitEachGesture { ... consume } }`) so lists cannot scroll under an overlay.
- `Placement.kt`: `enum class Side`, `data class Placement`, `placePanel(anchor, panel, window, safe, gap, margin, rtl)`
  as SKILL overlays 2; `panelMaxHeight(safe, margin)`; `fun Modifier.captureAnchor(holder: AnchorHolder)` writes
  `boundsInWindow()` into a plain field (not state).
- Add `implementation(libs.activity.compose)` to `android/design/build.gradle.kts`.
- `:catalog`: wrap `CatalogApp`'s root in `OverlayHost` (the host's `BackHandler` is registered after the catalog's,
  so it wins while an entry is open). Page `OverlaysPage` ("Overlay host"): two buttons; first presents a modal test
  entry (a plain card), second presents a second entry on top.

Tests:
- JVM `PlacePanelTest`: anchor at each corner and the centre (below preferred; above when no room), panel taller than
  free space (clamped, `Side.Below`, top >= safe.top + margin), RTL mirrors the trailing alignment, landscape
  (891x411 dp), cutout (safe.left = 32 dp), IME open (safe.bottom raised by 300 dp flips to above), origin fractions in
  0..1.
- Robolectric `OverlayHostTest` (manual clock): back closes only the top entry, then the next; programmatic `dismiss`
  keeps the entry composed until its exit transition ends (assert node exists at +50 ms, gone after settle); page
  semantics are cleared while a modal entry is open and restored after; `paneTitle` is set on the entry; scrim
  consumes a swipe (an underlying `LazyColumn` does not scroll).
- Robolectric `TriggerBoundsTest` (AC3): trigger button bounds before `present()`, at +16 ms, +200 ms, after dismiss are
  identical.

Screenshots: `e1.9-host-two-entries-stacked-{light,dark}` (two cards, the second above the first, scrims compounded,
page behind dimmed). Plus the re-recorded R1 shots of 1a/1b.

Commit: `Kotlin UI (E1): Flat legibility, title hand-off, OverlayHost and placePanel`.

---

## Step 2 (E1.10 part 1): menu morph maths and the button-anchored menu

Files (`overlay/`):
- `MenuMorph.kt` (**glisse header** + "Rovsti extension: `upward`"): `MenuMorphFrame`, `menuMorphFrame(...)`,
  `menuResistance`, `menuPressScale` ported from `glisse/src/menu.tsx:31-124,187-195` as in SKILL overlays 4.1.
- `MenuModel.kt` (ORIGINAL): `sealed interface MenuItem { data class Action(label, icon: RovstiIcon?, destructive,
  enabled, checked: Boolean?, onClick); data object Separator }` as `ImmutableList`; `MenuMetrics` object (card 220 dp,
  radius 20, padding 6, row min 44, row padding 6/12, gap 10, row radius 14, separator 0.5 dp margin 7/11, disabled
  0.38) from SKILL overlays 4.2.
- `ButtonMenu.kt` (glisse header, rendering is ours): `ButtonMenuEntry(anchor: Rect, items, trailing, triggerGlyph)`
  : `OverlayEntry(modal = true, title = "Menu")`. One `Animatable` `t`; open `RovstiMotion.MenuMorphOpen` (600/46),
  close `MenuMorphClose` (900/57), retargeted (never restarted) on direction change. The card box has its final size
  from frame 1; surface drawn in `drawBehind` from the frame; content column in `graphicsLayer` (scale, origin, alpha,
  `BlurEffect(contentBlurPx)`, rounded clip while not settled); clip and effects cleared when `settled`. Glass:
  `GlassSpec.popover(...)` (Flat in tests). `placePanel` decides `upward` (Side.Above) and `trailing`. Exit keeps the
  entry composed until `t` reaches 0. Reduce motion: 100 ms fade only. Reduce transparency: opaque popover fill.
- `fun Modifier.menuTrigger(controller, items, ...)`: captures the anchor with `captureAnchor`, opens on **down**
  (SKILL overlays 4.3). Trigger keeps its size (press is the existing `GlassPress`).
- Wire `LargeTitlePage.kt:67` (`TODO(E1.10)`): overflow opens a `ButtonMenuEntry` at the anchor with the hidden actions
  plus "Select", "Sort by", a separator, "Delete all" (destructive). Remove the TODO.

Tests:
- JVM `MenuMorphTest` (SKILL overlays 9): t=0 surface rect == trigger rect (both `trailing`/`upward` combos), content
  alpha 0; t=1 surface == card, `settled`, content scale 1; for t in 0..1 step 0.05 the clip rect mapped back through
  `contentScale` lies inside the card; leaving fades content earlier than entering at the same t;
  `menuResistance` -> 7 dp asymptote and odd; `menuPressScale` <= 1.025.
- Robolectric `ButtonMenuTest`: tap the overflow opens the menu; trigger bounds unchanged; Escape/back closes;
  open below the trigger near the top, above it near the bottom; clamped inside safe + 12 dp at the right edge.

Screenshots (frames from the trigger "..." in the large-title page and a test page with triggers at the top, bottom and
right edge): `e1.10-menu-closed`, `e1.10-menu-+40ms`, `e1.10-menu-+100ms`, `e1.10-menu-open-below`,
`e1.10-menu-open-above`, `e1.10-menu-clamped-edge` (light/dark). Judge: at +40 ms a small glass blob grows from the
"..." with blurred items; at +100 ms near full size; settled card 220 dp, rows 44 dp on the 6 dp padding, destructive
row red, no clipping of the last row, card never under the status bar or past 12 dp from an edge.

Commit: `Kotlin UI (E1): button menu morph (glisse) and menu model`.

---

## Step 3 (E1.10 part 2): press-slide-release, highlight plate, haptics, keyboard

- `MenuPanel.kt` (glisse header for the plate and card-press parts from `menu.tsx:128-262`): one pointer observer
  over the panel (never consumes); row rects measured once after layout into a plain `FloatArray`; `hoveredRow(y)` pure
  hit-test; highlight plate slides with `RovstiMotion.menuPlate()` (100 ms, PlateEasing), first highlight appears in
  place; card follows the finger with `menuResistance`, press scale `menuPressScale` with `MenuCardPress`, release
  `MenuCardRelease`; pressed sheen 0.11 -> 0.025 over 120 ms.
- Slide from the trigger: `OverlayController.beginSlide(entry, positions: Flow<Offset>)`, fed by the trigger's
  `menuTrigger` modifier while the same finger stays down; lift over an enabled row = dismiss then run the action.
  No global pointer singleton.
- Haptics: `HapticEvent` row change (`SegmentTick`/`TextHandleMove` per `RovstiHaptics`), commit `Confirm` for
  destructive. Keyboard: arrow keys move the plate with wrap, Enter commits, Escape dismisses; first enabled row
  focused on open.
- Semantics: rows `Role.Button`, checkmark rows `Role.Checkbox` + `selected`, destructive word in the label, 48 dp
  touch target.

Tests: JVM `MenuHitTest` (row under y, separators never hit, disabled rows skipped). Robolectric `MenuSlideTest`:
down on trigger, move onto row 3, up -> row 3 action ran once, menu closed; move across 3 rows -> 3 haptic ticks
(`RecordingHaptics`); release outside -> nothing ran, menu stays open; disabled row never commits; keyboard path.

Screenshots: `e1.10-menu-row-pressed` (plate under row 2, card grown by press, light/dark), `e1.10-menu-open-below-rtl`.

Commit: `Kotlin UI (E1): menu press-slide-release, plate, haptics, keys`.

---

## Step 4 (E1.11): long-press context menu, simple and preview-card variants

Files (`overlay/`, ORIGINAL; numbers from SKILL overlays 3, written without opening Convx):
- `ContextMenuGesture.kt`: `AnchorHolder`, `PressRamp` (300 ms tween to 1, release spring 0.7/600),
  `Modifier.contextMenu(anchor, press, onTap, onOpen, onPreviewDrag, onPreviewRelease)` resolving Tap / Cancel /
  ChildTook / Long as SKILL 3.1 (Main pass before timeout, Initial pass and consume after opening);
  `semantics { onLongClick("Show actions") }` + `customActions` from the item list (AC3).
- `ContextMenuEntry.kt` (simple): scrim 0.28 dark / 0.14 light (`scrimIn`/`scrimOut`), lifted **preview composable**
  (caller supplies `preview: @Composable () -> Unit`, not the stateful row) at the anchor rect, scale 1 -> 1.05 (150 /
  120 ms); real row alpha 0 while open; panel progress spring 0.78/700 open, `menuExit()` close, alpha p/0.35, per-axis
  scale from anchor/panel size (min 0.08), origin = `Placement.origin`, `BlurEffect` 20 dp -> 0 while opening only;
  rows reuse `MenuPanel` (step 3). Haptic `LongPress` on open.
- `PreviewCardLayout.kt`: pure `previewCardLayout(item, window, safe, panelSize)` -> card rect, panel rect, alignment
  (card width `min(0.78 W, 340 dp)`, shrink to fit card + 8 + panel + 76 dp text, never below 140 dp, top status + 8,
  bottom nav + 16, right-half items right-aligned).
- `PreviewCardEntry.kt`: card lift `menuLift` (0.80/420), land `menuLand` (1/520) with the 30 % cross-fade, radius 22,
  panel X `menuGrowX` / Y `menuGrowY` (0.25 -> 1, 0.08 -> 1, alpha clamp(3p)); drag while held: rubber x 0.6, up 0.3,
  down 1:1, panel alpha `1 - drag/120 dp`; release dismisses if dragged down > 64 dp or > 40 % toward home, else
  `previewReturn` (0.75/500).
- Catalog page `ContextMenuPage`: a `LazyColumn` of `ListRow`s (each with a trailing "..." `GlassIconButton` to prove the
  child-click rule) + a contact card for the preview variant.

Tests: JVM `PreviewCardLayoutTest` (left/right half, near bottom, small window, card >= 140 dp). Robolectric
`ContextMenuGestureTest`: tap -> `onTap` only; hold 500 ms -> open + `LongPress` haptic; move 20 dp before timeout
inside a `LazyColumn` -> list scrolls, no menu; tap on the trailing "..." -> its click, no row tap, no menu; TalkBack
`performSemanticsAction(OnLongClick)` opens; trigger row bounds unchanged while open; drag the preview 80 dp down and
release -> dismissed.

Screenshots: `e1.11-context-row-open`, `e1.11-context-row-open-near-bottom` (panel above the lifted row),
`e1.11-context-preview-card`, `e1.11-context-preview-dragged` (+ panel fading), `e1.11-context-open-rtl`. Judge: lifted
row 5 % larger and exactly over its slot, scrim dims everything else, panel 8 dp from the row, within margins.

Commit: `Kotlin UI (E1): long-press context menu (simple, preview card)`.

---

## Step 5 (E1.12 + E1.14): popover with arbitrary content; alert, action sheet, confirm

Popover (`overlay/Popover.kt`, ORIGINAL; morph reuses the step 2 `menuMorphFrame` with the trigger glyph disabled, so
it carries the glisse header):
- `PopoverEntry(anchor, maxWidth = 320 dp, content: @Composable () -> Unit)`; `modal = true`, scrim transparent but
  input-eating; measured content height capped at `panelMaxHeight`, content in a
  `verticalScroll` when taller; arrowless; placed by `placePanel`.
- Catalog: a "Date" toolbar button opens a popover with a small form (two `RovstiText` rows + a segmented-like row of
  capsules; real text fields arrive in step 7, use plain rows here) and a "Long" button opens 30 rows.
- Tests: `PopoverTest` (opening never moves the page content: bounds of three page nodes equal before/after (AC2);
  tall content scrolls: `performScrollToNode` on the last row succeeds; placement above when anchored near the bottom).

Dialogs (`overlay/Alert.kt`, `ActionSheet.kt`, ORIGINAL; SKILL overlays 6):
- `AlertEntry(title, message, actions)`: 270 dp, min 110 dp, title headline + message footnote centred, buttons in a
  row (column if > 2 or any label wraps, decided by measuring), enter scale 1.2 -> 1 + fade with `RovstiMotion.appear`,
  exit 150 ms fade; scrim 0.4 dark / 0.2 light; outside tap does nothing; back dismisses only if a cancel action exists.
- `ActionSheetEntry(title?, actions, cancel)`: max 500 dp, 8 dp side padding, 56 dp rows, separate cancel group,
  bottom inset nav bar + 8 dp, enter translate 400 ms `(0.2833, 0.99, 0.31833, 0.99)`, exit 150 ms EaseIn.
- `confirmDestructive(...)` helper: action sheet with one red action; two-tap variant (`DestructiveTwoTap`: first tap
  turns the row into "Tap again to delete" for 3 s, second tap commits, `Confirm` haptic; `Reject` haptic on timeout).
- Tests: `AlertTest` (focus moves into the alert, page semantics cleared, `paneTitle`, back with/without cancel,
  exit plays for 150 ms), `ActionSheetTest` (two-tap commits only on the second tap within 3 s).

Screenshots: `e1.12-popover-below`, `e1.12-popover-above`, `e1.12-popover-tall-scrolling`, `e1.14-alert`,
`e1.14-alert-fs2`, `e1.14-action-sheet`, `e1.14-confirm-destructive` (light/dark).

Commit: `Kotlin UI (E1): popover, alert, action sheet, destructive confirm`.

---

## Step 6 (E1.13): sheets with detents, pushback, non-modal mode

Files (`overlay/Sheet.kt`, `SheetState.kt`, `SheetScroll.kt`, `PushBack.kt`, ORIGINAL; SKILL overlays 5, motion 9):
- `Detent` (`Peek(dp)`, `Medium` = 0.5 H, `Large` = H - (status + 24 dp), `Fixed(dp)`, `Fraction(f)`), pure
  `detentOffsets(height, detents, topInset)` and `sheetReleaseTarget(offset, velocity, offsets, height, dismissible)`
  (0.998 projection via `Fling`, nearest detent, dismiss when projected > lowest + 12 % H). `SheetState` as SKILL 5.1
  (one `Animatable`, `snapTo` undispatched during drag, rubber band above the tallest detent, `sheetSettle` 0.86/380,
  `sheetDismiss` 1/400 seeded with the release velocity, programmatic dismiss = the same exit).
- `SheetEntry(detents, modal, content)`: live-resize `Layout` reading `offset` in the layout phase for lazy content;
  grabber 36 x 5 dp, stretch `tanh(drag/320 dp)`; 28 dp top corners; scrim `0.32 * presentedFraction` in a layer
  lambda; nested scroll via `sheetScrollConnection` (verify `NestedScrollSource.UserInput` at 1.12.1); haptic tick on a
  new detent; `PredictiveBackHandler` previews 35 % of the travel with `1 - (1 - p)^2`, then dismisses or springs back;
  clear focus on close.
- `Modifier.sheetPushBack(progress: () -> Float, statusBarPx, cornerPx)` (0.91, 32 dp, dim 0.22, `pushBack` spring,
  counter in `OverlayController` keeps it until the last modal sheet closes). The host applies it to the page.
- Non-modal mode (Stops): `modal = false` -> no scrim, no pushback, page keeps input and semantics at peek/medium;
  scrim and modality only at large.
- Catalog page `SheetsPage`: modal sheet with a 40-row list; non-modal sheet over `StripedBackdrop` with a draggable
  dot behind it to prove background interaction.

Tests: JVM `SheetMathTest` (detent offsets ascending, release targets for slow/fast flings up and down, dismiss rule,
landscape height < 480 dp -> Large only). Robolectric `SheetGestureTest` (swipe to each detent with
`performTouchInput { swipe(...) }`, fling down dismisses, list at top hands the drag to the sheet, list scrolled keeps
the drag; stacked sheets keep pushback until the last closes; predictive back at 50 % shows the preview offset; non-modal
sheet lets a tap reach the page behind).

Screenshots: `e1.13-sheet-peek`, `e1.13-sheet-medium`, `e1.13-sheet-large`, `e1.13-sheet-pushback`,
`e1.13-sheet-nonmodal-over-stripes` (light/dark). Judge: grabber centred 5 dp from the top, page behind scaled with
rounded corners and dimmed, large detent leaves status + 24 dp, last list row reachable above the nav inset.

Commit: `Kotlin UI (E1): sheets with detents, pushback, predictive back`.

---

## Step 7 (E1.16): text field, search field, composer field

Files (`android/design/src/main/kotlin/dev/rovsti/design/text/`):
- `GlassTextField.kt` and `SearchField.kt` (**glisse header**, from `glisse/src/input.tsx:26-62`,
  `search-field.tsx:1-20`, `styles.css:571-651`) as SKILL navigation-layout 7: `BasicTextField(state: TextFieldState)`,
  44 dp min height, capsule fill animated in draw (`focusFill()` 200 ms), `TextFieldDecorator` row (leading glyph,
  placeholder, inner field, 32 dp clear button with `focusProperties { canFocus = false }` that keeps the caret),
  tapping anywhere on the pill focuses the field, `cursorBrush = SolidColor(accent)`.
  Search glyph and clear glyph as `RovstiIcons` vectors on the glisse 16-unit grid.
- `TextSelection.kt` (ORIGINAL): `RovstiTextSelectionColors` (handle = accent, background = accent 0.3) provided by
  `RovstiTheme` for the whole tree (AC4).
- `ComposerField.kt` (ORIGINAL): multiline `TextFieldLineLimits.MultiLine(1, 6)`, internal scroll after 6 lines, send
  button (enabled only with non-blank text) that keeps focus, `onKeyboardAction` for Send and hardware Enter
  (`onPreviewKeyEvent`: Enter sends, Shift+Enter inserts a newline), docks with `imePadding()` in a bottom container.
- Keep the platform text toolbar (Compose 1.12 `BasicTextField` already provides cut/copy/paste/select all). Do not
  provide `LocalTextToolbar` this round; write the decision in `api-notes.md`.
- Catalog page `TextFieldsPage`: name field, search field, composer at the bottom over a dummy message list.

Tests (Robolectric, manual clock, `:design`) AC3: tap at x of character 5 places the caret at 5
(`TextFieldState.selection`); long-press selects a word (`performTouchInput { longClick(...) }`);
`performTextInputSelection(TextRange(0, 4))`; paste via `performTextInput` and via the clipboard
(`LocalClipboard` set, then the paste semantics action); IME Search/Send runs `onSubmit` once; clear keeps focus and
empties the state; field stays above a simulated IME (set `WindowInsets` via `WindowInsetsCompat` in Robolectric or a
test-only `imeInsets` parameter; assert the composer's bottom <= window bottom - ime); font scale 2 does not clip
(text node height >= line height x lines); `ComposerField` grows to 6 lines then scrolls.

Screenshots: `e1.16-field-empty`, `e1.16-field-focused`, `e1.16-field-with-selection` (highlight + both handles),
`e1.16-field-selection-toolbar` (if Robolectric draws the floating toolbar; otherwise the test asserts the toolbar was
requested via the semantics `CopyText`/`PasteText` actions and the shot shows the selection), `e1.16-search-with-clear`,
`e1.16-composer-3-lines`, `e1.16-composer-ime-open` (light/dark; `e1.16-field-focused-fs2`).

Commit: `Kotlin UI (E1): GlassTextField, SearchField (glisse), ComposerField`.

---

## Step 8 (E1.15 + round close): toast and undo pill; R2 contact sheet and checklist

- `overlay/Toast.kt` (ORIGINAL; SKILL overlays 6): `ToastController` inside `OverlayController` (one visible, a new
  toast replaces the current one with its exit), dark capsule (label 0.92, page-coloured subheadline text, max 2 lines,
  padding 18/12, outer 16/8), placed above the **measured** tab bar from `LocalChromeMetrics` (or the nav inset + 8 dp
  without a bar), 3 s default timed by `LocalRovstiClock`-independent `delay` (manual clock in tests), swipe down or
  sideways to dismiss (`Fling` projection), `liveRegion = Polite`, not modal (page keeps input).
- `UndoPill`: toast with a tint action "Undo" and a countdown ring (draw-phase arc, 5 s); action runs once and dismisses.
- Catalog page `ToastsPage` with the tab bar present.
- Tests: `ToastTest` (auto dismiss at 3 s, replace semantics, swipe dismiss, undo runs once and not after timeout, toast
  bottom <= bar top - 8 dp, live region set).
- Screenshots: `e1.15-toast-above-bar`, `e1.15-undo-pill` (light/dark).
- Round close: run `references/checklist.md` sections A-F over every R2 component and record ticks/reasons in the
  commit body; full `bash scripts/kotlin-ui-check.sh` must print `ALL GREEN`; update `epics.md` (E1.9-E1.16 `done` with
  evidence lines; R3 is E1.17-E1.22).

Commit: `Kotlin UI (E1): toast and undo pill; R2 checklist`.

---

## Order and dependencies inside the round

1 (host, placement, fixes) -> 2 (morph, button menu) -> 3 (panel interactions; reused by 4) -> 4 (context menu) ->
5 (popover reuses the morph; dialogs) -> 6 (sheets) -> 7 (text fields; popover form may be upgraded to real fields
here) -> 8 (toast + close). Each step ends green and committed.
