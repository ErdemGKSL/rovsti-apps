package dev.rovsti.design.ios.component

import dev.rovsti.design.controls.IosSwitchRules
import dev.rovsti.design.controls.segmentUnder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure decisions of the switch, slider and segmented control. Thresholds come from glisse `switch.tsx:107-179`. */
class IosControlsRulesTest {
    @Test
    fun aDragStartsAtFourDp() {
        assertFalse(IosSwitchRules.startsDrag(3.9f))
        assertTrue(IosSwitchRules.startsDrag(4f))
        assertTrue(IosSwitchRules.startsDrag(-4f))
    }

    @Test
    fun aTapCountsWithinTwentyFourPxOfTheBox() {
        assertTrue(IosSwitchRules.nearBox(-24f, 100f, 24f))
        assertTrue(IosSwitchRules.nearBox(124f, 100f, 24f))
        assertFalse(IosSwitchRules.nearBox(-24.1f, 100f, 24f))
        assertFalse(IosSwitchRules.nearBox(124.1f, 100f, 24f))
    }

    @Test
    fun aTapToggles_aFarReleaseDoesNothing() {
        assertTrue(IosSwitchRules.onRelease(checked = false, moved = false, value = 0f, near = true))
        assertFalse(IosSwitchRules.onRelease(checked = true, moved = false, value = 1f, near = true))
        assertFalse(IosSwitchRules.onRelease(checked = false, moved = false, value = 0f, near = false))
        assertTrue(IosSwitchRules.onRelease(checked = true, moved = false, value = 1f, near = false))
    }

    @Test
    fun aDragLandsWhereItWasLetGo_evenFarFromTheSwitch() {
        assertTrue(IosSwitchRules.onRelease(checked = false, moved = true, value = 0.51f, near = false))
        assertFalse(IosSwitchRules.onRelease(checked = true, moved = true, value = 0.5f, near = true))
        assertFalse(IosSwitchRules.onRelease(checked = true, moved = true, value = 0.2f, near = true))
    }

    @Test
    fun stepSnapsToTheNearestMultipleFromTheStart() {
        assertEquals(0.5f, snapToStep(0.52f, 0f, 1f, 0.25f), 1e-6f)
        assertEquals(10f, snapToStep(12f, 0f, 100f, 10f), 1e-6f)
        assertEquals(20f, snapToStep(15f, 0f, 100f, 10f), 1e-6f)
        assertEquals(100f, snapToStep(180f, 0f, 100f, 10f), 1e-6f)
        assertEquals(35f, snapToStep(31f, 5f, 105f, 10f), 1e-6f)
        assertEquals(0.37f, snapToStep(0.37f, 0f, 1f, null), 1e-6f)
        assertEquals(1f, snapToStep(3f, 0f, 1f, null), 1e-6f)
    }

    @Test
    fun segmentUnderMapsXToTheSlot() {
        // 300 px wide, 2 px inset, 3 slots of 98.67 px.
        assertEquals(0, segmentUnder(2f, 300f, 2f, 3))
        assertEquals(0, segmentUnder(100f, 300f, 2f, 3))
        assertEquals(1, segmentUnder(101f, 300f, 2f, 3))
        assertEquals(2, segmentUnder(298f, 300f, 2f, 3))
        assertEquals(0, segmentUnder(-50f, 300f, 2f, 3))
        assertEquals(2, segmentUnder(900f, 300f, 2f, 3))
    }
}
