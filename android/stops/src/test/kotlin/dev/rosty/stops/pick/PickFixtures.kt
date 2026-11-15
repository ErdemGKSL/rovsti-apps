package dev.rosty.stops.pick

import dev.rosty.stops.AppFixture
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.trip.TripFixtures

/** The home on the real core with nothing picked; [feed] puts the small test feed in the core's directory. */
internal object PickFixtures {
    fun app(feed: Boolean = false): AppFixture {
        val fixture = AppFixture(FakeStopsCore(scriptTransit = false, real = HostCore.real()))
        if (feed) TripFixtures.writeFeed()
        return fixture
    }

    const val SHARED = "geo:41.0082,28.9784?q=41.0082,28.9784(Market Square)"
}
