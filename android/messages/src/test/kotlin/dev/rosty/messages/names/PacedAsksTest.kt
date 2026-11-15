package dev.rosty.messages.names

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The questions to WhatsApp go out one at a time, newest first, and never twice in a row. */
@OptIn(ExperimentalCoroutinesApi::class)
class PacedAsksTest {
    private val a = "+905550000001"
    private val b = "+905550000002"
    private val c = "+905550000003"

    @Test fun aBurstGoesOutOneAtATimeTheNewestFirst() =
        runTest {
            val asked = ArrayList<String>()
            val asks = PacedAsks(backgroundScope, asked::add, now = { testScheduler.currentTime }, paceMs = 500)

            asks.ask(a)
            asks.ask(b)
            asks.ask(c)
            runCurrent()
            assertEquals("one question at once, the newest first", listOf(c), asked)

            advanceTimeBy(500)
            runCurrent()
            assertEquals(listOf(c, b), asked)
            advanceTimeBy(500)
            runCurrent()
            assertEquals(listOf(c, b, a), asked)
        }

    @Test fun aNumberIsNotAskedAgainUntilTheRetryTimeHasPassed() =
        runTest {
            val asked = ArrayList<String>()
            val asks =
                PacedAsks(
                    backgroundScope,
                    asked::add,
                    now = { testScheduler.currentTime },
                    paceMs = 500,
                    retryMs = 5_000,
                )

            asks.ask(a)
            asks.ask(a)
            advanceTimeBy(1_000)
            asks.ask(a)
            runCurrent()
            assertEquals(listOf(a), asked)

            advanceTimeBy(5_000)
            asks.ask(a)
            runCurrent()
            assertEquals(listOf(a, a), asked)
        }
}
