package dev.rosty.messages.spam

import dev.rosty.core.model.messages.DeliveryState
import dev.rosty.core.model.messages.SmsRow
import dev.rosty.platform.sms.FakeSmsRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** E6.11 M47: "Delete codes after copying" books the passcode message and deletes it 24 h later. */
class CodeCleanupTest {
    private val dispatcher = StandardTestDispatcher()
    private val start = 1_790_000_000_000L

    private fun sms(
        id: Long,
        body: String,
        at: Long,
        box: Int = CleanupPlan.INBOX_BOX,
    ) = SmsRow(id, 5, "BANK", body, at, box, true, null, DeliveryState.Delivered)

    private class Rig(
        val cleanup: CodeCleanup,
        val sms: FakeSmsRepository,
        val wakes: MutableList<Long>,
        var now: Long,
        var role: Boolean = true,
    )

    private fun rig(): Rig {
        val dir = Files.createTempDirectory("code-cleanup").toFile().apply { deleteOnExit() }
        val repo =
            FakeSmsRepository(
                listOf(
                    sms(1, "Your code is 482913", start - 60_000),
                    sms(2, "Thanks, code 482913 received", start - 30_000, box = 2),
                    sms(3, "Meet at six", start - 10_000),
                ),
            )
        lateinit var rig: Rig
        val cleanup =
            CodeCleanup(
                file = dir.resolve(CodeCleanup.FILE),
                sms = repo,
                roleHeld = { rig.role },
                wake = { rig.wakes += it },
                now = { rig.now },
                io = dispatcher,
            )
        rig = Rig(cleanup, repo, mutableListOf(), start)
        return rig
    }

    @Test fun offByDefaultNothingIsBooked() =
        runTest(dispatcher) {
            val r = rig()
            assertFalse(r.cleanup.load().enabled)
            assertFalse(r.cleanup.copied(5, "482913"))
            assertTrue(r.wakes.isEmpty())
        }

    @Test fun aCopiedCodeDeletesItsMessageADayLater() =
        runTest(dispatcher) {
            val r = rig()
            r.cleanup.setEnabled(true)
            assertTrue(r.cleanup.copied(5, "482913"))
            // The incoming passcode message, never my own reply that quotes the code.
            assertEquals(listOf(PendingCode(1, start + CodeCleanup.DELAY_MS)), r.cleanup.load().pending)
            assertEquals(listOf(CodeCleanup.DELAY_MS), r.wakes)

            r.now = start + CodeCleanup.DELAY_MS - 1
            assertEquals(0, r.cleanup.sweep())
            r.now = start + CodeCleanup.DELAY_MS
            assertEquals(1, r.cleanup.sweep())
            assertEquals(
                listOf(2L, 3L),
                r.sms
                    .messages(5)
                    .sms
                    .map { it.id }
                    .sorted(),
            )
            assertTrue(
                r.cleanup
                    .load()
                    .pending
                    .isEmpty(),
            )
        }

    @Test fun withoutTheSmsRoleTheBookingWaits() =
        runTest(dispatcher) {
            val r = rig()
            r.cleanup.setEnabled(true)
            r.cleanup.copied(5, "482913")
            r.now = start + CodeCleanup.DELAY_MS
            r.role = false
            assertEquals(0, r.cleanup.sweep())
            assertEquals(
                1,
                r.cleanup
                    .load()
                    .pending.size,
            )
            r.role = true
            assertEquals(1, r.cleanup.sweep())
        }

    @Test fun turningItOffForgetsTheBookings() =
        runTest(dispatcher) {
            val r = rig()
            r.cleanup.setEnabled(true)
            r.cleanup.copied(5, "482913")
            r.cleanup.setEnabled(false)
            assertTrue(
                r.cleanup
                    .load()
                    .pending
                    .isEmpty(),
            )
            assertFalse(r.cleanup.enabled.value)
        }

    @Test fun thePlanSplitsAtNowAndKeepsEachMessageOnce() {
        val plan =
            CleanupPlan.of(
                listOf(PendingCode(1, 10), PendingCode(1, 30), PendingCode(2, 50), PendingCode(3, 20)),
                nowMs = 20,
            )
        assertEquals(listOf(1L, 3L), plan.due.map { it.smsId }.sorted())
        assertEquals(listOf(PendingCode(2, 50)), plan.later)
        assertEquals(50L, plan.nextDueMs)
        assertNull(CleanupPlan.of(emptyList(), 0).nextDueMs)
    }

    @Test fun theMessageIsTheNewestIncomingOneWithTheCode() {
        val rows = listOf(sms(1, "code 1111", 10), sms(2, "code 1111", 20), sms(3, "code 1111", 30, box = 2))
        assertEquals(2L, CleanupPlan.messageOf(rows, "1111"))
        assertNull(CleanupPlan.messageOf(rows, "9999"))
        assertNull(CleanupPlan.messageOf(rows, " "))
    }
}
