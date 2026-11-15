package dev.rosty.messages.wa

import dev.rosty.core.model.wa.WaSyncHistory
import dev.rosty.core.model.wa.WaSyncHistoryState
import dev.rosty.core.model.wa.WaSyncMediaStats
import dev.rosty.core.model.wa.WaSyncUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The models of the history row and of the storage section: pure, from what the store answers. */
class WaHistoryUiTest {
    private fun history(
        state: WaSyncHistoryState,
        progress: Int? = null,
        chats: Int = 0,
        messages: Long = 0,
    ) = WaSyncHistory(state, progress, chats, messages, updatedMs = 1)

    @Test fun thereIsARowOnlyWhileTheHistoryIsArriving() {
        assertNull(WaHistoryUi.of(null))
        assertNull(WaHistoryUi.of(history(WaSyncHistoryState.Idle)))
        assertNull(WaHistoryUi.of(history(WaSyncHistoryState.Done, progress = 100, chats = 20, messages = 9_000)))
        assertEquals(
            WaHistoryUi(42, 128, 12_340),
            WaHistoryUi.of(history(WaSyncHistoryState.Syncing, progress = 42, chats = 128, messages = 12_340)),
        )
    }

    @Test fun anUnknownPercentHasNoBarAndCountsStillShow() {
        val ui = WaHistoryUi.of(history(WaSyncHistoryState.Syncing, chats = 3, messages = 70))!!
        assertNull(ui.percent)
        assertNull(ui.fraction)
        assertEquals(3 to 70L, ui.chats to ui.messages)
    }

    @Test fun thePercentStaysInsideItsRangeAndCountsAreNeverNegative() {
        assertEquals(100, WaHistoryUi.of(history(WaSyncHistoryState.Syncing, progress = 140))?.percent)
        assertEquals(0, WaHistoryUi.of(history(WaSyncHistoryState.Syncing, progress = -3))?.percent)
        val odd = WaHistoryUi.of(history(WaSyncHistoryState.Syncing, progress = 50, chats = -1, messages = -9))!!
        assertEquals(0 to 0L, odd.chats to odd.messages)
        assertEquals(0.5f, odd.fraction)
        assertEquals(1f, WaHistoryUi(100, 0, 0).fraction)
    }

    private val mib = 1024L * 1024

    @Test fun theCapChoicesAreTheFiveOfTheSettings() {
        assertEquals(listOf(256 * mib, 512 * mib, 1024 * mib, 2048 * mib, null), WaStorageUi.CAPS)
        assertEquals(
            listOf("256 MB", "512 MB", "1 GB", "2 GB"),
            WaStorageUi.CAPS.filterNotNull().map(WaStorageUi::size),
        )
    }

    @Test fun sizesReadTheSameInEveryLanguage() {
        assertEquals("0 B", WaStorageUi.size(0))
        assertEquals("512 B", WaStorageUi.size(512))
        assertEquals("3 KB", WaStorageUi.size(3 * 1024 + 100))
        assertEquals("187 MB", WaStorageUi.size(187 * mib + 5))
        assertEquals("1.5 GB", WaStorageUi.size(1536 * mib))
    }

    @Test fun theUsedSizeCountsCachedFilesAndFilesStillBeingSent() {
        val stats =
            WaSyncMediaStats(
                total = WaSyncUsage(12, 180 * mib),
                exempt = WaSyncUsage(2, 10 * mib),
                byKind = emptyList(),
                outbox = WaSyncUsage(1, 7 * mib),
                queued = 0,
                downloading = 0,
                failed = 0,
                capBytes = null,
                exemptOverCap = false,
            )
        assertEquals(WaStorageUi(187 * mib, null), WaStorageUi.of(stats))
        assertEquals(512 * mib, WaStorageUi.of(stats.copy(capBytes = 512 * mib)).capBytes)
    }
}
