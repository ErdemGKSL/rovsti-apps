/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/utils/ModifierExt.kt
 * (additiveChrome, outerDarkRim), app/src/main/kotlin/com/convx/music/ui/component/GlassCircleButton.kt (elasticStretchLayerBlock) and
 * app/src/main/kotlin/com/convx/music/ui/utils/FlingProjection.kt (projectedEndpoint, nearestProjectedSnap).
 * Changes for Rovsti: package dev.rovsti.design.ios.foundation; the four helpers the glass and menu code needs gathered in one file.
 */
package dev.rovsti.design.ios.foundation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import dev.rovsti.design.backdrop.catalog.utils.InteractiveHighlight
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

// --- ui/utils/ModifierExt.kt -------------------------------------------------

/** Adds the white player controls over dark artwork with additive blending. Apply alpha after this modifier. */
// Repeating the additive pass strengthens the glow; saturating the gray source layer has no effect.
private const val AdditiveChromePasses = 2

/** Shared strength of the additive chrome on menu and tab-bar glyphs. */
const val MenuIconChrome = 0.8f

/**
 * Menu rows and tiles: a light additive lift only, so the glyph stays near-white and readable on the
 * glass instead of taking the heavier vibrant look the tab bar uses.
 */
const val MenuItemChrome = 0.25f

fun Modifier.additiveChrome(
    enabled: Boolean = true,
    strength: Float = 1f,
    bleed: Dp = 0.dp,
    passes: Int = AdditiveChromePasses,
): Modifier =
    if (!enabled) {
        this
    } else {
        drawWithCache {
            // Record once and composite the same layer twice.
            val layer =
                obtainGraphicsLayer().apply {
                    blendMode = BlendMode.Plus
                    alpha = strength
                }
            // The layer only holds what is drawn inside it, so [bleed] widens it past the node's own box:
            // a glyph that scales up (a bounce) is then not cut off at the edge of its square.
            val pad = bleed.toPx()
            val layerSize =
                IntSize(
                    (size.width + 2 * pad).toInt().coerceAtLeast(1),
                    (size.height + 2 * pad).toInt().coerceAtLeast(1),
                )
            onDrawWithContent {
                layer.record(layerSize) { translate(pad, pad) { this@onDrawWithContent.drawContent() } }
                translate(-pad, -pad) { repeat(passes) { drawLayer(layer) } }
            }
        }
    }

// --- ui/utils/ModifierExt.kt: outerDarkRim -----------------------------------

/**
 * Dark counterpart to the specular rim: a thin black outline traced on the
 * surface's own edge, drawn WITHOUT clipping so half the stroke sits outside
 * the bounds — the outermost layer, wider than the light rim.
 *
 * Mirrors the light rim's wide-on-edge / slim-at-ends read on TWO sides: the
 * ring is drawn twice, once per diagonal axis, each pass black at both ends
 * fading to nothing at its middle — so every side gets a dark outer edge
 * that swells and slims around the shape instead of a flat uniform line.
 *
 * Normal alpha blend, not Plus: Plus adds zero for black, so a Plus black rim
 * would be a no-op. Applied by [Modifier.liquidGlass] itself; do not stack
 * manually on glass surfaces.
 */
fun Modifier.outerDarkRim(
    shape: Shape,
    width: Dp = 1.6.dp,
    alpha: Float = 0.32f,
    /** Light rim's angle, degrees — the dark axis is derived from it. */
    angle: Float = 45f,
    /**
     * Draw only the inner half of the stroke, inset so clipping preserves the outline width.
     */
    insideOnly: Boolean = false,
): Modifier =
    drawWithCache {
        val inset = if (insideOnly) width.toPx() / 4f else 0f
        val outline =
            shape.createOutline(
                androidx.compose.ui.geometry
                    .Size(size.width - inset * 2f, size.height - inset * 2f),
                layoutDirection,
                this,
            )
        val stroke = Stroke(width = if (insideOnly) width.toPx() / 2f else width.toPx())
        // One gradient per diagonal axis (canvas coords, y down — same convention
        // as the highlight shader's cos/sin normal). Each is black at both ends
        // fading out at its middle, so the two passes swell on opposite sides.
        val center = Offset(size.width / 2f, size.height / 2f)

        fun flankBrush(axisDeg: Float): Brush {
            val a = axisDeg * (PI / 180f).toFloat()
            val dir = Offset(cos(a), sin(a))
            // Matched so the shape's own corners land on the black ends.
            val reach = dir * (abs(dir.x) * size.width + abs(dir.y) * size.height) / 2f
            return Brush.linearGradient(
                0f to Color.Black.copy(alpha = alpha),
                0.5f to Color.Transparent,
                1f to Color.Black.copy(alpha = alpha),
                start = center - reach,
                end = center + reach,
            )
        }
        val firstBrush = flankBrush(angle)
        val secondBrush = flankBrush(angle + 90f)
        onDrawWithContent {
            drawContent()
            translate(inset, inset) {
                drawOutline(outline = outline, brush = firstBrush, style = stroke)
                drawOutline(outline = outline, brush = secondBrush, style = stroke)
            }
        }
    }

