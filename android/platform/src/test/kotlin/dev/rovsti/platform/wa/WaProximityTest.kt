package dev.rovsti.platform.wa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen goes dark at the ear: the proximity lock follows the call, the route and mute. */
class WaProximityTest {
    private class FakeLock(
        override val supported: Boolean = true,
    ) : ProximityLock {
        val log = mutableListOf<String>()

        override fun acquire() {
            log += "acquire"
        }

        override fun release() {
            log += "release"
        }
    }

    private val lock = FakeLock()
    private val proximity = WaProximity(lock)

    @Test fun heldOnlyWhileActiveOnTheEarpieceAndNotMuted() {
        proximity.update(active = false, earpiece = true, muted = false)
        assertEquals("connecting: not yet", emptyList<String>(), lock.log)
        proximity.update(active = true, earpiece = true, muted = false)
        assertTrue(proximity.holding)
        proximity.update(active = true, earpiece = true, muted = false)
        assertEquals("once", listOf("acquire"), lock.log)
    }

    @Test fun aRouteChangeReleasesAndTheEarpieceTakesItBack() {
        proximity.update(active = true, earpiece = true, muted = false)
        proximity.update(active = true, earpiece = false, muted = false)
        assertFalse(proximity.holding)
        proximity.update(active = true, earpiece = true, muted = false)
        assertEquals(listOf("acquire", "release", "acquire"), lock.log)
    }

    @Test fun muteAndTheEndOfTheCallRelease() {
        proximity.update(active = true, earpiece = true, muted = false)
        proximity.update(active = true, earpiece = true, muted = true)
        proximity.update(active = true, earpiece = true, muted = false)
        proximity.update(active = false, earpiece = true, muted = false)
        assertEquals(listOf("acquire", "release", "acquire", "release"), lock.log)
    }

    @Test fun teardownReleasesOnce() {
        proximity.update(active = true, earpiece = true, muted = false)
        proximity.close()
        proximity.close()
        assertEquals(listOf("acquire", "release"), lock.log)
    }

    @Test fun aDeviceWithoutTheSensorNeverAcquires() {
        val none = FakeLock(supported = false)
        WaProximity(none).apply {
            update(active = true, earpiece = true, muted = false)
            close()
        }
        assertEquals(emptyList<String>(), none.log)
    }

    @Test fun policyTable() {
        assertTrue(WaProximity.wants(active = true, earpiece = true, muted = false))
        assertFalse(WaProximity.wants(active = true, earpiece = false, muted = false))
        assertFalse(WaProximity.wants(active = true, earpiece = true, muted = true))
        assertFalse(WaProximity.wants(active = false, earpiece = true, muted = false))
    }
}
