# Round 03 plan: E1 design system, round R3 (stories E1.17-E1.22, plus two R2 screenshot fixes)

Planner: PLAN agent, round 3. Base: `d0dcf08` (branch `ui-overhaul`). Epic file: `_bmad-output/kotlin-ui/epics.md`.
Earlier plans: `round-01.md` (its "Rules for every step" still apply word for word), `round-02.md` (its "Rules changed
or added for this round" also still apply: overlay/text packages, no `Popup(`/`Dialog(`, overlay shots through a real
`OverlayHost` with the manual clock, one catalog page + one `CatalogNavigationTest` line per step, an `api-notes.md`
row for every new API).

## State found (verified, not trusted)

- `git log`: R2 landed as `d0f16a5` (R1 fixes F1/F2 + E1.9), `4269d3f` + `1aa277a` (E1.10), `03ca400` (E1.11),
  `0ba6814` (E1.12 + E1.14), `6bc19e9` (E1.13), `1898350` (E1.16), `f65b7bd` (E1.15, round close), `d0dcf08` (review
  fixes). Working tree clean. `stash@{0}` is the deprecated GPUI layout WIP: leave it, do not pop it.
- Code checked against the R2 stories: every file named in the E1.9-E1.16 evidence lines exists under
  `android/design/src/main/kotlin/dev/rovsti/design/{overlay,text}` (`OverlayHost`, `Scrim`, `Placement`, `MenuMorph`,
  `MenuModel`, `MenuPanel`, `ButtonMenu`, `ContextMenuGesture/Entry/Panel`, `PreviewCardEntry/Layout`, `Popover`,
  `SheetEntry/State/Math`, `PushBack`, `Alert`, `ActionSheet`, `Toast`, `ToastMath`; `GlassTextField`, `SearchField`,
  `ComposerField`, `TextSelection`). JVM tests `PlacePanelTest`, `MenuMorphTest`, `MenuHitTest`,
  `PreviewCardLayoutTest`, `SheetMathTest`, `ToastMathTest`, `TextSelectionTest`; Robolectric `OverlayHostTest`,
  `TriggerBoundsTest`, `ButtonMenuTest`, `MenuSlideTest`, `ContextMenuGestureTest`, `PopoverTest`, `SheetGestureTest`,
  `AlertTest`, `ActionSheetTest`, `ToastTest`, `TextFieldsTest`, `FlatLegibilityTest`. 166 goldens in
  `android/catalog/screenshots/` including every R2 name the stories list. E1.9-E1.16 stay `done`.
- **Not present yet** (R3 scope): no `controls/Switch.kt`, `Slider.kt`, `SegmentedControl.kt`; no navigation
  dependency or `transition` package; no swipe row or alphabet rail in `list/`; no `media` package, no Coil
  (`android/testing/src/main/kotlin/dev/rovsti/testing/ShotTest.kt:48` has `TODO(E1.20)` for the fake image fetcher);
  no recomposition counter or convention test; no `docs/kotlin-ui/design-system.md` or contact sheet.
- Ready to reuse: `SelectionPuck` (`chrome/SelectionPuck.kt`, glisse header, position/lift/velocity, projected
  release), `TabBarGeometry.kt` (`slotWidth`, `slotUnder`, `isTap`, `puckRect`), `Hold.kt` (hold rule),
  `RovstiMotion.Toggle` (1000/80) and `ThumbLift` (2000/80) (`motion/RovstiMotion.kt:42-43`), lens presets
  `SegmentLens`, `SwitchThumb`, `SliderThumb` (`glass/Lens.kt:39-43`), `GlassSpec` thumb preset with
  `LensOptions.SwitchThumb` (`glass/GlassSpec.kt:145`), `RovstiHaptics.toggle(on)` (`haptics/RovstiHaptics.kt:69`),
  `Fling`, `Rubber`, `.gated()`, `PredictiveBackHandler` already used by sheets, `rovsti_core::zoom` as the inventory
  for viewer maths (`crates/rovsti_core/src/zoom.rs:40-155`: `fit`, `pinch`, `pan`, `double_tap`, `settled`,
  `max_offset`, `dismiss_progress`, `should_dismiss`, `page_drag`).
- Compose reports already exist behind `-PcomposeReports`
  (`android/build-logic/convention/src/main/kotlin/ComposeConventionPlugin.kt:11-25`).
- Library versions checked against the repositories today (2026-11-08):
  - Navigation 3: `androidx.navigation3:navigation3-ui` / `navigation3-runtime` **1.2.1** (latest stable; 1.3.0 is
    alpha). Its POM needs compose `ui`/`animation`/`runtime` 1.10.0, `navigationevent-compose` 1.1.1,
    `savedstate-compose` 1.4.0: all satisfied by BOM 2026.09.00 (1.12.1). So Navigation 3 is usable; the ARCH 5.1
    fallback is not needed.
  - Coil 3: `io.coil-kt.coil3:coil-compose` **3.6.3** (Kotlin stdlib 2.4.10, below our 2.4.21).
  - Neither is in `~/.gradle/caches` yet (4.8 GB used); the first build downloads them.
