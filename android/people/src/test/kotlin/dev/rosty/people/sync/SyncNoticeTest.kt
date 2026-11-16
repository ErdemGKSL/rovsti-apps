package dev.rosty.people.sync

import android.app.Application
import android.app.Notification
import dev.rosty.core.model.people.PeopleJobWire
import dev.rosty.core.model.people.SyncJobView
import dev.rosty.core.model.people.SyncProgress
import dev.rosty.core.model.people.SyncWire
import dev.rosty.platform.notify.PlatformChannels
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

    // ---- people sync v3: the gateway's job

    private fun job(
        phase: String,
        done: Int,
        total: Int,
        state: String = PeopleJobWire.RUNNING,
        waitUntilMs: Long? = null,
        waitSource: String? = null,
    ) = SyncJobView(
        jobId = "5f0c9a1b22d4e7a3",
        state = state,
        phase = phase,
        done = done,
        total = total,
        numbers = 40,
        waitUntilMs = waitUntilMs,
        waitSource = waitSource,
    )

    private fun server(view: SyncJobView) =
        RunnerState(
            active = true,
            stage = SyncWire.STAGE_SERVER,
            progress = SyncProgress(view.done, view.total),
            job = view,
        )

    @Test fun theGatewaysPhaseShowsWithItsCountAndABar() {
        val notice = SyncNotice.of(server(job(PeopleJobWire.PHASE_LOOKUP, 15, 40)), now)
        assertEquals(SyncNotice(SyncNotice.Line.Server(PeopleJobWire.PHASE_LOOKUP, 15, 40), 15, 40), notice)
        assertEquals("Gateway is checking numbers 15/40", notices.text(notice))
        assertEquals(
            "Gateway is getting names 3/9",
            notices.text(SyncNotice.of(server(job(PeopleJobWire.PHASE_PROFILES, 3, 9)), now)),
        )
        assertEquals(
            "Gateway is getting photos 1/4",
            notices.text(SyncNotice.of(server(job(PeopleJobWire.PHASE_PICTURES, 1, 4)), now)),
        )
        assertEquals(
            "a job that counted nothing yet is starting",
            SyncNotice.STARTING,
            SyncNotice.of(server(job(PeopleJobWire.PHASE_BUNDLE, 0, 0)), now),
        )
    }

    @Test fun theGatewaysLimitWaitReadsAsALimitAndKeepsTheBar() {
        val waiting =
            job(
                PeopleJobWire.PHASE_PROFILES,
                15,
                30,
                PeopleJobWire.WAITING,
                now + 3 * 60_000L,
                PeopleJobWire.WAIT_WHATSAPP,
            )
        val notice = SyncNotice.of(server(waiting), now)
        assertEquals(SyncNotice(SyncNotice.Line.Limit(3), 15, 30), notice)
        val offline =
            job(
                PeopleJobWire.PHASE_PROFILES,
                15,
                30,
                PeopleJobWire.WAITING,
                now + 60_000,
                PeopleJobWire.WAIT_NOT_CONNECTED,
            )
        assertEquals(
            "WhatsApp not connected on the gateway is not a limit",
            SyncNotice.Line.Server(PeopleJobWire.PHASE_PROFILES, 15, 30),
            SyncNotice.of(server(offline), now).line,
        )
    }

    @Test fun theDownloadShowsTheRecordsCommitted() {
        val state =
            RunnerState(
                active = true,
                stage = SyncWire.STAGE_DOWNLOAD,
                progress = SyncProgress(3, 12),
                job = job(PeopleJobWire.PHASE_BUNDLE, 40, 40, PeopleJobWire.READY),
            )
        val notice = SyncNotice.of(state, now)
        assertEquals(SyncNotice(SyncNotice.Line.Download(3, 12), 3, 12), notice)
        assertEquals("Downloading changes 3/12", notices.text(notice))
    }

    @Test
    @Config(qualifiers = "tr")
    fun turkishGatewayWords() {
        assertEquals(
            "Ağ geçidi numaraları denetliyor 15/40",
            notices.text(SyncNotice(SyncNotice.Line.Server(PeopleJobWire.PHASE_LOOKUP, 15, 40))),
        )
        assertEquals(
            "Ağ geçidi adları alıyor 3/9",
            notices.text(SyncNotice(SyncNotice.Line.Server(PeopleJobWire.PHASE_PROFILES, 3, 9))),
        )
        assertEquals(
            "Ağ geçidi fotoğrafları alıyor 1/4",
            notices.text(SyncNotice(SyncNotice.Line.Server(PeopleJobWire.PHASE_PICTURES, 1, 4))),
        )
        assertEquals("Değişiklikler indiriliyor 3/12", notices.text(SyncNotice(SyncNotice.Line.Download(3, 12))))
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
