package dev.rosty.wa.sync

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gateway budget, the back-off and the gate on a fake clock (`plans/wa-vault.md` 14). */
class WaRequestBudgetTest {
    private var clock = 1_000_000L
    private val budget = WaRequestBudget({ clock })

    @Test fun nothingGoesOutWhileLocked() {
        assertTrue(budget.locked)
        assertFalse(budget.tryTake())
        assertEquals(Long.MAX_VALUE, budget.waitMs())
        assertFalse(budget.tryColdSync())
    }

    @Test fun sixRequestsInTheFirstTenSecondsThenThirtyAMinute() {
        budget.unlocked()
        repeat(6) { assertTrue("request $it", budget.tryTake()) }
        assertFalse(budget.tryTake())
        clock += 4_000
        assertEquals(6_000L, budget.waitMs())
        clock += 6_000
        // After the burst window: the six count toward the sliding minute, 24 more fit.
        repeat(24) { assertTrue("request ${it + 6}", budget.tryTake()) }
        assertFalse(budget.tryTake())
        assertEquals(50_000L, budget.waitMs())
        clock += 50_000
        // The burst's six left the minute.
        repeat(6) { assertTrue(budget.tryTake()) }
        assertFalse(budget.tryTake())
    }

    @Test fun aLockForgetsTheUnlockAndAnUnlockStartsAFreshBurst() {
        budget.unlocked()
        repeat(6) { budget.tryTake() }
        budget.lock()
        assertFalse(budget.tryTake())
        budget.unlocked()
        assertTrue(budget.tryTake())
    }

    @Test fun atMostOneColdSyncPerThirtySeconds() {
        budget.unlocked()
        assertTrue(budget.tryColdSync())
        clock += 29_999
        assertFalse(budget.tryColdSync())
        clock += 1
        assertTrue(budget.tryColdSync())
    }

    @Test fun theBackOffDoublesFromTwoSecondsToFiveMinutesWithJitter() {
        var draw = 1.0
        val backoff = WaBackoff({ clock }, random = { draw })
        assertEquals(2_000L, backoff.failed())
        assertEquals(4_000L, backoff.failed())
        assertEquals(8_000L, backoff.failed())
        draw = 0.0
        // The lower half of the next step.
        assertEquals(8_000L, backoff.failed())
        draw = 1.0
        repeat(20) { backoff.failed() }
        assertEquals(300_000L, backoff.failed())
        assertEquals(300_000L, backoff.waitMs())
        clock += 100_000
        assertEquals(200_000L, backoff.waitMs())
        backoff.succeeded()
        assertEquals(0L, backoff.waitMs())
        assertEquals(2_000L, backoff.failed())
    }

    @Test fun theGatewaysRetryAfterIsHonoured() {
        val backoff = WaBackoff({ clock }, random = { 0.0 })
        assertEquals(45_000L, backoff.failed(retryAfterMs = 45_000))
        // Longer than the cap: the gateway asked for it.
        assertEquals(600_000L, backoff.failed(retryAfterMs = 600_000))
    }

    @Test fun theGateWaitsForTheBudgetAndBacksOffAfterARateLimit() =
        runTest {
            val slept = mutableListOf<Long>()
            val gate =
                WaRequestGate(budget, WaBackoff({ clock }, random = { 1.0 }), sleep = { ms ->
                    slept += ms
                    clock += ms
                })
            assertTrue(WaRequestGate.isLocked((gate.call { Outcome.Ok(1) } as Outcome.Err).error))
            assertEquals(0, gate.sent)

            budget.unlocked()
            repeat(6) { assertTrue(gate.call { Outcome.Ok(it) } is Outcome.Ok) }
            assertEquals(emptyList<Long>(), slept)
            // The seventh waits for the burst window to end.
            gate.call { Outcome.Ok(7) }
            assertEquals(listOf(10_000L), slept)

            val limited =
                RustError.Invalid(
                    "429",
                    buildJsonObject {
                        put("code", JsonPrimitive("rate_limited"))
                        put("retry_after_ms", JsonPrimitive(20_000))
                    },
                )
            gate.call { Outcome.Err(limited) }
            gate.call { Outcome.Ok(9) }
            assertEquals(listOf(10_000L, 20_000L), slept)
            assertEquals(9, gate.sent)

            budget.lock()
            assertTrue(gate.call { Outcome.Ok(10) } is Outcome.Err)
            assertEquals(9, gate.sent)
        }
}
