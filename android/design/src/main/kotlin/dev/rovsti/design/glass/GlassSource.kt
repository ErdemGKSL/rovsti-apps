package dev.rovsti.design.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import dev.rovsti.design.motion.MotionGate

/**
 * One per screen root. The content subtree records itself into [layer]; glass siblings drawn after it replay that
 * recording translated to their own position. The source must never contain glass that samples it.
 */
@Stable
class GlassSource internal constructor(
    internal val layer: GraphicsLayer,
) {
    internal var coordinates: LayoutCoordinates? = null

    /**
     * True while the screen moves. A plain field on purpose: glass nodes read it in draw without subscribing, so it
     * costs no invalidation per frame. Written on edges only (see [rememberGlassSource]).
     */
    @JvmField
    internal var frozen: Boolean = false
}

/** Creates the screen's source. With a [gate], [GlassSource.frozen] follows [MotionGate.frozen]. */
@Composable
fun rememberGlassSource(gate: MotionGate? = null): GlassSource {
    val layer = rememberGraphicsLayer()
    val source = remember(layer) { GlassSource(layer) }
    if (gate != null) {
        LaunchedEffect(gate, source) { snapshotFlow { gate.frozen }.collect { source.frozen = it } }
    }
    return source
}

/**
 * On the content root (list, map). Draws normally and keeps a recording for glass to sample. [background] is recorded
 * under the content so glass over transparent areas still has a solid base to blur.
 */
fun Modifier.glassSource(
    source: GlassSource,
    background: Color,
): Modifier =
    this
        .onPlaced { source.coordinates = it }
        .drawWithContent {
            source.layer.record {
                drawRect(background)
                this@drawWithContent.drawContent()
            }
            drawLayer(source.layer)
        }
