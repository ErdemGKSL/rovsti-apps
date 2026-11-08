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
