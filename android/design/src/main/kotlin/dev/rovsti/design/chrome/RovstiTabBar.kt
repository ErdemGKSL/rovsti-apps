// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// Tab bar geometry, hold rule, tap slop and lens staging after glisse src/tab-bar.tsx and src/internal/segments.tsx.
package dev.rovsti.design.chrome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rovsti.design.glass.GlassShadow
import dev.rovsti.design.glass.GlassSource
import dev.rovsti.design.glass.GlassSpec
import dev.rovsti.design.glass.GlassTier
import dev.rovsti.design.glass.LensOptions
import dev.rovsti.design.glass.LocalGlassConditions
import dev.rovsti.design.glass.glass
import dev.rovsti.design.glass.glassTier
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.motion.gated
import dev.rovsti.design.overlay.LocalOverlayOrNull
import dev.rovsti.design.press.HoldResult
import dev.rovsti.design.press.awaitHold
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** One tab. [id] is stable and becomes the test tag `tab-<id>`; [label] is also the accessibility name. */
@Immutable
data class TabItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
)

private val IconSize = 26.dp
private val LabelStyle = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium)
private val MinimizeShift = 40.dp
private val TugMax = 4.dp

/** Labels are dropped (icons only) from this system font scale, so they never clip. */
const val LabelsDropAtFontScale = 1.5f

