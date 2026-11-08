// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// The morph timeline (what grows, fades and blurs when) follows glisse `src/menu.tsx`; the rendering is Rovsti's own.
package dev.rovsti.design.overlay

import android.os.Build
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.rovsti.design.glass.GlassSource
import dev.rovsti.design.glass.GlassSpec
import dev.rovsti.design.glass.GlassTier
import dev.rovsti.design.glass.LocalGlassConditions
import dev.rovsti.design.glass.glass
import dev.rovsti.design.glass.glassTier
import dev.rovsti.design.glass.rememberGlassSource
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where the card ended up. Plain fields written during layout and read during draw: no state, no recomposition. */
private class CardGeometry {
    var trailing = false
    var upward = false
}

/**
 * The menu that grows out of a button (glisse `menu.tsx` morph; the rendering is ORIGINAL).
 *
 * One transition value `t` drives everything: the glass grows from the trigger's rect to the card, the trigger's
 * glyph drifts and blurs away, the rows scale up from the trigger centre and unblur, later than the glass. The card
 * has its final size and position from the first frame, so nothing is relaid out while it morphs: the surface is a
 * rounded rect drawn at the frame's rect, the rows sit in a layer that is scaled, faded, blurred and clipped, and both
 * effects are dropped once `t` reaches 1. Opening and closing are the same animation run in either direction and
 * retargeted (never restarted) when the direction changes, so a fast tap-tap keeps its velocity. The entry stays
 * composed until `t` is back at 0.
 *
 * [anchor] is the trigger's rect in window pixels, captured once by the caller. [source] is the page's glass source
 * (for the glass tiers); without one, and in every test, the surface is the Flat popover fill. With reduce motion the
 * card does not morph, it fades in 100 ms; with reduce transparency its fill is opaque.
 */
class ButtonMenuEntry(
    private val anchor: Rect,
    private val items: ImmutableList<MenuItem>,
    private val source: GlassSource? = null,
    private val triggerGlyph: ImageVector? = RovstiIcons.Ellipsis,
) : OverlayEntry(modal = true, title = "Menu") {
    @Composable
    override fun Content(host: OverlayScope) {
        val reduceMotion = LocalReduceMotion.current
        val transition = rememberTransition(state, label = "button-menu")
        val t: State<Float> =
            transition.animateFloat(
                transitionSpec = {
                    when {
                        reduceMotion -> tween(ReducedFadeMs)
                        targetState -> RovstiMotion.MenuMorphOpen.spec(0.001f)
                        else -> RovstiMotion.MenuMorphClose.spec(0.001f)
                    }
                },
                label = "button-menu-t",
            ) { if (it) 1f else 0f }

        val geometry = remember { CardGeometry() }
        val focus = remember { FocusRequester() }
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val dismiss = { host.controller.dismiss(this) }

        OverlayScrim(progress = { t.value }, onTap = { dismiss() })
        Box(
            Modifier.fillMaxSize().layout { measurable, constraints ->
                val margin = MenuMetrics.EdgeMargin.roundToPx().toFloat()
                val width =
                    min(MenuMetrics.CardWidth.roundToPx().toFloat(), host.safe.width - 2f * margin)
                        .roundToInt()
                        .coerceAtLeast(0)
                val maxHeight = panelMaxHeight(host.safe, margin).roundToInt()
                val placeable = measurable.measure(Constraints(width, width, 0, maxHeight))
                // gap = -anchor height: the card's top (below) or bottom (above) edge lines up with the trigger's, so the
                // glass can grow out of the trigger itself.
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
                geometry.trailing = anchor.center.x > placement.topLeft.x + placeable.width / 2f
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(IntOffset(placement.topLeft.x.roundToInt(), placement.topLeft.y.roundToInt()))
                }
            },
        ) {
            MenuCard(t, geometry, focus, host, dismiss, reduceMotion)
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }

    @Composable
    private fun MenuCard(
        t: State<Float>,
        geometry: CardGeometry,
        focus: FocusRequester,
        host: OverlayScope,
        dismiss: () -> Unit,
        reduceMotion: Boolean,
    ) {
        val colors = RovstiTheme.colors
        val conditions = LocalGlassConditions.current
        val sourceNow = source
        val tier = glassTier(conditions.copy(sourceAvailable = sourceNow != null))
        val cornerDp = MenuMetrics.CardRadius.value
        val baseSpec = GlassSpec.popover(RovstiTheme.glass, colors, tier, cornerDp)
        // The Flat fallback has no blur, so rows and the trigger behind the card would read through it: opaque there,
        // and with reduce transparency.
        val spec =
            when {
                conditions.reduceTransparency -> baseSpec.copy(flatAlpha = 1f, fill = baseSpec.flatBase)
                tier == GlassTier.Flat -> baseSpec.copy(flatAlpha = 1f)
                else -> baseSpec
            }
        val glassSource = sourceNow ?: rememberGlassSource()
        val from = Size(anchor.width, anchor.height)
        val glyph = triggerGlyph?.let { rememberVectorPainter(it) }
        val glyphTint = colors.labelPrimary
        val leaving: () -> Boolean = { !state.targetState }

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
                leaving = leaving(),
                pxPerDp = density,
            )

        Box(
            Modifier
                .testTag("menu-card")
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        dismiss()
                        true
                    } else {
                        false
                    }
                }.pointerInput(Unit) { detectTapGestures { } }, // taps on the card's padding must not reach the scrim
        ) {
            // The surface: the card's glass, clipped to the growing rect. The clip goes when the card has settled.
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val f = frame(size, t.value)
                        alpha = if (reduceMotion) t.value.coerceIn(0f, 1f) else f.surfaceAlpha
                        if (f.settled || reduceMotion) {
                            clip = false
                            shape = androidx.compose.ui.graphics.RectangleShape
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
                    }.glass(glassSource, spec),
            )
            // The rows, scaled up from the trigger centre. Their layer covers the whole card (the padding is inside).
            Column(
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
                    }.padding(MenuMetrics.CardPadding)
                    .verticalScroll(rememberScrollState()),
            ) {
                val anyChecked = items.any { it is MenuItem.Action && it.checked != null }
                items.forEachIndexed { index, item ->
                    when (item) {
                        MenuItem.Separator -> {
                            MenuSeparator()
                        }

                        is MenuItem.Action -> {
                            MenuRow(item, index, anyChecked) {
                                host.controller.dismiss(this@ButtonMenuEntry)
                                item.onClick()
                            }
                        }
                    }
                }
            }
            // The trigger's glyph: a copy at the trigger's place that drifts to the middle, swells and blurs away.
            if (glyph != null && !reduceMotion) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val blur = frame(size, t.value).glyphBlurPx
                            renderEffect =
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blur >= 0.5f) {
                                    BlurEffect(blur, blur, TileMode.Decal)
                                } else {
                                    null
                                }
                        }.drawBehind {
                            val f = frame(size, t.value)
                            if (f.glyphAlpha <= 0f) return@drawBehind
                            val g = MenuMetrics.IconSize.toPx()
                            val cx = f.originX + f.glyphDx
                            val cy = f.originY + f.glyphDy
                            withTransform({
                                translate(cx - g / 2f, cy - g / 2f)
                                scale(
                                    f.glyphScale,
                                    f.glyphScale,
                                    pivot =
                                        androidx.compose.ui.geometry.Offset(
                                            g / 2f,
                                            g / 2f,
                                        ),
                                )
                            }) {
                                with(glyph) {
                                    draw(Size(g, g), alpha = f.glyphAlpha, colorFilter = ColorFilter.tint(glyphTint))
                                }
                            }
                        },
                )
            }
        }
    }

    private companion object {
        const val ReducedFadeMs = 100
    }
}

