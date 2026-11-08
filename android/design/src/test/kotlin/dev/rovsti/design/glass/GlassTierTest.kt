package dev.rovsti.design.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassTierTest {
    private val base =
        GlassConditions(
            sdk = 34,
            lowRam = false,
            powerSave = false,
            reduceTransparency = false,
            reduceEffects = false,
            sourceAvailable = true,
            contentMoving = false,
            forTests = false,
        )

    private fun tier(c: GlassConditions) = glassTier(c)

    @Test
    fun plainModernDeviceIsLiquid() {
        assertEquals(GlassTier.Liquid, tier(base))
        assertEquals(GlassTier.Liquid, tier(base.copy(sdk = 33)))
        assertEquals(GlassTier.Liquid, tier(base.copy(sdk = 37)))
    }

    @Test
    fun forTestsWinsOverEverything() {
        assertEquals(GlassTier.Flat, tier(base.copy(forTests = true)))
        assertEquals(GlassTier.Flat, tier(base.copy(forTests = true, reduceEffects = true, powerSave = true)))
    }

    @Test
    fun reduceTransparencyIsFlat() {
        assertEquals(GlassTier.Flat, tier(base.copy(reduceTransparency = true)))
    }

    @Test
    fun noSourceIsFlat() {
        assertEquals(GlassTier.Flat, tier(base.copy(sourceAvailable = false)))
    }

    @Test
    fun oldSdkIsFlatBelow31AndFrostBelow33() {
        assertEquals(GlassTier.Flat, tier(base.copy(sdk = 30)))
        assertEquals(GlassTier.Flat, tier(base.copy(sdk = 26)))
        assertEquals(GlassTier.Frost, tier(base.copy(sdk = 31)))
        assertEquals(GlassTier.Frost, tier(base.copy(sdk = 32)))
    }

    @Test
    fun lowRamPlusPowerSaveIsFlat() {
        assertEquals(GlassTier.Flat, tier(base.copy(lowRam = true, powerSave = true)))
    }

    @Test
    fun oneOfLowRamPowerSaveReduceEffectsOrMovingIsFrost() {
        assertEquals(GlassTier.Frost, tier(base.copy(lowRam = true)))
        assertEquals(GlassTier.Frost, tier(base.copy(powerSave = true)))
        assertEquals(GlassTier.Frost, tier(base.copy(reduceEffects = true)))
        assertEquals(GlassTier.Frost, tier(base.copy(contentMoving = true)))
    }

    @Test
    fun flatReasonsBeatFrostReasons() {
        val worst = base.copy(sdk = 32, reduceEffects = true, contentMoving = true, powerSave = true)
        assertEquals(GlassTier.Frost, tier(worst))
        assertEquals(GlassTier.Flat, tier(worst.copy(reduceTransparency = true)))
        assertEquals(GlassTier.Flat, tier(worst.copy(lowRam = true)))
    }

    @Test
    fun fullTableOverEveryBooleanCombination() {
        for (bits in 0 until 64) {
            val c =
                base.copy(
                    lowRam = bits and 1 != 0,
                    powerSave = bits and 2 != 0,
                    reduceTransparency = bits and 4 != 0,
                    reduceEffects = bits and 8 != 0,
                    sourceAvailable = bits and 16 == 0,
                    contentMoving = bits and 32 != 0,
                )
            val expected =
                when {
                    c.reduceTransparency || !c.sourceAvailable -> GlassTier.Flat
                    c.lowRam && c.powerSave -> GlassTier.Flat
                    c.lowRam || c.powerSave || c.reduceEffects || c.contentMoving -> GlassTier.Frost
                    else -> GlassTier.Liquid
                }
            assertEquals("bits=$bits", expected, tier(c))
        }
    }
}
