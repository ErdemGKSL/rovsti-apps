package dev.rosty.stops.map

import android.graphics.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import dev.rosty.design.motion.MotionGate
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.stops.R
import kotlinx.coroutines.channels.Channel

/**
 * The map (ARCH 8.4): tiles drawn by one node from [camera] and [tiles], then [overlay] on top (E7.3 draws the radar
 * layers there, in the same draw pass and with the same camera). Put it full-window behind the floating chrome.
 *
 * A pan, a pinch or a fling changes [camera] only, and the camera is read in draw: this composable and its callers
 * do not recompose while the map moves (`StopsMapRecompositionTest`). [overlay] must keep it that way: read the
 * camera it is given, not composition state that changes per frame.
 *
 * - [dark] picks the eclipse palette (the buffer's dark flag) and the dark radar plate.
 * - [gesturePolicy] gets single taps and may claim drags (pins, E7.4); [MapGesturePolicy.None] only moves the map.
 * - [gesturesEnabled] false makes the map a still picture (the alarm screen).
 *
 * The attribution ([MapAttribution]) and the scale bar ([MapScaleBar]) are separate composables so the screen can
 * place them above its sheet; chrome follows the map's motion with [MapMotionEffect].
 */
@Composable
fun StopsMap(
    camera: MapCamera,
    tiles: MapTileStore,
    dark: Boolean,
    modifier: Modifier = Modifier,
    gesturePolicy: MapGesturePolicy = MapGesturePolicy.None,
    gesturesEnabled: Boolean = true,
    overlay: DrawScope.(MapCamera) -> Unit = {},
) {
    DisposableEffect(tiles, camera) {
        tiles.attach(camera)
        onDispose { tiles.detach() }
    }
    // Not a recomposition per theme flip of the tiles: the store repaints, the draw node follows its counter.
    LaunchedEffect(tiles, dark) { tiles.setDark(dark) }
    LaunchedEffect(camera) { runCameraAnimations(camera) }
    val description = stringResource(R.string.map_content_description)
    Box(
        modifier
            .semantics { contentDescription = description }
            .mapGestures(camera, gesturePolicy, gesturesEnabled)
            .then(MapDrawElement(camera, tiles, dark, overlay)),
    )
}

/** Runs the camera's fling and zoom animations on the frame clock; idle (no frames) while nothing animates. */
private suspend fun runCameraAnimations(camera: MapCamera) {
    val wake = Channel<Unit>(Channel.CONFLATED)
    var running = false
    val listener = { if (!running && camera.animating) wake.trySend(Unit) }
    camera.addListener(listener)
    try {
        if (camera.animating) wake.trySend(Unit)
        while (true) {
            wake.receive()
            running = true
            var last = withFrameNanos { it }
            while (camera.animating) {
                val now = withFrameNanos { it }
                camera.advance((now - last) / NANOS_PER_SECOND)
                last = now
            }
            running = false
        }
    } finally {
        camera.removeListener(listener)
    }
}

private const val NANOS_PER_SECOND = 1e9

/**
 * Tells [gate] when the map moves, so glass over the map is Frost while panning and Liquid (Flat in tests) at rest
 * (E7.2 AC3, `MotionGate.frozen`). Two writes per gesture, no recomposition of the caller.
 */
@Composable
fun MapMotionEffect(
    camera: MapCamera,
    gate: MotionGate,
) {
    LaunchedEffect(camera, gate) {
        snapshotFlow { camera.moving.value }.collect { gate.gesture(it) }
    }
}

/** "© OpenStreetMap contributors" (AD-ST4), small and legible over both palettes. */
@Composable
fun MapAttribution(modifier: Modifier = Modifier) {
    val colors = RostyTheme.colors
    val ink = if (colors.isDark) Color(0xFFEBEBF5) else Color(0xFF3C3C43)
    val halo = if (colors.isDark) Color(0xCC000000) else Color(0xE6FFFFFF)
    RostyText(
        text = stringResource(R.string.map_attribution),
        modifier = modifier,
        style = RostyTheme.type.caption1.copy(shadow = Shadow(halo, Offset.Zero, ATTRIBUTION_HALO_BLUR)),
        color = ink,
        maxLines = 1,
    )
}

private const val ATTRIBUTION_HALO_BLUR = 6f

/** The radar plate and grid where no tile is loaded: `secondary_background` and `hairline` of the GPUI theme. */
internal object RadarColors {
    const val PLATE_LIGHT = 0xFFFFFFFF.toInt()
    const val PLATE_DARK = 0xFF1C1C1E.toInt()
    const val GRID_LIGHT = 0x14000000
    const val GRID_DARK = 0x1FFFFFFF
}

private class MapDrawElement(
    private val camera: MapCamera,
    private val tiles: MapTileStore,
    private val dark: Boolean,
    private val overlay: DrawScope.(MapCamera) -> Unit,
) : ModifierNodeElement<MapDrawNode>() {
    override fun create() = MapDrawNode(camera, tiles, dark, overlay)

    override fun update(node: MapDrawNode) = node.update(camera, tiles, dark, overlay)

    override fun equals(other: Any?): Boolean =
        other is MapDrawElement && other.camera === camera && other.tiles === tiles && other.dark == dark &&
            other.overlay === overlay

    override fun hashCode(): Int = 31 * (31 * camera.hashCode() + tiles.hashCode()) + dark.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = "stopsMap"
    }
}

/** The one node that draws the map: lays the viewport into the camera and draws tiles, then the overlay. */
private class MapDrawNode(
    private var camera: MapCamera,
    private var tiles: MapTileStore,
    private var dark: Boolean,
    private var overlay: DrawScope.(MapCamera) -> Unit,
) : androidx.compose.ui.Modifier.Node(),
    DrawModifierNode,
    LayoutAwareModifierNode {
    private var painter = TileLayerPainter(tiles).also { applyColors(it) }
    private var size = IntSize.Zero

    fun update(
        camera: MapCamera,
        tiles: MapTileStore,
        dark: Boolean,
        overlay: DrawScope.(MapCamera) -> Unit,
    ) {
        if (tiles !== this.tiles) painter = TileLayerPainter(tiles)
        this.camera = camera
        this.tiles = tiles
        this.dark = dark
        this.overlay = overlay
        applyColors(painter)
        camera.setViewport(size.width, size.height, requireDensity().density)
        invalidateDraw()
    }

    private fun applyColors(painter: TileLayerPainter) {
        if (dark) {
            painter.setRadarColors(RadarColors.PLATE_DARK, RadarColors.GRID_DARK)
        } else {
            painter.setRadarColors(RadarColors.PLATE_LIGHT, RadarColors.GRID_LIGHT)
        }
    }

    override fun onRemeasured(size: IntSize) {
        this.size = size
        camera.setViewport(size.width, size.height, requireDensity().density)
    }

    override fun ContentDrawScope.draw() {
        // The only two reads that redraw the map: the camera moved, a tile arrived or left.
        camera.observe()
        tiles.observe()
        drawIntoCanvas { canvas ->
            val native: Canvas = canvas.nativeCanvas
            painter.draw(native, camera)
        }
        overlay(camera)
        drawContent()
    }
}
