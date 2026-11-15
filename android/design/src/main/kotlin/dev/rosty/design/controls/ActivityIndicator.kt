package dev.rosty.design.controls

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rosty.design.motion.LocalReduceMotion
import dev.rosty.design.theme.RostyTheme
import kotlin.math.floor

/** iOS `UIActivityIndicatorView.medium`: 20 pt across, eight spokes. */
private val IndicatorSize: Dp = 20.dp
private const val SPOKES = 8
private const val CYCLE_MS = 1000

/** The spoke length and width as fractions of the size, and the faintest spoke's alpha (UIKit's look). */
private const val INNER = 0.24f
private const val OUTER = 0.48f
private const val WIDTH = 0.09f
private const val FAINT = 0.25f

/**
 * The spinning-spokes activity indicator of iOS, for work whose length is not known. The spokes step round once a
 * second; the turn is read in the draw phase, so the indicator never recomposes while it spins. Under reduce motion it
 * stands still, the spokes still fading round. It reads as an indeterminate progress bar named [label].
 */
@Composable
fun ActivityIndicator(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = RostyTheme.colors.labelSecondary,
    size: Dp = IndicatorSize,
) {
    val still = LocalReduceMotion.current
    val turn =
        if (still) {
            null
        } else {
            rememberInfiniteTransition(label = "activity").animateFloat(
                0f,
                SPOKES.toFloat(),
                infiniteRepeatable(tween(CYCLE_MS, easing = LinearEasing), RepeatMode.Restart),
                label = "activityTurn",
            )
        }
    Canvas(
        modifier.size(size).semantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        },
    ) {
        // Whole steps, as UIKit: the brightest spoke jumps from one place to the next.
        val step = floor(turn?.value ?: 0f)
        val r = this.size.minDimension
        val stroke = r * WIDTH
        rotate(step * 360f / SPOKES) {
            for (i in 0 until SPOKES) {
                val alpha = 1f - (1f - FAINT) * i / (SPOKES - 1)
                rotate(-i * 360f / SPOKES) {
                    drawLine(
                        color.copy(alpha = color.alpha * alpha),
                        start = Offset(center.x, center.y - r * INNER),
                        end = Offset(center.x, center.y - r * OUTER),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
