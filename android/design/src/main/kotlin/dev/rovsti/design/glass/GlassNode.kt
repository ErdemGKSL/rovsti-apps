package dev.rovsti.design.glass

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Glass on this element: a shadow, then the surface drawn by [GlassNode]. The element's own content is drawn on top.
 * Place it as a sibling AFTER the content that [source] records, never inside it.
 */
fun Modifier.glass(
    source: GlassSource,
    spec: GlassSpec,
): Modifier {
    val shape =
        androidx.compose.foundation.shape
            .RoundedCornerShape(spec.cornerDp.coerceAtMost(CAPSULE_DP).dp)
    val shadow = spec.shadow
    val shadowed =
        if (shadow.radiusDp > 0f) {
            dropShadow(
                shape,
                Shadow(radius = shadow.radiusDp.dp, color = shadow.color, offset = DpOffset(0.dp, shadow.yDp.dp)),
            )
        } else {
            this
        }
    return shadowed.then(GlassElement(source, spec))
}

private const val CAPSULE_DP = 5_000f

/** Replay scale for a surface that is itself scaled by [pressScale]: the node draws the replay under the inverse. */
internal fun replayScale(
    captureScale: Float,
    pressScale: Float,
): Float = captureScale / (if (pressScale > 0.01f) pressScale else 1f)

private data class GlassElement(
    val source: GlassSource,
    val spec: GlassSpec,
) : ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(source, spec)

    override fun update(node: GlassNode) {
        node.update(source, spec)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "glass"
        properties["tier"] = spec.tier
    }
}

private class GlassNode(
    var source: GlassSource,
    var spec: GlassSpec,
) : Modifier.Node(),
    DrawModifierNode,
    GlobalPositionAwareModifierNode {
    private var own: GraphicsLayer? = null
    private var me: LayoutCoordinates? = null

    private var recordedOffset = Offset.Unspecified
    private var recordedSize = Size.Unspecified
    private var recordedPressX = 1f
    private var recordedPressY = 1f
    private var recordedSource: GlassSource? = null
    private var effectKey: EffectKey? = null
    private var rim: RimBrush? = null

    private data class EffectKey(
        val size: Size,
        val spec: GlassSpec,
        val cornerPx: Float,
        val density: Float,
    )

    fun update(
        source: GlassSource,
        spec: GlassSpec,
    ) {
        if (source !== this.source || spec != this.spec) {
            this.source = source
            this.spec = spec
            recordedSize = Size.Unspecified
            invalidateDraw()
        }
    }

    override fun onAttach() {
        own = requireGraphicsContext().createGraphicsLayer()
    }

    override fun onDetach() {
        own?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        own = null
        effectKey = null
        rim = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        me = coordinates
        invalidateDraw()
    }

    private fun ContentDrawScope.cornerPx(): Float {
        val r = spec.cornerDp.coerceAtMost(CAPSULE_DP) * density
        return min(r, min(size.width, size.height) / 2f)
    }

    override fun ContentDrawScope.draw() {
        val corner = cornerPx()
        val layer = own
        val src = source.coordinates
        val here = me
        val usable =
            spec.tier != GlassTier.Flat && layer != null && src != null && here != null &&
                src.isAttached && here.isAttached && size.width > 0f && size.height > 0f &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        if (!usable) {
            drawFlat(corner)
            drawContent()
            drawHairline(corner, spec.flatEdge)
            return
        }
        checkNotNull(layer)
        checkNotNull(src)
        checkNotNull(here)
        record(layer, src, here)
        applyEffects(layer, corner)

        val s = spec.captureScale
        scale(1f / s, 1f / s, pivot = Offset.Zero) { drawLayer(layer) }
        drawRoundRect(spec.scrim, size = size, cornerRadius = CornerRadius(corner))
        drawRoundRect(spec.fill, size = size, cornerRadius = CornerRadius(corner))
        drawContent()
        // The rim shader paints the hairline under its light, so only one of the two is drawn.
        if (spec.tier == GlassTier.Liquid && spec.rimShine > 0f) drawRim(corner) else drawHairline(corner, spec.edge)
    }

    /** Re-record only when this surface moved, resized or is being pressed relative to the source. */
    private fun ContentDrawScope.record(
        layer: GraphicsLayer,
        src: LayoutCoordinates,
        here: LayoutCoordinates,
    ) {
        val offset = src.localPositionOf(here, Offset.Zero)
        val topRight = here.localToRoot(Offset(size.width, 0f)) - here.localToRoot(Offset.Zero)
        val bottomLeft = here.localToRoot(Offset(0f, size.height)) - here.localToRoot(Offset.Zero)
        val pressX = if (size.width > 0f) topRight.x / size.width else 1f
        val pressY = if (size.height > 0f) bottomLeft.y / size.height else 1f

        val moved = offset != recordedOffset || pressX != recordedPressX || pressY != recordedPressY
        val resized = size != recordedSize || source !== recordedSource
        val tiny =
            recordedOffset.isSpecified && abs(offset.x - recordedOffset.x) < 1f &&
                abs(offset.y - recordedOffset.y) < 1f && pressX == recordedPressX && pressY == recordedPressY
        // While the screen moves, sub-pixel drift is not worth a re-record.
        if (!resized && !moved) return
        if (!resized && source.frozen && tiny) return

        val s = spec.captureScale
        val kx = replayScale(s, pressX)
        val ky = replayScale(s, pressY)
        layer.record(
            density = this,
            layoutDirection = layoutDirection,
            size = IntSize(max(1, (size.width * s).roundToInt()), max(1, (size.height * s).roundToInt())),
        ) {
            scale(kx, ky, pivot = Offset.Zero) {
                translate(-offset.x, -offset.y) { drawLayer(source.layer) }
            }
        }
        recordedOffset = offset
        recordedSize = size
        recordedPressX = pressX
        recordedPressY = pressY
        recordedSource = source
    }

    /** Effects depend on size, spec and corner only: build them when one of those changes. */
    private fun ContentDrawScope.applyEffects(
        layer: GraphicsLayer,
        corner: Float,
    ) {
        val key = EffectKey(size, spec, corner, density)
        if (key == effectKey) return
        val s = spec.captureScale
        val layerSize = Size(size.width * s, size.height * s)
        layer.colorFilter =
            if (spec.saturation == 1f) {
                null
            } else {
                ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(spec.saturation) })
            }
        layer.renderEffect = glassEffect(layerSize, corner * s, spec, density * s)?.asComposeRenderEffect()
        layer.setRoundRectOutline(Offset.Zero, layerSize, corner * s)
        layer.clip = true
        effectKey = key
        rim = null
    }

    private fun ContentDrawScope.drawFlat(corner: Float) {
        drawRoundRect(
            spec.flatBase.copy(alpha = GlassSpec.FLAT_ALPHA),
            size = size,
            cornerRadius = CornerRadius(corner),
        )
    }

    private fun ContentDrawScope.drawHairline(
        corner: Float,
        color: androidx.compose.ui.graphics.Color,
    ) {
        val width = max(1f, 0.5f * density)
        val inset = width / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(size.width - width, size.height - width),
            cornerRadius = CornerRadius(max(0f, corner - inset)),
            style = Stroke(width),
        )
    }

    private fun ContentDrawScope.drawRim(corner: Float) {
        val current = rim
        val brush =
            if (current != null && current.matches(size, corner, spec.rimShine, spec.edge)) {
                current
            } else {
                RimBrush(size, corner, spec.rimShine, spec.edge).also { rim = it }
            }
        drawRect(brush, size = size)
    }
}
