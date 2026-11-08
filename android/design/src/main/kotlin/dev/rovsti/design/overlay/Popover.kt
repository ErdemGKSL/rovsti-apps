// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// The morph (`menuMorphFrame`: glass grows out of the anchor, content scales up from it, unblurs, fades in later) follows
// glisse `src/menu.tsx`, here without the trigger glyph; the layout, the scroll cap and the rendering are Rovsti's own.
package dev.rovsti.design.overlay

import android.os.Build
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.rovsti.design.glass.panelGlass
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.theme.RovstiTheme
import kotlin.math.min
import kotlin.math.roundToInt

/** Dimensions of a popover. */
object PopoverMetrics {
    /** The widest a popover gets; narrower content gives a narrower popover. */
    val MaxWidth = 320.dp
    val CornerRadius = 24.dp

    /** Between the popover's edge and the safe area. */
    val EdgeMargin = 12.dp
}

/**
 * A popover with arbitrary [content] (a date chooser, a small form), arrowless (ORIGINAL layout, glisse morph).
 *
 * It grows out of [anchor] the way the button menu does, but carries no glyph. It is at most [maxWidth] wide, placed by
 * [placePanel] below the anchor or above it where there is no room below, and as tall as its content up to
 * [panelMaxHeight]; taller content scrolls inside. The scrim is transparent but eats every touch, and a tap on it
 * closes the popover. Opening never changes the page's layout: the entry is a sibling of the page in the host.
 */
