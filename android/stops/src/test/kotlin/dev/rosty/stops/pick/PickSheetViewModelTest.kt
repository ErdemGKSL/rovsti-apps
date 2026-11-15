package dev.rosty.stops.pick

import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.DestShareRequest
import dev.rosty.core.model.stops.Ends
import dev.rosty.core.model.stops.Slot
import dev.rosty.core.ops.NativeStopsOps
import dev.rosty.design.text.UiText
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.R
import dev.rosty.stops.SampleStops
import dev.rosty.stops.data.SavedStore
import dev.rosty.stops.trip.TripFixtures
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

/** The pick sheet's rows and offers on the REAL core over the fake's files (the sample stops and the one trip). */
class PickSheetViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fake = FakeStopsCore(scriptTransit = false, real = HostCore.real())
    private val ops = NativeStopsOps(fake.rust)
    private val model =
        PickSheetViewModel(
            ops,
            SavedStore(ops, nowMs = {
                NOW
            }),
            { Coord(SampleStops.here.lat, SampleStops.here.lon) },
            scope = scope,
        )

    @After fun tearDown() = scope.cancel()

    private fun rows(check: (PickSheetUiState) -> Boolean): PickSheetUiState =
        runBlocking { withTimeout(TIMEOUT) { model.state.first { !it.loading && check(it) } } }

    private fun nextEffect(): PickEffect = runBlocking { withTimeout(TIMEOUT) { model.effects.first() } }

    private fun query(text: String) = model.onAction(PickAction.Query(text))

    @Test fun withNoQueryTheSheetOffersSavedStopsAndRecents() {
        model.open(Ends())
        val state = rows { it.rows.any { r -> r is PickRow.Pair } }
        assertEquals(Slot.A, state.slot)
        assertTrue(state.title, state.title.isNotBlank())
        assertEquals(2, state.rows.count { it is PickRow.Saved })
        val pair = state.rows.filterIsInstance<PickRow.Pair>().single()
        assertTrue(pair.title, pair.title.contains("Pier West") && pair.title.contains("Pier East"))
        assertTrue(state.rows.any { it is PickRow.Recent })
        assertTrue(state.rows.none { it == PickRow.Hint })
    }

    @Test fun theSecondEndIsBOnceAIsPicked() {
        model.open(Ends(a = TripFixtures.a))
        assertEquals(Slot.B, rows { it.title.isNotBlank() }.slot)
    }

    @Test fun typedCoordinatesAreAnOfferAtTheTop() {
        model.open(Ends())
        query("41.0082, 28.9784")
        val offer = rows { it.rows.firstOrNull() is PickRow.Offer }.rows.first() as PickRow.Offer
        assertEquals(41.0082, offer.dest.coord.lat, 1e-6)
        assertTrue(offer.title.isNotBlank())
    }

    @Test fun aNameSearchesSavedStopsAndTransitStops() {
        model.open(Ends())
        TripFixtures.writeFeed()
        query("Pier")
        val state = rows { it.query == "Pier" && it.rows.any { r -> r is PickRow.Stop } }
        val stops = state.rows.filterIsInstance<PickRow.Stop>()
        assertTrue(stops.any { it.id == "PE" && it.lines == 2 })
        assertTrue(stops.all { it.distance != null })
        assertEquals(
            "Pier",
            state.rows
                .filterIsInstance<PickRow.Saved>()
                .single()
                .name,
        )
        java.io.File(HostCore.dataDir, "gtfs.json").delete()
    }

    @Test fun aLinkWithoutCoordinatesSaysWhyAndANameDoesNot() {
        model.open(Ends())
        query("https://maps.example/no-place-here")
        val refusal =
            rows { it.rows.any { r -> r is PickRow.Refusal } }.rows.filterIsInstance<PickRow.Refusal>().single()
        assertEquals(UiText.Res(R.string.pick_error_not_a_location), refusal.text)
        query("zzqx")
        val named = rows { it.query == "zzqx" && it.rows.any { r -> r is PickRow.NoMatch } }
        assertTrue(named.rows.none { it is PickRow.Refusal })
    }

    @Test fun nothingFoundSaysSo() {
        model.open(Ends())
        query("zzqx")
        assertTrue(rows { it.rows.any { r -> r is PickRow.NoMatch } }.rows.single() is PickRow.NoMatch)
    }

    @Test fun aPastedStopShareFillsBothEnds() {
        model.open(Ends())
        val share =
            runBlocking {
                val stop = SampleStops.saved.stops.first()
                (
                    ops.destShare(
                        DestShareRequest(stop.a.coord, stop.a.label, stop.b.coord, stop.name),
                    ) as Outcome.Ok
                ).value.stopText!!
            }
        model.onAction(PickAction.Paste(share))
        val pair = nextEffect() as PickEffect.SetPair
        assertEquals(SampleStops.pierW.coord.lat, pair.a.coord.lat, 1e-5)
        // The pair is filled and said so (with the stop's name when the shared text carries one).
        assertTrue(pair.toast is UiText.Res)
    }

    @Test fun aPastedPointAsksToConfirmAndOtherTextGoesToTheField() {
        model.open(Ends())
        model.onAction(PickAction.Paste("geo:41.0082,28.9784?q=41.0082,28.9784(Sultanahmet)"))
        val confirm = nextEffect() as PickEffect.Confirm
        assertEquals("Sultanahmet", confirm.place.dest.label)
        model.onAction(PickAction.Paste("  the market  "))
        assertEquals(PickEffect.FieldText("the market"), nextEffect())
        model.onAction(PickAction.Paste("   "))
        assertEquals(PickEffect.Toast(UiText.Res(R.string.pick_nothing_to_paste)), nextEffect())
    }

    @Test fun sharedTextIsOfferedOrRefusedWithItsReason() {
        model.offerText("41.0082, 28.9784")
        assertTrue(nextEffect() is PickEffect.Confirm)
        model.offerText("see you at six")
        val toast = nextEffect() as PickEffect.Toast
        assertTrue(
            (toast.text as UiText.Res).id in
                setOf(R.string.pick_error_no_coordinates, R.string.pick_error_not_a_location),
        )
    }

    @Test fun rowsChooseTheirEnds() {
        model.open(Ends())
        val state = rows { it.rows.any { r -> r is PickRow.Recent } }
        model.onAction(PickAction.Choose(state.rows.filterIsInstance<PickRow.Recent>().first()))
        assertEquals(Slot.A, (nextEffect() as PickEffect.Place).slot)
        model.onAction(PickAction.Choose(state.rows.filterIsInstance<PickRow.Saved>().first()))
        assertTrue(nextEffect() is PickEffect.SetPair)
    }

    private companion object {
        const val NOW = 1_790_100_000_000L
        const val TIMEOUT = 10_000L
    }
}