// --- ui/component/GlassCircleButton.kt ---------------------------------------

/**
 * Same tanh-damped press/drag-out scale+translate distortion as
 * [dev.rovsti.design.backdrop.catalog.components.LiquidButton], reused
 * (not duplicated) here so every small floating glass button — top bar back/share/
 * search, selection-mode exit — gets the same "elastic stretch" liquid feel on
 * press. Cheap: a per-frame graphicsLayer transform, no extra backdrop re-capture.
 */
fun elasticStretchLayerBlock(
    interactiveHighlight: InteractiveHighlight,
    // Bar pills pass 2x: same feel, larger travel so the stretch reads at pill size.
    magnitude: Float = 1f,
): GraphicsLayerScope.() -> Unit = {
    val width = size.width
    val height = size.height

    val progress = interactiveHighlight.pressProgress
    val scale = lerp(1f, 1f + magnitude * 4f.dp.toPx() / size.height, progress)

    val maxOffset = size.minDimension
    val initialDerivative = 0.05f
    val offset = interactiveHighlight.offset
    translationX = magnitude * maxOffset * tanh(initialDerivative * offset.x / maxOffset)
    translationY = magnitude * maxOffset * tanh(initialDerivative * offset.y / maxOffset)

    val maxDragScale = magnitude * 4f.dp.toPx() / size.height
    val offsetAngle = atan2(offset.y, offset.x)
    scaleX = scale +
        maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
        (width / height).fastCoerceAtMost(1f)
    scaleY = scale +
        maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
        (height / width).fastCoerceAtMost(1f)
}

// --- ui/utils/FlingProjection.kt ---------------------------------------------

/**
 * Apple's momentum projection from *Designing Fluid Interfaces* (WWDC 2018).
 *
 * Don't snap from the release point — project where the gesture is *going*
 * (exponential decay, like scroll deceleration), then pick the snap point
 * nearest that projected endpoint. This is what makes a flick throw.
 *
 * Physics-textbook `v²/(2·decel)` is NOT what Apple ships — use this form.
 *
 * @param velocityPxPerSec release velocity, same units as [current] per second.
 * @param decelerationRate 0.998 normal scroll feel, 0.99 snappier.
 */
fun projectMomentum(
    velocityPxPerSec: Float,
    decelerationRate: Float = 0.998f,
): Float {
    if (velocityPxPerSec == 0f) return 0f
    return (velocityPxPerSec / 1000f) * decelerationRate / (1f - decelerationRate)
}

/** Projected resting position: current + decay tail of the release velocity. */
fun projectedEndpoint(
    current: Float,
    velocityPxPerSec: Float,
    decelerationRate: Float = 0.998f,
): Float = current + projectMomentum(velocityPxPerSec, decelerationRate)

/**
 * Snap target nearest the *projected* endpoint, not the release point.
 * Returns null for empty [snapPoints]. Ties resolve toward velocity direction.
 */
fun nearestProjectedSnap(
    current: Float,
    velocityPxPerSec: Float,
    snapPoints: List<Float>,
    decelerationRate: Float = 0.998f,
): Float? {
    if (snapPoints.isEmpty()) return null
    val projected = projectedEndpoint(current, velocityPxPerSec, decelerationRate)
    var best = snapPoints[0]
    var bestDist = abs(best - projected)
    for (i in 1..snapPoints.lastIndex) {
        val d = abs(snapPoints[i] - projected)
        // Strictly-less keeps the first (lower) on ties; nudge ties toward fling dir.
        if (d < bestDist ||
            (d == bestDist && (snapPoints[i] - current) * velocityPxPerSec > (best - current) * velocityPxPerSec)
        ) {
            best = snapPoints[i]
            bestDist = d
        }
    }
    return best
}
