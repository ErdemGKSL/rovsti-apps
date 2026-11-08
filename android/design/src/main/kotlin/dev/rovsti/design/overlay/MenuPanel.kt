// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// The highlight plate and the card press follow glisse `src/menu.tsx:128-262` and `src/styles.css:985-1070`
// (100 ms plate slide, first highlight in place, 120 ms sheen, 7 dp resistance, +5 dp press). The input handling, the
// hit-test, the keyboard path and the Compose rendering are Rovsti's own.
package dev.rovsti.design.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.rovsti.design.haptics.RovstiHaptics
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Which row [y] falls in. [rects] holds `top, bottom` for every item (pixels, in the rows' own coordinate space):
 * a separator or a disabled row has `NaN` for both, so it can never be hit. Returns `-1` between rows and outside
 * them. Pure: the whole hit-test of a press-slide-release.
 */
internal fun hoveredRow(
    rects: FloatArray,
    y: Float,
): Int {
    var i = 0
    while (2 * i + 1 < rects.size) {
        if (y >= rects[2 * i] && y < rects[2 * i + 1]) return i
        i++
    }
    return -1
}

/** The first row that can be chosen, or `-1` when none can. */
internal fun firstEnabledRow(items: List<MenuItem>): Int = items.indexOfFirst { it is MenuItem.Action && it.enabled }

/**
 * The row the arrow keys land on from [current], moving [step] (+1 or -1) through the rows that can be chosen and
 * wrapping around the ends. Pure.
 */
internal fun stepRow(
    items: List<MenuItem>,
    current: Int,
    step: Int,
): Int {
    val enabled = items.indices.filter { (items[it] as? MenuItem.Action)?.enabled == true }
    if (enabled.isEmpty()) return -1
    val at = enabled.indexOf(current)
    val next = if (at < 0) (if (step > 0) 0 else enabled.lastIndex) else (at + step + enabled.size) % enabled.size
    return enabled[next]
}

private val SheenEasing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/**
 * Everything the open menu's finger handling keeps: the rows' rectangles (one plain `FloatArray`, written while the
 * rows are laid out), which row is lit, the plate and the pressed card.
 *
 * Positions are in the card's own layout space (origin at its top-left corner). A finger comes from two places: a
 * press on the card itself ([begin], [move], [end]) or a finger that went down on the trigger and slid on
 * ([beginSlide]); both end in [end]. A slide that has not left the touch slop yet is a tap on the trigger and never
 * commits, so tapping a trigger leaves the menu open.
 */
@Stable
internal class MenuPanelState(
    private val items: List<MenuItem>,
    private val scope: CoroutineScope,
    private val geometry: CardGeometry,
    private val haptics: () -> RovstiHaptics,
    private val reduceMotion: () -> Boolean,
    private val pxPerDp: Float,
    private val onCommit: (MenuItem.Action) -> Unit,
) {
    /** `top, bottom` per item in row space; `NaN` for separators and disabled rows. */
    val rects = FloatArray(items.size * 2) { Float.NaN }

    /** Set once the rows have been laid out. */
    var ready = false
        private set

    /** The scroll position of the rows, for a menu that is taller than the screen. */
    var scrollPx: () -> Int = { 0 }

    /** The row the plate is on (`-1` for none). Read only by [runPlate], so it never recomposes anything. */
    val highlighted = mutableIntStateOf(-1)

    /** The row Enter chooses: the first enabled row at first, then wherever the finger or the arrows went. */
    var focusIndex = firstEnabledRow(items)
        private set

    val plateTop = Animatable(0f)
    val plateHeight = Animatable(0f)
    val plateAlpha = Animatable(0f)
    val scale = Animatable(1f)
    val dx = Animatable(0f)
    val dy = Animatable(0f)
    val sheen = Animatable(0f)

    private var committed = false
    private var active = false
    private var armed = false
    private var pressed = false
    private var originX = 0f
    private var originY = 0f

    private fun hittable(i: Int) = (items[i] as? MenuItem.Action)?.enabled == true

    /** Called from the rows' layout: records row [i]'s extent if it can be chosen. */
    fun place(
        i: Int,
        top: Int,
        bottom: Int,
    ) {
        if (hittable(i)) {
            rects[2 * i] = top.toFloat()
            rects[2 * i + 1] = bottom.toFloat()
        }
    }

    fun markReady() {
        ready = true
    }

    private fun insideCard(
        x: Float,
        y: Float,
    ) = x >= 0f && y >= 0f && x <= geometry.width && y <= geometry.height

    private fun rowAt(
        x: Float,
        y: Float,
    ): Int {
        if (x < geometry.padding || x > geometry.width - geometry.padding) return -1
        return hoveredRow(rects, y - geometry.padding + scrollPx())
    }

    private fun light(row: Int) {
        if (row == highlighted.intValue) return
        highlighted.intValue = row
        if (row >= 0) {
            focusIndex = row
            haptics().textHandle()
        }
    }

    // ---- fingers -------------------------------------------------------------------------------------------

    /** A finger went down on the card at ([x], [y]). */
    fun begin(
        x: Float,
        y: Float,
    ) {
        active = true
        armed = true
        originX = x
        originY = y
        press()
        light(rowAt(x, y))
    }

    /** A finger that went down on the trigger starts to slide; [x], [y] is where it went down. */
    fun beginSlide(
        x: Float,
        y: Float,
    ) {
        active = true
        armed = false
        originX = x
        originY = y
    }

    fun move(
        x: Float,
        y: Float,
    ) {
        if (!active) return
        if (!armed) {
            if (hypot(x - originX, y - originY) < SlopDp * pxPerDp) return
            armed = true
            press()
        }
        if (!pressed && insideCard(x, y)) press()
        light(rowAt(x, y))
        if (pressed && !reduceMotion()) {
            val px = menuResistance(x - originX, pxPerDp)
            val py = menuResistance(y - originY, pxPerDp)
            scope.launch { dx.snapTo(px) }
            scope.launch { dy.snapTo(py) }
        }
    }

    /** The finger lifted at ([x], [y]) ([lifted]) or was taken away. Chooses the row under it, if any. */
    fun end(
        x: Float,
        y: Float,
        lifted: Boolean,
    ) {
        if (!active) return
        val row = if (lifted && armed) rowAt(x, y) else -1
        active = false
        armed = false
        light(-1)
        release()
        if (row >= 0) commit(row)
    }

    private fun press() {
        pressed = true
        scope.launch { sheen.animateTo(1f, tween(SheenMs, easing = SheenEasing)) }
        if (reduceMotion()) return
        val target = menuPressScale(geometry.width, geometry.height, pxPerDp)
        scope.launch { scale.animateTo(target, RovstiMotion.MenuCardPress.spec(0.0001f)) }
    }

    private fun release() {
        if (!pressed) return
        pressed = false
        scope.launch { sheen.animateTo(0f, tween(SheenMs, easing = SheenEasing)) }
        val spring = RovstiMotion.MenuCardRelease
        scope.launch { scale.animateTo(1f, spring.spec(0.0001f)) }
        scope.launch { dx.animateTo(0f, spring.spec(0.5f)) }
        scope.launch { dy.animateTo(0f, spring.spec(0.5f)) }
    }

    // ---- choosing ------------------------------------------------------------------------------------------

    /** Chooses row [index] once: a destructive one is felt, then the entry closes and runs it. */
    fun commit(index: Int) {
        val item = items.getOrNull(index) as? MenuItem.Action ?: return
        if (!item.enabled || committed) return
        committed = true
        if (item.destructive) haptics().confirm()
        onCommit(item)
    }

    // ---- keyboard ------------------------------------------------------------------------------------------

    /** Arrow keys, Home, End and Enter. Returns whether the key was used. Escape and Tab belong to the entry. */
    fun onKey(key: Key): Boolean {
        when (key) {
            Key.DirectionDown -> moveFocus(stepRow(items, focusIndex, +1))
            Key.DirectionUp -> moveFocus(stepRow(items, focusIndex, -1))
            Key.MoveHome -> moveFocus(firstEnabledRow(items))
            Key.MoveEnd -> moveFocus(items.indexOfLast { it is MenuItem.Action && it.enabled })
            Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> commit(focusIndex)
            else -> return false
        }
        return true
    }

    private fun moveFocus(row: Int) {
        if (row < 0) return
        focusIndex = row
        highlighted.intValue = row
    }

    // ---- the plate -----------------------------------------------------------------------------------------

    /** Keeps the plate on [highlighted]: lights in place the first time, slides in 100 ms after, fades out. */
    suspend fun runPlate() {
        snapshotFlow { highlighted.intValue }.collectLatest { row ->
            if (row < 0) {
                if (reduceMotion()) plateAlpha.snapTo(0f) else plateAlpha.animateTo(0f, tween(PlateFadeMs))
                return@collectLatest
            }
            val top = rects[2 * row]
            val height = rects[2 * row + 1] - top
            if (top.isNaN() || height.isNaN()) return@collectLatest
            plateHeight.snapTo(height)
            if (plateAlpha.value < 0.01f || reduceMotion()) {
                plateTop.snapTo(top)
                plateAlpha.snapTo(1f)
            } else {
                coroutineScope {
                    launch { plateAlpha.animateTo(1f, tween(PlateFadeMs)) }
                    plateTop.animateTo(top, RovstiMotion.menuPlate())
                }
            }
        }
    }

    fun drawPlate(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        color: Color,
    ) {
        val alpha = plateAlpha.value
        if (alpha <= 0.001f || plateHeight.value <= 0f) return
        with(scope) {
            drawRoundRect(
                color = color,
                topLeft = Offset(0f, plateTop.value),
                size = Size(size.width, plateHeight.value),
                cornerRadius = CornerRadius(MenuMetrics.RowRadius.toPx()),
                alpha = alpha,
            )
        }
    }

    fun drawSheen(scope: androidx.compose.ui.graphics.drawscope.DrawScope) {
        val alpha = sheen.value
        if (alpha <= 0.001f) return
        with(scope) {
            drawRoundRect(
                brush =
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.11f), Color.White.copy(alpha = 0.025f)),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                cornerRadius = CornerRadius(MenuMetrics.CardRadius.toPx()),
                alpha = alpha,
            )
        }
    }

    private companion object {
        const val SlopDp = 8f
        const val SheenMs = 120
        const val PlateFadeMs = 80
    }
}