/**
 * The floating tab bar: glass capsule, icon over a 10 sp label per tab, and a puck under the selected tab that slides
 * with a spring, follows a drag and lifts into a lens while held (glisse `TabBar`).
 *
 * - Width: the window less [Spacing.gutter] on both sides, at most [Sizes.tabBarMaxWidth]. Bottom margin: system inset
 *   plus 8 dp. The bar's measured height goes to [chrome] (see [ChromeMetrics]); it writes 0 while hidden.
 * - Touch: a quick vertical swipe belongs to the page; a press that rests 150 ms or moves 6 dp sideways belongs to the
 *   bar. A tap counts when it ends within 24 dp of the bar. A drag selects the slot under the projected rest point.
 * - [minimizer] slides the bar 40 dp down and fades it 35 % while the page scrolls down. [folded] shrinks it to a circle
 *   holding the selected tab; tapping the circle calls [onFoldedClick].
 * - [imeVisible] hides the bar while the keyboard is up.
 *
 * [onSelect] runs when the user picks another tab; [onReselect] when the selected tab is picked again. [selected] is the
 * single source of truth: if the parent ignores [onSelect], the puck returns to [selected].
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun RovstiTabBar(
    tabs: ImmutableList<TabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onReselect: (Int) -> Unit,
    glass: GlassSource,
    modifier: Modifier = Modifier,
    minimizer: TabBarMinimizer? = null,
    folded: Boolean = false,
    onFoldedClick: () -> Unit = {},
    chrome: ChromeMetrics? = LocalChromeMetrics.current,
    imeVisible: @Composable () -> Boolean = { WindowInsets.isImeVisible },
    selectedDescription: String = "Selected",
) {
    val hidden = imeVisible()
    val overlays = LocalOverlayOrNull.current
    // Entries above the page (toasts) read the bar from the controller: they are composed outside the bar's locals.
    DisposableEffect(overlays, chrome) {
        if (chrome != null) overlays?.chromeMetrics = chrome
        onDispose { if (overlays?.chromeMetrics === chrome) overlays?.chromeMetrics = null }
    }
    LaunchedEffect(hidden, chrome) { if (hidden) chrome?.bottomBarPx = 0 }
    if (hidden || tabs.isEmpty()) return

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val reduceNow by rememberUpdatedState(reduceMotion)
    val selectedNow by rememberUpdatedState(selected.coerceIn(0, tabs.lastIndex))
    val haptics = LocalRovstiHaptics.current
    val colors = RovstiTheme.colors
    val tokens = RovstiTheme.glass
    val tier = glassTier(LocalGlassConditions.current)
    val slop = LocalViewConfiguration.current.touchSlop

    val puck = remember(scope) { SelectionPuck(selectedNow, tabs.size, scope) { reduceNow } }
    SideEffect { puck.count = tabs.size }
    LaunchedEffect(selected, tabs.size) {
        if (!puck.dragging && abs(puck.position.targetValue - selectedNow) > 0.001f) puck.select(selectedNow)
    }

    val minimize = remember { Animatable(0f) }
    if (minimizer != null) {
        LaunchedEffect(minimizer) {
            snapshotFlow { minimizer.minimized }.collect { m ->
                minimize.animateTo(if (m) 1f else 0f, RovstiMotion.barMinimize(UNIT).gated(reduceNow))
            }
        }
    }
    val fold = remember { Animatable(if (folded) 1f else 0f) }
    LaunchedEffect(folded) { fold.animateTo(if (folded) 1f else 0f, RovstiMotion.barMinimize(UNIT).gated(reduceNow)) }
    val tug = remember { Animatable(0f) }

    val barHeightPx = with(density) { TabBarSpec.BarHeightDp.dp.toPx() }
    val paddingPx = with(density) { TabBarSpec.PaddingDp.dp.toPx() }
    val cellHeightPx = with(density) { TabBarSpec.CellHeightDp.dp.toPx() }
    val growX = with(density) { TabBarSpec.GrowXDp.dp.toPx() }
    val growY = with(density) { TabBarSpec.GrowYDp.dp.toPx() }
    val inset = with(density) { TabBarSpec.PuckInsetDp.dp.toPx() }
    val dragStart = with(density) { TabBarSpec.DragStartDp.dp.toPx() }
    val tapSlop = with(density) { TabBarSpec.TapSlopDp.dp.toPx() }
    val tugMax = with(density) { TugMax.toPx() }
    val shiftPx = with(density) { MinimizeShift.toPx() }
    val showLabels = density.fontScale < LabelsDropAtFontScale
    val count = tabs.size

    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(start = Spacing.gutter, end = Spacing.gutter, bottom = ChromeMetrics.BarMargin),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fullPx = min(constraints.maxWidth.toFloat(), with(density) { Sizes.tabBarMaxWidth.toPx() })

            fun rect(): Rect =
                puckRect(
                    position = puck.position.value,
                    lift = puck.lift.value,
                    squash = puck.squash(),
                    barWidth = fullPx,
                    barHeight = barHeightPx,
                    count = count,
                    padding = paddingPx,
                    cellHeight = cellHeightPx,
                    growX = growX,
                    growY = growY,
                    inset = inset,
                )

            fun pick(index: Int) {
                if (index == selectedNow) {
                    haptics.tick()
                    onReselect(index)
                } else {
                    haptics.selection()
                    onSelect(index)
                }
            }

            fun settleAfter(index: Int) {
                // The parent may refuse the change: after it had two frames to apply it, go back to what it shows.
                scope.launch {
                    androidx.compose.runtime.withFrameNanos { }
                    androidx.compose.runtime.withFrameNanos { }
                    if (selectedNow != index) puck.select(selectedNow)
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = minimize.value * shiftPx
                        alpha = 1f - MINIMIZE_FADE * minimize.value
                        translationX = tug.value
                    }.layout { measurable, c ->
                        val geometry = foldGeometry(fold.value, fullPx, barHeightPx)
                        val placeable =
                            measurable.measure(Constraints.fixed(geometry.width.roundToInt(), barHeightPx.roundToInt()))
                        layout(c.maxWidth, placeable.height) {
                            val free = (c.maxWidth - placeable.width).toFloat()
                            val x = (free / 2f * (1f + geometry.anchorBias)).roundToInt()
                            val rtl = layoutDirection == LayoutDirection.Rtl
                            placeable.place(if (rtl) c.maxWidth - placeable.width - x else x, 0)
                        }
                    }.onSizeChanged { chrome?.bottomBarPx = it.height }
                    .testTag("tab-surface")
                    .barSurface(glass, tier)
                    .selectableGroup()
                    .then(
                        if (folded) {
                            Modifier.semantics(mergeDescendants = true) {
                                role = Role.Button
                                contentDescription = tabs[selectedNow].label
                                onClick {
                                    onFoldedClick()
                                    true
                                }
                            }
                        } else {
                            Modifier
                        },
                    ).pointerInput(folded, count, fullPx, slop) {
                        if (folded) {
                            tapToExpand(onFoldedClick)
                        } else {
                            tabGestures(
                                puck = puck,
                                tug = tug,
                                count = count,
                                fullPx = fullPx,
                                paddingPx = paddingPx,
                                barHeightPx = barHeightPx,
                                dragStartPx = dragStart,
                                tapSlopPx = tapSlop,
                                tugMaxPx = tugMax,
                                touchSlop = slop,
                                scope = scope,
                                onTap = { index ->
                                    puck.select(index)
                                    pick(index)
                                    settleAfter(index)
                                },
                                onDragEnd = { target ->
                                    if (target != selectedNow) pick(target)
                                    settleAfter(target)
                                },
                                onHandedToPage = { puck.cancel(selectedNow) },
                                onOffBar = { puck.cancel(selectedNow) },
                            )
                        }
                    },
            ) {
                Stage(
                    fullPx = fullPx,
                    barHeightPx = barHeightPx,
                    paddingPx = paddingPx,
                    tabs = tabs,
                    selected = selectedNow,
                    showLabels = showLabels,
                    selectedDescription = selectedDescription,
                    puck = puck,
                    fold = fold,
                    rect = ::rect,
                    glass = glass,
                    tier = tier,
                    folded = folded,
                    onTabClick = { index ->
                        puck.select(index)
                        pick(index)
                        settleAfter(index)
                    },
                    dark = colors.isDark,
                    accent = colors.accent,
                    foreground = tokens.foreground,
                )
            }
        }
    }
}

private const val UNIT = 0.001f
private const val MINIMIZE_FADE = 0.35f

/** The glass capsule of the bar (clear glass over the page; Flat in tests and when blur is unavailable). */
@Composable
private fun Modifier.barSurface(
    glass: GlassSource,
    tier: GlassTier,
): Modifier {
    val spec = GlassSpec.bar(RovstiTheme.glass, RovstiTheme.colors, tier)
    return this.glass(glass, spec)
}

