package dev.rovsti.design.press

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import dev.rovsti.design.theme.LocalRovstiColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * List-row press (ORIGINAL): a flat `fillTertiary` behind the content while a finger is down, cleared on lift or
 * cancel. No scale, no ripple, and nothing about layout. With an [interactionSource] it follows that source's press
 * interactions (so it stays in step with a `clickable` that owns the gesture); without one it watches pointers itself.
 */
internal class RowPressNode(
    var enabled: Boolean,
    interactionSource: InteractionSource?,
    var color: Color,
) : DelegatingNode(),
    DrawModifierNode,
    CompositionLocalConsumerModifierNode {
    private var pressed by mutableStateOf(false)
    private var job: Job? = null

    var interactionSource: InteractionSource? = interactionSource
        set(value) {
            if (field != value) {
                field = value
                follow()
            }
        }

    init {
        delegate(
            SuspendingPointerInputModifierNode {
                awaitEachGesture {
                    observePress(
                        enabled = { enabled && this@RowPressNode.interactionSource == null },
                        onDown = { pressed = true },
                        onMove = { _, _ -> },
                        onEnd = { pressed = false },
                    )
                }
            },
        )
    }

    override fun onAttach() = follow()

    private fun follow() {
        job?.cancel()
        job = null
        pressed = false
        val source = interactionSource ?: return
        job =
            coroutineScope.launch {
                source.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> pressed = true
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                    }
                }
            }
    }

    override fun ContentDrawScope.draw() {
        if (enabled && pressed) {
            val fill = if (color == Color.Unspecified) currentValueOf(LocalRovstiColors).fillTertiary else color
            drawRect(fill)
        }
        drawContent()
    }
}

private data class RowPressElement(
    val enabled: Boolean,
    val interactionSource: InteractionSource?,
    val color: Color,
) : ModifierNodeElement<RowPressNode>() {
    override fun create() = RowPressNode(enabled, interactionSource, color)

    override fun update(node: RowPressNode) {
        node.enabled = enabled
        node.color = color
        node.interactionSource = interactionSource
    }
}

/** Flat row highlight (see [RowPressNode]). Put it before `clickable`; clip the row first if it has rounded corners. */
fun Modifier.rowPress(
    interactionSource: InteractionSource? = null,
    enabled: Boolean = true,
    color: Color = Color.Unspecified,
): Modifier = this then RowPressElement(enabled, interactionSource, color)
