package dev.rovsti.design.overlay

import android.os.Build
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.theme.RovstiTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a long-pressed element shares with its menu: its window bounds ([anchor]) and how much of it is hidden
 * ([cover], 0..1) while a lifted copy stands in for it. A plain field and a float state read in the draw phase only.
 */
@Stable
class ContextMenuTarget {
    val anchor = AnchorHolder()
    val cover = mutableFloatStateOf(0f)
}

/** An entry that follows the finger that opened it: see [contextMenu]'s `onPreviewDrag` and `onPreviewRelease`. */
interface PreviewDragTarget {
    /** The finger is [offset] away from where it went down. */
    fun drag(offset: Offset)

    /** The finger was lifted or taken away. */
    fun release()
}

/** How a long-pressed element is shown while its menu is open. */
sealed interface ContextMenuStyle {
    /** The element itself, drawn by [preview], lifts 5 % and the action panel sits next to it (rows, chips). */
    class Lifted(
        val preview: @Composable () -> Unit,
    ) : ContextMenuStyle

    /** The element grows into a [card] [cardHeight] tall and the action panel hangs under it (contacts, stops). */
    class Card(
        val card: @Composable () -> Unit,
        val cardHeight: Dp,
    ) : ContextMenuStyle
}

/**
 * Long-press menu on this element, all wired (ORIGINAL): the gesture of [contextMenu] with its press feedback, the
 * entry for [style], the element hidden while its copy is shown, and the finger handed to the entry for dragging.
 * A tap calls [onTap]; the element's own click handling must be left to this modifier (a child button still wins its
 * own taps). [items] are the actions of the panel and, for screen readers, the custom accessibility actions.
 */
@Composable
fun Modifier.longPressMenu(
    controller: OverlayController,
    items: ImmutableList<MenuItem>,
    style: ContextMenuStyle,
    onTap: (() -> Unit)? = null,
    label: String = "Show actions",
): Modifier {
    val target = remember { ContextMenuTarget() }
    val press = rememberPressRamp()
    val live = remember { arrayOfNulls<OverlayEntry>(1) }
    val latestItems = androidx.compose.runtime.rememberUpdatedState(items)
    val latestStyle = androidx.compose.runtime.rememberUpdatedState(style)
    return contextMenu(
        anchor = target.anchor,
        press = press,
        onTap = onTap,
        onOpen = { rect ->
            val current = live[0]
            if (current == null || !current.state.targetState) {
                val entry =
                    when (val s = latestStyle.value) {
                        is ContextMenuStyle.Lifted -> {
                            target.cover.floatValue = 1f
                            ContextMenuEntry(rect, latestItems.value, target, s.preview)
                        }

                        is ContextMenuStyle.Card -> {
                            PreviewCardEntry(rect, latestItems.value, target, s.card, s.cardHeight)
                        }
                    }
                live[0] = entry
                controller.present(entry)
            }
        },
        onPreviewDrag = { (live[0] as? PreviewDragTarget)?.drag(it) },
        onPreviewRelease = { (live[0] as? PreviewDragTarget)?.release() },
        actions = items,
        label = label,
        cover = { target.cover.floatValue },
    )
}

/** Share of the open progress after which the panel's alpha has reached 1 (SKILL overlays 3.2). */
internal const val PanelAlphaRamp = 0.35f

/** Blur radius of the panel while it condenses on opening. */
internal val ClearingBlur = 20.dp

/** A blur that clears while a panel opens; none while it closes (a plain fade-shrink reads better there). */
internal fun GraphicsLayerScope.clearingBlur(
    progress: Float,
    opening: Boolean,
) {
    val radius = if (opening) ClearingBlur.toPx() * (1f - progress).coerceIn(0f, 1f) else 0f
    renderEffect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && radius >= 0.5f) {
            BlurEffect(radius, radius, TileMode.Decal)
        } else {
            null
        }
}

private const val LiftScale = 1.05f
private const val LiftInMs = 150
private const val LiftOutMs = 120
private const val ReducedFadeMs = 100
private val LiftRadius = 16.dp
private val LiftShadow = 14.dp
private val PanelGap = 8.dp
private const val PreviewId = "preview"
private const val PanelId = "panel"

/**
 * The context menu of rows and chips (ORIGINAL; SKILL overlays 3.2). The scrim dims the page (0.28 dark, 0.14 light),
 * a *dedicated* copy of the element, drawn by [preview] at the element's exact window rect, lifts to 1.05 (150 ms in,
 * 120 ms out) while the real element goes fully transparent (its layout is untouched), and the action panel opens
 * next to it with a 0.78/700 spring and scales per axis out of the element's centre, blurred at first. It closes with
 * a 140 ms fade-shrink. The `LongPress` haptic plays when it opens.
 *
 * [target] receives the hiding of the real element; [anchor] is its window rect, captured once at the long press.
 */
