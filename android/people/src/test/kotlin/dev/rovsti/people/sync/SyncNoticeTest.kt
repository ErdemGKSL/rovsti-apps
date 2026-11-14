package dev.rovsti.people.sync

import android.app.Application
import android.app.Notification
import dev.rovsti.core.model.people.SyncProgress
import dev.rovsti.core.model.people.SyncWire
import dev.rovsti.platform.notify.PlatformChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** People sync v2 S11: the words and the bar of the sync's ongoing notification, in English and Turkish. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncNoticeTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val notices = SyncNotices(app, null)
    private val now = 1_000_000L

    private fun running(
        stage: String,
        done: Int,
        total: Int,
    ) = RunnerState(active = true, stage = stage, progress = SyncProgress(done, total))

    @Test fun aRunningStageShowsItsCountAndADeterminateBar() {
        val notice = SyncNotice.of(running(SyncWire.STAGE_LOOKUP, 12, 340), now)
        assertEquals(SyncNotice(SyncNotice.Line.Stage(SyncWire.STAGE_LOOKUP, 12, 340), 12, 340), notice)
        assertEquals(false, notice.indeterminate)
        assertEquals("Checking numbers 12/340", notices.text(notice))
        assertEquals("Getting names 3/9", notices.text(SyncNotice.of(running(SyncWire.STAGE_PROFILES, 3, 9), now)))
        assertEquals(
            "Downloading photos 0/4",
            notices.text(SyncNotice.of(running(SyncWire.STAGE_PICTURES, 0, 4), now)),
        )
    }

    @Test fun nothingCountedYetIsStartingWithAnIndeterminateBar() {
        val notice = SyncNotice.of(RunnerState(active = true), now)
        assertEquals(SyncNotice.STARTING, notice)
        assertTrue(notice.indeterminate)
        assertEquals("Starting…", notices.text(notice))
    }

    @Test fun aCountPastTheTotalIsClamped() {
        val notice = SyncNotice.of(running(SyncWire.STAGE_LOOKUP, 400, 340), now)
        assertEquals(340, notice.done)
        assertEquals(340, notice.max)
    }

    @Test fun aShortLimitSaysWhenItContinuesAndKeepsTheBar() {
        val inline = running(SyncWire.STAGE_PROFILES, 30, 90).copy(sleepingUntilMs = now + 70_000, limited = true)
        val notice = SyncNotice.of(inline, now)
        assertEquals(SyncNotice.Line.Limit(2), notice.line)
        assertEquals(30, notice.done)
        assertEquals("WhatsApp limit, continues in 2 min", notices.text(notice))
        // The worker's own wait between two slices (the runner is idle) reads the same.
        val between = SyncNotice.of(RunnerState(), now, limitUntilMs = now + 4 * 60_000L)
        assertEquals(SyncNotice.Line.Limit(4), between.line)
    }

    @Test fun theNormalPaceIsNotALimit() {
        val pace = running(SyncWire.STAGE_LOOKUP, 15, 60).copy(sleepingUntilMs = now + 20_000, limited = false)
        assertEquals(SyncNotice.Line.Stage(SyncWire.STAGE_LOOKUP, 15, 60), SyncNotice.of(pace, now).line)
    }

    @Test fun minutesRoundUpAndNeverSayZero() {
        assertEquals(1, SyncNotice.minutes(0))
        assertEquals(1, SyncNotice.minutes(1))
        assertEquals(1, SyncNotice.minutes(60_000))
        assertEquals(2, SyncNotice.minutes(60_001))
        assertEquals(5, SyncNotice.minutes(5 * 60_000L))
        assertEquals(1, SyncNotice.minutes(-5))
    }

    @Test
    @Config(qualifiers = "tr")
    fun turkishWords() {
        assertEquals(
            "Numaralar denetleniyor 12/340",
            notices.text(SyncNotice.of(running(SyncWire.STAGE_LOOKUP, 12, 340), now)),
        )
        assertEquals(
            "WhatsApp sınırı, 3 dk sonra devam edecek",
            notices.text(SyncNotice(SyncNotice.Line.Limit(3))),
        )
        val built = notices.build(SyncNotice.STARTING)
        val title = built.extras.getString(Notification.EXTRA_TITLE)
        assertEquals("WhatsApp adları ve fotoğrafları eşitleniyor", title)
        assertEquals(
            "Durdur",
            built.actions
                .single()
                .title
                .toString(),
        )
    }

    @Test fun theNotificationIsOngoingSilentAndHasStop() {
        val built = notices.build(SyncNotice.of(running(SyncWire.STAGE_LOOKUP, 12, 340), now))
        assertEquals(PlatformChannels.PEOPLE_SYNC, built.channelId)
        assertTrue("ongoing", built.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue("not cleared by Clear all", built.flags and Notification.FLAG_NO_CLEAR != 0)
        assertTrue("alerts once", built.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals("Syncing WhatsApp names and photos", built.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(340, built.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(12, built.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(
            "Stop",
            built.actions
                .single()
                .title
                .toString(),
        )
        assertEquals(Notification.CATEGORY_PROGRESS, built.category)
    }
}
