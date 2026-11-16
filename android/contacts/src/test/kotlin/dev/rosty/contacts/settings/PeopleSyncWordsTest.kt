package dev.rosty.contacts.settings

import dev.rosty.contacts.R
import dev.rosty.contacts.people.PeopleSyncStatus
import dev.rosty.core.model.people.PeopleJobWire
import dev.rosty.core.model.people.SyncWire
import dev.rosty.design.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** People sync v2, section 9, and v3, section 5: every state's wording and which buttons it offers. */
class PeopleSyncWordsTest {
    private val now = 10_000_000L

    private fun ui(status: PeopleSyncStatus) = peopleSyncUi(status, now) { "ago($it)" }

    @Test fun notLinkedHidesTheRows() {
        assertNull(ui(PeopleSyncStatus.NotLinked))
    }

    @Test fun idleConnectingAndNever() {
        assertEquals(UiText.Res(R.string.settings_people_never), ui(PeopleSyncStatus.Idle(null))!!.last)
        assertEquals(UiText.Res(R.string.settings_people_synced, listOf("ago(5)")), ui(PeopleSyncStatus.Idle(5))!!.last)
        val connecting = ui(PeopleSyncStatus.Connecting)!!
        assertEquals(UiText.Res(R.string.settings_people_connecting), connecting.last)
        assertFalse(connecting.canContinue)
        assertFalse(connecting.confirmRestart)
    }

    @Test fun runningShowsTheStageTheProgressAndStop() {
        val ui = ui(PeopleSyncStatus.Running(SyncWire.STAGE_PROFILES, 30, 120))!!
        assertEquals(UiText.Res(R.string.settings_people_running_profiles, listOf(30, 120)), ui.last)
        assertTrue(ui.running)
        assertEquals(0.25f, ui.progress!!, 0.001f)
        assertEquals(
            UiText.Res(R.string.settings_people_running_pictures, listOf(0, 0)),
            ui(PeopleSyncStatus.Running(SyncWire.STAGE_PICTURES, 0, 0))!!.last,
        )
        assertEquals(0f, ui(PeopleSyncStatus.Running(SyncWire.STAGE_LOOKUP, 0, 0))!!.progress!!, 0f)
    }

    @Test fun aRateLimitDisablesBothButtonsUntilItsEta() {
        val waiting = ui(PeopleSyncStatus.RateLimited(now + 11 * 60_000 + 1, SyncWire.STAGE_LOOKUP, 1, 2))!!
        assertEquals(
            UiText.Res(R.string.settings_people_limited, listOf(UiText.Plural(R.plurals.settings_people_minutes, 12))),
            waiting.last,
        )
        assertTrue(waiting.canContinue)
        assertFalse(waiting.continueEnabled)
        assertFalse(waiting.syncEnabled)
        val ready = ui(PeopleSyncStatus.RateLimited(now - 1, SyncWire.STAGE_LOOKUP, 1, 2))!!
        assertEquals(UiText.Res(R.string.settings_people_limited_ready), ready.last)
        assertTrue(ready.continueEnabled && ready.syncEnabled && ready.confirmRestart)
    }

    @Test fun interruptionsAreWordedByReason() {
        fun words(reason: String) = ui(PeopleSyncStatus.Interrupted(reason, SyncWire.STAGE_LOOKUP, 3, 9, 7))!!
        assertEquals(UiText.Res(R.string.settings_people_paused_network, listOf(3, 9)), words(SyncWire.NETWORK).last)
        assertEquals(
            UiText.Res(R.string.settings_people_paused_whatsapp, listOf(3, 9)),
            words(SyncWire.WA_DISCONNECTED).last,
        )
        assertEquals(UiText.Res(R.string.settings_people_stopped, listOf(3, 9)), words(SyncWire.CANCELLED).last)
        assertEquals(UiText.Res(R.string.settings_people_continues), words(SyncWire.SLICE_BUDGET).last)
        assertEquals(
            UiText.Res(R.string.settings_people_interrupted, listOf(3, 9, "ago(7)")),
            words(SyncWire.PROCESS_DEATH).last,
        )
        assertTrue(words(SyncWire.CANCELLED).canContinue && words(SyncWire.CANCELLED).confirmRestart)
    }

