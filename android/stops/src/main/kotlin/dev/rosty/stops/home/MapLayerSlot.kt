package dev.rosty.stops.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import dev.rosty.design.theme.RostyTheme
import dev.rosty.stops.StopsSizes

/**
 * The map layer of the home (ARCH 8.4): drawn edge to edge under the status and navigation bars, below every piece of
 * chrome. [modifier] fills the window and carries the test tag; [camera] is the area the glass covers, so the map can
 * keep the user's dot, the picked stop and the attribution in the free part (it changes when the sheet settles on a
 * detent or the chrome is measured, never per frame). The real map (`dev.rosty.stops.map`, E7.2a/b) plugs in here;
 * tests pass a fake that paints a fixed picture.
 */
typealias MapLayer = @Composable (modifier: Modifier, camera: MapCameraPadding) -> Unit

/**
 * The glass-covered edges of the window, for the map camera: [insets] is the top chrome (pill and chips), the
 * button column at the end, and the sheet at its **settled** detent at the bottom. Written by the home on settle and
 * on resize only; a map reads it where it fits or centres the camera.
 */
@Stable
class MapCameraPadding {
    var insets: PaddingValues by mutableStateOf(PaddingValues())
        internal set

    /** How many times [insets] changed (tests check it moves on settle, not per frame). */
    var updates: Int = 0
        private set

    internal fun update(next: PaddingValues) {
        if (next == insets) return
        insets = next
        updates++
    }
}

/** Test tag of the map layer's node. */
const val HomeMapTag = "home-map"

/**
 * The map until the tile renderer arrives (E7.2a/b): the radar background the renderer also shows where tiles are
 * missing, a flat land tone with a faint grid in the theme's light or dark, drawn in one draw lambda.
 */
val RadarMapLayer: MapLayer = { modifier, _ -> RadarMap(modifier) }

@Composable
private fun RadarMap(modifier: Modifier) {
    val dark = RostyTheme.colors.isDark
    val land = if (dark) RadarDark else RadarLight
    val line = if (dark) RadarLineDark else RadarLineLight
    Box(
        modifier.testTag(HomeMapTag).drawBehind {
            drawRect(land)
            val step = StopsSizes.radarGrid.toPx()
            val stroke = StopsSizes.radarLine.toPx()
            var x = step
            while (x < size.width) {
                drawLine(line, Offset(x, 0f), Offset(x, size.height), stroke)
                x += step
            }
            var y = step
            while (y < size.height) {
                drawLine(line, Offset(0f, y), Offset(size.width, y), stroke)
                y += step
            }
        },
    )
}

private val RadarLight = Color(0xFFEDEAE3)
private val RadarDark = Color(0xFF1A1D22)
private val RadarLineLight = Color(0x14000000)
private val RadarLineDark = Color(0x1FFFFFFF)
