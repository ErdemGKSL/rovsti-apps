package dev.rosty.phone.stats

import dev.rosty.design.theme.DarkColors
import dev.rosty.design.theme.LightColors
import dev.rosty.design.theme.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** r19-s6: a short bar's count ends opaque, and the labels on the call panels read at 4.5:1 in both themes. */
class StatsCountReadableTest {
    @Test
    fun aShortBarEndsWithAnOpaqueCount() {
        assertEquals(1f, statsCountAlpha(fill = 0.2f, fraction = 0.2f), 0f)
        assertEquals(0.5f, statsCountAlpha(fill = 0.1f, fraction = 0.2f), 1e-6f)
        assertEquals(1f, statsCountAlpha(fill = 0.5f, fraction = 0f), 0f)
    }

    @Test
    fun theCountAndDropColoursReadOnTheirSurfaces() {
        assertTrue(contrastRatio(LightColors.labelSecondary, LightColors.page) >= 4.5)
        assertTrue(contrastRatio(DarkColors.labelSecondary, DarkColors.page) >= 4.5)
        // The conference "End" label is the theme destructive on the glass pill (light grey / dark grey).
        assertTrue(
            contrastRatio(
                LightColors.destructive,
                androidx.compose.ui.graphics
                    .Color(0xFFE8E8EA),
            ) >= 4.5,
        )
        assertTrue(
            contrastRatio(
                DarkColors.destructive,
                androidx.compose.ui.graphics
                    .Color(0xFF2C2C2E),
            ) >= 4.5,
        )
    }
}
