package dev.rovsti.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RebuildCoalescerTest {
    /** A fake Handler: runnables wait in a queue until [runDue]. */
    private class Fake {
        val queue = ArrayList<Pair<Runnable, Long>>()
        val log = ArrayList<String>()
        fun post(r: Runnable, delay: Long) { queue.add(r to delay) }
        fun cancel(r: Runnable) { queue.removeAll { it.first === r } }
        fun runDue() { val due = queue.toList(); queue.clear(); due.forEach { it.first.run() } }
    }

    private fun make(fake: Fake, onRebuild: () -> Unit = { fake.log.add("rebuild") }) =
        RebuildCoalescer(fake::post, fake::cancel, rebuild = onRebuild)

    @Test fun aBurstOfTwentyCallbacksIsOneRebuildWithinTheWindow() {
        val fake = Fake()
        val c = make(fake)
        repeat(20) { c.schedule() }
        assertEquals(1, fake.queue.size)
        assertEquals(12L, fake.queue.single().second)
        assertTrue(fake.log.isEmpty())
        fake.runDue()
        assertEquals(listOf("rebuild"), fake.log)
        assertFalse(c.isScheduled)
    }

    @Test fun anImmediateRebuildCancelsThePendingOne() {
        val fake = Fake()
        val c = make(fake)
        c.schedule()
        c.now()
        assertEquals(listOf("rebuild"), fake.log)
        assertTrue(fake.queue.isEmpty())
        fake.runDue()
        assertEquals(1, fake.log.size)
    }

    @Test fun immediateEventsAreNeverDeferredAndKeepOrder() {
        val fake = Fake()
        val events = ArrayList<String>()
        val c = RebuildCoalescer(fake::post, fake::cancel) { events.add("snapshot") }
        c.schedule()              // onCallEndpointChanged
        events.add("answer")      // answer / reject / notifications run at once
        c.now()                   // onCallAdded: immediate
        events.add("known-ids")
        fake.runDue()             // the scheduled one was cancelled
        assertEquals(listOf("answer", "snapshot", "known-ids"), events)
    }

    @Test fun anotherScheduleWorksAfterTheFirstRan() {
        val fake = Fake()
        val c = make(fake)
        c.schedule(); fake.runDue()
        c.schedule(); fake.runDue()
        assertEquals(2, fake.log.size)
    }
}
