package dev.rosty.stops.saved

import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.ops.NativeStopsOps
import dev.rosty.design.text.UiText
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.R
import dev.rosty.stops.SampleStops
import dev.rosty.stops.data.SavedStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Saved list on the REAL Rust core (list, edits, texts) over the fake's files. */
class SavedViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fake = FakeStopsCore(real = HostCore.real())
    private val ops = NativeStopsOps(fake.rust)
    private val store = SavedStore(ops, nowMs = { NOW })
    private var here: Coord? = Coord(SampleStops.here.lat, SampleStops.here.lon)

    // Built on first use, so a test sets [here] before the model's first listing reads it (no listing with a stale
    // position can come first).
    private val model by lazy { SavedViewModel(store, ops, { here }, { NOW }, { 0 }, scope) }

    @After fun tearDown() = scope.cancel()

    private fun settled(check: (SavedUiState) -> Boolean): SavedUiState =
        runBlocking { withTimeout(TIMEOUT) { model.state.first { !it.loading && check(it) } } }

    private fun nextEffect(): SavedEffect = runBlocking { withTimeout(TIMEOUT) { model.effects.first() } }

    @Test fun listsTheStopsWithGateDistanceAndUsage() {
        val state = settled { it.rows.size == 2 }
        val pier = state.rows.first { it.stop.id == 1L }
        assertEquals(listOf(1L, 2L), state.rows.map { it.stop.id })
        assertTrue(pier.length, pier.length.endsWith("m"))
        assertNotNull(pier.distance)
        // The one past trip used the Pier gate.
        assertTrue(pier.usage.orEmpty(), pier.usage.orEmpty().startsWith("Used"))
        assertEquals(null, state.rows.first { it.stop.id == 2L }.usage)
        assertTrue(state.listText.contains("Pier"))
        assertTrue(state.canReorder)
    }

    @Test fun noPositionNoDistance() {
        here = null
        val state = settled { it.rows.size == 2 }
        assertTrue(state.rows.all { it.distance == null })
    }

    @Test fun searchAndSort() {
        settled { it.rows.size == 2 }
        model.onAction(SavedAction.Sort(SavedSort.Name))
        assertEquals(listOf("Pier", "School"), settled { it.sort == SavedSort.Name }.rows.map { it.stop.name })
        model.onAction(SavedAction.Query("sch"))
        val found = settled { it.rows.size == 1 }
        assertEquals(
            "School",
            found.rows
                .single()
                .stop.name,
        )
        assertFalse(found.canReorder)
    }

    @Test fun favouriteIsSavedAndToasted() {
        settled { it.rows.size == 2 }
        model.onAction(SavedAction.Favourite(1))
        assertEquals(SavedEffect.Toast(UiText.Res(R.string.saved_favourite_off)), nextEffect())
        assertFalse(
            fake.saved!!
                .stops
                .first { it.id == 1L }
                .favourite,
        )
    }

    @Test fun deleteThenUndoPutsItBack() {
        settled { it.rows.size == 2 }
        model.onAction(SavedAction.Delete(2))
        val deleted = nextEffect() as SavedEffect.Deleted
        assertEquals("School", deleted.stop.name)
        assertEquals(1, settled { it.rows.size == 1 }.rows.size)
        model.onAction(SavedAction.Restore(deleted.stop, deleted.index))
        assertEquals(listOf(1L, 2L), settled { it.rows.size == 2 }.rows.map { it.stop.id })
        assertEquals(2, fake.saved!!.stops.size)
    }

    @Test fun colourCycles() {
        settled { it.rows.size == 2 }
        model.onAction(SavedAction.CycleColour(2))
        val toast = nextEffect() as SavedEffect.Toast
        assertEquals(R.string.saved_colour, (toast.text as UiText.Res).id)
        assertTrue(
            fake.saved!!
                .stops
                .first { it.id == 2L }
                .colour != dev.rosty.core.model.stops.StopColour.Orange,
        )
    }

    @Test fun renameRefusesAnEmptyName() {
        settled { it.rows.size == 2 }
        model.onAction(SavedAction.Rename(2, "   "))
        assertEquals(SavedEffect.Toast(UiText.Res(R.string.saved_error_name)), nextEffect())
        model.onAction(SavedAction.Rename(2, "School run"))
        assertEquals("School run", settled { s -> s.rows.any { it.stop.name == "School run" } }.rows[1].stop.name)
    }

    @Test fun saveGateAddsWithASuggestedNameAndUndoRemovesIt() {
        settled { it.rows.size == 2 }
        val a = Destination("Market", Coord(SampleStops.here.lat + 0.004, SampleStops.here.lon))
        val b = Destination("Harbour", Coord(SampleStops.here.lat + 0.004, SampleStops.here.lon + 0.004))
        model.onAction(SavedAction.SaveGate(a, b))
        // First the name is asked for, with an end's own name as the suggestion; nothing is saved yet.
        val ask = nextEffect() as SavedEffect.AskName
        assertEquals("Market", ask.name)
        assertEquals(60, ask.maxChars)
        assertEquals(2, model.state.value.rows.size)
        model.onAction(SavedAction.Add("  Morning ferry ", a, b))
        val saved = nextEffect() as SavedEffect.Saved
        assertEquals("Morning ferry", saved.name)
        assertEquals(3, settled { it.rows.size == 3 }.rows.size)
        // The same gate again is that stop: no second name sheet.
        model.onAction(SavedAction.SaveGate(b, a))
        assertEquals(SavedEffect.Toast(UiText.Res(R.string.saved_already, listOf("Morning ferry"))), nextEffect())
        model.onAction(SavedAction.Unsave(saved.id))
        assertEquals(2, settled { it.rows.size == 2 }.rows.size)
    }

    /** The owner's bug: two dropped pins have no name, and the save failed on an empty one. */
    @Test fun pinsWithoutANameGetANumberedSuggestionThatSaves() {
        settled { it.rows.size == 2 }
        val a = Destination("", Coord(SampleStops.here.lat + 0.004, SampleStops.here.lon))
        val b = Destination("", Coord(SampleStops.here.lat + 0.004, SampleStops.here.lon + 0.004))
        model.onAction(SavedAction.SaveGate(a, b))
        val ask = nextEffect() as SavedEffect.AskName
        assertEquals("Stop 3", ask.name)
        model.onAction(SavedAction.Add(ask.name, a, b))
        assertEquals("Stop 3", (nextEffect() as SavedEffect.Saved).name)
        // The next one does not reuse the name.
        val c = Destination("", Coord(SampleStops.here.lat - 0.01, SampleStops.here.lon))
        val d = Destination("", Coord(SampleStops.here.lat - 0.01, SampleStops.here.lon + 0.004))
        model.onAction(SavedAction.SaveGate(c, d))
        assertEquals("Stop 4", (nextEffect() as SavedEffect.AskName).name)
        // A name the core refuses is said, and nothing is saved.
        model.onAction(SavedAction.Add("   ", c, d))
        assertEquals(SavedEffect.Toast(UiText.Res(R.string.saved_error_name)), nextEffect())
        // Two points that cannot make a stop never reach the name sheet.
        model.onAction(SavedAction.SaveGate(a, a))
        assertEquals(SavedEffect.Toast(UiText.Res(R.string.saved_error_gate)), nextEffect())
    }

    @Test fun moveStaysInTheGroup() {
        settled { it.rows.size == 2 }
        // Pier is the only favourite: School cannot pass it.
        model.onAction(SavedAction.Move(2, -1))
        model.onAction(SavedAction.Favourite(2))
        nextEffect()
        settled { s -> s.stops.all { it.favourite } }
        model.onAction(SavedAction.Move(2, -1))
        assertEquals(listOf(2L, 1L), settled { s -> s.stops.first().id == 2L }.stops.map { it.id })
    }

    @Test fun shareQrShortcutAndMaps() {
        settled { it.rows.size == 2 }
        model.onAction(SavedAction.Share(1))
        val share = nextEffect() as SavedEffect.Share
        assertTrue(share.text, share.text.contains("Pier"))
        model.onAction(SavedAction.Qr(1))
        val qr = nextEffect() as SavedEffect.Qr
        assertEquals("Pier", qr.title)
        assertTrue(qr.payload.isNotEmpty())
        model.onAction(SavedAction.HomeScreen(1))
        assertEquals(1L, (nextEffect() as SavedEffect.Shortcut).spec.stopId)
        model.onAction(SavedAction.OpenInMaps(1))
        assertTrue((nextEffect() as SavedEffect.OpenInMaps).geoUri.startsWith("geo:"))
        model.onAction(SavedAction.CopyAll)
        val copy = nextEffect() as SavedEffect.Copy
        assertEquals(UiText.Plural(R.plurals.saved_copied_list, 2), copy.done)
    }

    @Test fun openAndStartAnswerTheStopOrSayItIsGone() {
        model.onAction(SavedAction.Open(1))
        val open = nextEffect() as SavedEffect.ShowOnMap
        assertEquals(SampleStops.pierW.coord, open.a.coord)
        model.onAction(SavedAction.Start(2))
        assertEquals("School", (nextEffect() as SavedEffect.Start).stop.name)
        model.onAction(SavedAction.Open(42))
        assertEquals(SavedEffect.Toast(UiText.Res(R.string.saved_error_gone)), nextEffect())
    }

    @Test fun editingNeedsTheOwnOrder() {
        settled { it.rows.size == 2 }
        model.onAction(SavedAction.Editing(true))
        assertTrue(model.state.value.editing)
        model.onAction(SavedAction.Sort(SavedSort.Nearest))
        assertFalse(settled { it.sort == SavedSort.Nearest }.editing)
        model.onAction(SavedAction.Editing(true))
        assertFalse(model.state.value.editing)
    }

    /** S44: a note is kept, shared with the stop, cleared by an empty one; a long one is refused inline. */
    @Test fun aNoteIsKeptSharedClearedAndCapped() {
        settled { it.rows.size == 2 }
        model.onAction(SavedAction.Note(2, "  Gate by the bakery  "))
        assertEquals(
            "Gate by the bakery",
            settled { s -> s.stops.any { it.note.isNotEmpty() } }
                .stops
                .first {
                    it.id ==
                        2L
                }.note,
        )
        model.onAction(SavedAction.Share(2))
        val share = nextEffect() as SavedEffect.Share
        assertTrue(share.text, share.text.contains("Gate by the bakery"))
        model.onAction(SavedAction.Note(2, "x".repeat(SavedUiState.NOTE_MAX_CHARS + 1)))
        val refused = settled { it.noteError != null }.noteError!!
        assertEquals(2L, refused.id)
        assertEquals(UiText.Res(R.string.saved_error_note), refused.text)
        assertEquals(
            "Gate by the bakery",
            fake.saved!!
                .stops
                .first { it.id == 2L }
                .note,
        )
        model.onAction(SavedAction.Note(2, ""))
        assertTrue(settled { s -> s.noteError == null && s.stops.all { it.note.isEmpty() } }.stops.isNotEmpty())
    }

    private companion object {
        const val NOW = 1_790_100_000_000L
        const val TIMEOUT = 10_000L
    }
}
