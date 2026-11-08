package dev.rovsti.catalog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.rovsti.testing.ShotTest
import org.junit.Test

/** The liquid glass over stripes, through the real backdrop path (no forced fallback anywhere). */
class GlassPageTest : ShotTest() {
    @Test
    fun liquidGlassOverStripes() {
        shot("e1.26-liquid-glass-over-stripes") { CatalogApp(initialPage = CatalogPage.Glass) }
    }

    @Test
    fun labelsAreDisplayed() {
        composeFrame { CatalogApp(initialPage = CatalogPage.Glass) }
        compose.onNodeWithText("Liquid glass").assertIsDisplayed()
        compose.onNodeWithTag("glass-popover").assertIsDisplayed()
        for (label in listOf("Messages", "Calls", "Stops")) {
            compose.onNodeWithTag("glass-capsule-$label").assertIsDisplayed()
        }
    }
}
