package dev.rosty.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.rosty.design.ios.component.IosSwipeAction
import dev.rosty.design.ios.component.IosSwipeableRow
import dev.rosty.design.ios.component.IosText
import dev.rosty.design.ios.foundation.IosTheme
import dev.rosty.testing.ShotTest
import dev.rosty.testing.assertReadable
import org.junit.Test

/** Revealed swipe-action labels read at 4.5:1 on orange, red and blue in both themes (readableOn picks ink or white). */
class SwipeActionContrastTest : ShotTest() {
    @Test
    fun actionLabelsReadOnOrangeRedAndBlue() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) {
                val c = IosTheme.colors
                Column {
                    val fills = listOf("Pin" to c.systemOrange, "Delete" to c.systemRed, "Mark" to c.systemBlue)
                    fills.forEach { (l, tint) ->
                        IosSwipeableRow(
                            modifier = Modifier.fillMaxWidth().testTag("row-$l"),
                            trailingActions = listOf(IosSwipeAction(l, tint) {}),
                        ) { Box(Modifier.fillMaxWidth().height(72.dp)) { IosText("Row") } }
                    }
                }
            }
            val density = compose.activity.resources.displayMetrics.density
            for (label in listOf("Pin", "Delete", "Mark")) {
                compose.onNodeWithTag("row-$label").performTouchInput {
                    down(center)
                    repeat(8) { moveBy(Offset(-50f, 0f), 100) }
                    advance(300)
                    up()
                }
            }
            advance(800)
            val image = compose.onRoot().captureToImage()
            for (label in listOf("Pin", "Delete", "Mark")) {
                val b = compose.onNodeWithTag("swipe-action-$label").getBoundsInRoot()
                val rect =
                    Rect(
                        b.left.value * density,
                        b.top.value * density,
                        b.right.value * density,
                        b.bottom.value * density,
                    )
                assertReadable(image, rect, 4.5, "dark=$dark action=$label")
            }
        }
    }
}
