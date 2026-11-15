package dev.rosty.stops.trip

import dev.rosty.core.Outcome
import dev.rosty.core.model.headless.Side
import dev.rosty.core.model.headless.TripFixAnswer
import dev.rosty.core.model.headless.TripState
import dev.rosty.core.model.phone.DirRequest
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.History
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.core.model.stops.StoreReadRequest
import dev.rosty.core.ops.NativeStopsOps
import dev.rosty.design.text.UiText
import dev.rosty.platform.location.FakeTripRepository
import dev.rosty.platform.location.StartResult
import dev.rosty.platform.location.TripStatus
import dev.rosty.stops.HostCore
import dev.rosty.stops.R
import dev.rosty.stops.SampleStops
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The trip on the REAL Rust core in a temp data directory, with a fake trip service. */
class TripViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ops = NativeStopsOps(HostCore.real())
    private val dir: File = Files.createTempDirectory("stops-trip").toFile().apply { deleteOnExit() }
    private val service = FakeTripRepository()
    private val model =
        TripViewModel(
            ops = ops,
            trips = service,
            prefs = { StopsPrefs() },
            dir = dir.absolutePath,
            here = { Coord(SampleStops.here.lat, SampleStops.here.lon) },
            nowMs = { NOW },
            waitingText = "Waiting for a location fix",
            scope = scope,
        )

    private val a = Destination(SampleStops.pierW.label, SampleStops.pierW.coord)
    private val b = Destination(SampleStops.pierE.label, SampleStops.pierE.coord)

    @After fun tearDown() = scope.cancel()

    private fun settled(check: (TripUiState) -> Boolean): TripUiState =
        runBlocking { withTimeout(TIMEOUT) { model.state.first { !it.loading && !it.busy && check(it) } } }

    private fun nextEffect(): TripEffect = runBlocking { withTimeout(TIMEOUT) { model.effects.first() } }

    private fun tripFile(): Boolean =
        runBlocking { (ops.storeTripLoad(DirRequest(dir.absolutePath)) as Outcome.Ok).value.trip != null }

    private fun history(): History? =
        runBlocking {
            (ops.storeRead(StoreReadRequest("history", dir.absolutePath)) as Outcome.Ok).value.value?.let {
                Json { ignoreUnknownKeys = true }.decodeFromJsonElement(History.serializer(), it)
            }
        }

    private fun startTrip(): TripUiState {
        settled { true }
        model.onAction(TripAction.Start(a, b))
        assertTrue(nextEffect() is TripEffect.Started)
        return settled { it.trip != null }
    }

    @Test fun startWritesTheTripAndStartsTheService() {
        val state = startTrip()
        assertEquals("Pier West", state.trip!!.a.label)
        assertTrue(tripFile())
        val spec = service.started.single()
        assertEquals(dir.absolutePath, spec.dir)
        assertEquals(TripViewModel.FIRST_FIX_MS, spec.nextFixMs)
        assertTrue(spec.highAccuracy)
        // The alarm plan rides along so the service can ring after a restart.
        assertNotNull(spec.alarm)
        assertNotNull(state.fromYou)
    }

    @Test fun aRefusedStartLeavesNothingBehind() {
        service.startResult = StartResult.NotAllowed
        settled { true }
        model.onAction(TripAction.Start(a, b))
        assertEquals(TripEffect.Toast(UiText.Res(R.string.trip_could_not_start)), nextEffect())
        val state = settled { it.problem == TripProblem.CouldNotStart }
        assertNull(state.trip)
        assertTrue(!tripFile())
        assertTrue(history()?.records.orEmpty().isEmpty())
    }

    @Test fun aSecondStartIsRefusedWhileOneRuns() {
        startTrip()
        model.onAction(TripAction.Start(a, b))
        assertEquals(TripEffect.Toast(UiText.Res(R.string.trip_already_running)), nextEffect())
        assertEquals(1, service.started.size)
    }

    @Test fun aFixFillsTheLiveCard() {
        startTrip()
        service.push(TripStatus.Fixed(fix(TripState.Tracking, 420.0, ring = false)))
        val live = settled { it.live != null }.live!!
        assertEquals("420 m", live.distance)
        assertEquals(Side.Left, live.side)
        assertEquals("1.2 km", live.circle)
        assertEquals("420 m to Pier, about 3 min", live.text)
        // S41: the arrival clock of the estimate.
        assertEquals(3, live.etaMin)
        assertTrue(live.arrive.orEmpty(), live.arrive.orEmpty().matches(Regex("\\d{1,2}:\\d{2}.*")))
    }

    @Test fun aRingIsHandedToTheAlarm() {
        startTrip()
        service.push(TripStatus.Fixed(fix(TripState.Arrived, 0.0, ring = true)))
        assertTrue(nextEffect() is TripEffect.Ring)
    }

    /** AD-SA8: a refused background restart keeps the trip and shows the row; Resume starts the service again. */
    @Test fun aRefusedRestartKeepsTheTripAndResumes() {
        startTrip()
        service.push(TripStatus.Error("restart"))
        val paused = settled { it.problem == TripProblem.Restart }
        assertNotNull(paused.trip)
        assertTrue(tripFile())
        model.onAction(TripAction.Resume)
        settled { it.problem == null }
        assertEquals(2, service.started.size)
    }

    @Test fun aLostGrantKeepsTheTrip() {
        startTrip()
        service.push(TripStatus.Error("permission"))
        val state = settled { it.problem == TripProblem.Permission }
        assertNotNull(state.trip)
        assertTrue(tripFile())
    }

    @Test fun stopEndsTheTripIntoTheHistory() {
        startTrip()
        model.onAction(TripAction.Stop)
        val stopped = nextEffect() as TripEffect.Stopped
        assertEquals(a.coord, stopped.a.coord)
        assertNull(settled { it.trip == null }.trip)
        assertTrue(!tripFile())
        assertTrue(!service.running)
        assertEquals(1, history()!!.records.size)
    }

    @Test fun statusTextIsCopied() {
        startTrip()
        model.onAction(TripAction.CopyStatus)
        val copy = nextEffect() as TripEffect.Copy
        assertTrue(copy.text, copy.text.contains("Pier"))
    }

    @Test fun anEndedTripLeavesTheCard() {
        startTrip()
        runBlocking {
            ops.storeTripEnd(
                dev.rosty.core.model.stops
                    .StoreTripEndRequest("arrived", NOW, dir.absolutePath),
            )
        }
        service.push(TripStatus.Ended)
        assertTrue(nextEffect() is TripEffect.Over)
        assertNull(settled { it.trip == null }.trip)
    }

    @Test fun noFeedNoLines() {
        settled { true }
        val lines = runBlocking { model.lines(b.copy(gtfsStop = "S1")) }
        assertTrue(lines.isEmpty())
    }

    private fun fix(
        state: TripState,
        distanceM: Double,
        ring: Boolean,
    ) = TripFixAnswer(
        state = state,
        distanceM = distanceM,
        nextFixMs = 20_000,
        highAccuracy = true,
        ring = ring,
        title = "Pier West – Pier East",
        text = "420 m to Pier, about 3 min",
        noticeSent = false,
        side = Side.Left,
        inside = false,
        circleM = 1200.0,
        etaMin = 3,
    )

    private companion object {
        const val NOW = 1_790_100_000_000L
        const val TIMEOUT = 10_000L
    }
}
