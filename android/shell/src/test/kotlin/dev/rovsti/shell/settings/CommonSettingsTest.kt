package dev.rovsti.shell.settings

import dev.rovsti.core.model.phone.HapticsLevel
import dev.rovsti.core.model.stops.Appearance
import dev.rovsti.design.haptics.HapticMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonSettingsTest {
    @Test fun autoFollowsTheSystemAndTheOthersForceIt() {
        assertTrue(Appearance.Auto.isDark(systemDark = true))
        assertFalse(Appearance.Auto.isDark(systemDark = false))
        assertFalse(Appearance.Light.isDark(systemDark = true))
        assertTrue(Appearance.Dark.isDark(systemDark = false))
        assertTrue(null.isDark(systemDark = true))
    }

    @Test fun everyLevelHasAModeAndOffIsOff() {
        assertEquals(HapticMode.Off, HapticsLevel.Off.toMode())
        assertEquals(HapticMode.System, HapticsLevel.Light.toMode())
        assertEquals(HapticMode.Strong, HapticsLevel.Normal.toMode())
        assertEquals(
            HapticsLevel.entries.size,
            HapticsLevel.entries
                .map { it.toMode() }
                .toSet()
                .size,
        )
    }

    @Test fun theFakeKeepsWhatIsWrittenAndHidesWhatIsNull() {
        val fake = FakeCommonSettings(appearance = null)
        assertNull(fake.appearance.value)
        assertEquals(HapticsLevel.Normal, fake.haptics.value)
        fake.setHaptics(HapticsLevel.Light)
        fake.setAppearance(Appearance.Dark)
        assertEquals(HapticsLevel.Light, fake.haptics.value)
        assertEquals(Appearance.Dark, fake.appearance.value)
        assertEquals(listOf<Any>(HapticsLevel.Light, Appearance.Dark), fake.writes)
    }
}