- Hard-dependency table: E1 R3 has no prerequisites. First not-done epic = **E1**, next round = **R3** = E1.17-E1.22
  (6 stories) plus the R2 screenshot fixes. After this round E1 is done and E2/E3 may start.
- Glisse sources for this round: `src/switch.tsx` (226 lines), `src/slider.tsx` (128), `src/segmented-control.tsx`
  (24), `src/internal/segments.tsx` (534; already ported for the tab bar), `src/styles.css:653-903` (switch, slider),
  `src/styles.css:505-535` (segmented), tests `test/segmented-control.test.tsx` (5 tests). There is no switch/slider
  test file in glisse; the E1.17 AC2 gesture tests are derived from `switch.tsx:107-179` and `segments.tsx:175-339`.

## Screenshots of R2 re-judged (opened with Read this round)

| PNG | Seen | Verdict |
|---|---|---|
| `e1.13-sheet-medium-light` | page scaled to ~.91 with 32 dp corners on black, dimmed grey; sheet top at half height, grabber 36x5 centred, "Recents" + "Done" header, two-line rows on the gutter, last row cut by the window edge (scrolls) | good |
| `e1.13-sheet-pushback-dark` | only the top "Details" sheet is visible; the **first ("Recents") sheet is completely hidden** under it because both sit at the medium detent; nothing shows that a sheet is stacked. The page card edge is invisible on black (acceptable, iOS dark does the same) | **fix F4** |
| `e1.13-sheet-nonmodal-over-stripes-light` | peek sheet at the bottom, stripes and page buttons fully visible and undimmed, no pushback | good (demo copy "Nearby stops" lists "Ayla Demir": change the row text to a stop name in F4) |
| `e1.14-action-sheet-light` | 3 actions + separate Cancel, red destructive row, 56 dp rows; a **white haze from the Cancel group's shadow paints over the bottom of "Delete contact"** (the known issue in the E1.14 evidence) | **fix F3** |
| `e1.16-field-with-selection-light` | "back" highlighted in accent .3, both handles drawn in accent below the line, field filled; handles hang 12 dp below the pill (platform teardrop handles, expected on Android) | good |
| `e1.16-composer-ime-open-dark` | composer docked above the simulated keyboard area, send disc enabled in accent, text "Running late" readable | good |

---

## Step 1 (R2 fixes F3, F4): action-sheet shadow, visible sheet stacking

### F3: the Cancel group's shadow must not veil the actions group

- In `overlay/ActionSheet.kt`, find the shadow/elevation drawn for the groups (it is the glass surface shadow from
  `controls/Buttons.kt` `glassSurface` or a `shadow()` on each group). Draw the shadow **behind both groups as one
  layer** (draw both groups' shadows first, then both fills), or drop the Cancel group's upward shadow: iOS action
  sheets cast no visible shadow between the two groups. Simplest correct fix: shadow only on the union of both groups,
  drawn in a single `drawBehind` on the column that contains them, and no per-group shadow.
- Test (Robolectric, `ActionSheetTest`): capture the open action sheet (`captureToImage()`); sample a 4x4 dp patch in
  the last action row's background 6 dp above the group gap; its colour equals (within 2/255) a patch in the first
  action row's background. Fails today (haze).

### F4: a sheet presented over a sheet steps the lower sheet back

ORIGINAL design (no Convx/glisse evidence for stacked sheets; iOS behaviour): when a second modal sheet is presented
over a sheet, the lower sheet behaves like the page did: scale 0.94 around its top centre, translated up by 10 dp,
its own corners stay, a 0.12 dim over it; the page behind stays at its pushback (.91, dim .22) and is not pushed
further. The lower sheet's top edge then shows above the upper sheet when the upper one is at the same or a lower
detent. The upper sheet is placed so its top is at least 10 dp below the lower sheet's stepped-back top (clamp its
detent offset).
- `overlay/PushBack.kt` / `OverlayHost.kt`: extend the pushback registry to a stack depth per sheet entry
  (`depthAbove(entry): Int`, number of modal sheets above it). `SheetEntry` reads its depth in `graphicsLayer`
  (scale, translationY, dim layer alpha) animated with the same spring as the page pushback (`RovstiMotion` token
  used by `PushBack.kt`; add `SheetStepBack` only if no fitting token exists, with provenance comment "ORIGINAL").
- `SheetMath.kt`: pure `stackedTopPx(lowerTopPx, stepBackPx)` and `upperDetentOffset(...)` clamp; JVM tests.
- Catalog `SheetsPage.stacked()`: lower sheet opens at **Large**, the second at Medium, so the stack reads clearly.
  Non-modal demo rows: stop names ("Kadikoy Iskele", "Moda Caddesi", ...) instead of contact names.
- Tests: `SheetGestureTest` add "second sheet steps the first back" (lower sheet bounds scaled by ~0.94 via
  `SemanticsNode.boundsInRoot` width ratio, page scale unchanged at .91), "closing the top sheet restores the lower one
  to scale 1 after settle", predictive back on the top sheet does not move the lower sheet.
- Screenshots: re-record and OPEN `e1.13-sheet-pushback-{light,dark}` (lower sheet's top edge and grabber visible above
  the upper sheet, both dimmed differently, page card behind) and `e1.14-action-sheet-{light,dark}` (no haze over the
  last action row).

