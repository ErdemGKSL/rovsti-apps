package dev.rovsti.contacts.settings

import dev.rovsti.contacts.R
import dev.rovsti.contacts.people.PeopleSyncStatus
import dev.rovsti.core.model.people.SyncWire
import dev.rovsti.design.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** People sync v2, section 9: every state's wording and which buttons it offers. */
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
}
