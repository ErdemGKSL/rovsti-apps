package dev.rosty.catalog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.rosty.design.chrome.BackdropScope
import dev.rosty.design.chrome.RostyTabBar
import dev.rosty.design.theme.RostyTheme
import dev.rosty.testing.ShotTest
import org.junit.Test

/**
 * The tab bar's glass over saturated colour right under its two end caps: avatar-sized discs and blocks in red, blue,
 * purple and green, where a list's avatars and trailing buttons sit. The ends must still refract what is behind them,
 * but only in a narrow rim band, with no magnified colour blob filling the cap (the Messages inbox showed a red smear
 * at the left end, Phone recents purple and blue ones).
 */
class GlassBarEndsShotTest : ShotTest() {
    @Composable
    private fun BarOverSaturatedEnds() {
        var selected by remember { mutableIntStateOf(1) }
        BackdropScope(
            modifier = Modifier.fillMaxSize(),
            baseBg = RostyTheme.colors.page,
            content = { SaturatedEnds() },
            chrome = {
                RostyTabBar(
                    tabs = CatalogTabs,
                    selected = selected,
                    onSelect = { selected = it },
                    onReselect = {},
                    modifier = Modifier.align(Alignment.BottomCenter),
                    imeVisible = { false },
                )
            },
        )
    }

    @Composable
    private fun SaturatedEnds() {
        Canvas(Modifier.fillMaxSize()) {
            val disc = 44.dp.toPx()
            val step = 56.dp.toPx()
            val left = 20.dp.toPx()
            val right = size.width - 20.dp.toPx() - disc
            var y = size.height - 260.dp.toPx()
            var row = 0
            while (y < size.height) {
                drawCircle(LeftColors[row % LeftColors.size], disc / 2, Offset(left + disc / 2, y + disc / 2))
                drawCircle(RightColors[row % RightColors.size], disc / 2, Offset(right + disc / 2, y + disc / 2))
                y += step
                row++
            }
            // A saturated block in the middle: the bar's centre must still show it, refracted only at its rim.
            drawRect(
                Color(0xFFFF9500),
                topLeft = Offset(size.width * 0.42f, size.height - 150.dp.toPx()),
                size = Size(size.width * 0.16f, 120.dp.toPx()),
            )
        }
    }

    @Test
    fun barEndsOverSaturatedColour() {
        shot("glass-bar-ends-saturated") { BarOverSaturatedEnds() }
    }

    private companion object {
        val LeftColors = listOf(Color(0xFFE5243B), Color(0xFF7A3FE4), Color(0xFF00A86B), Color(0xFFFF2D55))
        val RightColors = listOf(Color(0xFF0A84FF), Color(0xFFAF52DE), Color(0xFFFF3B30), Color(0xFF34C759))
    }
}
