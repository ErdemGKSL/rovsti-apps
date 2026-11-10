package dev.rovsti.design.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.theme.RovstiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** F20: the plain icon action has no background, a 48 dp target, a Button role and dims to 0.5 while pressed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class PlainIconActionTest {
    @get:Rule
    val compose = createComposeRule()

    private var clicks = 0

    private fun show(enabled: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RovstiTheme {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    PlainIconAction(
                        RovstiIcons.Bell,
                        "Remind me",
                        { clicks++ },
                        Modifier.testTag("action"),
                        enabled = enabled,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
    }

    private fun brightest(image: ImageBitmap): Int {
        val bmp = image.asAndroidBitmap()
        var best = 0
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                best = maxOf(best, (bmp.getPixel(x, y) shr 8) and 0xFF)
            }
        }
        return best
    }

    @Test fun theTargetIsAtLeastFortyEightDpAndIsAButton() {
        show()
        val node = compose.onNodeWithTag("action")
        node.assertHasClickAction()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val density = compose.density
        assertTrue(bounds.width >= with(density) { 48.dp.toPx() } - 1f)
        assertTrue(bounds.height >= with(density) { 48.dp.toPx() } - 1f)
        assertEquals(Role.Button, node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
    }

    @Test fun nothingIsDrawnBehindTheGlyph() {
        show()
        val bmp = compose.onNodeWithTag("action").captureToImage().asAndroidBitmap()
        for ((x, y) in listOf(0 to 0, bmp.width - 1 to 0, 0 to bmp.height - 1, bmp.width - 1 to bmp.height - 1)) {
            assertEquals("corner $x,$y is the black page", Color.Black.toArgb(), bmp.getPixel(x, y))
        }
    }

    @Test fun pressedItDimsToHalfAtOnceAndReleaseBringsItBack() {
        show()
        val rest = brightest(compose.onNodeWithTag("action").captureToImage())
        assertTrue("white glyph at rest", rest > 240)
        compose.onNodeWithTag("action").performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val pressed = brightest(compose.onNodeWithTag("action").captureToImage())
        assertTrue("0.5 alpha: $pressed", pressed in 118..138)
        compose.onNodeWithTag("action").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(400)
        assertTrue(brightest(compose.onNodeWithTag("action").captureToImage()) > 240)
        assertEquals(1, clicks)
    }

    @Test fun aClickIsReported() {
        show()
        compose.onNodeWithTag("action").performClick()
        assertEquals(1, clicks)
    }
}
