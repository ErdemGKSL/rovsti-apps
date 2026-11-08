package dev.rovsti.design.press

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import dev.rovsti.design.motion.RovstiMotion
import kotlinx.coroutines.launch

/**
 * Shrinks the element to [RovstiMotion.PressScale] while a finger is down and springs back on lift (ORIGINAL).
 * For cards and tiles that are not glass. A layer transform only: layout size never changes, nothing else moves.
 */
internal class ScalePressNode(
    var enabled: Boolean,
) : DelegatingNode(),
    LayoutModifierNode {
    private val scale = Animatable(1f)

    init {
        delegate(
            SuspendingPointerInputModifierNode {
                awaitEachGesture {
                    observePress(
                        enabled = { enabled },
                        onDown = { aim(RovstiMotion.PressScale) },
                        onMove = { _, _ -> },
                        onEnd = { aim(1f) },
                    )
                }
            },
        )
    }

    private fun aim(target: Float) {
        coroutineScope.launch { scale.animateTo(target, RovstiMotion.press(Threshold)) }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale.value
                scaleY = scale.value
            }
        }
    }

    private companion object {
        const val Threshold = 0.0001f
    }
}

private data class ScalePressElement(
    val enabled: Boolean,
) : ModifierNodeElement<ScalePressNode>() {
    override fun create() = ScalePressNode(enabled)

    override fun update(node: ScalePressNode) {
        node.enabled = enabled
    }
}

/** Scale-down press feedback (see [ScalePressNode]). Put it before `clickable`. */
fun Modifier.scalePress(enabled: Boolean = true): Modifier = this then ScalePressElement(enabled)