    @Test fun failuresAreWordedByReason() {
        fun words(reason: String) = ui(PeopleSyncStatus.Failed(reason, "boom", 7))!!.last
        assertEquals(UiText.Res(R.string.settings_people_failed_auth), words(SyncWire.AUTH))
        assertEquals(UiText.Res(R.string.settings_people_failed_gateway), words(SyncWire.GATEWAY))
        assertEquals(UiText.Res(R.string.settings_people_failed_storage), words(SyncWire.CORE))
        assertEquals(UiText.Res(R.string.settings_people_failed_other, listOf("ago(7)")), words(SyncWire.OTHER))
    }

    // ---- people sync v3: the gateway prepares, the phone downloads

    private fun preparing(
        phase: String,
        done: Int = 30,
        total: Int = 120,
        waitUntilMs: Long? = null,
        waitSource: String? = null,
        watched: Boolean = true,
    ) = ui(PeopleSyncStatus.Preparing(phase, done, total, waitUntilMs, waitSource, watched))!!

    @Test fun theGatewaysPhasesShowWithCountsABarAndStop() {
        val lookup = preparing(PeopleJobWire.PHASE_LOOKUP)
        assertEquals(UiText.Res(R.string.settings_people_server_lookup, listOf(30, 120)), lookup.last)
        assertTrue(lookup.running)
        assertEquals(0.25f, lookup.progress!!, 0.001f)
        assertFalse(lookup.canContinue)
        assertEquals(
            UiText.Res(R.string.settings_people_server_profiles, listOf(30, 120)),
            preparing(PeopleJobWire.PHASE_PROFILES).last,
        )
        assertEquals(
            UiText.Res(R.string.settings_people_server_pictures, listOf(30, 120)),
            preparing(PeopleJobWire.PHASE_PICTURES).last,
        )
    }

    @Test fun theGatewaysLimitWaitKeepsTheBar() {
        val limit = preparing(PeopleJobWire.PHASE_PROFILES, waitUntilMs = now + 4 * 60_000, waitSource = "whatsapp")
        assertEquals(
            UiText.Res(
                R.string.settings_people_server_limit,
                listOf(UiText.Plural(R.plurals.settings_people_minutes, 4)),
            ),
            limit.last,
        )
        assertEquals(0.25f, limit.progress!!, 0.001f)
        assertTrue("Stop stays", limit.running)
        val gateway = preparing(PeopleJobWire.PHASE_LOOKUP, waitUntilMs = now + 60_000, waitSource = "gateway")
        assertEquals(
            UiText.Res(
                R.string.settings_people_server_limit,
                listOf(UiText.Plural(R.plurals.settings_people_minutes, 1)),
            ),
            gateway.last,
        )
        val offline =
            preparing(
                PeopleJobWire.PHASE_LOOKUP,
                waitUntilMs = now + 60_000,
                waitSource = PeopleJobWire.WAIT_NOT_CONNECTED,
            )
        assertEquals(UiText.Res(R.string.settings_people_server_offline), offline.last)
    }

    @Test fun aJobNobodyFollowsHereIsQuietWithStopAndNoBar() {
        val quiet = preparing(PeopleJobWire.PHASE_PROFILES, watched = false)
        assertEquals(UiText.Res(R.string.settings_people_server_quiet), quiet.last)
        assertNull(quiet.progress)
        assertTrue(quiet.running)
        assertEquals(
            "nothing counted yet reads the same",
            UiText.Res(R.string.settings_people_server_quiet),
            preparing("", done = 0, total = 0).last,
        )
    }

    @Test fun theDownloadShowsItsCount() {
        val ui = ui(PeopleSyncStatus.Downloading(3, 12))!!
        assertEquals(UiText.Res(R.string.settings_people_downloading, listOf(3, 12)), ui.last)
        assertTrue(ui.running)
        assertEquals(0.25f, ui.progress!!, 0.001f)
    }

    @Test fun theLastRunSaysWhatChanged() {
        assertEquals(
            UiText.Res(R.string.settings_people_synced_unchanged, listOf("ago(5)")),
            ui(PeopleSyncStatus.Idle(5, people = 7, changed = 0))!!.last,
        )
        assertEquals(
            UiText.Res(
                R.string.settings_people_synced_changed,
                listOf("ago(5)", UiText.Plural(R.plurals.settings_people_changes, 3)),
            ),
            ui(PeopleSyncStatus.Idle(5, people = 7, changed = 3))!!.last,
        )
        assertEquals(
            "a run of an older build",
            UiText.Res(R.string.settings_people_synced, listOf("ago(5)")),
            ui(PeopleSyncStatus.Idle(5, people = 7, changed = null))!!.last,
        )
    }
}