/**
 * The one pointer observer of the card. It watches on the Final pass and never consumes, so nothing else on the card
 * changes, and a drag that a scroll container takes over cancels the gesture. Its mere presence also keeps a touch
 * on the card's padding from reaching the scrim behind it.
 */
internal fun Modifier.menuPanelPointer(panel: MenuPanelState): Modifier =
    pointerInput(panel) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            var last = down.position
            var lifted = false
            panel.begin(last.x, last.y)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    last = change.position
                    if (!change.pressed) {
                        lifted = true
                        break
                    }
                    if (change.isConsumed && change.positionChange() != Offset.Zero) break
                    panel.move(last.x, last.y)
                }
            } finally {
                panel.end(last.x, last.y, lifted)
            }
        }
    }

/** The rows, stacked, with the highlight plate drawn behind them. Records each row's extent as it lays them out. */
@Composable
internal fun MenuRows(
    items: List<MenuItem>,
    panel: MenuPanelState,
    modifier: Modifier = Modifier,
) {
    val anyChecked = remember(items) { items.any { it is MenuItem.Action && it.checked != null } }
    val plateColor = RovstiTheme.glass.highlight
    Layout(
        content = {
            items.forEachIndexed { index, item ->
                when (item) {
                    MenuItem.Separator -> MenuSeparator()
                    is MenuItem.Action -> MenuRow(item, index, anyChecked, panel)
                }
            }
        },
        modifier = modifier.drawBehind { panel.drawPlate(this, plateColor) },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints(width, width, 0, Constraints.Infinity)) }
        val height = placeables.sumOf { it.height }
        layout(width, height) {
            var y = 0
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative(0, y)
                panel.place(index, y, y + placeable.height)
                y += placeable.height
            }
            panel.markReady()
        }
    }
}

