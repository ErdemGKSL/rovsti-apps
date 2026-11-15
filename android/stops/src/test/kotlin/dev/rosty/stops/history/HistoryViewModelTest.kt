package dev.rosty.stops.history

import dev.rosty.core.model.stops.Outcome
import dev.rosty.core.ops.NativeStopsOps
import dev.rosty.design.text.UiText
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.R
import dev.rosty.stops.data.HistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The History sheet's rows, statistics, edits and exports on the REAL core over the fake's files. */
class HistoryViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fake = FakeStopsCore(history = HistoryFixtures.history, scriptTransit = false, real = HostCore.real())
    private val ops = NativeStopsOps(fake.rust)
    private val model =
        HistoryViewModel(
            ops,
            HistoryStore(ops),
            nowMs = { HistoryFixtures.NOW },
            tzOffsetS = { 0 },
            today = { "2026-09-21" },
            scope = scope,
        )

    @After fun tearDown() = scope.cancel()

    private fun settled(check: (HistoryUiState) -> Boolean): HistoryUiState =
        runBlocking { withTimeout(TIMEOUT) { model.state.first { !it.loading && check(it) } } }

    private fun nextEffect(): HistoryEffect = runBlocking { withTimeout(TIMEOUT) { model.effects.first() } }

    private fun open(): HistoryUiState {
        model.onAction(HistoryAction.Refresh)
        return settled { it.rows.size == 6 }
    }

    @Test fun theListHasEveryTripNewestFirstWithStatistics() {
        val state = open()
        assertEquals(listOf(6L, 5L, 4L, 3L, 2L, 1L), state.rows.map { it.record.id })
        val stats = state.stats!!
        assertEquals(6, stats.trips)
        assertEquals(4, stats.arrived)
        assertTrue(stats.top.isNotEmpty())
        assertTrue(
            state.rows.first().summaryLine,
            state.rows
                .first()
                .summaryLine
                .contains("km"),
        )
    }

    @Test fun theFilterAndTheSearchNarrowTheList() {
        open()
        model.onAction(HistoryAction.Filter(HistoryFilter.Cancelled))
        val cancelled = settled { it.filter == HistoryFilter.Cancelled && it.rows.size == 2 }
        assertTrue(cancelled.rows.toString(), cancelled.rows.all { it.record.outcome == Outcome.Cancelled })
        model.onAction(HistoryAction.Filter(HistoryFilter.All))
        model.onAction(HistoryAction.Query("school"))
        // The rows of the last filter stay until the search's answer comes.
        val found = settled { it.query == "school" && it.rows.size == 2 && it.rows.none { r -> r.record.id == 4L } }
        assertTrue(found.rows.toString(), found.rows.all { it.record.label == "School" })
    }

    @Test fun deleteOffersUndoAndUndoPutsTheTripBack() {
        val state = open()
        model.onAction(HistoryAction.Delete(state.rows[1]))
        val undo = nextEffect() as HistoryEffect.Undoable
        assertTrue(undo.text is UiText.Res)
        assertEquals(5, settled { it.total == 5 }.rows.size)
        assertEquals(5, fake.history!!.records.size)
        model.onAction(HistoryAction.Restore(undo.undo))
        assertEquals(6, settled { it.total == 6 }.rows.size)
        assertEquals(6, fake.history!!.records.size)
    }

    @Test fun oldTripsCanBeDeletedByAge() {
        val state = open()
        val choice = state.prune.first()
        assertTrue(choice.count > 0)
        model.onAction(HistoryAction.Prune(choice))
        val undo = nextEffect() as HistoryEffect.Undoable
        assertEquals(R.plurals.history_pruned, (undo.text as UiText.Plural).id)
        settled { it.total == 6 - choice.count }
    }

    @Test fun theTripsOnScreenExportAsCsvOrGpx() {
        open()
        model.onAction(HistoryAction.Filter(HistoryFilter.Arrived))
        settled { it.rows.size == 4 }
        model.onAction(HistoryAction.Export(HistoryViewModel.FORMAT_CSV))
        val csv = nextEffect() as HistoryEffect.Export
        assertEquals(HistoryViewModel.MIME_CSV, csv.mime)
        assertTrue(csv.fileName, csv.fileName.endsWith(".csv"))
        // A header and the four arrived trips.
        assertEquals(
            5,
            csv.text
                .trim()
                .lines()
                .size,
        )
        model.onAction(HistoryAction.Export(HistoryViewModel.FORMAT_GPX))
        val gpx = nextEffect() as HistoryEffect.Export
        assertTrue(gpx.text.contains("<gpx"))
    }

    @Test fun aTripSharesItsSummaryAndStartsAgain() {
        val state = open()
        model.onAction(HistoryAction.Share(state.rows.first()))
        val share = nextEffect() as HistoryEffect.Share
        assertTrue(share.text, share.text.contains("Pier West"))
        model.onAction(HistoryAction.StartAgain(state.rows.first()))
        val start = nextEffect() as HistoryEffect.Start
        assertEquals("Pier East", start.b.label)
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
