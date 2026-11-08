package dev.rovsti.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.rovsti.design.press.glassPress
import dev.rovsti.design.press.rowPress
import dev.rovsti.design.press.scalePress
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** AC3: pressing changes the layout bounds of neither the pressed control nor its neighbour. */
class PressNoLayoutShiftTest : ShotTest() {
    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    /**
     * [press] applies the effect to the outer box (tagged `target`, layout bounds); an inner box (tagged `visual`)
     * is what the transform acts on. The neighbour text sits right below.
     */
    private fun check(
        press: Modifier.() -> Modifier,
        visualMustMove: Boolean,
    ) {
        composeFrame {
            Column {
                Box(Modifier.testTag("target").press()) {
                    Box(
                        Modifier
                            .testTag("visual")
                            .size(width = 160.dp, height = 48.dp)
                            .background(Color.Gray)
                            .clickable {},
                    )
                }
                RovstiText("neighbour", modifier = Modifier.testTag("neighbour").fillMaxWidth().height(24.dp))
            }
        }
        val targetRest = bounds("target")
        val neighbourRest = bounds("neighbour")
        val visualRest = bounds("visual")

        compose.onNodeWithTag("target").performTouchInput {
            down(Offset(width * 5f / 6f, height / 2f))
            moveBy(Offset(30f, 0f))
        }
        advance(120)
        assertEquals("target while pressed", targetRest, bounds("target"))
        assertEquals("neighbour while pressed", neighbourRest, bounds("neighbour"))
        if (visualMustMove) assertNotEquals("the effect must be visible", visualRest, bounds("visual"))

        compose.onNodeWithTag("target").performTouchInput { up() }
        advance(500)
        assertEquals("target after release", targetRest, bounds("target"))
        assertEquals("neighbour after release", neighbourRest, bounds("neighbour"))
        advance(2_000)
        assertEquals("visual back at rest", visualRest, bounds("visual"))
    }

    @Test
    fun glassPressMovesNothingInLayout() = check({ glassPress(glow = true) }, visualMustMove = true)

    @Test
    fun scalePressMovesNothingInLayout() = check({ scalePress() }, visualMustMove = true)

    @Test
    fun rowPressMovesNothingInLayout() = check({ rowPress() }, visualMustMove = false)

    @Test
    fun aDisabledGlassPressDoesNotTransform() {
        composeFrame {
            Box(Modifier.testTag("target").glassPress(enabled = false)) {
                Box(
                    Modifier
                        .testTag("visual")
                        .size(160.dp, 48.dp)
                        .background(Color.Gray)
                        .clickable {},
                )
            }
        }
        val rest = bounds("visual")
        compose.onNodeWithTag("target").performTouchInput { down(center) }
        advance(200)
        assertEquals(rest, bounds("visual"))
        compose.onNodeWithTag("target").performTouchInput { up() }
    }
}
