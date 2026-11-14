package dev.rovsti.people.sync

import dev.rovsti.core.model.people.SyncWire
import dev.rovsti.people.sync.ForegroundSyncPlan.Next
import org.junit.Assert.assertEquals
import org.junit.Test

/** People sync v2 S11: what the worker does after a slice, a Stop and a stop by the system. */
class ForegroundSyncPlanTest {
    private val now = 1_000_000L
    private val gap = PeopleSyncRunner.CONTINUE_DELAY_MS

    @Test fun aShortLimitIsWaitedOutInTheForeground() {
        assertEquals(
            Next.Wait(150_000 + ForegroundSyncPlan.WAIT_MARGIN_MS),
            ForegroundSyncPlan.afterSlice(SliceResult.Waiting(now + 150_000), now, foreground = true),
        )
        assertEquals(
            "exactly five minutes is still short",
            Next.Wait(ForegroundSyncPlan.SHORT_WAIT_MS + ForegroundSyncPlan.WAIT_MARGIN_MS),
            ForegroundSyncPlan.afterSlice(
                SliceResult.Waiting(now + ForegroundSyncPlan.SHORT_WAIT_MS),
                now,
                foreground = true,
            ),
        )
        assertEquals(
            "a refusal until a near time waits too",
            Next.Wait(30_000 + ForegroundSyncPlan.WAIT_MARGIN_MS),
            ForegroundSyncPlan.afterSlice(SliceResult.Refused(now + 30_000), now, foreground = true),
        )
        assertEquals(
            "a time already past goes on at once",
            Next.Wait(ForegroundSyncPlan.WAIT_MARGIN_MS),
            ForegroundSyncPlan.afterSlice(SliceResult.Waiting(now - 5), now, foreground = true),
        )
    }

    @Test fun aLongLimitEndsTheForegroundRunWithAContinuation() {
        val wait = ForegroundSyncPlan.SHORT_WAIT_MS + 1
        assertEquals(
            Next.Continue(wait),
            ForegroundSyncPlan.afterSlice(SliceResult.Waiting(now + wait), now, foreground = true),
        )
        assertEquals(
            Next.Continue(20 * 60_000L),
            ForegroundSyncPlan.afterSlice(SliceResult.Waiting(now + 20 * 60_000L), now, foreground = true),
        )
    }

    @Test fun inTheBackgroundEveryLimitContinuesLaterAsBefore() {
        assertEquals(
            Next.Continue(150_000),
            ForegroundSyncPlan.afterSlice(SliceResult.Waiting(now + 150_000), now, foreground = false),
        )
        assertEquals(
            Next.Continue(gap),
            ForegroundSyncPlan.afterSlice(SliceResult.Interrupted(SyncWire.SLICE_BUDGET), now, foreground = false),
        )
    }

    @Test fun tooManyWaitsLeaveTheRestToAContinuation() {
        assertEquals(
            Next.Continue(gap),
            ForegroundSyncPlan.afterSlice(
                SliceResult.Waiting(now + 10),
                now,
                foreground = true,
                waits = ForegroundSyncPlan.MAX_WAITS,
            ),
        )
    }

    @Test fun theEndsOfARun() {
        assertEquals(Next.End, ForegroundSyncPlan.afterSlice(SliceResult.Done, now, foreground = true))
        assertEquals(Next.End, ForegroundSyncPlan.afterSlice(SliceResult.NotLinked, now, foreground = true))
        assertEquals(Next.End, ForegroundSyncPlan.afterSlice(SliceResult.Failed("auth", ""), now, foreground = true))
        assertEquals(Next.End, ForegroundSyncPlan.afterSlice(SliceResult.Refused(null), now, foreground = true))
        assertEquals(Next.Retry, ForegroundSyncPlan.afterSlice(SliceResult.LinkNotReady, now, foreground = true))
        assertEquals(
            Next.Continue(ForegroundSyncPlan.OFFLINE_DELAY_MS),
            ForegroundSyncPlan.afterSlice(SliceResult.Interrupted(SyncWire.NETWORK), now, foreground = true),
        )
    }

    @Test fun anotherRunInTheProcessEndsThisOneButALivePassIsWaitedFor() {
        assertEquals(Next.End, ForegroundSyncPlan.afterSlice(SliceResult.AlreadyRunning, now, foreground = true))
        assertEquals(
            "a Sync Now tap is not lost to a 30 s picture pass",
            Next.Wait(ForegroundSyncPlan.LIVE_RETRY_MS),
            ForegroundSyncPlan.afterSlice(SliceResult.AlreadyRunning, now, foreground = true, liveActive = true),
        )
    }

    @Test fun theUsersStopSchedulesNothingTheSystemsStopContinues() {
        assertEquals(Next.End, ForegroundSyncPlan.afterStop(byUser = true))
        assertEquals(Next.Continue(gap), ForegroundSyncPlan.afterStop(byUser = false))
    }
}