Commit: `Kotlin UI (E1): R2 fixes: action-sheet shadow, stacked sheets step back`.

---

## Step 2 (E1.17 part 1): Switch

Files (`android/design/src/main/kotlin/dev/rovsti/design/controls/`):
- `SwitchRules.kt` (**glisse header**; ported from `glisse/src/switch.tsx:25-179`, SKILL navigation-layout 5.1):
  `object SwitchRules { TRAVEL_DP = 57.9f * 0.375f; dragPosition(from, dxPx, travelPx) (rubber band /22 past either
  end); isScroll(dxDp, dyDp, held); startsDrag(dxDp) (>= 4 dp); onRelease(checked, moved, value, nearVertically)
  (drag lands where let go: value > 0.5; tap toggles only within 24 dp vertically); colourTarget(value) }`, plus
  `SwitchGeometry` (track 60 x 25.1 dp, thumb rest scale 0.65, held `0.65 + 0.25 * lift`, travel 21.71 dp, stand-in
  track sizes `(W-24)*(.85+.1*lift)`, `(H-12)*.75*(.525+.45*lift)`, face opacity `1 - 0.85 * lift`, hit area >= 44 dp
  tall and +8 dp each side, disabled alpha 0.35).
  Rovsti extension (marked in a comment): when the drag moved and the release speed is above 300 dp/s, the target
  comes from `Fling` projection of `value * travel` (SKILL 5.1 "release velocity decides when the drag is fast").
- `RovstiSwitch.kt` (glisse header for the drawing order; rendering is ours): `@Composable fun RovstiSwitch(checked:
  Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier, enabled = true)`. Three `Animatable`s: `position`
  (`RovstiMotion.Toggle`), `colour` (`Toggle`), `lift` (`ThumbLift`), all with visibility thresholds, retargeted not
  restarted. Semantics and keyboard through `Modifier.toggleable(value = checked, role = Role.Switch, ...)`; gestures
  in one `pointerInput` that consumes only after `held` (hold rule from `press/Hold.kt`) or `startsDrag`, so a vertical
  swipe on a list still scrolls. Track fill `RovstiTheme.colors` on-colour (glisse `tokens.on`) blended by `colour` in
  the draw phase; thumb = `GlassSpec` thumb preset (Flat tier in tests: white thumb 0.85 + rim). All animated values
  read in `graphicsLayer`/`drawBehind` only. Haptic `RovstiHaptics.toggle(on)` on commit (not on every drag frame).
  Reduce motion: `.gated()` springs, no lift growth.
- `ListRow` trailing slot: add a `ListRowTrailing.Switch(checked, onChange)` variant (or a documented example in the
  catalog) so a settings row's whole surface toggles the switch (TalkBack reads one node: label + state).

Tests:
- JVM `SwitchRulesTest`: rubber band (drag 2 travels -> 1 + 1/22), `isScroll` matrix (10 dp vertical scroll vs held),
  `startsDrag` at 3.9/4.0 dp, `onRelease` (tap inside 24 dp toggles, tap released 25 dp away does nothing, drag to
  0.6 lands on, drag to 0.4 lands off), fast flick from 0.3 with +600 dp/s lands on.
- Robolectric `SwitchTest` (manual clock, `RecordingHaptics`): tap toggles + one `ToggleOn` haptic; drag thumb across
  and release past half turns on; drag off the bar by > 24 dp vertically then release does nothing; hold blocks the
  parent `LazyColumn` from scrolling (list offset unchanged after a vertical drag that began with a 300 ms hold);
  a quick vertical swipe scrolls the list and does not toggle; `ACTION_CANCEL` returns to the old state with no
  haptic; interrupt: tap twice 60 ms apart ends unchecked with position animating back without a jump (value at
  +16 ms after the second tap within 0.1 of the value just before it); semantics `Role.Switch`, `ToggleableState`,
  touch target >= 48 dp; switch bounds unchanged while pressed (no layout shift).
- Screenshots (catalog page `ControlsPage`, "Controls"): `e1.17-switch-off-on` (off, on, disabled off, disabled on, in
  an inset-grouped section of settings rows), `e1.17-switch-pressed` (thumb held: lifted, clear face, stand-in track
  visible), light/dark. Judge: thumb vertically centred on the track, on-colour green-ish token, disabled visibly
  faded, held thumb wider than the track and not clipped by the row.

Commit: `Kotlin UI (E1): Switch (glisse)`.

---

## Step 3 (E1.17 part 2): Slider and segmented control; close E1.17

### Slider
- `SliderGeometry.kt` (**glisse header**, `glisse/src/slider.tsx`, `styles.css:770-903`, SKILL 5.2): height 40 dp;
  rail 6 dp at 21 dp from the top, inset 16 dp, radius 3; fill width `(W - 36dp) * p + 18dp`; thumb 60 x 40 dp drawn
  at 0.6 at rest, `0.6 + 0.4 * lift` held; thumb x `(W - 36dp) * p - 12dp`; touch target >= 36 x 40 dp (wrap to
  48 dp tall for TalkBack); pure `progressAt(xPx, widthPx)`, `valueFor(progress, range, steps)`.
