package dev.rovsti.catalog

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.height
import dev.rovsti.design.theme.LightColors
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.contrastRatio
import dev.rovsti.testing.ShotTest
import dev.rovsti.testing.ShotVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePageTest : ShotTest() {
    @Test
    fun palette() {
        shot("e1.2-palette") { CatalogApp(initialPage = CatalogPage.ThemePalette) }
    }

    @Test
    fun typeScale() {
        shot("e1.2-type-scale", variants = setOf(ShotVariant.Fs2)) { CatalogApp(initialPage = CatalogPage.ThemeType) }
    }

    @Test
    fun paletteShowsMeasuredNumbers() {
        composeFrame { CatalogApp(initialPage = CatalogPage.ThemePalette) }
        compose.onNodeWithText("Palette").assertIsDisplayed()
        compose.onAllNodes(hasText("Primary label")).onFirst().assertIsDisplayed()
        // Black on the F2F2F7 page is 18.9:1; the number is computed, so a wrong colour changes it.
        val shown =
            compose
                .onAllNodes(
                    hasText("%.1f:1".format(contrastRatio(LightColors.labelPrimary, LightColors.page))),
                ).fetchSemanticsNodes()
        assertTrue(shown.isNotEmpty())
    }

    @Test
    fun everyTypeStyleIsListedAtFontScale2() {
        val names = mutableListOf<String>()
        composeFrame(fontScale = 2f) {
            names += RovstiTheme.type.all.map { it.first }
            CatalogApp(initialPage = CatalogPage.ThemeType)
        }
        assertEquals(11, names.size)
        for (name in names) {
            compose
                .onNode(
                    SemanticsMatcher("text starts with $name") { node ->
                        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("$name  ") } == true
                    },
                ).assertExists()
        }
        // At font scale 2 the 34 sp sample is 68 sp: it must wrap to several lines, not clip to one.
        val sample = compose.onAllNodes(hasText("Rovsti sample text", substring = true)).onFirst().getBoundsInRoot()
        assertTrue("sample height ${sample.height}", sample.height.value > 2 * 41)
    }

    @Test
    fun darkThemeSwapsColours() {
        var light: Long = 0
        var dark: Long = 0
        composeFrame(dark = false) {
            light =
                RovstiTheme.colors.page.value
                    .toLong()
        }
        composeFrame(dark = true) {
            dark =
                RovstiTheme.colors.page.value
                    .toLong()
        }
        assertTrue(light != dark)
    }
}
