package dev.rosty.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.theme.RostyTheme
import dev.rosty.testing.ShotTest
import dev.rosty.testing.assertReadable
import org.junit.Test

/**
 * Filled controls read on every fill: a Filled CapsuleButton's label is measured in the rendered pixels in both
 * themes on the default accent, destructive, success and a mid green that white fails on.
 */
class FilledButtonContrastTest : ShotTest() {
    @Test
    fun filledCapsuleLabelReadsAt4_5OnEveryFill() {
        for (dark in listOf(false, true)) {
            val fills: List<Pair<String, @Composable () -> Color>> =
                listOf(
                    "accent" to { Color.Unspecified },
                    "destructive" to { RostyTheme.colors.destructive },
                    "success" to { RostyTheme.colors.success },
                    "green34C759" to { Color(0xFF34C759) },
                )
            for ((name, tint) in fills) {
                composeFrame(dark = dark) {
                    Box(Modifier.background(RostyTheme.colors.page).padding(24.dp), Alignment.Center) {
                        CapsuleButton("Continue", onClick = {}, style = CapsuleStyle.Filled, tint = tint())
                    }
                }
                advance(300)
                val density = compose.activity.resources.displayMetrics.density
                val b = compose.onNodeWithText("Continue", useUnmergedTree = true).getBoundsInRoot()
                val rect =
                    Rect(
                        b.left.value * density,
                        b.top.value * density,
                        b.right.value * density,
                        b.bottom.value * density,
                    )
                assertReadable(compose.onRoot().captureToImage(), rect, 4.5, "dark=$dark fill=$name")
            }
        }
    }
}
