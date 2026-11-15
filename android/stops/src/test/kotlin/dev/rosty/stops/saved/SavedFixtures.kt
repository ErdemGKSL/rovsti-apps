package dev.rosty.stops.saved

import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.Endpoint
import dev.rosty.core.model.stops.SavedStop
import dev.rosty.core.model.stops.SavedStops
import dev.rosty.core.model.stops.StopColour
import dev.rosty.stops.Background
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.SampleStops
import dev.rosty.stops.map.MapFixtures
import dev.rosty.testing.ShotTest

/** Invented saved stops for the Saved sheet tests: [count] gates around the fixture village, made-up names. */
internal object SavedFixtures {
    private val names =
        listOf(
            "Bakery",
            "Bridge",
            "Clinic",
            "Ferry",
            "Garden",
            "Harbour",
            "Library",
            "Market",
            "Museum",
            "Park",
            "Station",
            "Theatre",
        )

    fun many(count: Int): SavedStops {
        val stops =
            (0 until count).map { i ->
                val lat = MapFixtures.cityLat + (i % 4 - 2) * 0.0015
                val lon = MapFixtures.cityLon + (i / 4 - 1) * 0.002
                SavedStop(
                    id = i + 1L,
                    name = names[i % names.size],
                    a = Endpoint("${names[i % names.size]} A", Coord(lat, lon)),
                    b = Endpoint("${names[i % names.size]} B", Coord(lat + 0.0006, lon + 0.0008)),
                    createdMs = 1_789_000_000_000L + i,
                    favourite = i < 2,
                    colour = StopColour.entries[i % StopColour.entries.size],
                )
            }
        return SavedStops(version = 2, nextId = count + 1L, stops = stops)
    }

    /** The home's files on the real core: the sample stops (or [saved]) and the one past trip. */
    fun core(saved: SavedStops = SampleStops.saved): FakeStopsCore =
        FakeStopsCore(saved = saved, real = HostCore.real())
}

/**
 * Lets the real core answer: its calls run on their own threads, so the clock moves in small steps and each step
 * first hands over the answers that are due ([Background.await] waits for the calls themselves, so an answer lands
 * in the step that asked for it however busy the machine is). The short real wait after each step is for the app's
 * other threads (the container's scope, the permission controller), which no counter sees.
 */
internal fun ShotTest.settleRust(ms: Long = 1500) {
    repeat((ms / STEP).toInt()) {
        Background.await { compose.waitForIdle() }
        advance(STEP)
        Thread.sleep(WAIT)
    }
    Background.await { compose.waitForIdle() }
    advance(STEP)
}

private const val STEP = 100L
private const val WAIT = 15L
