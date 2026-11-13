package dev.rovsti.design.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertTrue
import org.junit.Test

/** E5.14: the melt starts from the old page colour, ends on the new theme, and the first composition does not melt. */
class ThemeMeltTest : ShotTest() {
    private fun red(): Int {
        val bmp = compose.onNodeWithTag("melt").captureToImage().asAndroidBitmap()
        return (bmp.getPixel(bmp.width / 2, bmp.height / 2) shr 16) and 0xFF
    }

    @Test fun flippingToDarkStartsFromTheLightPageAndEndsDark() {
        var dark by mutableStateOf(false)
        composeFrame {
            ThemeMelt(dark, Modifier.size(40.dp).testTag("melt")) {
                Box(Modifier.fillMaxSize().background(if (dark) DarkColors.page else LightColors.page))
            }
        }
        advance(50)
        val light = red()
        assertTrue("light page is bright: $light", light > 200)
        dark = true
        advance(30)
        val early = red()
        advance(THEME_MELT_MS + 200L)
        val late = red()
        assertTrue("early frame still shows the old page: $early", early > 150)
        assertTrue("the melt ended on the dark page: $late", late < 60)
    }
}
