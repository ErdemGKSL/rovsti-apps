package dev.rovsti.design.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.rovsti.design.glass.GlassSource
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.theme.RovstiTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Drag of the card while the finger is down: sideways resists to 0.6, upward to 0.3, downward follows 1:1. */
internal fun previewDragMapping(raw: Offset): Offset =
    Offset(raw.x * PreviewDragSide, if (raw.y < 0f) raw.y * PreviewDragUp else raw.y)

private const val PreviewDragSide = 0.6f
private const val PreviewDragUp = 0.3f

/** The card is shown fully once the lift has passed this share; below it the real element cross-fades in. */
internal const val CrossFadeShare = 0.3f

/** Share of the real element that is still visible at [lift]: 1 at rest, 0 once the card has taken over. */
internal fun elementShare(lift: Float): Float = (1f - lift / CrossFadeShare).coerceIn(0f, 1f)

/** Distance of the drag at which the panel has faded out completely. */
internal val PanelFadeDistance = 120.dp

/** Drag distance at which a released card is dismissed. */
internal val DismissDistance = 64.dp

private const val ReducedFadeMs = 100
private const val CardId = "card"
private const val PanelId = "panel"
private const val MinScale = 0.05f
private const val PanelMinX = 0.25f
private const val PanelMinY = 0.08f
private val CardShadow = 18.dp

/**
 * The context menu of contacts, stops and messages (ORIGINAL; SKILL overlays 3.3): the long-pressed element grows into
 * a [card] and the action panel hangs under it.
 *
 * The card is laid out by [previewCardLayout]. It starts as a miniature exactly over the element (same width, same
 * top-left corner) and lifts to full size with `menuLift` (0.80/420), lands back with `menuLand` (1/520); in the first
 * 30 % of the lift it cross-fades with the real element, whose alpha follows through [target]. The panel grows from the
 * card's corner on the element's side: horizontally with `menuGrowX` (it leads), vertically with `menuGrowY`.
 *
 * While the finger that opened the menu is still down the card follows it ([previewDragMapping]) and the panel fades
 * out over 120 dp. Releasing past 64 dp downward, or 40 % of the way back to the element, dismisses; otherwise the card
 * returns with `previewReturn`. Closing runs the same animation backwards, the drag fading with the lift.
 */
