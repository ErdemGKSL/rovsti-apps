package dev.rovsti.design.glass

import dev.rovsti.design.theme.GlassTokens
import dev.rovsti.design.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatAlphaTest {
    private val colors = LightColors
    private val tokens = GlassTokens.Light

    @Test
    fun presetsKeepTheirOwnFlatOpacity() {
        assertEquals(0.96f, GlassSpec.bar(tokens, colors, GlassTier.Flat).flatAlpha, 0f)
        assertEquals(0.97f, GlassSpec.popover(tokens, colors, GlassTier.Flat).flatAlpha, 0f)
        assertEquals(0.98f, GlassSpec.sheet(tokens, colors, GlassTier.Flat).flatAlpha, 0f)
        assertEquals(0.85f, GlassSpec.thumb(tokens, colors, GlassTier.Flat).flatAlpha, 0f)
    }

    @Test
    fun whatShowsThroughStaysUnderFivePercentForChrome() {
        for (spec in listOf(
            GlassSpec.bar(tokens, colors, GlassTier.Flat),
            GlassSpec.popover(tokens, colors, GlassTier.Flat),
            GlassSpec.sheet(tokens, colors, GlassTier.Flat),
        )) {
            assertTrue(1f - spec.flatAlpha < 0.05f)
        }
    }
}
