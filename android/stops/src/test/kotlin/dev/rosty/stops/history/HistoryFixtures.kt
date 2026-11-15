package dev.rosty.stops.history

import dev.rosty.core.model.stops.History
import dev.rosty.core.model.stops.TripRecord
import dev.rosty.stops.AppFixture
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.SampleStops
import dev.rosty.core.model.stops.Outcome as TripOutcome

/** Invented trips over the sample stops: six, arrived and cancelled, from an hour to four months before [NOW]. */
internal object HistoryFixtures {
    /** 2026-09-21 08:40 UTC. */
    const val NOW = 1_790_066_400_000L
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    private fun trip(
        id: Long,
        label: String,
        ago: Long,
        outcome: TripOutcome,
        metres: Double,
        minutes: Long,
        snoozes: Int = 0,
        reverse: Boolean = false,
    ): TripRecord {
        val ended = NOW - ago
        val (a, b) =
            if (reverse) SampleStops.pierE to SampleStops.pierW else SampleStops.pierW to SampleStops.pierE
        return TripRecord(
            id = id,
            label = label,
            a = if (label == "School") SampleStops.school else a,
            b = if (label == "School") SampleStops.library else b,
            startedMs = ended - minutes * 60_000L,
            endedMs = ended,
            outcome = outcome,
            travelledM = metres,
            snoozes = snoozes,
            noticeSent = outcome == TripOutcome.Arrived,
        )
    }

    val history =
        History(
            listOf(
                trip(6, "Pier East", HOUR, TripOutcome.Arrived, 3400.0, 18),
                trip(5, "School", 2 * DAY, TripOutcome.Arrived, 2100.0, 12, snoozes = 1),
                trip(4, "Pier West", 3 * DAY, TripOutcome.Cancelled, 450.0, 4, reverse = true),
                trip(3, "Pier East", 12 * DAY, TripOutcome.Arrived, 3500.0, 21),
                trip(2, "School", 45 * DAY, TripOutcome.Arrived, 2050.0, 11),
                trip(1, "Pier East", 120 * DAY, TripOutcome.Cancelled, 900.0, 7, snoozes = 2),
            ),
        )

    /** The app on the real core with [history] as `history.json`. */
    fun app(history: History = HistoryFixtures.history): AppFixture =
        AppFixture(FakeStopsCore(history = history, scriptTransit = false, real = HostCore.real()))
}