class PreviewCardEntry(
    private val anchor: Rect,
    private val items: ImmutableList<MenuItem> = persistentListOf(),
    private val target: ContextMenuTarget? = null,
    private val source: GlassSource? = null,
    private val card: @Composable () -> Unit,
    private val cardHeight: Dp = 200.dp,
) : OverlayEntry(modal = true, title = "Preview and actions"),
    PreviewDragTarget {
    private val rawX = mutableFloatStateOf(0f)
    private val rawY = mutableFloatStateOf(0f)

    /** 1 while the card follows the finger; springs to 0 on a return. */
    private val hold = Animatable(1f)
    private val returns = mutableIntStateOf(0)

    // Written by the layout pass, read when the finger lifts.
    private var home = Offset.Zero
    private var dismissPx = 0f
    private var controller: OverlayController? = null

    override fun drag(offset: Offset) {
        rawX.floatValue = offset.x
        rawY.floatValue = offset.y
    }

    override fun release() {
        val applied = previewDragMapping(Offset(rawX.floatValue, rawY.floatValue))
        if (shouldDismissPreview(applied, home, dismissPx)) {
            controller?.dismiss(this)
        } else {
            returns.intValue++
        }
    }

    private fun applied(): Offset {
        val mapped = previewDragMapping(Offset(rawX.floatValue, rawY.floatValue))
        val h = hold.value
        return Offset(mapped.x * h, mapped.y * h)
    }

    @Composable
    override fun Content(host: OverlayScope) {
        val reduceMotion = LocalReduceMotion.current
        val transition = rememberTransition(state, label = "preview-card")
        val scrim =
            transition.animateFloat(
                transitionSpec = { if (targetState) RovstiMotion.scrimIn() else RovstiMotion.scrimOut() },
                label = "scrim",
            ) { if (it) 1f else 0f }
        val lift =
            transition.animateFloat(
                transitionSpec = {
                    when {
                        reduceMotion -> tween(ReducedFadeMs)
                        targetState -> RovstiMotion.menuLift(0.001f)
                        else -> RovstiMotion.menuLand(0.001f)
                    }
                },
                label = "lift",
            ) { if (it) 1f else 0f }
        val growX =
            transition.animateFloat(
                transitionSpec = {
                    when {
                        reduceMotion -> tween(ReducedFadeMs)
                        targetState -> RovstiMotion.menuGrowX(0.001f)
                        else -> RovstiMotion.menuExit()
                    }
                },
                label = "grow-x",
            ) { if (it) 1f else 0f }
        val growY =
            transition.animateFloat(
                transitionSpec = {
                    when {
                        reduceMotion -> tween(ReducedFadeMs)
                        targetState -> RovstiMotion.menuGrowY(0.001f)
                        else -> RovstiMotion.menuExit()
                    }
                },
                label = "grow-y",
            ) { if (it) 1f else 0f }

        val geometry = remember { CardGeometry() }
        val focus = remember { FocusRequester() }
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val dismiss = { host.controller.dismiss(this) }
        val panel =
            rememberContextPanelState(items, geometry, host) { item ->
                host.controller.dismiss(this)
                item.onClick()
            }
        val haptics = LocalRovstiHaptics.current
        val colors = RovstiTheme.colors
        val cardShape = remember { RoundedCornerShape(PreviewCardMetrics.CardRadius.dp) }
        val trailingSide = remember { BooleanArray(1) }

        controller = host.controller
        LaunchedEffect(Unit) { haptics.longPress() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        LaunchedEffect(returns.intValue) {
            if (returns.intValue > 0) hold.animateTo(0f, RovstiMotion.previewReturn(0.001f))
        }
        // The real element fades back in over the last 30 % of the landing, while the card fades out.
        LaunchedEffect(target) {
            snapshotFlow { 1f - elementShare(lift.value) }.collect { target?.cover?.floatValue = it }
        }
        DisposableEffect(Unit) { onDispose { target?.cover?.floatValue = 0f } }

        OverlayScrim(progress = { scrim.value }, lightAlpha = ScrimLight, darkAlpha = ScrimDark, onTap = dismiss)
        Layout(
            content = {
                Box(
                    Modifier
                        .layoutId(CardId)
                        .testTag("preview-card")
                        .clip(cardShape)
                        .background(colors.groupedSurface)
                        .pointerInput(Unit) { detectTapGestures { dismiss() } },
                ) {
                    card()
                }
                ContextPanelCard(items, panel, source, focus, dismiss, Modifier.layoutId(PanelId))
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val margin = MenuMetrics.EdgeMargin.roundToPx()
            val width =
                min(MenuMetrics.CardWidth.roundToPx(), (host.safe.width - 2 * margin).roundToInt()).coerceAtLeast(0)
            val maxHeight = panelMaxHeight(host.safe, margin.toFloat()).roundToInt()
            val panelP =
                measurables.first { it.layoutId == PanelId }.measure(Constraints(width, width, 0, maxHeight))
            val density = host.density.density
            val placement =
                previewCardLayout(
                    item = anchor,
                    window = host.windowSize,
                    safe = host.safe,
                    panelSize = Size(panelP.width.toFloat(), panelP.height.toFloat()),
                    cardHeight = cardHeight.toPx(),
                    pxPerDp = density,
                    rtl = rtl,
                )
            val cardRect = placement.card
            val cardP =
                measurables
                    .first { it.layoutId == CardId }
                    .measure(Constraints.fixed(cardRect.width.roundToInt(), cardRect.height.roundToInt()))
            home = anchor.topLeft - cardRect.topLeft
            dismissPx = DismissDistance.toPx()
            trailingSide[0] = placement.trailing
            geometry.left = placement.panel.left
            geometry.top = placement.panel.top
            geometry.width = panelP.width.toFloat()
            geometry.height = panelP.height.toFloat()
            geometry.padding = MenuMetrics.CardPadding.toPx()
            val miniature = (anchor.width / cardRect.width).coerceIn(MinScale, 1f)
            val fadeDistance = PanelFadeDistance.toPx()
            layout(constraints.maxWidth, constraints.maxHeight) {
                cardP.placeWithLayer(cardRect.left.roundToInt(), cardRect.top.roundToInt()) {
                    val l = if (reduceMotion) 1f else lift.value
                    val s = miniature + (1f - miniature) * l
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(0f, 0f)
                    val drag = applied()
                    translationX = home.x * (1f - l) + drag.x * l
                    translationY = home.y * (1f - l) + drag.y * l
                    alpha = (lift.value / CrossFadeShare).coerceIn(0f, 1f)
                    shadowElevation = CardShadow.toPx() * l.coerceIn(0f, 1f)
                    shape = cardShape
                    clip = false
                }
                panelP.placeWithLayer(placement.panel.left.roundToInt(), placement.panel.top.roundToInt()) {
                    val gx = growX.value
                    val gy = growY.value
                    val drag = applied()
                    val away = (hypot(drag.x, drag.y) / fadeDistance).coerceIn(0f, 1f)
                    alpha = (3f * gx).coerceIn(0f, 1f) * (1f - away)
                    if (!reduceMotion) {
                        scaleX = PanelMinX + (1f - PanelMinX) * gx
                        scaleY = PanelMinY + (1f - PanelMinY) * gy
                        transformOrigin = TransformOrigin(if (trailingSide[0]) 1f else 0f, 0f)
                        clearingBlur(gx, opening = state.targetState)
                    }
                }
            }
        }
    }

    private companion object {
        const val ScrimLight = 0.14f
        const val ScrimDark = 0.28f
    }
}
