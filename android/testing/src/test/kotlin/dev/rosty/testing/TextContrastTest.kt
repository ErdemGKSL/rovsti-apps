package dev.rosty.testing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import dev.rosty.design.theme.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TextContrastTest {
    private fun render(
        ink: Color,
        fill: Color,
        antiAlias: Boolean,
    ): androidx.compose.ui.graphics.ImageBitmap {
        val bitmap = Bitmap.createBitmap(200, 60, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(fill.toArgb())
        val paint =
            Paint().apply {
                color = ink.toArgb()
                textSize = 40f
                isAntiAlias = antiAlias
                isFakeBoldText = true
            }
        canvas.drawText("Hello", 8f, 44f, paint)
        return bitmap.asImageBitmap()
    }

    private val bounds = Rect(0f, 0f, 200f, 60f)

    @Test
    fun blackOnWhiteIsAboutTwentyOne() {
        val ratio = textContrast(render(Color.Black, Color.White, antiAlias = false), bounds)
        assertEquals(21.0, ratio, 0.5)
    }

    @Test
    fun whiteOnSkyBlueMeasuresTwoPointThree() {
        val fill = Color(0xFF5AB0FF)
        val ratio = textContrast(render(Color.White, fill, antiAlias = false), bounds)
        assertEquals(2.3, ratio, 0.1)
    }

    @Test
    fun antiAliasedTextStaysWithinTenPercentOfThePureRatio() {
        val fill = Color(0xFF5AB0FF)
        val pure = contrastRatio(Color.White, fill)
        val measured = textContrast(render(Color.White, fill, antiAlias = true), bounds)
        assertEquals(pure, measured, pure * 0.10)
    }

    @Test
    fun assertReadableFailsWithTheRatioInTheMessage() {
        val image = render(Color.White, Color(0xFF5AB0FF), antiAlias = true)
        val error = assertThrows(IllegalStateException::class.java) { assertReadable(image, bounds, 4.5, "label") }
        assert(error.message!!.contains("label") && error.message!!.contains("2.3"))
        assertReadable(render(Color.Black, Color.White, true), bounds, 4.5, "label")
    }
}