- `RovstiSlider.kt`: `RovstiSlider(value: Float, onValueChange, modifier, valueRange = 0f..1f, steps = 0,
  onValueChangeFinished)`. Finger owns the value (`snapTo` undispatched); tap on the rail jumps there with the
  `Toggle` spring; lift `ThumbLift`; haptic `SegmentTick` per step when `steps > 0`, none for continuous sliders.
  Semantics `progressSemantics(value, range, steps)` + `setProgress`. Rovsti extension (ORIGINAL, written from the
  skill's description only): `elasticEnds = true` soft-caps a drag past an end with a sigmoid at 28 dp and releases
  with `spring(0.5, 1500)` (add the token `RovstiMotion.SliderOverflow` with provenance).
### Segmented control
- `SegmentedControl.kt` (**glisse header**, `segmented-control.tsx`, `segments.tsx:55-339`, `styles.css:505-511`):
  capsule 32 dp (track `tokens.segmentTrack`), labels 13 sp, thumb inset 2 dp (`tokens.segmentThumb`), held thumb
  fades and the `SegmentLens` lifts in its place 24 dp wider and 16 dp taller. Reuse `SelectionPuck` and
  `TabBarGeometry` (`slotWidth`, `slotUnder`, `isTap`) unchanged: generalise names only if needed, never fork the
  physics. Gesture rules from glisse: drag deadzone 6 px, "near" = 24 px (a release more than 24 px off the bar
  vertically selects nothing), release rounds to the projected slot. `Modifier.selectableGroup()`, each segment
  `Role.RadioButton` + `selected`; arrow keys / Home / End move the choice (glisse test "moves the choice with arrow
  keys"). Haptic `SegmentTick` once per committed change and per slot crossed while dragging.
- Catalog `ControlsPage`: slider (continuous and 5 steps), volume slider with elastic ends, segmented control with 3
  and with 4 segments ("Day / Week / Month", "All / Missed / Contacts / WhatsApp").

Tests:
- JVM `SliderGeometryTest` (fill ends under the thumb centre at 0 and 1, thumb x, `valueFor` with steps, sigmoid cap
  never exceeds 28 dp and is odd).
- Robolectric `SliderTest`: drag from 0.2 to the right end -> value 1.0 and `onValueChangeFinished` once; tap at 75 %
  of the rail -> settles at 0.75; steps snap and tick once per step; semantics `setProgress` works; bounds unchanged
  while held.
- Robolectric `SegmentedControlTest` (ports the intent of `glisse/test/segmented-control.test.tsx`): tap selects;
  drag from segment 0 to 2 selects 2 on release; drag released 30 px below the bar selects nothing; a 4 px drag is a
  tap; hold (300 ms) then vertical move does not scroll the parent list; cancel returns to the old segment; arrow keys
  move the choice with one tab stop; exactly one `selected` RadioButton; haptics per crossing.
- Screenshots: `e1.17-slider` (continuous at 0.3, stepped at 2/4, held thumb), `e1.17-segmented-3`,
  `e1.17-segmented-drag` (puck between segments 1 and 2 with lift and stretch, manual clock mid-drag), light/dark, plus
  `e1.17-segmented-3-fs2` (labels do not clip at font scale 2: the control grows in height, not truncates). Judge:
  fill ends under the thumb centre, thumb not clipped by the page gutter, segment labels optically centred, held
  lens larger than the capsule but inside the screen.
- Close E1.17 in `epics.md` with an Evidence line.

Commit: `Kotlin UI (E1): Slider and SegmentedControl (glisse)`.

---

## Step 4 (E1.18): Navigation 3 scaffold, per-tab stacks, push/pop, predictive back, shared bounds

Dependencies (`android/gradle/libs.versions.toml`): `navigation3 = "1.2.1"`, libraries `navigation3-runtime`,
`navigation3-ui`; `kotlinx-serialization-json` + the `org.jetbrains.kotlin.plugin.serialization` plugin (version =
kotlin) for typed routes. `:design` gets `api(libs.navigation3.ui)` only if public signatures expose Nav3 types;
otherwise `implementation`. Record in `api-notes.md` (javap against the downloaded aars): `NavDisplay` (parameters:
`backStack`, `onBack`, `entryDecorators`, `transitionSpec`, `popTransitionSpec`, `predictivePopTransitionSpec`,
`entryProvider`), `entryProvider { entry<T> { } }` DSL, `NavEntry`, `rememberSaveableStateHolderNavEntryDecorator`,
`NavDisplay`'s scene/`LocalNavAnimatedContentScope` (the `AnimatedContentScope` for shared bounds),
`SharedTransitionLayout`, `Modifier.sharedBounds`, `rememberSharedContentState`, `BoundsTransform`. If any name
differs at 1.2.1, use the real one and write the difference in the notes; do not guess.

Files (`android/design/src/main/kotlin/dev/rovsti/design/transition/`, ORIGINAL):
- `TabNavigator.kt`: pure Kotlin, no Compose types in its logic: `class TabNavigator<R : Any>(tabs: List<Tab>,
  roots: Map<Tab, R>)` holding one `SnapshotStateList<R>` per tab; `select(tab)` (switches; reselecting the current
  tab with a stack deeper than 1 pops to root, at depth 1 emits `ScrollToTop(tab)` through a `MutableSharedFlow
  (extraBufferCapacity = 1)` — never a sticky flag), `push(route)`, `pop(): Boolean` (false at root so the activity
  finishes), `replaceTop`, `resetTo(tab, routes)` (deep links: `routeFor(extras)` results), `current`, `stack(tab)`.
  Saveable via a `Saver` of route lists (routes are `@Serializable`).
- `NavTransitions.kt`: `RovstiNav.push`/`pop`/`predictivePop` `ContentTransform`s from SKILL navigation-layout 3:
  enter slide from full width; exit slide to `-PushParallax` (add `PushParallax = 0.3f` and `PushDimAlpha = 0.7f` to
  `RovstiMotion` with provenance comments if missing) with fade to the dim alpha; tweens (`RovstiMotion.push()`; add a
  350 ms `CriticalEasing` tween if missing) so predictive back can scrub. Tab switch = 200 ms crossfade, decided from
  initial/target route (a `isTabSwitch` lambda). Reduce motion: crossfade only, no parallax.
- `RovstiNavHost.kt`: `@Composable fun <R : Any> RovstiNavHost(navigator: TabNavigator<R>, chromeFor: (R) ->
  ChromeSpec, entries: EntryProviderBuilder<R>.() -> Unit)` wrapping `SharedTransitionLayout { NavDisplay(...) }`;
  exposes `LocalSharedScopes` (shared transition scope + the entry's animated-content scope) for `sharedBounds`.
  Back order: overlay host first (its `BackHandler` is registered later, so it wins), then the tab stack, then the
  activity (ARCH 5.1 reason 2). `ChromeSpec(tabBar: Visible|Folded|Hidden, largeTitle: String?)` data class.
- `SharedBounds.kt`: `Modifier.rovstiSharedBounds(key: Any)` reading `LocalSharedScopes`, `BoundsTransform` 350 ms
  critically damped (500 ms variant for large art), `renderInOverlayDuringTransition = true`, `zIndexInOverlay = 2f`;
  no-op when the scopes are absent (previews/tests outside the host). `isTransitionActive` exposed so heavy work can
  pause (SKILL motion 8).
- `docs/kotlin-ui/shell-usage.md` (new, short; AC4): how an app declares `sealed interface Route`, `chromeFor`
  (pure, JVM-tested), `routeFor(extras)`, and wires `RovstiNavHost` + `RovstiTabBar` + `OverlayHost` in that order.
  E3.11 `:shell` will move `RovstiRoot` there; this doc is the contract.
- `:catalog`: convert the catalog's own navigation (`CatalogApp.kt`) to `RovstiNavHost` with two tabs ("Components",
  "Demo") so the scaffold is exercised for real; "Demo" tab: a contact list (avatars) -> detail page with the avatar
  flying via `rovstiSharedBounds("avatar-$id")`, detail -> sub-detail push.

Tests:
- JVM `TabNavigatorTest`: per-tab stacks independent; push on tab A, switch to B, back to A restores A's stack;
  reselect at depth 3 pops to root; reselect at root emits exactly one `ScrollToTop`; `pop()` at root is false;
  `resetTo` for a deep link; saver round-trip.
- JVM `ChromeSpecTest` for the catalog's `chromeFor` (tab bar hidden on the sub-detail page).
- Robolectric `NavTest` (manual clock): push animates (at +175 ms the incoming page's left edge is between 0 and the
  width; the outgoing page is offset by about -0.3 W x progress); back pops; predictive back via
  `OnBackPressedDispatcher.dispatchOnBackStarted/Progressed(BackEventCompat(progress = 0.5))` leaves the pop at 50 %
  and cancel restores; tab switch is a crossfade (no horizontal offset); the overlay host closes a menu before the
  stack pops; reselect scrolls the list to item 0; shared avatar node's bounds at mid-flight lie between the row
  avatar and the detail avatar.
- `CatalogNavigationTest` still opens every page through the new host.
- Screenshots: `e1.18-push-mid` (+175 ms: incoming detail sliding over the dimmed, parallaxed list),
  `e1.18-predictive-back-50pct`, `e1.18-shared-avatar-mid` (avatar between row and header), light/dark. Judge: no
  blank band between pages, outgoing page dimmed not blacked out, avatar not clipped by either page, tab bar
  behaviour matches `chromeFor`.

Commit: `Kotlin UI (E1): Navigation 3 scaffold, TabNavigator, push/pop, predictive back, shared bounds`.

---

## Step 5 (E1.19): swipe-to-reveal row and alphabet rail

Files (`list/`, ORIGINAL; behaviour from SKILL motion 5.3 and navigation-layout 6):
- `SwipeMath.kt` (pure): `swipeTarget(offsetPx, velocityPx, leadingW, trailingW, rowW): SwipeOutcome`
  (`FullLeading`, `FullTrailing`, `OpenLeading`, `OpenTrailing`, `Closed`) using `Fling` projection, full swipe past
  55 % of the row width (checked before snapping), snaps `{-trailingW, 0, +leadingW}`; rubber band (`Rubber`) past the
  full-swipe extent; `crossedFull(prev, next, rowW)` for the haptic.
- `SwipeRow.kt`: `SwipeRow(leading: ImmutableList<SwipeAction>, trailing: ImmutableList<SwipeAction>, modifier,
  content)`; `SwipeAction(label, icon, tint, destructive, onClick)`; 76 dp per action (`Sizes.swipeAction`), actions
  drawn behind the row pinned to the edge, row translated in `graphicsLayer` only; horizontal drag claimed only after
  touch slop with |dx| > |dy| (vertical scroll wins otherwise); settle `RovstiMotion.SwipeSettle` (1.0/500, add if
  missing) seeded with release velocity; full swipe: the first action expands to fill, `HapticEvent`
  `GestureThresholdActivate` once on crossing (and once again if it un-crosses), commit runs the action once; a tap on
  the open row or a scroll of the list closes it; only one row open per list (`SwipeRowGroup` state holder).
  Accessibility: every action as `customActions`; the row's `onClick` unaffected. Reduce motion: no rubber band.
- `AlphabetRail.kt`: vertical rail of letters (only letters that have sections, `ImmutableList<Char>`), 13 sp tint,
  min 16 dp per letter, sits in the trailing gutter above content insets; scrubbing with one finger calls
  `listState.scrollToItem(sectionIndex)` (**never** `animateScrollToItem`, SKILL performance 3), haptic
  `SegmentTick` per letter change, a 64 dp letter bubble (popover-style surface, drawn in the rail's own layer, not
  an overlay entry) beside the finger; semantics: `Role` adjustable via `setProgress` + `stateDescription` = letter so
  TalkBack can step letters; hidden while the IME is up.
- Catalog page `ListsPage`: 120 contacts in A-Z sections with sticky headers, swipe actions (leading "Pin",
  trailing "Mute" and destructive "Delete" with undo toast), and the rail.

Tests:
- JVM `SwipeMathTest`: slow drag past half an action opens; small drag closes; fast flick from 30 dp opens; drag to
  60 % width = full; projected 70 % but released at 40 % = full (projection rule); rubber band monotone.
- Robolectric `SwipeRowTest`: reveal shows actions with 76 dp width each; full swipe runs Delete once and one
  threshold haptic; vertical swipe scrolls the list and leaves the row closed; opening row 2 closes row 1; tap on the
  open row closes without clicking it; custom actions run; row bounds (layout) never change during the swipe.
- Robolectric `AlphabetRailTest`: scrubbing from A to M scrolls so section M's header is the first visible item, one
  haptic per letter crossed, no `animateScrollToItem` (convention test in step 7 also greps), TalkBack `setProgress`
  jumps; rail does not overlap the last row above the tab bar.
- Screenshots: `e1.19-swipe-revealed` (trailing two actions open), `e1.19-swipe-full` (Delete expanded across the row
  at the threshold), `e1.19-alphabet-rail-scrubbing` (bubble "M" beside the finger, list at M), light/dark +
  `e1.19-swipe-revealed-rtl`. Judge: actions flush with the row edge, labels fit 76 dp at font scale 1, row content
  not overlapped by actions except where translated, rail letters evenly spaced and inside the safe area.

Commit: `Kotlin UI (E1): swipe-to-reveal row and alphabet rail`.

---

## Step 6 (E1.20): Coil 3 loader and PhotoViewer

Dependencies: `coil = "3.6.3"`, `coil-compose` (and `coil-core`; no network fetcher module: apps load `content://`
and file URIs; WhatsApp media come as bytes/files from `:wa`). Add `api-notes.md` rows: `ImageLoader.Builder`,
`MemoryCache.Builder.maxSizePercent`, `DiskCache`, `crossfade`, `AsyncImage` (painter placeholder), `ImageRequest
.Builder.size(...)`, `SingletonImageLoader.setSafe` or `LocalImageLoader`-equivalent at 3.6.3 (verify which one
exists), `Fetcher.Factory`/`FakeImage` test utilities (`coil-test` artifact if present at 3.6.3; else a hand-written
`Fetcher`).

Files:
- `media/RovstiImages.kt` (ORIGINAL; SKILL performance 4): `rovstiImageLoader(context, lowRam: Boolean)` (memory 25 %,
  15 % on low RAM, disk cache in `filesDir/images`, crossfade off by default, hardware bitmaps on),
  `rememberSlotRequest(model, sizeDp)` (size from `roundToPx` of the slot, guards size <= 0), `RowImage`
  (crossfade off) and `Avatar` photo path: `list/Avatar.kt` gains a photo model that falls back to initials on
  error/empty (no `SubcomposeAsyncImage`). `onTrimMemory(RUNNING_LOW)` hook documented for `:shell`.
- `media/ZoomMath.kt` (pure, ORIGINAL Kotlin re-implementation of the behaviour of `rovsti_core::zoom`, not a
  translation of its code): `fit(content, viewport)`, `ZoomState(scale, offset)`, `pinch(delta, focus)` (focus stays
  under the fingers), `pan` with bounds `maxOffset`, `doubleTap(focus)` (fit <-> 2.5x at the focus), `settled()`
  (rubber band back inside bounds, scale clamped 1..5 with a 0.8..6 rubber range while pinching),
  `dismissProgress(dy)`, `shouldDismiss(dy, velocity)` (> 25 % of height or projected past it), `pageDrag(dx, index,
  count)` (rubber at the first/last page).
- `media/PhotoViewer.kt`: an `OverlayEntry` (modal, title "Photo", black background with alpha = 1 - dismiss
  progress) holding a `HorizontalPager`-free pager of our own (one `Animatable` page offset, settle with `Fling`
  projection; pager libraries animate across pages, SKILL performance 3); per-page transform in `graphicsLayer`
  (scale, translation) driven by gestures in one `pointerInput` (`detectTransformGestures`-like loop written for
  pinch + pan + double tap + vertical dismiss, claiming the vertical dismiss only at scale 1); tap toggles chrome
  (top bar with "3 of 7" and close, bottom toolbar slots Save, Share, Forward, Edit, Remove passed as
  `ImmutableList<ViewerAction>`; chrome fades 200 ms); back / swipe down dismiss; shared-bounds entry from the
  thumbnail when opened inside `RovstiNavHost` (optional; otherwise a scale-fade from the anchor rect like the
  context-menu lift). Semantics: page title "Photo 3 of 7", custom actions for next/previous, zoom actions.
- `:testing`: replace `TODO(E1.20)` in `ShotTest.kt:48` with a fake image loader (deterministic coloured test images
  from `testing/src/main/res` or generated bitmaps) installed for every shot.
- Catalog page `PhotosPage`: a 3-column grid of 7 test images; tap opens the viewer.

Tests:
- JVM `ZoomMathTest`: fit for wide/tall/square, pinch keeps the focus point fixed, pan clamps at `maxOffset`,
  double tap toggles, settle returns into bounds, dismiss thresholds, page drag rubber at ends.
- Robolectric `PhotoViewerTest`: open from the grid (entry present, `paneTitle`), double tap zooms to 2.5 at the tap,
  pan stays in bounds, swipe down 30 % dismisses and the background alpha falls with the drag, short swipe returns,
  vertical swipe while zoomed pans instead of dismissing, horizontal fling pages to "4 of 7", tap hides chrome, Back
  closes, toolbar action callbacks run.
- Robolectric `ImageSlotTest`: a 44 dp avatar requests 44 dp x density px (fake loader records the size), size 0 does
  not crash, crossfade off in rows.
- Screenshots: `e1.20-viewer-fit`, `e1.20-viewer-zoomed`, `e1.20-viewer-dismiss-drag` (image down 120 dp, background
  ~50 %), `e1.20-viewer-paging` (two pages mid-swipe), light/dark (the viewer is dark in both; the dark variant proves
  the chrome), plus `e1.20-photos-grid`. Judge: image centred and letterboxed at fit, chrome inside safe area, "3 of 7"
  legible, toolbar icons evenly spaced, no white flash behind the image.

Commit: `Kotlin UI (E1): Coil loader, slot-sized images, PhotoViewer`.

---

## Step 7 (E1.21): performance harness and convention tests

- `android/testing/src/main/kotlin/dev/rovsti/testing/Recomposition.kt` (ORIGINAL, SKILL performance 10.2):
  `RecompositionCounter`, `@Composable CountRecompositions(tag, counter)` (SideEffect increment). To count inside
  `:design` composables without test hooks in production code, the test wraps child slots (labels, rows) with the
  counter, and for the bar/menu itself uses `Modifier.drawBehind` counters only where a slot exists; write down in
  `ShotTest` KDoc which composables are covered.
- Robolectric `RecompositionTest` (`:catalog`): (a) tab bar: drag the puck across 4 tabs over 1 s with the manual
  clock -> each tab cell label and the bar slot <= 2 recompositions; (b) menu: open the button menu and let it
  settle -> each row <= 2; (c) sheet drag medium -> large -> the sheet content <= 2; (d) slider drag 1 s -> label
  slot <= 2 (the value text may change: count only the static slots); (e) swipe row drag -> row content <= 2.
- `android/testing/src/test/.../ConventionTest.kt` (plain JVM, scans `android/*/src/main/kotlin` from the repo root
  located via a system property set in `build.gradle.kts`): fails on `import androidx.compose.material3`,
  `composed {`, `Popup(`/`Dialog(` outside `design/.../overlay`, `FlowRow` in files under `list/` or in chip code,
  `animateScrollToItem` in any rail/scrubber file, `Thread.sleep`, `System.currentTimeMillis()`/`Instant.now()`
  outside the injected clock file (`time/` package), and, **only for app modules** (`:contacts`, `:phone`,
  `:messages`, `:stops`, `:shell`; none exist yet, so the rule is tested with fixture source strings): raw `.dp`
  literals outside `Spacing`/`Sizes` objects and string literals passed to `Text(`/`contentDescription =`. Each rule
  has a positive and a negative fixture test so the scanner itself is verified. Existing `:design`/`:catalog` hits are
  fixed, not allow-listed (allow-list only with a one-line reason in the test).
- `ShotTest`: `enableAccessibilityChecks()` helper (`ComposeContentTestRule.enableAccessibilityChecks()` from
  ui-test 1.12; verify the name and its ATF dependency in `api-notes.md`) and a `screenTest {}` wrapper that later
  epics must use (AC2c); turn it on for the catalog page tests now and fix or suppress findings with written reasons.
- Compiler-report gate (AC3): a Gradle task `:design:checkComposeStability` (in `build-logic`) that runs after
  compilation with `-PcomposeReports`, parses `build/compose_reports/*-composables.txt`, and fails if any of the named
  hot composables is not `restartable skippable`: `RovstiTabBar` cells, `ListRow`, `Avatar`, `SwipeRow`, `MenuRows`,
  `RovstiSwitch`, `RovstiSlider`, `SegmentedControl`, `GlassTextField`. Fix instability with `@Immutable` models,
  `ImmutableList`, or `compose_stability.conf` entries. Add it to `scripts/kotlin-ui-check.sh` as one more Gradle
  task (keep the script's structure; `-PcomposeReports` only on that task's invocation).
- No screenshots.

Commit: `Kotlin UI (E1): recomposition counter, convention tests, a11y checks, stability gate`.

---

## Step 8 (E1.22 + round and epic close): catalog complete, contact sheet, design-system doc, checklist

- Catalog audit: every component and state of E1.1-E1.21 reachable from the catalog home; one "states" page per
  overlay (menu: closed/opening/open below/above/row pressed; context menu simple/preview; popover below/above/tall;
  sheet peek/medium/large/stacked/non-modal; alert/action sheet/confirm; toast/undo) driven by buttons, so a device
  user can see each state without timing. `CatalogNavigationTest` asserts every `CatalogPage` entry opens.
- Contact sheet tool: a small JVM task in `build-logic` (`contactSheet`, `javax.imageio`, no new dependency) that
  tiles the light goldens of a module (or a name filter) into `docs/kotlin-ui/screenshots/design.png` with captions,
  each tile downscaled so the file stays under ~2 MB. Open it with Read and judge it as a whole: consistent gutters,
  type scale, radii and tint across components; anything inconsistent is fixed in this step or written as a finding.
- `docs/kotlin-ui/design-system.md`: one page per package (theme, motion, press, haptics, glass, controls, list,
  chrome, overlay, text, transition, media): what to use, one call-site example each, the rules that matter
  (overlays via `LocalOverlay`, lists take `LocalContentInsets`, press is transform-only), and a pointer to the skill
  for numbers. No numbers duplicated from the skill.
- Checklist: run `.claude/skills/convx-motion-compose/references/checklist.md` sections A-H over the whole catalog;
  record ticks/reasons in the commit body; fix what fails. Licence pass: `grep -L` that every file which took glisse
  code carries the header (switch, slider, segmented, menu, popover, fields, tab bar, puck, lens, rim, tokens,
  motion), `android/THIRD_PARTY_NOTICES.md` still has glisse's full MIT text, add Coil (Apache-2.0) and Navigation 3
  (Apache-2.0) entries.
- Prune superseded goldens (none should be orphaned: a test checks every PNG in `screenshots/` is produced by some
  test, or simply list them and delete orphans) and keep each under ~150 KB.
- Full `bash scripts/kotlin-ui-check.sh` prints `ALL GREEN`. Update `epics.md`: E1.17-E1.22 `done` with Evidence
  lines naming the PNGs opened; the E1 section gets "E1 done" and the next planner starts E2 R1 / E3 R1 (they may
  overlap per the hard-dependency table).

Commit: `Kotlin UI (E1): catalog complete, design contact sheet, design-system doc, checklist; close E1`.

---

## Order and dependencies inside the round

1 (fixes) -> 2 (switch) -> 3 (slider, segmented; reuse the puck) -> 4 (navigation; the catalog moves onto it, so later
pages are added through `RovstiNavHost`) -> 5 (swipe row, rail: they live in the Demo/List pages) -> 6 (images and
viewer; avatars gain photos, used by the shared-bounds demo) -> 7 (perf harness and conventions run over everything
above) -> 8 (catalog complete and close). Each step ends with `bash scripts/kotlin-ui-check.sh` green (record goldens
with `RECORD=1`, then verify without it) and its own commit. Keep 6 GB free (`df -h .`): two new dependency trees
(Navigation 3, Coil) download in steps 4 and 6; clean `android/**/build` before them if space is below 8 GB.

## Licence notes for this round

- Glisse-derived (header required): `SwitchRules.kt`, `RovstiSwitch.kt`, `SliderGeometry.kt`, `RovstiSlider.kt`,
  `SegmentedControl.kt`.
- ORIGINAL (no header, no Convx code; numbers from the skill only): F4 sheet stacking, `TabNavigator`,
  `NavTransitions`, `RovstiNavHost`, `SharedBounds`, `SwipeMath`, `SwipeRow`, `AlphabetRail`, `RovstiImages`,
  `ZoomMath` (Kotlin re-implementation of our own Rust `rovsti_core::zoom` behaviour; Rovsti code, MIT/Apache),
  `PhotoViewer`, `Recomposition.kt`, `ConventionTest`, the stability gate, the contact-sheet task. Do not open Convx
  files while writing them.
- New third-party: Navigation 3 and Coil 3 (both Apache-2.0) as dependencies: NOTICE entries in step 8.