class PopoverEntry(
    private val anchor: Rect,
    private val maxWidth: Dp = PopoverMetrics.MaxWidth,
    title: String = "Popover",
    private val content: @Composable () -> Unit,
) : OverlayEntry(modal = true, title = title) {
    @Composable
    override fun Content(host: OverlayScope) {
        val reduceMotion = LocalReduceMotion.current
        val transition = rememberTransition(state, label = "popover")
        val t: State<Float> =
            transition.animateFloat(
                transitionSpec = {
                    when {
                        reduceMotion -> tween(ReducedFadeMs)
                        targetState -> RovstiMotion.MenuMorphOpen.spec(0.001f)
                        else -> RovstiMotion.MenuMorphClose.spec(0.001f)
                    }
                },
                label = "popover-t",
            ) { if (it) 1f else 0f }

        val geometry = remember { CardGeometry() }
        val focus = remember { FocusRequester() }
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val dismiss = { host.controller.dismiss(this) }
        val latestContent = rememberUpdatedState(content)

        // Transparent, but it still eats input and closes on a tap.
        OverlayScrim(progress = { t.value }, lightAlpha = 0f, darkAlpha = 0f, onTap = { dismiss() })
        Box(
            Modifier.fillMaxSize().layout { measurable, constraints ->
                val margin = PopoverMetrics.EdgeMargin.roundToPx().toFloat()
                val width =
                    min(maxWidth.roundToPx().toFloat(), host.safe.width - 2f * margin).roundToInt().coerceAtLeast(0)
                val maxHeight = panelMaxHeight(host.safe, margin).roundToInt()
                val placeable = measurable.measure(Constraints(0, width, 0, maxHeight))
                // gap = -anchor height: like the button menu, the glass grows out of the anchor itself.
                val placement =
                    placePanel(
                        anchor = anchor,
                        panel = Size(placeable.width.toFloat(), placeable.height.toFloat()),
                        window = host.windowSize,
                        safe = host.safe,
                        gap = -anchor.height,
                        margin = margin,
                        rtl = rtl,
                    )
                geometry.upward = placement.side == Side.Above
                geometry.left = placement.topLeft.x
                geometry.top = placement.topLeft.y
                geometry.width = placeable.width.toFloat()
                geometry.height = placeable.height.toFloat()
                geometry.trailing = anchor.center.x > placement.topLeft.x + placeable.width / 2f
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(IntOffset(placement.topLeft.x.roundToInt(), placement.topLeft.y.roundToInt()))
                }
            },
        ) {
            PopoverCard(t, geometry, focus, dismiss, reduceMotion, latestContent)
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }

    @Composable
    private fun PopoverCard(
        t: State<Float>,
        geometry: CardGeometry,
        focus: FocusRequester,
        dismiss: () -> Unit,
        reduceMotion: Boolean,
        body: State<@Composable () -> Unit>,
    ) {
        val cornerDp = PopoverMetrics.CornerRadius.value
        val panelShape = remember(cornerDp) { RoundedCornerShape(cornerDp.dp) }
        val from = Size(anchor.width, anchor.height)
        val scrollState = rememberScrollState()

        fun Density.frame(
            cardSize: Size,
            progress: Float,
        ): MenuMorphFrame =
            menuMorphFrame(
                value = if (reduceMotion) 1f else progress,
                from = from,
                to = cardSize,
                cornerPx = cornerDp * density,
                trailing = geometry.trailing,
                upward = geometry.upward,
                leaving = !state.targetState,
                pxPerDp = density,
            )

        Box(
            Modifier
                .testTag("popover-card")
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        dismiss()
                        true
                    } else {
                        false
                    }
                },
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val f = frame(size, t.value)
                        alpha = if (reduceMotion) t.value.coerceIn(0f, 1f) else f.surfaceAlpha
                        if (f.settled || reduceMotion) {
                            clip = false
                            shape = RectangleShape
                        } else {
                            clip = true
                            shape =
                                SurfaceShape(
                                    f.surfaceLeft,
                                    f.surfaceTop,
                                    f.surfaceWidth,
                                    f.surfaceHeight,
                                    surfaceRadius(from, cornerDp * density, t.value),
                                )
                        }
                    }.panelGlass(panelShape, "popover.panel"),
            )
            Box(
                Modifier
                    .graphicsLayer {
                        val f = frame(size, t.value)
                        if (reduceMotion) {
                            alpha = t.value.coerceIn(0f, 1f)
                        } else {
                            alpha = f.contentAlpha
                            scaleX = f.contentScale
                            scaleY = f.contentScale
                            transformOrigin = TransformOrigin(f.originX / size.width, f.originY / size.height)
                            val blur = f.contentBlurPx
                            renderEffect =
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blur >= 0.5f) {
                                    BlurEffect(blur, blur, TileMode.Decal)
                                } else {
                                    null
                                }
                            if (f.settled) {
                                clip = false
                            } else {
                                clip = true
                                shape =
                                    SurfaceShape(
                                        f.clipLeft,
                                        f.clipTop,
                                        f.clipRight - f.clipLeft,
                                        f.clipBottom - f.clipTop,
                                        f.clipRadius,
                                    )
                            }
                        }
                    }.verticalScroll(scrollState)
                    .testTag("popover-scroll"),
            ) {
                body.value()
            }
        }
    }

    private companion object {
        const val ReducedFadeMs = 100
    }
}

/**
 * Makes this element open a [PopoverEntry] with [content] on click, anchored to its bounds at that moment. Tapping it
 * again while the popover is open closes it. The element keeps its size and position.
 */
@Composable
fun Modifier.popoverTrigger(
    controller: OverlayController,
    label: String = "Open popover",
    content: @Composable () -> Unit,
): Modifier {
    val holder = remember { AnchorHolder() }
    val live = remember { arrayOfNulls<PopoverEntry>(1) }
    val latest = rememberUpdatedState(content)

    fun toggle() {
        val current = live[0]
        if (current != null && current.state.targetState) {
            controller.dismiss(current)
        } else {
            val entry =
                PopoverEntry(holder.rect, title = label) { latest.value() }
            live[0] = entry
            controller.present(entry)
        }
    }
    return this
        .captureAnchor(holder)
        .clickable(role = Role.Button, onClickLabel = label) { toggle() }
}
