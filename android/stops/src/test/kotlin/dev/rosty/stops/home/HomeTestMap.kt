package dev.rosty.stops.home

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import dev.rosty.design.theme.RostyTheme

/**
 * A fixed stand-in for the tile map in tests (no tiles, no network): a flat land colour with a park, a river, a few
 * roads and a block of buildings, all as fractions of the window so every size draws the same picture. [taps] counts
 * taps that reach the map (the sheet at peek and medium must let them through).
 */
class HomeTestMap {
    var taps = 0
        private set

    val layer: @Composable (Modifier) -> Unit = { modifier ->
        val p = if (RostyTheme.colors.isDark) Dark else Light
        Box(
            modifier
                .testTag(HomeMapTag)
                .pointerInput(Unit) { detectTapGestures { taps++ } }
                .drawBehind {
                    val w = size.width
                    val h = size.height
                    drawRect(p.land)
                    drawRect(p.park, Offset(w * 0.08f, h * 0.30f), Size(w * 0.34f, h * 0.16f))
                    drawRect(p.buildings, Offset(w * 0.58f, h * 0.55f), Size(w * 0.30f, h * 0.12f))
                    val river =
                        Path().apply {
                            moveTo(0f, h * 0.62f)
                            cubicTo(w * 0.3f, h * 0.55f, w * 0.55f, h * 0.78f, w, h * 0.70f)
                        }
                    drawPath(river, p.water, style = Stroke(width = w * 0.06f))
                    val road = w * 0.018f
                    drawLine(p.road, Offset(0f, h * 0.22f), Offset(w, h * 0.27f), road)
                    drawLine(p.road, Offset(w * 0.5f, 0f), Offset(w * 0.46f, h), road)
                    drawLine(p.road, Offset(w * 0.1f, h), Offset(w * 0.9f, h * 0.40f), road * 0.7f)
                },
        )
    }

    private class Palette(
        val land: Color,
        val park: Color,
        val buildings: Color,
        val water: Color,
        val road: Color,
    )

    private companion object {
        val Light =
            Palette(Color(0xFFF1EEE6), Color(0xFFC9E6B8), Color(0xFFDCD5CA), Color(0xFF9CC9F0), Color(0xFFFFFFFF))
        val Dark =
            Palette(Color(0xFF1B1E23), Color(0xFF1F3324), Color(0xFF2A2D33), Color(0xFF15324A), Color(0xFF3A3E46))
    }
}