class ContextMenuEntry(
    private val anchor: Rect,
    private val items: ImmutableList<MenuItem> = persistentListOf(),
    private val target: ContextMenuTarget? = null,
    private val preview: @Composable () -> Unit,
) : OverlayEntry(modal = true, title = "Actions") {
    @Composable
    override fun Content(host: OverlayScope) {
        val reduceMotion = LocalReduceMotion.current
        val transition = rememberTransition(state, label = "context-menu")
        val scrim =
            transition.animateFloat(
                transitionSpec = { if (targetState) RovstiMotion.scrimIn() else RovstiMotion.scrimOut() },
                label = "scrim",
            ) { if (it) 1f else 0f }
        val lift =
            transition.animateFloat(
                transitionSpec = {
                    when {
                        reduceMotion -> tween(1)
                        targetState -> tween(LiftInMs)
                        else -> tween(LiftOutMs)
                    }
                },
                label = "lift",
            ) { if (it) 1f else 0f }
        val progress =
            transition.animateFloat(
                transitionSpec = {
                    when {
                        reduceMotion -> tween(ReducedFadeMs)
                        targetState -> spring(PanelDamping, PanelStiffness, 0.001f)
                        else -> RovstiMotion.menuExit()
                    }
                },
                label = "panel",
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
        val origin = remember { arrayOf(TransformOrigin.Center) }
        val colors = RovstiTheme.colors
        val liftShape = remember { RoundedCornerShape(LiftRadius) }

        LaunchedEffect(Unit) { haptics.longPress() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        DisposableEffect(Unit) {
            target?.cover?.floatValue = 1f
            onDispose { target?.cover?.floatValue = 0f }
        }

        OverlayScrim(progress = { scrim.value }, lightAlpha = ScrimLight, darkAlpha = ScrimDark, onTap = dismiss)
        Layout(
            content = {
                Box(
                    Modifier
                        .layoutId(PreviewId)
                        .testTag("lifted-row")
                        .clip(liftShape)
                        .background(colors.groupedSurface)
                        .clearAndSetSemantics { }
                        .pointerInput(Unit) { detectTapGestures { dismiss() } },
                ) {
                    preview()
                }
                ContextPanelCard(items, panel, focus, dismiss, Modifier.layoutId(PanelId))
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val margin = MenuMetrics.EdgeMargin.roundToPx()
            val width =
                min(MenuMetrics.CardWidth.roundToPx(), (host.safe.width - 2 * margin).roundToInt()).coerceAtLeast(0)
            val maxHeight = panelMaxHeight(host.safe, margin.toFloat()).roundToInt()
            val panelP =
                measurables.first { it.layoutId == PanelId }.measure(Constraints(width, width, 0, maxHeight))
            val previewP =
                measurables
                    .first { it.layoutId == PreviewId }
                    .measure(
                        Constraints.fixed(
                            anchor.width.roundToInt().coerceAtLeast(1),
                            anchor.height.roundToInt().coerceAtLeast(1),
                        ),
                    )
            // The panel keeps its gap from the *lifted* copy.
            val grow = (LiftScale - 1f) / 2f
            val lifted =
                Rect(
                    anchor.left - anchor.width * grow,
                    anchor.top - anchor.height * grow,
                    anchor.right + anchor.width * grow,
                    anchor.bottom + anchor.height * grow,
                )
            val placement =
                placePanel(
                    anchor = lifted,
                    panel = Size(panelP.width.toFloat(), panelP.height.toFloat()),
                    window = host.windowSize,
                    safe = host.safe,
                    gap = PanelGap.toPx(),
                    margin = margin.toFloat(),
                    rtl = rtl,
                )
            origin[0] = TransformOrigin(placement.origin.x, placement.origin.y)
            geometry.left = placement.topLeft.x
            geometry.top = placement.topLeft.y
            geometry.width = panelP.width.toFloat()
            geometry.height = panelP.height.toFloat()
            geometry.padding = MenuMetrics.CardPadding.toPx()
            geometry.upward = placement.side == Side.Above
            val anchorWidth = anchor.width
            val anchorHeight = anchor.height
            layout(constraints.maxWidth, constraints.maxHeight) {
                previewP.placeWithLayer(anchor.left.roundToInt(), anchor.top.roundToInt()) {
                    val l = if (reduceMotion) 0f else lift.value
                    val s = 1f + (LiftScale - 1f) * l
                    scaleX = s
                    scaleY = s
                    shadowElevation = LiftShadow.toPx() * l
                    shape = liftShape
                    clip = false
                }
                panelP.placeWithLayer(placement.topLeft.x.roundToInt(), placement.topLeft.y.roundToInt()) {
                    val p = progress.value
                    alpha = (p / PanelAlphaRamp).coerceIn(0f, 1f)
                    if (!reduceMotion) {
                        val sx0 = (anchorWidth / size.width.coerceAtLeast(1f)).coerceIn(MinPanelScale, 1f)
                        val sy0 = (anchorHeight / size.height.coerceAtLeast(1f)).coerceIn(MinPanelScale, 1f)
                        scaleX = sx0 + (1f - sx0) * p
                        scaleY = sy0 + (1f - sy0) * p
                        transformOrigin = origin[0]
                        clearingBlur(p, opening = state.targetState)
                    }
                }
            }
        }
    }

    private companion object {
        const val PanelDamping = 0.78f
        const val PanelStiffness = 700f
        const val MinPanelScale = 0.08f
        const val ScrimLight = 0.14f
        const val ScrimDark = 0.28f
    }
}
