package dev.rosty.people.sync

import dev.rosty.core.model.people.SyncPhase
import dev.rosty.core.model.people.SyncProgress
import dev.rosty.core.model.people.SyncRunStatus
import dev.rosty.core.model.people.SyncStats
import dev.rosty.core.model.people.SyncWire
import dev.rosty.people.sync.PeopleSyncTriggers.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** People sync v3, section 6: when the app coming to the foreground, a reconnect or a new link starts a run. */
class PeopleSyncTriggersTest {
    private val now = 10_000_000_000L
    private val hour = 3_600_000L

    private fun run(
        kind: String,
        reason: String? = null,
    ) = SyncRunStatus(
        id = now - hour,
        mode = "incremental",
        stage = SyncWire.STAGE_SERVER,
        phase = SyncPhase(kind, reason = reason),
        progress = SyncProgress(0, 0),
        resumable = kind != SyncWire.RUNNING,
        heartbeatMs = now - hour,
        stats = SyncStats(0, 0, 0, 0, 0, 0),
    )

    private fun start(
        kind: Kind = Kind.FOREGROUND,
        lastFinishedMs: Long? = null,
        lastAttemptMs: Long? = null,
        run: SyncRunStatus? = null,
        notBeforeMs: Long? = null,
    ) = PeopleSyncTriggers.shouldStart(kind, now, lastFinishedMs, lastAttemptMs, run, notBeforeMs)

    @Test fun thePeriodIsTwelveHours() {
        assertEquals(12 * hour, PeopleSyncTriggers.PERIOD_MS)
        assertEquals(30 * 60_000L, PeopleSyncTriggers.ATTEMPT_DEBOUNCE_MS)
    }

    @Test fun neverSyncedStarts() {
        assertTrue(start())
        assertTrue(start(Kind.RECONNECT))
    }

    @Test fun aRunFinishedLessThanTwelveHoursAgoIsEnough() {
        assertFalse(start(lastFinishedMs = now - 11 * hour, lastAttemptMs = now - 11 * hour))
        assertFalse(start(Kind.RECONNECT, lastFinishedMs = now - 6 * hour, lastAttemptMs = now - 6 * hour))
        assertTrue(start(lastFinishedMs = now - 12 * hour, lastAttemptMs = now - 12 * hour))
    }

    @Test fun anAttemptInTheLastHalfHourIsNotRepeated() {
        assertFalse(start(lastFinishedMs = now - 20 * hour, lastAttemptMs = now - 10 * 60_000L))
        assertFalse(start(Kind.RECONNECT, lastAttemptMs = now - 29 * 60_000L))
        assertTrue(start(lastFinishedMs = now - 20 * hour, lastAttemptMs = now - 30 * 60_000L))
    }

    @Test fun theUsersStopAndAFailureAreNotUndoneByAnAutomaticStart() {
        val old = now - 20 * hour
        assertFalse(
            start(lastFinishedMs = old, lastAttemptMs = old, run = run(SyncWire.INTERRUPTED, SyncWire.CANCELLED)),
        )
        assertFalse(
            start(Kind.RECONNECT, lastFinishedMs = old, lastAttemptMs = old, run = run(SyncWire.FAILED, "auth")),
        )
        assertTrue(
            "a run the system interrupted goes on",
            start(lastFinishedMs = old, lastAttemptMs = old, run = run(SyncWire.INTERRUPTED, SyncWire.NETWORK)),
        )
    }

    @Test fun whatsAppsPenaltyHoldsEveryTrigger() {
        assertFalse(start(notBeforeMs = now + 60_000))
        assertFalse(start(Kind.NEW_LINK, notBeforeMs = now + 60_000))
        assertTrue("a penalty that is over", start(notBeforeMs = now - 1))
    }

    @Test fun aNewLinkStartsWhateverTheLastRunWas() {
        assertTrue(
            start(
                Kind.NEW_LINK,
                lastFinishedMs = now - 60_000,
                lastAttemptMs = now - 60_000,
                run = run(SyncWire.INTERRUPTED, SyncWire.CANCELLED),
            ),
        )
    }
}
