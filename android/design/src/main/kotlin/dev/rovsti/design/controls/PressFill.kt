package dev.rovsti.design.controls

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.motion.gated
import kotlinx.coroutines.flow.collectLatest

/**
 * 0 at rest, 1 the instant a press starts, and back to 0 over the 200 ms `focusFill` tween when it ends (ORIGINAL, after
 * the behaviour glisse's CSS describes for `.lg-button`). Read it inside a draw lambda only.
 */
@Composable
internal fun rememberPressFill(source: InteractionSource): Animatable<Float, *> {
    val amount = remember { Animatable(0f) }
    val reduce = LocalReduceMotion.current
    LaunchedEffect(source, reduce) {
        source.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    amount.snapTo(1f)
                }

                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    amount.animateTo(0f, RovstiMotion.focusFill<Float>().gated(reduce))
                }
            }
        }
    }
    return amount
}
