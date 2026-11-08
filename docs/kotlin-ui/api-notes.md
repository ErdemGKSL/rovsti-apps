# Compose API notes (checked against the pinned 1.12.1 artifacts)

Compose BOM 2026.09.00 resolves every `androidx.compose.*` library used here to **1.12.1**. Each row below was
checked on 2026-11-08 by unpacking `classes.jar` from the cached aar
(`~/.gradle/caches/modules-2/files-2.1/<group>/<artifact>/1.12.1/<hash>/<file>.aar`) and running `javap -public` on the
named class. The "jar" column is the aar the class was found in (the aar's `classes.jar`). Re-run the check when the BOM
changes:

```
unzip -p <aar> classes.jar > /tmp/x.jar && javap -cp /tmp/x.jar -public <class> | grep <member>
```

| API | jar (artifact:version, file) | class checked | note |
|---|---|---|---|
| `spring(dampingRatio, stiffness, visibilityThreshold)` | animation-core-android:1.12.1, `animation-core.aar` | `androidx.compose.animation.core.AnimationSpecKt` | `spring(float, float, T)`; the threshold is the third argument and generic. There is no `SpringSpecKt`. |
| `Animatable.animateTo(target, animationSpec, initialVelocity, block)` | animation-core-android:1.12.1, `animation-core.aar` | `androidx.compose.animation.core.Animatable` | also `snapTo`, `stop`, `animateDecay`, `updateBounds`. Velocity hand-off is the third parameter. |
| `VelocityTracker` | ui-android:1.12.1, `ui.aar` | `androidx.compose.ui.input.pointer.util.VelocityTracker` | `addPosition(timeMillis, Offset)`, `calculateVelocity()`, `calculateVelocity(maximum: Velocity)`, `resetTracking()`. |
| `SuspendingPointerInputModifierNode` | ui-android:1.12.1, `ui.aar` | `androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode` | `pointerInputEventHandler` getter/setter (type `PointerInputEventHandler`) is a default method; the older `pointerInputHandler` still exists. `resetPointerInputHandler()` is abstract. |
| `ModifierNodeElement<N>` | ui-android:1.12.1, `ui.aar` | `androidx.compose.ui.node.ModifierNodeElement` | abstract `create()`, `update(N)`, plus `equals`/`hashCode` to implement. |
| `DelegatingNode` | ui-android:1.12.1, `ui.aar` | `androidx.compose.ui.node.DelegatingNode` | abstract class extending `Modifier.Node`; `delegate(...)` is a protected member. |
| `LayoutModifierNode` | ui-android:1.12.1, `ui.aar` | `androidx.compose.ui.node.LayoutModifierNode` | `measure(MeasureScope, Measurable, Constraints)` is abstract (mangled name `measure-3p2s80s`). |
| `Placeable.PlacementScope.placeWithLayer` | ui-android:1.12.1, `ui.aar` | `androidx.compose.ui.layout.Placeable$PlacementScope` | overloads: `(Placeable, x, y, zIndex, layerBlock: GraphicsLayerScope.() -> Unit)`, an `IntOffset` variant, and variants taking a `GraphicsLayer`. |
| `DrawModifierNode` | ui-android:1.12.1, `ui.aar` | `androidx.compose.ui.node.DrawModifierNode` | `draw(ContentDrawScope)` abstract; `onMeasureResultChanged()` default. |
| `GlobalPositionAwareModifierNode` | ui-android:1.12.1, `ui.aar` | `androidx.compose.ui.node.GlobalPositionAwareModifierNode` | `onGloballyPositioned(LayoutCoordinates)`. |
| `requireGraphicsContext()` | ui-android:1.12.1, `ui.aar` | `androidx.compose.ui.node.DelegatableNodeKt` | extension on `DelegatableNode`, returns `GraphicsContext`. |
| `GraphicsContext.createGraphicsLayer()` / `releaseGraphicsLayer()` | ui-graphics-android:1.12.1, `ui-graphics.aar` | `androidx.compose.ui.graphics.GraphicsContext` | also `getShadowContext()` default. |
| `GraphicsLayer.record(density, layoutDirection, size, block)` | ui-graphics-android:1.12.1, `ui-graphics.aar` | `androidx.compose.ui.graphics.layer.GraphicsLayer` | `record(Density, LayoutDirection, IntSize, DrawScope.() -> Unit)`; also `renderEffect` setter, `blendMode` setter, `toImageBitmap()` (suspend). |
| `RenderEffect` / `asComposeRenderEffect()` | ui-graphics-android:1.12.1, `ui-graphics.aar` | `androidx.compose.ui.graphics.RenderEffect`, `AndroidRenderEffect_androidKt` | `android.graphics.RenderEffect.asComposeRenderEffect()`; `RenderEffect.asAndroidRenderEffect()`; `isSupported()`. |
| `RuntimeShader` (platform) | `platforms/android-37.0/android.jar` (not a Compose jar) | `android.graphics.RuntimeShader` | `RuntimeShader(String agsl)`, `setFloatUniform(name, ...)` 1 to 4 floats or array, `setColorUniform`, `setInputBuffer`. API 33+, so always present at minSdk 34. |
| `ShaderBrush` | ui-graphics-android:1.12.1, `ui-graphics.aar` | `androidx.compose.ui.graphics.ShaderBrush` | abstract `createShader(size: Size): Shader`; has a `transform` property. |
| `Modifier.dropShadow` / `innerShadow` | ui-android:1.12.1, `ui.aar` | `androidx.compose.ui.draw.ShadowKt` | `dropShadow(Shape, Shadow)` and `dropShadow(Shape, DropShadowScope.() -> Unit)`. Support types `DropShadowScope`, `BlockDropShadowNode` are in the same jar. |
| `Modifier.shadow` | ui-android:1.12.1, `ui.aar` | `androidx.compose.ui.draw.ShadowKt` | `shadow(elevation, shape, clip)` and `shadow(elevation, shape, clip, ambientColor, spotColor)`. |
| `HapticFeedbackType` values | ui-android:1.12.1, `ui.aar` | `androidx.compose.ui.hapticfeedback.HapticFeedbackType$Companion` | `Confirm, ContextClick, GestureEnd, GestureThresholdActivate, KeyboardTap, LongPress, Reject, SegmentFrequentTick, SegmentTick, TextHandleMove, ToggleOff, ToggleOn, VirtualKey`. `HapticFeedback.performHapticFeedback(type)`. |
| `WindowInsets.safeDrawing`, `statusBars`, `navigationBars`, `systemBars`, `ime` | foundation-layout-android:1.12.1, `foundation-layout.aar` | `androidx.compose.foundation.layout.WindowInsets_androidKt` | `@Composable` getters on `WindowInsets.Companion`; also `*IgnoringVisibility`, `imeAnimationSource`, `imeAnimationTarget`. |
| `WindowInsets.isImeVisible` | foundation-layout-android:1.12.1, `foundation-layout.aar` | `androidx.compose.foundation.layout.WindowInsets_androidKt` | `@Composable` extension getter on `WindowInsets.Companion`. The class references `ExperimentalLayoutApi`, so opt in with `@OptIn(ExperimentalLayoutApi::class)`. |
| `WindowInsets.add`, `union`, `asPaddingValues`, `windowInsetsPadding`, `consumeWindowInsets` | foundation-layout-android:1.12.1, `foundation-layout.aar` | `WindowInsetsKt`, `WindowInsetsPaddingKt` | `asPaddingValues()` has a composable and a `Density` overload. Used by the catalog shell. |
| `NestedScrollConnection` | ui-android:1.12.1, `ui.aar` | `androidx.compose.ui.input.nestedscroll.NestedScrollConnection` | `onPreScroll(Offset, NestedScrollSource)`, `onPostScroll(consumed, available, source)`, suspend `onPreFling(Velocity)`, `onPostFling(consumed, available)`; `Modifier.nestedScroll(connection, dispatcher)` in `NestedScrollModifierKt`. |
| `LazyListState` | foundation-android:1.12.1, `foundation.aar` | `androidx.compose.foundation.lazy.LazyListState` | `isScrollInProgress`, `layoutInfo`, `scrollToItem(index, offset)`, `requestScrollToItem(index, offset)`; `firstVisibleItemScrollOffset` is a property getter on the same class. |
| `Modifier.selectableGroup()` | foundation-android:1.12.1, `foundation.aar` | `androidx.compose.foundation.selection.SelectableGroupKt` | |
| `Role.Tab` | ui-android:1.12.1, `ui.aar` | `androidx.compose.ui.semantics.Role$Companion` | `Button, Checkbox, Switch, RadioButton, Tab, Image, DropdownList, ValuePicker, Carousel`. |
| `enableAccessibilityChecks` | ui-test-android:1.12.1, `ui-test.aar` and ui-test-junit4-android:1.12.1 | `GlobalAssertions`, `ActionsKt`, `AndroidComposeTestRule` | **Not available as a public API in 1.12.1.** `GlobalAssertions` only holds deprecation messages saying it moved to `ComposeUiTest.enableAccessibilityChecks()` / `ComposeTestRule.enableAccessibilityChecks()`, but neither exists in these jars. What exists: `SemanticsNodeInteraction.tryPerformAccessibilityChecks()` (and the collection variant) in `ActionsKt`/`AndroidActions`, and a `@RestrictTo` `setComposeAccessibilityValidator(ComposeAccessibilityValidator)`. Plan: use `tryPerformAccessibilityChecks()` on key nodes; it does nothing without a validator, so touch-target and label rules are covered by our own convention tests until a later BOM ships the public switch. Revisit at every BOM bump. |

## Other facts recorded while building E1.1

- `spring()` lives in `AnimationSpecKt`, not a `SpringSpecKt` file.
- Compose compiler stability config files accept only `//` comments, not `#` (a `#` line fails compilation with
  `COMPOSE_CONFIGURATION_ERROR`).
- AGP 9.5 has built-in Kotlin: modules apply no `kotlin-android` plugin, only `org.jetbrains.kotlin.plugin.compose`.
- Maven Central answers HTTP 429 for some plugin marker poms; `build-logic/settings.gradle.kts` lists the Gradle plugin
  portal first in `pluginManagement` so the `kotlin-dsl` marker resolves.
- Lint (`NonObservableLocale`, an error under `abortOnError`) rejects `Locale.getDefault()` inside a composable. Use
  `androidx.compose.ui.platform.LocalLocale.current.platformLocale` (ui-android:1.12.1, `CompositionLocalsKt.getLocalLocale`,
  type `androidx.compose.ui.text.intl.Locale`).

## Renderer probe (E1.5): can Robolectric run `RenderEffect` and `RuntimeShader`?

`catalog/src/test/.../RuntimeShaderProbeTest.kt` renders a 40 x 40 dp box (left half red, right half blue) with and
without an effect under Robolectric native graphics (sdk 35) and compares the captured pixels. Result, 2026-11-08:

- `RenderEffect.createBlurEffect`: EFFECT APPLIED (3120 of 14400 px differ; seam pixel 0xff0000ff -> 0xff760089).
- AGSL `RuntimeShader` via `createRuntimeShaderEffect` (green fill): EFFECT APPLIED (all 14400 px; 0xff0000ff -> 0xff00ff00).
- `GlassLensShader.effect` (the real lens): EFFECT APPLIED (380 px differ, at the rim band as expected).

So Frost and Liquid screenshots are real: `e1.5-glass-frost-over-stripes-*` and `e1.5-glass-liquid-over-stripes-*`.
Normal tests still run Flat (`ShotTest` sets `GlassConditions.forTests = true`); only the glass page tests opt in.
Not checkable on the JVM and left as a device check (E8.7): the replay staying live while a list scrolls under glass,
inverse-press sampling on a pressed button (code path and `replayScale` are unit tested), frame cost.

Other facts from E1.5: `Modifier.dropShadow(Shape, Shadow(radius, color, offset=DpOffset))` renders under Robolectric and is
used for the glass shadow; AGSL has no `tanh` (written out); `Offset.isSpecified` is an extension in
`androidx.compose.ui.geometry`. Flat and Frost draw no rim shader (glass.md 6); the Liquid rim shader paints the 0.5 px edge
line itself, so the hairline is drawn only when no rim is.

## E1.9 and R2 step 1: APIs added (all ORIGINAL, package `dev.rovsti.design`)

- `glass/GlassSpec`: new field `flatAlpha: Float` (default `FLAT_ALPHA` 0.85); constants `FLAT_ALPHA_BAR` 0.96,
  `FLAT_ALPHA_POPOVER` 0.97, `FLAT_ALPHA_SHEET` 0.98; `bar()`, `popover()`, `sheet()` set theirs, `thumb()` keeps 0.85.
  `GlassNode` draws the Flat base at `spec.flatAlpha`.
- `chrome/FrostedTopBar.kt` (F9, reviewed R5): the inline title is laid out by `titleSpan`: centred on the bar when it fits between
  the larger of `titleSideReserve`/`titleEndReserve` on both sides (Convx's symmetric `padding(horizontal = 96.dp)`), otherwise
  shifted into the free span between the two reserves. The review found the plain symmetric padding truncated "Contacts" to
  "Con..." beside a two-capsule toolbar although the span had room.
- `chrome/TitleCollapse.kt`: `inlineTitleAlpha(f)` = smoothstep((f - 0.65) / 0.35), `largeTitleAlpha(f)` = 1 - smoothstep(f / 0.65);
  their product is 0 for every f. `topScrimStops(barFraction, peak, bandPeak = peak)` gained `bandPeak` (the share over the bar band).
- `overlay/OverlayHost.kt`: `OverlayController` (`present`, `dismiss`, `dismissTop(): Boolean`, `hasEntries`, internal
  `entries`, `removeFinished()`); `OverlayEntry(modal, title, dismissOnBack = true)` with `state: MutableTransitionState<Boolean>` and
  `@Composable Content(host: OverlayScope)`; `OverlayScope(windowSize: Size, safe: Rect, density, controller)`;
  `LocalOverlay`; `OverlayHost(controller, modifier, content)`. `safe` = window minus `WindowInsets.safeDrawing` (which already
  includes the IME). The host's `BackHandler` is keyed on the entry count so it re-registers last and wins over page handlers.
  An entry with `dismissOnBack = false` swallows Back.
- `overlay/Scrim.kt`: `OverlayScrim(progress: () -> Float, modifier, lightAlpha = 0.20, darkAlpha = 0.40, onTap)`; alpha read in `drawBehind`,
  eats all pointer input, a press that ends within touch slop is a tap. `ScrimAlpha` constants.
- `overlay/Placement.kt`: `Side`, `Placement(topLeft, side, origin)`, `placePanel(anchor, panel, window, safe, gap, margin, rtl)`
  (neither side fits: clamped below so the panel's bottom meets the margin, or its top if taller), `panelMaxHeight(safe, margin)`,
  `AnchorHolder` (plain `rect` field), `Modifier.captureAnchor(holder)`.
- Build: `:design` has `implementation(libs.activity.compose)` (1.13.0) for `BackHandler`.
- Test facts: Robolectric manual clock: after `targetState` changes, advance one small step (16 to 40 ms) before a large one, or a
  `Transition`/`Animatable` exit does not start. `clearAndSetSemantics` hides descendants from the merged tree only; use
  `useUnmergedTree = true` to read their bounds.

## E1.10 part 1: button menu (overlay/MenuMorph.kt, MenuModel.kt, ButtonMenu.kt)

- `androidx.compose.ui.graphics.BlurEffect(radiusX, radiusY, TileMode)` (API 31+, guarded by `Build.VERSION.SDK_INT`) set as
  `GraphicsLayerScope.renderEffect` for the rows (6 dp to 0) and the trigger glyph copy (0 to 8 dp).
- `GraphicsLayerScope.shape` + `clip` with a custom `Shape` (`Outline.Rounded(RoundRect)`) for the animated clips; both reset
  (`clip = false`) once `t == 1`.
- `Modifier.layout` places the card: the placement (`placePanel` with `gap = -anchor.height`, so the card's top or bottom edge
  lines up with the trigger's) is computed in the layout pass and written to plain fields read in draw.
- `Transition.animateFloat` with a per-direction spring (`GlisseSpring.spec`) is the one master `t`; a `Transition` also
  keeps `MutableTransitionState.currentState` true until `t` is back at 0, so the entry stays composed. It retargets with the
  current velocity on a direction change.
- `onPreviewKeyEvent` + `Key.Escape` + `FocusRequester` close the menu from a hardware keyboard.
- `drawscope.withTransform { translate; scale }` draws the glyph copy; `Painter.draw(size, alpha, colorFilter)`.
- Test fact: a menu opened by a touch-down (`menuTrigger`) needs two 16 ms advances before a big `advance` or its spring has
  not started when a screenshot is taken.

## E1.11: context menu (overlay/ContextMenu*.kt, PreviewCard*.kt)

- `AwaitPointerEventScope.withTimeoutOrNull`, `PointerEventPass.Main/Initial`, `changedToUp`, `PointerInputChange.isConsumed`,
  `viewConfiguration.longPressTimeoutMillis/touchSlop`: verified by the passing `ContextMenuGestureTest`.
- `semantics { onLongClick(label) }`, `customActions = listOf(CustomAccessibilityAction)`: `SemanticsActions.OnLongClick/CustomActions`.
- `Placeable.placeWithLayer(x, y) { }` carries the per-frame scale/alpha/blur/translation of the panel and card (state read in the layer block only).
- `LayoutCoordinates.positionInWindow()` + `size` instead of `boundsInWindow()` for anchors: the latter is clipped by a scrolling parent.
- Test facts: `boundsInRoot` of a half-visible lazy item is clipped too; while a modal entry is open the page is hidden from the merged tree (use `useUnmergedTree = true`); the Robolectric touch slop makes a 20 dp move scroll only about 4 dp.

## E1.16: text fields (text/GlassTextField.kt, SearchField.kt, ComposerField.kt, TextSelection.kt)

- `BasicTextField(state: TextFieldState, lineLimits, decorator = TextFieldDecorator, onKeyboardAction: KeyboardActionHandler, cursorBrush, interactionSource)`;
  `TextFieldLineLimits.SingleLine` / `MultiLine(minHeightInLines, maxHeightInLines)`; `TextFieldState.clearText()`, `edit { insert(i, s) }`.
- `LocalTextSelectionColors` is provided by `RovstiTheme` (`rovstiSelectionColors`: handles accent, highlight accent at 30 %).
- DECISION: the platform text toolbar is kept. Neither `LocalTextToolbar` nor (foundation 1.12) `LocalTextContextMenuToolbarProvider`
  is provided in production, so cut/copy/paste/select-all, the magnifier and handles are the system's. Tests provide a recording
  `TextContextMenuProvider` (`catalog/src/test/.../RecordingToolbar.kt`) to assert the request and press Paste; Robolectric draws no toolbar.
- Foundation 1.12 routes the selection toolbar through `LocalTextContextMenuToolbarProvider` (`TextContextMenuProvider.showTextContextMenu(dataProvider)`,
  items are `TextContextMenuItem(key, label, onClick(session))`, keys in `TextContextMenuKeys`), not `TextToolbar` (a `LocalTextToolbar` fake is never called).
- Clear and send buttons use `focusProperties { canFocus = false }` + `clickable`, so pressing them keeps focus and the caret.
- Tapping the pill: `pointerInput { detectTapGestures { focusRequester.requestFocus(); keyboard.show() } }` on the field modifier (the decorator's padding and glyph
  area are inside it; taps on the text itself are consumed by the field first).
- Hardware Enter: `onPreviewKeyEvent` (Enter/NumPadEnter, KeyDown, Shift = newline) because the multi-line field would otherwise insert a newline itself.
- Dock: `navigationBarsPadding().imePadding()` in that order (consumed insets are not added twice).
- Placeholder and glyphs use `labelSecondary` (4.5:1), not glisse's `muted`.
- Test facts: Robolectric's platform `Magnifier` throws in `dismiss()` during long-press selection; `ShotTest` installs `ShadowNoopMagnifier`.
  `ShotTest.setSimulatedIme(dp)` dispatches an IME inset. A Ctrl+V key injection types a literal "v" in Robolectric; use the toolbar item or
  `SemanticsActions.PasteText`. Caret position: click at `14 dp + layout.getCursorRect(n).left` (`SemanticsActions.GetTextLayoutResult`).

## E1.24 Convx backdrop engine port (Compose BOM 2026.09, Kotlin 2.4.21)

- All 42 files of Convx `ui/component/backdrop/**` and 15 of `ui/component/shapes/**` compile against BOM 2026.09 unchanged:
  no Compose API drift was found, only package renames (`dev.rovsti.design.backdrop`, `dev.rovsti.design.shapes`) and ktlint formatting.
- `.editorconfig` disables the ktlint rules kdoc, function-naming, backing-property-naming and no-consecutive-comments for
  `design/{backdrop,shapes}/**` so the ported files stay close to upstream.
- `rememberNavTransitionFreeze(currentRoute, lastNavWasPop = { false })`: Convx's global `lastNavWasPop()` (SharedArtwork) is a parameter.
- `LiquidToggle/Slider/BottomTabs`: `glassUsesDarkAppearance()` became `RovstiTheme.colors.isDark`.
- `rememberAppBackdropState(baseBg, currentRoute, extraFrozen, lastNavWasPop)` replaces Convx's `pureBlack` and floating-bar connection.
- `LayerBackdrop.staticRegions` is filled during draw, after layout. The first draw declares nothing (fail-safe: always record);
  tests need a second draw (`captureToImage` forces it under Robolectric).
- Robolectric NATIVE runs the backdrop effect chain (blur, vibrancy, AGSL lens, highlight): `BackdropEngineTest` measures it.
  The design module's JVM tests now have robolectric and compose-ui-test-junit4 (`StaticRegionTest`, `NavTransitionFreezeTest`).
- Test pitfalls: a 3 dp stripe pattern needs the sampled patch off a stripe edge; red 0xD32F2F and blue 0x1565C0 have equal luminance,
  so variance checks use red and white.

## E1.25 (glass layer, tokens, iOS foundation)

- Haze: Convx pins 1.3.1. 2.x split the artifact (`haze-blur`, `haze-blur-materials`, no `haze-materials:2.1.0`) and changes the API,
  so `libs.versions.toml` pins the newest 1.x, 1.7.3. `TopFadeBlur` (`hazeEffect`, `HazeProgressive.verticalGradient`,
  `HazeMaterials.ultraThin`, `HazeInputScale.Fixed`) compiles unchanged against BOM 2026.09; the single-pass backdrop branch is
  the one the catalog shot exercises (`TopBarBlurStyle.SinglePass`).
- `GlassEffect.kt` is a second file of package `dev.rovsti.design.glass` until S4 deletes the old engine: its facade is renamed
  with `@file:JvmName("ConvxGlassEffect")` to avoid a duplicate `GlassEffectKt`.
- `androidx.core.graphics.ColorUtils` calls `android.graphics.Color`, so `GlassTintTokensTest` runs under Robolectric.
- Test fixes: `GlassTintTokensTest` expected a dark neutral tint alpha of 0.06 and dark hero opacity 0.30; the tokens are 0 on
  purpose (measured, see the comments in `GlassTintTokens.kt`), so the assertions were updated. `AppleColorsTest` passes as is.

## E1.18 (Navigation 3 1.2.1; checked with `javap` and the `-sources.jar` of `navigation3-ui-android` / `-runtime-android`)

- Dependencies: `androidx.navigation3:navigation3-runtime:1.2.1`, `navigation3-ui:1.2.1`, `kotlinx-serialization-json:1.9.0` and the
  `org.jetbrains.kotlin.plugin.serialization` plugin (version = kotlin 2.4.21). `:design` exposes `kotlinx-serialization-json` as `api`
  (`TabNavigator.saver` takes a `KSerializer`); the Nav3 artifacts stay `implementation`.
- `androidx.navigation3.ui.NavDisplay` (package `...navigation3.ui`) has five overloads. The one used: `NavDisplay(backStack: List<T>,
  modifier, contentAlignment, onBack: () -> Unit, entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
  sceneStrategies, sceneDecoratorStrategies, sharedTransitionScope: SharedTransitionScope?, sizeTransform, transitionSpec,
  popTransitionSpec, predictivePopTransitionSpec, entryProvider: (T) -> NavEntry<T>)`. `transitionSpec` /
  `popTransitionSpec` are `AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform`; `predictivePopTransitionSpec` is
  `AnimatedContentTransitionScope<Scene<T>>.(Int swipeEdge) -> ContentTransform`. The scope's `initialState` / `targetState` are
  `Scene<T>`, not routes.
- Differences from the plan: `NavEntry.key` is **private** (`NavEntry(key, contentKey, metadata, content)`), so a transition spec cannot
  read the route from a scene's entries; `RovstiNavHost` stores its `StackKey` in `NavEntry.metadata` (`StackKeyMetadata`) and the specs
  read `scene.metadata`. `Scene` has `key`, `entries`, `previousEntries`, `content`, `metadata`.
- `entryProvider { entry<K> { } }` exists (`EntryProviderScope`), keyed by the back-stack element type. Because our back-stack element is
  the wrapper `StackKey(tab, depth, route)`, the host takes its own `RovstiEntries<R>.entry<K : R>` and builds `NavEntry` directly.
- `rememberSaveableStateHolderNavEntryDecorator()` is the default decorator; `contentKey` defaults to the string `"$key:${key::class}"`
  (saveable), so keys need no `Bundle` support.
- `LocalNavAnimatedContentScope: ProvidableCompositionLocal<AnimatedContentScope>` (package `...navigation3.ui`) throws outside an entry.
  It is the `AnimatedVisibilityScope` for `sharedBounds`. `SharedTransitionLayout`, `Modifier.sharedBounds(sharedContentState,
  animatedVisibilityScope, boundsTransform, renderInOverlayDuringTransition, zIndexInOverlay)`, `rememberSharedContentState(key)`,
  `BoundsTransform { initial, target -> FiniteAnimationSpec<Rect> }` are `androidx.compose.animation` (`@ExperimentalSharedTransitionApi`).
- Predictive back: `NavDisplay` registers a `NavigationBackHandler` (navigationevent) and scrubs a `SeekableTransitionState` with the
  pop spec; `ComponentActivity.onBackPressedDispatcher.dispatchOnBackStarted/Progressed/Cancelled` drive it under Robolectric. The scrub
  maps gesture progress to the transition's time, so with the iOS push curve (0.32, 0.72, 0, 1) 50 % progress is already about 90 % of
  the distance; that is Convx's behaviour too.
- Back order: `NavigationBackHandler` is composed with the pages, the overlay host's `BackHandler` later (in `OverlayEntries`), so an
  open overlay closes first (`NavTest.overlayClosesBeforeTheStackPops`).
- Convx deviation: `AppNavHost.kt` pop-enter is `slideInHorizontally(push) { parallaxOffset(-it) }`, which is +0.3 W (the page below starts
  to the right and leaves a blank band on the left while the leaving page slides off). `NavTransitions` uses `parallaxOffset(it)`
  (-0.3 W, the mirror of the push exit).
- Test pitfall: `NavTransitionFreeze` reads `System.nanoTime()`, so tests need real time (`Thread.sleep`) plus
  `ShadowSystemClock.advanceBy` to leave the 330 ms window; the Compose manual clock does not move it.

## :core-bridge (E2.6)

- cargo-ndk platform is 26, not the project's ARCH level 34 (`RovstiSdk.MIN`): a shared library linked against platform 34
  refuses to load on API 26 to 33 (missing symbols/`DT_NEEDED` versions), and `scripts/android-env.sh` links for 26 as well.
  Linking low costs nothing: the `.so` only uses libc/liblog/libdl symbols that exist since 26.
- `CargoTasks.kt` (`rovsti.cargo` convention plugin): `cargoBuildAndroid` (debug) / `cargoBuildAndroid<Variant>` register through
  `variant.sources.jniLibs.addGeneratedSourceDirectory(task, CargoBuildTask::outputDir)`, which also wires the task before
  `merge*JniLibFolders` and `preBuild`-equivalent consumers. Typed inputs: `crates/**/*.rs|Cargo.toml|*.json`, root `Cargo.toml`
  and `Cargo.lock`. The second run is UP-TO-DATE; the Cargo invocation after a source change is incremental.
- `cargoBuildHost` builds with `--features test-ops` (`core.panic`, `core.emit_test`); the Android build never enables it.
- AGP 9 built-in Kotlin: extra test sources go through `android.sourceSets.getByName("test").kotlin.directories.add(...)`;
  `.java.directories` is not compiled by Kotlin. `variant.unitTest` is not available on the library `Variant` type.
- `testRustBridge` reuses the `testDebugUnitTest` classpath and class dirs, filters to package `dev.rovsti.core.rust` and
  `testDebugUnitTest` excludes that package (those tests load the real `.so`, the Robolectric JVM must not).
- Debug `.so` files are unstripped: about 36 MB per ABI, the catalog debug APK is about 108 MB. Release builds use `--release`.

## Bridge contract (E2.7, E2.8)

- Fixtures: `crates/rovsti_ffi/fixtures/<op>.{request,answer}.json` (`core.version`, `core.echo`, `core.data_dir`,
  `core.fail.<kind>`) and `headless.<kind>.{request,answer}.json`. `core.*` answers are the full envelope bytes; headless
  answers are the handler string. Headless requests are authored inputs (`$DIR` = data dir seeded from
  `rovsti_app_core/tests/fixtures/dirs/<app>`); the answers are generated and compared by `tests/fixtures.rs`
  (`UPDATE_FIXTURES=1` rewrites). `core.version` carries the crate version, so a version bump needs one rewrite.
- Models live in `dev.rovsti.core.model.core` and `.headless`; the registry in `ContractFixturesTest` maps every fixture stem
  to a serializer. Adding an op in Rust without a fixture+model pair, or a fixture without a model, fails a test.
- kotlinx.serialization conventions in the models: wire names via `@SerialName`; fields a fixture does not carry are
  `@EncodeDefault(NEVER)`; failure shapes (`{"ok":false,"error":..}`) decode into the same class, so success-only fields
  default to null. The contract test uses `encodeDefaults = true` and compares numbers numerically (a double may render
  differently on the JVM).
- Unknown error kinds (a newer library) are `RustError.Unknown(kind)`, not a crash; a malformed envelope is `RustError.Parse`.
- `HeadlessClient.ask(kind, json)` returns `HeadlessAnswer?`: `null` for library missing, `""`, `RustError`, any runtime
  error (the caller posts the plain notification); `CancellationException` propagates. It loads the library itself, so a
  receiver needs no `Rust.init`.
- Platform 26 decision (see above): cargo-ndk links for the minSdk, not ARCH level 34.
- Disk note: the build cache of Gradle (`~/.gradle/caches/build-cache-1`, ~5 GB) is safe to delete between steps.