/**
 * Everything inside the bar at its full width: puck under the labels, labels (drawn once outside the puck), the tinted
 * copy of the labels clipped to the puck, and, on a dark page, the lens above the labels. Folding slides the stage
 * so the selected tab ends up centred in the circle.
 */
@Composable
private fun Stage(
    fullPx: Float,
    barHeightPx: Float,
    paddingPx: Float,
    tabs: ImmutableList<TabItem>,
    selected: Int,
    showLabels: Boolean,
    selectedDescription: String,
    puck: SelectionPuck,
    fold: Animatable<Float, AnimationVector1D>,
    rect: () -> Rect,
    glass: GlassSource,
    tier: GlassTier,
    folded: Boolean,
    onTabClick: (Int) -> Unit,
    dark: Boolean,
    accent: Color,
    foreground: Color,
) {
    val tokens = RovstiTheme.glass
    val capsule =
        androidx.compose.foundation.shape
            .RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(capsule)
            .layout { measurable, c ->
                val placeable = measurable.measure(Constraints.fixed(fullPx.roundToInt(), barHeightPx.roundToInt()))
                layout(c.maxWidth, c.maxHeight) {
                    val geometry = foldGeometry(fold.value, fullPx, barHeightPx)
                    val centre =
                        paddingPx + (puck.position.value.coerceIn(0f, (tabs.size - 1).toFloat()) + 0.5f) *
                            slotWidth(fullPx, tabs.size, paddingPx)
                    val shift = fold.value * (geometry.width / 2f - centre)
                    placeable.place(shift.roundToInt(), 0)
                }
            },
    ) {
        // Selection at rest, under the labels; fades out during the first half of the lift.
        PuckBox(
            rect,
            Modifier.testTag("tab-puck").drawBehind {
                val rest = (1f - puck.lift.value / TabBarSpec.Under).coerceIn(0f, 1f) * (1f - fold.value)
                if (rest > 0f) {
                    drawRoundRect(
                        tokens.selection.copy(alpha = tokens.selection.alpha * rest),
                        cornerRadius = CornerRadius(size.height / 2f),
                    )
                }
            },
        )
        // Light page: the lens lies under the labels and grows from nothing after half the lift.
        if (!dark) {
            PuckLens(rect, glass, tier, amount = {
                ((puck.lift.value - TabBarSpec.Under) / (1f - TabBarSpec.Under)).coerceIn(0f, 1f) * (1f - fold.value)
            })
        }
        // Labels: every pixel is drawn once, outside the puck in the ink colour, inside it in the accent.
        TabCells(
            tabs,
            selected,
            showLabels,
            selectedDescription,
            fold,
            foreground,
            Modifier.drawWithContent {
                val inside = Path().apply { addRoundRect(RoundRect(rect(), CornerRadius(rect().height / 2f))) }
                clipPath(inside, ClipOp.Difference) { this@drawWithContent.drawContent() }
            },
            semantic = !folded,
            onTabClick = onTabClick,
        )
        TabCells(
            tabs,
            selected,
            showLabels,
            selectedDescription,
            fold,
            accent,
            Modifier.drawWithContent {
                val inside = Path().apply { addRoundRect(RoundRect(rect(), CornerRadius(rect().height / 2f))) }
                clipPath(inside) { this@drawWithContent.drawContent() }
            },
            semantic = false,
            onTabClick = {},
        )
        // Dark page: the lens lies over the labels.
        if (dark) {
            PuckLens(rect, glass, tier, amount = { puck.lift.value.coerceIn(0f, 1f) * (1f - fold.value) })
        }
    }
}

