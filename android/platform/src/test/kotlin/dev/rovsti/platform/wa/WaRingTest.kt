package dev.rovsti.platform.wa

import android.app.NotificationManager
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The WhatsApp ring: when it sounds, how (ringer mode, Do Not Disturb, a live call) and every way it stops. */
class WaRingTest {
    private class FakeOutputs : RingOutputs {
        val log = mutableListOf<String>()

        override fun ring() {
            log += "ring"
        }

        override fun vibrate() {
            log += "vibrate"
        }

        override fun waiting() {
            log += "waiting"
        }

        override fun stopAll() {
            log += "stop"
        }
    }

    private class FakeConditions(
        override var ringerMode: Int = AudioManager.RINGER_MODE_NORMAL,
        override var dndAllowsCalls: Boolean = true,
        override var otherCallLive: Boolean = false,
    ) : RingConditions

    private val outputs = FakeOutputs()
    private val conditions = FakeConditions()
    private val timers = mutableListOf<Pair<Runnable, Long>>()
    private val ring =
        WaRing(outputs, conditions, { r, ms -> timers += r to ms }, { r -> timers.removeAll { it.first === r } })

    @Test fun aRingSoundsAndVibratesUntilItIsAnswered() {
        assertEquals(RingStyle.Ring, ring.start("c1"))
        assertEquals(listOf("ring", "vibrate"), outputs.log)
        assertEquals("c1", ring.current)
        assertEquals(WaIncoming.RING_TIMEOUT_MS, timers.single().second)
        assertTrue(ring.stop("c1"))
        assertEquals(listOf("ring", "vibrate", "stop"), outputs.log)
        assertNull(ring.current)
        assertTrue("the timeout is cancelled", timers.isEmpty())
    }

    @Test fun thePushAndTheGatewayEventRingOnce() {
        ring.start("c1")
        assertNull("the same call again", ring.start("c1"))
        assertEquals(listOf("ring", "vibrate"), outputs.log)
        assertEquals(1, timers.size)
    }

    @Test fun anotherCallsStopDoesNothing() {
        ring.start("c1")
        assertFalse(ring.stop("c2"))
        assertEquals("c1", ring.current)
        assertFalse("nothing rings", WaRing(outputs, conditions, { _, _ -> }, {}).stop("c1"))
    }

    @Test fun theRingStopsByItselfAtTheGatewaysTimeout() {
        ring.start("c1")
        timers.single().first.run()
        assertNull(ring.current)
        assertEquals("stop", outputs.log.last())
    }

    @Test fun aVolumeOrPowerKeySilencesWithoutEndingTheRing() {
        ring.start("c1")
        assertTrue(ring.silence())
        assertEquals("stop", outputs.log.last())
        assertEquals("still ringing (no restart on a late push)", "c1", ring.current)
        assertFalse("a second key has nothing left to silence", ring.silence())
        assertNull(ring.start("c1"))
        assertTrue(ring.stop("c1"))
    }

    @Test fun aNewCallTakesOverFromAStaleRing() {
        ring.start("c1")
        assertEquals(RingStyle.Ring, ring.start("c2"))
        assertEquals(listOf("ring", "vibrate", "stop", "ring", "vibrate"), outputs.log)
        assertEquals("c2", ring.current)
        assertEquals(1, timers.size)
    }

    @Test fun vibrateModeOnlyVibratesAndSilentModeDoesNothing() {
        conditions.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertEquals(RingStyle.Vibrate, ring.start("c1"))
        assertEquals(listOf("vibrate"), outputs.log)
        ring.stop("c1")
        outputs.log.clear()
        conditions.ringerMode = AudioManager.RINGER_MODE_SILENT
        assertEquals(RingStyle.Silent, ring.start("c2"))
        assertEquals(emptyList<String>(), outputs.log)
        assertFalse("nothing to silence", ring.silence())
        assertTrue("still stoppable, and the timeout still runs", ring.stop("c2"))
    }

    @Test fun aLiveCallGetsTheCallWaitingBeep() {
        conditions.otherCallLive = true
        assertEquals(RingStyle.Waiting, ring.start("c1"))
        assertEquals(listOf("waiting"), outputs.log)
        assertTrue(ring.silence())
    }

    @Test fun doNotDisturbHoldsTheRingBack() {
        conditions.dndAllowsCalls = false
        conditions.otherCallLive = true
        assertEquals(RingStyle.Silent, ring.start("c1"))
        assertEquals(emptyList<String>(), outputs.log)
    }

    @Test fun styleTable() {
        val normal = AudioManager.RINGER_MODE_NORMAL
        assertEquals(RingStyle.Ring, WaRing.styleFor(normal, dndAllowsCalls = true, otherCallLive = false))
        assertEquals(RingStyle.Waiting, WaRing.styleFor(AudioManager.RINGER_MODE_SILENT, true, true))
        assertEquals(RingStyle.Silent, WaRing.styleFor(normal, dndAllowsCalls = false, otherCallLive = false))
    }

    @Test fun doNotDisturbLetsCallsThroughAsThePlatformSays() {
        val any = NotificationManager.Policy.PRIORITY_SENDERS_ANY
        val contacts = NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS

        fun allows(
            filter: Int,
            calls: Boolean = true,
            senders: Int = any,
            bypass: Boolean = false,
        ) = WaRing.dndAllowsCalls(filter, calls, senders, bypass)
        assertTrue("off", allows(NotificationManager.INTERRUPTION_FILTER_ALL))
        assertTrue(allows(NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertFalse("calls not in the exceptions", allows(NotificationManager.INTERRUPTION_FILTER_PRIORITY, false))
        assertFalse("only contacts", allows(NotificationManager.INTERRUPTION_FILTER_PRIORITY, senders = contacts))
        assertTrue(
            "the channel may override DND",
            allows(NotificationManager.INTERRUPTION_FILTER_PRIORITY, calls = false, bypass = true),
        )
        assertFalse("total silence", allows(NotificationManager.INTERRUPTION_FILTER_NONE, bypass = true))
        assertFalse("alarms only", allows(NotificationManager.INTERRUPTION_FILTER_ALARMS))
    }
}
