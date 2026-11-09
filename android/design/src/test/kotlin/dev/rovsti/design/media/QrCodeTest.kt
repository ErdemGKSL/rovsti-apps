package dev.rovsti.design.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import dev.rovsti.design.theme.RovstiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class QrCodeTest {
    @get:Rule
    val compose = createComposeRule()

    /** 5 x 5 with a dark first module, a dark centre and a dark run on the last row. */
    private val modules =
        QrModules(5) { x, y -> (x == 0 && y == 0) || (x == 2 && y == 2) || (y == 4 && x in 1..3) }

    private fun pixel(
        image: androidx.compose.ui.graphics.ImageBitmap,
        x: Int,
        y: Int,
    ): Int {
        val buffer = IntArray(image.width * image.height)
        image.readPixels(buffer)
        return buffer[y * image.width + x]
    }

    @Test
    fun theBitmapHasTheQuietZoneAndTheModules() {
        val px = 90
        val image = renderQrBitmap(modules, px)
        assertEquals(px, image.width)
        // 5 modules + 2 * 4 quiet = 13 cells of 6 px (90 / 13 = 6), centred: origin (90 - 78) / 2 = 6.
        val cell = 6
        val origin = 6
        val dark = Color.Black.toArgb()
        val light = Color.White.toArgb()
        assertEquals("corner is quiet zone", light, pixel(image, 1, 1))
        assertEquals(
            "inside the quiet zone, left of the first module",
            light,
            pixel(
                image,
                origin + 2 * cell,
                origin + 5 * cell,
            ),
        )
        assertEquals("first module", dark, pixel(image, origin + 4 * cell + 1, origin + 4 * cell + 1))
        assertEquals("centre module", dark, pixel(image, origin + 6 * cell + 1, origin + 6 * cell + 1))
        assertEquals("a light module", light, pixel(image, origin + 5 * cell + 1, origin + 4 * cell + 1))
        assertEquals("run on the last row", dark, pixel(image, origin + 7 * cell + 1, origin + 8 * cell + 1))
    }

    @Test
    fun theExportIs1024PixelsByDefault() {
        assertEquals(1024, renderQrBitmap(modules).width)
        assertEquals(1024, QR_EXPORT_PX)
    }

    @Test
    fun theDarkThemeCardStaysWhiteWithBlackModules() {
        compose.setContent {
            RovstiTheme(darkTheme = true) {
                // 130 dp = 13 cells of 10 dp at density 1 (Robolectric default); the first module is at cell 4.
                Box(Modifier.size(130.dp)) { QrCode(modules, Modifier.size(130.dp)) }
            }
        }
        compose.waitForIdle()
        val image = compose.onNodeWithContentDescription("QR code").captureToImage()
        val density = compose.density.density

        fun at(cell: Int) = pixel(image, ((cell * 10 + 5) * density).toInt(), ((cell * 10 + 5) * density).toInt())
        assertEquals("first module is black in the dark theme", Color.Black.toArgb(), at(4))
        assertEquals("quiet zone is white in the dark theme", Color.White.toArgb(), at(2))
        assertNotEquals("light module", Color.Black.toArgb(), at(5))
    }

    @Test
    fun anEmptyCardIsAllWhiteInside() {
        compose.setContent { RovstiTheme(darkTheme = true) { QrCode(null, Modifier.size(100.dp)) } }
        compose.waitForIdle()
        val image = compose.onNodeWithContentDescription("QR code").captureToImage()
        assertEquals(Color.White.toArgb(), pixel(image, image.width / 2, image.height / 2))
    }
}