@Composable
private fun MenuSeparator() {
    val color = RovstiTheme.colors.separator
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MenuMetrics.SeparatorMarginHorizontal, vertical = MenuMetrics.SeparatorMarginVertical)
            .height(MenuMetrics.SeparatorThickness)
            .drawBehind { drawRect(color) }
            .testTag("menu-separator"),
    )
}

@Composable
private fun MenuRow(
    item: MenuItem.Action,
    index: Int,
    reserveCheckSlot: Boolean,
    panel: MenuPanelState,
) {
    val colors = RovstiTheme.colors
    val tint = if (item.destructive) colors.destructive else colors.labelPrimary
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MenuMetrics.RowMinHeight)
            .graphicsLayer { alpha = if (item.enabled) 1f else MenuMetrics.DisabledAlpha }
            .semantics(mergeDescendants = true) {
                role = if (item.checked != null) Role.Checkbox else Role.Button
                item.checked?.let { selected = it }
                if (item.enabled) {
                    // Touch goes through the card's observer; this is the route for screen readers and switches.
                    onClick(null) {
                        panel.commit(index)
                        true
                    }
                } else {
                    disabled()
                }
            }.padding(horizontal = MenuMetrics.RowPaddingHorizontal, vertical = MenuMetrics.RowPaddingVertical)
            .testTag("menu-row-$index"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MenuMetrics.RowGap),
    ) {
        item.icon?.let { RovstiIcon(it, contentDescription = null, tint = tint, size = MenuMetrics.IconSize) }
        RovstiText(
            item.label,
            style = RovstiTheme.type.callout,
            color = tint,
            modifier = Modifier.weight(1f),
        )
        when (item.checked) {
            true -> {
                RovstiIcon(RovstiIcons.Checkmark, contentDescription = null, tint = tint, size = MenuMetrics.IconSize)
            }

            false -> {
                Spacer(Modifier.size(MenuMetrics.IconSize))
            }

            null -> {
                if (reserveCheckSlot) Spacer(Modifier.width(MenuMetrics.IconSize))
            }
        }
    }
}
