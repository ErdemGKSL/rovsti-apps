package dev.rosty.stops.trip

import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.core.model.stops.StoreTripEndRequest
import dev.rosty.core.ops.ActiveTripHandle
import dev.rosty.core.ops.NativeStopsOps
import dev.rosty.stops.FakeAlarmControl
import dev.rosty.stops.HostCore
import dev.rosty.stops.SampleStops
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The alarm and the last trip card on the REAL core in a temp data directory, with a fake alarm. */
class AlarmViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ops = NativeStopsOps(HostCore.real())
    private val dir: File = Files.createTempDirectory("stops-alarm").toFile().apply { deleteOnExit() }
    private val control = FakeAlarmControl()
    private var now = NOW
    private val model =
        AlarmViewModel(ops, control, { StopsPrefs(snoozeMin = 7) }, dir.absolutePath, { now }, { 0 }, scope)

    @After fun tearDown() = scope.cancel()

    private fun settled(check: (AlarmUiState) -> Boolean): AlarmUiState =
        runBlocking { withTimeout(TIMEOUT) { model.state.first(check) } }

    private fun nextEffect(): AlarmEffect = runBlocking { withTimeout(TIMEOUT) { model.effects.first() } }

    /** A trip in the directory, as the start writes it. */
    private fun tripOnFile() =
        runBlocking {
            val handle =
                (
                    ActiveTripHandle.start(
                        NOW,
                        SampleStops.pierW,
                        SampleStops.pierE,
                        NOW,
                        dir = dir.absolutePath,
                        ops = ops,
                    ) as Outcome.Ok
                ).value
            handle.close()
        }

    @Test fun eventsParse() {
        assertEquals(AlarmEvent.Ringing, AlarmEvent.parse("""{"event":"ringing"}"""))
        assertEquals(AlarmEvent.Later(5L), AlarmEvent.parse("""{"event":"snoozed","at_ms":5}"""))
        assertEquals(AlarmEvent.Later(9L), AlarmEvent.parse("""{"event":"timed_out","at_ms":9}"""))
        assertEquals(AlarmEvent.Dismissed, AlarmEvent.parse("""{"event":"dismissed"}"""))
        assertNull(AlarmEvent.parse("""{"event":"snoozed"}"""))
        assertNull(AlarmEvent.parse("not json"))
    }

    @Test fun ringingShowsTheTripWithItsSnooze() {
        tripOnFile()
        control.push(AlarmEvent.Ringing)
        val alarm = settled { it.alarm != null }.alarm!!
        assertEquals("Pier West – Pier East", alarm.label)
        assertEquals(7, alarm.snoozeMin)
    }

    @Test fun dismissAsksTheAlarmAndOffersReArm() {
        tripOnFile()
        control.push(AlarmEvent.Ringing)
        settled { it.alarm != null }
        model.onAction(AlarmAction.Dismiss)
        assertEquals(1, control.dismissed)
        assertNull(model.state.value.alarm)
        // The platform ends the trip and reports it.
        runBlocking { ops.storeTripEnd(StoreTripEndRequest("arrived", NOW + 60_000, dir.absolutePath)) }
        now = NOW + 120_000
        control.push(AlarmEvent.Dismissed)
        val dismissed = nextEffect() as AlarmEffect.Dismissed
        assertEquals(SampleStops.pierW.coord, dismissed.ends!!.first.coord)
        assertEquals(AlarmEffect.TripOver, nextEffect())
        // S27: the trip that just ended is the last trip card.
        val last = settled { it.shownLastTrip != null }.shownLastTrip!!
        assertTrue(last.row.title, last.row.title.contains("Pier"))
    }

    @Test fun snoozeHidesTheAlarmUntilItRingsAgain() {
        tripOnFile()
        control.push(AlarmEvent.Ringing)
        settled { it.alarm != null }
        model.onAction(AlarmAction.Snooze)
        assertEquals(1, control.snoozed)
        control.push(AlarmEvent.Later(NOW + 7 * 60_000))
        assertEquals(AlarmEffect.Snoozed(7), nextEffect())
        assertNull(model.state.value.alarm)
    }

    @Test fun theLastTripCardStartsSavesSharesAndCloses() {
        tripOnFile()
        runBlocking { ops.storeTripEnd(StoreTripEndRequest("arrived", NOW + 60_000, dir.absolutePath)) }
        now = NOW + 120_000
        model.onAction(AlarmAction.Refresh)
        settled { it.shownLastTrip != null }
        model.onAction(AlarmAction.StartAgain)
        assertEquals(SampleStops.pierE.coord, (nextEffect() as AlarmEffect.Start).b.coord)
        model.onAction(AlarmAction.SaveLastTrip)
        assertTrue(nextEffect() is AlarmEffect.Save)
        model.onAction(AlarmAction.ShareLastTrip)
        assertTrue((nextEffect() as AlarmEffect.Share).text.isNotBlank())
        model.onAction(AlarmAction.CloseLastTrip)
        assertNull(settled { it.shownLastTrip == null }.shownLastTrip)
    }

    @Test fun anOldTripHasNoCard() {
        tripOnFile()
        runBlocking { ops.storeTripEnd(StoreTripEndRequest("arrived", NOW + 60_000, dir.absolutePath)) }
        now = NOW + 13 * 3_600_000L
        model.onAction(AlarmAction.Refresh)
        Thread.sleep(500)
        assertNull(model.state.value.shownLastTrip)
    }

    private companion object {
        const val NOW = 1_790_100_000_000L
        const val TIMEOUT = 10_000L
    }
}
