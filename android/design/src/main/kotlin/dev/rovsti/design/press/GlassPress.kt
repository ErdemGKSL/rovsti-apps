// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
package dev.rovsti.design.press

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.rovsti.design.motion.GlisseSpring
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.motion.bounceless
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** How a press feels for one pointer type: how far the longer side grows, and the springs in and out. */
internal class PressFeel(
    val growDp: Float,
    val press: GlisseSpring,
    val release: GlisseSpring,
)

/** A finger gets the iOS 26 glass button: the longer side grows 15 dp and bounces past rest on release. */
internal val TouchFeel = PressFeel(15f, RovstiMotion.GlassPressDown, RovstiMotion.GlassPressUp)

/** A mouse press stays subtle. */
internal val PointerFeel = PressFeel(6f, RovstiMotion.PointerPressDown, RovstiMotion.PointerPressUp)

/** The glow around the finger: radius, and the white it starts with at the centre and keeps to half way out. */
internal val GlowRadius = 240.dp
internal const val GlowInner = 0.035f
internal const val GlowMid = 0.025f

private val StretchDp = 6.dp
private val ShiftDp = 8.dp

/**
 * Where the four press springs aim, as `[scaleX, scaleY, translateX, translateY]` (pure, glisse `press.ts` `track`).
 *
 * The longer side grows by [grow] px; a drag of ([dx0], [dy0]) px from the down point stretches the button along the
 * drag axis (by at most [stretchPx] px, saturating) and squeezes the other axis, and shifts it up to [maxShift] px
 * toward the finger. A horizontal drag counts less on a wide button.
 */
fun pressTargets(
    width: Float,
    height: Float,
    dx0: Float,
    dy0: Float,
    grow: Float,
    stretchPx: Float,
    maxShift: Float,
): FloatArray {
    if (width <= 0f || height <= 0f) return floatArrayOf(1f, 1f, 0f, 0f)
    val base = 1f + grow / max(width, height)
    val aspect = width / height
    val dx = dx0 / aspect
    val dy = dy0
    val length = hypot(dx, dy)
    if (length == 0f) return floatArrayOf(base, base, 0f, 0f)
    val nx = dx / length
    val ny = dy / length
    val rubber = 1f - 1f / (1f + length / height / (5f * aspect))
    val stretch = 1f + (stretchPx / height) * rubber * abs(abs(nx) - abs(ny))
    val horizontal = abs(nx) > abs(ny)
    return floatArrayOf(
        base * if (horizontal) stretch else 1f / stretch,
        base * if (horizontal) 1f / stretch else stretch,
        nx * maxShift * rubber,
        ny * maxShift * rubber,
    )
}

private val Rest = floatArrayOf(1f, 1f, 0f, 0f)

/**
 * The glass press: scale and translate springs driven by the finger, applied as a layer transform only, so the
 * layout size never changes and neighbours never move. Sits before `clickable`; observes on the Final pass and never
 * consumes. With [glow] a faint radial light follows the finger and fades over 220 ms after it lifts.
 */
internal class GlassPressNode(
    var enabled: Boolean,
    var glow: Boolean,
    var glowShape: Shape,
) : DelegatingNode(),
    LayoutModifierNode,
    DrawModifierNode,
    CompositionLocalConsumerModifierNode {
    private val values = arrayOf(Animatable(1f), Animatable(1f), Animatable(0f), Animatable(0f))
    private val glowAlpha = Animatable(0f)
    private var glowCenter by mutableStateOf(Offset.Zero)

    init {
        delegate(
            SuspendingPointerInputModifierNode {
                awaitEachGesture {
                    var feel = TouchFeel
                    var reduce = false
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val grow = { f: PressFeel -> f.growDp.dp.toPx() }
                    observePress(
                        enabled = { enabled },
                        onDown = { down ->
                            feel = if (down.type == PointerType.Mouse) PointerFeel else TouchFeel
                            reduce = currentValueOf(LocalReduceMotion)
                            glowCenter = down.position
                            if (glow) coroutineScope.launch { glowAlpha.snapTo(1f) }
                            aim(
                                pressTargets(w, h, 0f, 0f, grow(feel), StretchDp.toPx(), ShiftDp.toPx()),
                                feel.press,
                                reduce,
                            )
                        },
                        onMove = { down, offset ->
                            glowCenter = down.position + offset
                            val targets =
                                pressTargets(w, h, offset.x, offset.y, grow(feel), StretchDp.toPx(), ShiftDp.toPx())
                            aim(targets, feel.press, reduce)
                        },
                        onEnd = {
                            aim(Rest, feel.release, reduce)
                            if (glow) coroutineScope.launch { glowAlpha.animateTo(0f, RovstiMotion.glowOut()) }
                        },
                    )
                }
            },
        )
    }

    private fun aim(
        targets: FloatArray,
        spring: GlisseSpring,
        reduce: Boolean,
    ) {
        for (i in 0..3) {
            val threshold = if (i < 2) ScaleThreshold else OffsetThreshold
            coroutineScope.launch { values[i].animateTo(targets[i], spring.spec(threshold).bounceless(reduce)) }
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = values[0].value
                scaleY = values[1].value
                translationX = values[2].value
                translationY = values[3].value
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = if (glow) glowAlpha.value else 0f
        if (alpha <= 0f) return
        val brush =
            Brush.radialGradient(
                0f to Color.White.copy(alpha = GlowInner),
                0.5f to Color.White.copy(alpha = GlowMid),
                1f to Color.Transparent,
                center = glowCenter,
                radius = GlowRadius.toPx(),
            )
        // The glow rides the same transform as the button and is clipped to its shape.
        withTransform({
            translate(values[2].value, values[3].value)
            scale(values[0].value, values[1].value, pivot = size.center)
        }) {
            val clip = Path().apply { addOutline(glowShape.createOutline(size, layoutDirection, this@draw)) }
            clipPath(clip) { drawRect(brush, alpha = alpha) }
        }
    }

    private companion object {
        const val ScaleThreshold = 0.0001f
        const val OffsetThreshold = 0.05f
    }
}

private data class GlassPressElement(
    val enabled: Boolean,
    val glow: Boolean,
    val glowShape: Shape,
) : ModifierNodeElement<GlassPressNode>() {
    override fun create() = GlassPressNode(enabled, glow, glowShape)

    override fun update(node: GlassPressNode) {
        node.enabled = enabled
        node.glow = glow
        node.glowShape = glowShape
    }
}

/**
 * Glass-button press feedback (see [GlassPressNode]). Put it before `clickable` and `semantics`; it never changes
 * layout size. [glowShape] clips the optional [glow] and should match the element's shape.
 */
fun Modifier.glassPress(
    enabled: Boolean = true,
    glow: Boolean = false,
    glowShape: Shape = RectangleShape,
): Modifier = this then GlassPressElement(enabled, glow, glowShape)
