package dev.rovsti.catalog

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.rovsti.design.glass.GlassConditions
import dev.rovsti.design.glass.LocalGlassConditions
import dev.rovsti.testing.ShotTest
import org.junit.Test

class GlassPageTest : ShotTest() {
    @Test
    fun flatGlassOverStripes() {
        shot("e1.5-glass-flat-over-stripes") { CatalogApp(initialPage = CatalogPage.Glass) }
    }

    @Test
    fun flatLabelsAreDisplayed() {
        composeFrame { CatalogApp(initialPage = CatalogPage.Glass) }
        compose.onNodeWithText("Glass: Flat tier").assertIsDisplayed()
        compose.onNodeWithTag("glass-popover").assertIsDisplayed()
        for (label in listOf("Messages", "Calls", "Stops")) {
            compose.onNodeWithTag("glass-capsule-$label").assertIsDisplayed()
        }
    }

    @Test
    fun rimCpuTwin() {
        shot("e1.5-rim-cpu-twin") { RimTwinPanel() }
    }

    /** Frost tier: needs `RenderEffect` blur, which only some JVM renderers run. Judged by opening the PNG. */
    @Test
    fun frostGlassOverStripes() {
        shot("e1.5-glass-frost-over-stripes") {
            CompositionLocalProvider(
                LocalGlassConditions provides GlassConditions.Default.copy(sdk = 32),
            ) { CatalogApp(initialPage = CatalogPage.Glass) }
        }
    }

    /** Liquid tier: blur, lens and rim shader. The probe shows this Robolectric setup runs AGSL. */
    @Test
    fun liquidGlassOverStripes() {
        shot("e1.5-glass-liquid-over-stripes") {
            CompositionLocalProvider(LocalGlassConditions provides GlassConditions.Default) {
                CatalogApp(initialPage = CatalogPage.Glass)
            }
        }
    }
}
