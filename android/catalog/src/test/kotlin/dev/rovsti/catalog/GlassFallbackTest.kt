package dev.rovsti.catalog

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import dev.rovsti.design.backdrop.BackdropDebug
import dev.rovsti.design.glass.GlassSettings
import dev.rovsti.design.glass.LocalGlassSettings
import dev.rovsti.testing.ShotTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The only test that asks for the fallback: with reduce transparency on, every glass surface paints the translucent
 * fallback (recorded as such, no RenderEffect), and none draws through the backdrop. (A device below API 31 takes the
 * same path; it is below the app's minSdk, so it is not run here.)
 */
class GlassFallbackTest : ShotTest() {
    @Before
    fun enableDebug() {
        BackdropDebug.clear()
        BackdropDebug.enabled = true
    }

    @After
    fun disableDebug() {
        BackdropDebug.enabled = false
        BackdropDebug.clear()
    }

    @Test
    fun reduceTransparencyPaintsTheFallbackEverywhere() {
        composeFrame {
            CompositionLocalProvider(LocalGlassSettings provides GlassSettings(reduceTransparency = true)) {
                CatalogApp(initialPage = CatalogPage.Glass)
            }
        }
        advance(600)
        compose.onRoot().captureToImage()
        val records = BackdropDebug.snapshot()
        assertTrue("the glass page must have glass surfaces", records.isNotEmpty())
        assertTrue(
            "no surface may use the backdrop effect with reduce transparency: ${records.filter { it.hasRenderEffect }}",
            records.none { it.hasRenderEffect },
        )
        assertTrue("fallbacks are tagged", records.all { it.tag != null })
    }
}