/** A box that follows [rect] (bar-local px) each frame, in the layout phase. */
@Composable
private fun PuckBox(
    rect: () -> Rect,
    modifier: Modifier = Modifier,
) {
    Box(
        Modifier
            .layout { measurable, c ->
                val r = rect()
                val w = r.width.roundToInt().coerceAtLeast(0)
                val h = r.height.roundToInt().coerceAtLeast(0)
                val p = measurable.measure(Constraints.fixed(w, h))
                layout(c.maxWidth, c.maxHeight) { p.place(r.left.roundToInt(), r.top.roundToInt()) }
            }.then(modifier),
    )
}

/**
 * The lifted selection. Flat tier: a raised fill with a hairline. Frost and Liquid: real glass with the tab
 * lens (glisse `segments.tsx` `LENS`). [amount] is read in the draw phase only.
 */
@Composable
private fun PuckLens(
    rect: () -> Rect,
    source: GlassSource,
    tier: GlassTier,
    amount: () -> Float,
) {
    val tokens = RovstiTheme.glass
    val colors = RovstiTheme.colors
    val spec =
        remember(tier, tokens, colors) {
            GlassSpec.bar(tokens, colors, tier, GlassSpec.CAPSULE).copy(
                blurDp = 2f,
                lens = LensOptions.TabLens,
                fill = tokens.lens,
                scrim = Color.Transparent,
                shadow = GlassShadow(10f, 2f, Color.Black.copy(alpha = 0.12f)),
            )
        }
    val surface =
        if (tier == GlassTier.Flat) {
            Modifier.drawBehind {
                val corner = CornerRadius(size.height / 2f)
                drawRoundRect(tokens.glassActive, cornerRadius = corner)
                drawRoundRect(tokens.edge, cornerRadius = corner, style = Stroke(1.dp.toPx()))
            }
        } else {
            Modifier.glass(source, spec)
        }
    PuckBox(rect, Modifier.graphicsLayer { alpha = amount() }.then(surface))
}

/** The row of icon-over-label cells, all in [tint], with [modifier] drawn around it (the clip passes). */
@Composable
private fun TabCells(
    tabs: ImmutableList<TabItem>,
    selected: Int,
    showLabels: Boolean,
    selectedDescription: String,
    fold: Animatable<Float, AnimationVector1D>,
    tint: Color,
    modifier: Modifier,
    semantic: Boolean,
    onTabClick: (Int) -> Unit,
) {
    Row(
        modifier
            .fillMaxSize()
            .padding(horizontal = TabBarSpec.PaddingDp.dp, vertical = TabBarSpec.PaddingDp.dp)
            .then(if (semantic) Modifier else Modifier.clearAndSetSemantics {}),
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = index == selected
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .graphicsLayer {
                        alpha = if (isSelected) 1f else foldGeometry(fold.value, 1f, 0f).otherAlpha
                    }.then(
                        if (semantic) {
                            Modifier
                                .testTag("tab-${tab.id}")
                                .semantics(mergeDescendants = false) {
                                    role = Role.Tab
                                    this.selected = isSelected
                                    contentDescription = tab.label
                                    if (isSelected) stateDescription = selectedDescription
                                    onClick {
                                        onTabClick(index)
                                        true
                                    }
                                }
                        } else {
                            Modifier
                        },
                    ),
                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RovstiIcon(
                    tab.icon,
                    null,
                    tint = tint,
                    size = IconSize,
                    modifier =
                        Modifier.graphicsLayer {
                            if (showLabels) {
                                val gone = 1f - foldGeometry(fold.value, 1f, 0f).labelAlpha
                                translationY = gone * LabelLiftDp * density
                            }
                        },
                )
                if (showLabels) {
                    BasicText(
                        text = tab.label,
                        style = LabelStyle.copy(color = tint, textAlign = TextAlign.Center),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer { alpha = foldGeometry(fold.value, 1f, 0f).labelAlpha },
                    )
                }
            }
        }
    }
}