/** Corner radius of the growing glass: from half the trigger's short side (a capsule) to the card's own radius. */
private fun surfaceRadius(
    from: Size,
    cardCornerPx: Float,
    t: Float,
): Float {
    val capsule = min(from.width, from.height) / 2f
    return capsule + (cardCornerPx - capsule) * t.coerceIn(0f, 1f)
}

/** A rounded rectangle at an arbitrary rect of the layer, used as the animated clip of the surface and the rows. */
private class SurfaceShape(
    private val left: Float,
    private val top: Float,
    private val width: Float,
    private val height: Float,
    private val radius: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = max(0f, min(radius, min(width, height) / 2f))
        return Outline.Rounded(RoundRect(left, top, left + max(0f, width), top + max(0f, height), CornerRadius(r)))
    }
}

@Composable
private fun MenuSeparator() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MenuMetrics.SeparatorMarginHorizontal, vertical = MenuMetrics.SeparatorMarginVertical)
            .height(MenuMetrics.SeparatorThickness)
            .background(RovstiTheme.colors.separator)
            .testTag("menu-separator"),
    )
}

@Composable
private fun MenuRow(
    item: MenuItem.Action,
    index: Int,
    reserveCheckSlot: Boolean,
    onCommit: () -> Unit,
) {
    val colors = RovstiTheme.colors
    val tint = if (item.destructive) colors.destructive else colors.labelPrimary
    val commit by rememberUpdatedState(onCommit)
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MenuMetrics.RowMinHeight)
            .graphicsLayer { alpha = if (item.enabled) 1f else MenuMetrics.DisabledAlpha }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = item.enabled,
                role = Role.Button,
            ) { commit() }
            .semantics(mergeDescendants = true) { item.checked?.let { selected = it } }
            .padding(horizontal = MenuMetrics.RowPaddingHorizontal, vertical = MenuMetrics.RowPaddingVertical)
            .testTag("menu-row-$index"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            androidx.compose.foundation.layout.Arrangement
                .spacedBy(MenuMetrics.RowGap),
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
                RovstiIcon(
                    RovstiIcons.Checkmark,
                    contentDescription = null,
                    tint = tint,
                    size = MenuMetrics.IconSize,
                )
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

/**
 * Makes this element open a [ButtonMenuEntry] on *down* (not on lift), anchored to the element's bounds as they were
 * at that moment. The element keeps its size and position while the menu is open (a pressed look is the caller's
 * business and must only change draw-phase properties). Use it on an element that has no click action of its own.
 * Touching it while its menu is open dismisses the menu (the scrim normally catches that touch first).
 */
@Composable
fun Modifier.menuTrigger(
    controller: OverlayController,
    items: ImmutableList<MenuItem>,
    glyph: ImageVector? = RovstiIcons.Ellipsis,
    source: GlassSource? = null,
    label: String = "Open menu",
): Modifier {
    val holder = remember { AnchorHolder() }
    val live = remember { arrayOfNulls<ButtonMenuEntry>(1) }
    val latestItems by rememberUpdatedState(items)
    val latestSource by rememberUpdatedState(source)
    val latestGlyph by rememberUpdatedState(glyph)

    fun open() {
        val current = live[0]
        if (current != null && current.state.targetState) {
            controller.dismiss(current)
            return
        }
        live[0] =
            controller.present(
                ButtonMenuEntry(holder.rect, latestItems, latestSource, latestGlyph),
            ) as ButtonMenuEntry
    }
    return this
        .captureAnchor(holder)
        .pointerInput(controller) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                open()
            }
        }.semantics {
            role = Role.Button
            onClick(label) {
                open()
                true
            }
        }
}
