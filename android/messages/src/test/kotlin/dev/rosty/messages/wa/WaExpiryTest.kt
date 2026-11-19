package dev.rosty.messages.wa

import dev.rosty.core.model.wa.WaSyncExpireRequest
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** E6-WA R4: disappearing messages leave the store when their timer runs out, also after the app was closed. */
@OptIn(ExperimentalCoroutinesApi::class)
class WaExpiryTest {
    private val minute = 60_000L

    @Test fun whatExpiredWhileClosedGoesAtOpenAndTheRestWhenItsTimeComes() =
        runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA)
            kit.message(WaTestKit.text(ADA, "m1", NOW - 3 * minute, "gone").copy(expiresAt = NOW - minute))
            kit.message(WaTestKit.text(ADA, "m2", NOW - 2 * minute, "soon").copy(expiresAt = NOW + 5 * minute))
            kit.message(WaTestKit.text(ADA, "m3", NOW - minute, "kept"))
            kit.offline.start()
            settle()

            assertEquals(
                listOf("m2", "m3"),
                kit.ops.messages
                    .getValue(ADA)
                    .map { it.id },
            )

            kit.now = NOW + 6 * minute
            advanceTimeBy(6 * minute)
            runCurrent()
            assertEquals(
                listOf("m3"),
                kit.ops.messages
                    .getValue(ADA)
                    .map { it.id },
            )

            // Nothing left to wait for: the loop sleeps until rows change, it does not spin.
            val asked = kit.ops.writes.count { it is WaSyncExpireRequest }
            advanceTimeBy(60 * minute)
            runCurrent()
            assertEquals(asked, kit.ops.writes.count { it is WaSyncExpireRequest })
        }
}