/** Half of a label line plus the gap: how far the icon moves down when the label has gone. */
private const val LabelLiftDp = 6.5f

/** Folded: any touch on the circle expands. */
private suspend fun PointerInputScope.tapToExpand(onClick: () -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val up = waitForUpOrCancellation()
        if (up != null) {
            up.consume()
            onClick()
        }
    }
}

/**
 * The tab bar's touch rules (glisse `segments.tsx` `onPointerDown/Move/End` plus `hold.ts`):
 * - the pressed slot lifts the puck at once;
 * - a vertical move before the hold time, or a consumed touch, goes to the page (nothing is consumed here);
 * - once held (150 ms) or moved sideways, the bar consumes; a sideways travel of [dragStartPx] starts the drag;
 * - a drag lands on the projected slot wherever it ends; a tap needs the release within [tapSlopPx] of the bar.
 */
private suspend fun PointerInputScope.tabGestures(
    puck: SelectionPuck,
    tug: Animatable<Float, AnimationVector1D>,
    count: Int,
    fullPx: Float,
    paddingPx: Float,
    barHeightPx: Float,
    dragStartPx: Float,
    tapSlopPx: Float,
    tugMaxPx: Float,
    touchSlop: Float,
    scope: kotlinx.coroutines.CoroutineScope,
    onTap: (Int) -> Unit,
    onDragEnd: (Int) -> Unit,
    onHandedToPage: () -> Unit,
    onOffBar: () -> Unit,
) {
    val slot = slotWidth(fullPx, count, paddingPx)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val anchor = slotUnder(down.position.x, fullPx, count, paddingPx)
        puck.press(anchor)
        val outcome = awaitHold(down, touchSlop)
        when (outcome.result) {
            HoldResult.Scrolled -> {
                onHandedToPage()
                return@awaitEachGesture
            }

            HoldResult.Released -> {
                if (isTap(outcome.position.y, barHeightPx, tapSlopPx)) onTap(anchor) else onOffBar()
                return@awaitEachGesture
            }

            HoldResult.Held -> {
                Unit
            }
        }

        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var moved = abs(outcome.position.x - down.position.x) > dragStartPx
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                finishTug(scope, tug)
                onOffBar()
                return@awaitEachGesture
            }
            tracker.addPosition(change.uptimeMillis, change.position)
            val dx = change.position.x - down.position.x
            if (!moved && abs(dx) > dragStartPx) moved = true
            if (!change.pressed) {
                finishTug(scope, tug)
                if (moved) {
                    val v = tracker.calculateVelocity().x / slot
                    onDragEnd(puck.release(v))
                } else if (isTap(change.position.y, barHeightPx, tapSlopPx)) {
                    onTap(anchor)
                } else {
                    onOffBar()
                }
                return@awaitEachGesture
            }
            if (moved) {
                puck.drag(anchor + dx / slot, tracker.calculateVelocity().x / slot)
                val toward = (dx / slot).coerceIn(-1f, 1f) * tugMaxPx
                scope.launch(start = CoroutineStart.UNDISPATCHED) { tug.snapTo(toward) }
            }
            change.consume()
        }
    }
}

private fun finishTug(
    scope: kotlinx.coroutines.CoroutineScope,
    tug: Animatable<Float, AnimationVector1D>,
) {
    scope.launch { tug.animateTo(0f, spring(1f, 300f, 0.5f)) }
}
