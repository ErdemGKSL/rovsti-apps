package dev.rosty.stops.trip

import android.Manifest
import dev.rosty.core.model.headless.Side
import dev.rosty.core.model.headless.TripFixAnswer
import dev.rosty.core.model.headless.TripState
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.Ends
import dev.rosty.stops.AppFixture
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.SampleStops
import dev.rosty.stops.map.MapFixtures
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

/** The trip tests' setup: the real core, A and B on the map, the grants, a small transit feed. */
internal object TripFixtures {
    val a = Destination(SampleStops.pierW.label, SampleStops.pierW.coord)
    val b = Destination(SampleStops.pierE.label, SampleStops.pierE.coord)

    /** B as the transit stop "Pier East" of [writeFeed]. */
    val bStop = b.copy(gtfsStop = "PE")

    /** The home on the real core with A and B picked in the last session ([b] by default). */
    fun app(end: Destination = b): AppFixture =
        AppFixture(FakeStopsCore(saved = SampleStops.saved, draft = Ends(a, end), real = HostCore.real()))

    val foreground = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    val notifications = arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    val background = arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /** Grants (or takes back) [permissions] and lets the controller see it, as a resume would. */
    fun grant(
        fixture: AppFixture,
        vararg permissions: String,
        granted: Boolean = true,
    ) {
        val app = shadowOf(RuntimeEnvironment.getApplication())
        if (granted) app.grantPermissions(*permissions) else app.denyPermissions(*permissions)
        fixture.container.permissions.refreshNow { false }
    }

    /**
     * A made-up feed in the real core's directory: two lines that call at "Pier East" after "Pier West" and one more
     * stop, so the stop offers both lines with "N stops earlier".
     */
    fun writeFeed() {
        val lat = MapFixtures.cityLat
        val lon = MapFixtures.cityLon
        val json =
            """
            {"stops":[
              {"id":"PW","name":"Pier West","coord":{"lat":${lat + 0.002},"lon":${lon - 0.006}},"parent":null},
              {"id":"MK","name":"Market","coord":{"lat":${lat + 0.001},"lon":${lon - 0.002}},"parent":null},
              {"id":"PE","name":"Pier East","coord":{"lat":${lat + 0.002},"lon":${lon + 0.002}},"parent":null}
            ],
            "routes":[
              {"id":"R1","short_name":"14","long_name":"Pier line","kind":3},
              {"id":"R2","short_name":"7","long_name":"Harbour ferry","kind":4}
            ],
            "patterns":[
              {"route":0,"headsign":"Pier East","stops":[0,1,2]},
              {"route":1,"headsign":"Harbour","stops":[1,2]}
            ],
            "imported_ms":1790000000000}
            """.trimIndent()
        File(HostCore.dataDir, "gtfs.json").writeText(json)
    }

    fun fix(
        distanceM: Double = 420.0,
        state: TripState = TripState.Tracking,
    ) = TripFixAnswer(
        state = state,
        distanceM = distanceM,
        nextFixMs = 20_000,
        highAccuracy = true,
        ring = state != TripState.Tracking,
        title = "Pier West – Pier East",
        text = "420 m to Pier East, about 3 min",
        noticeSent = false,
        side = Side.Left,
        inside = false,
        circleM = 1200.0,
    )
}
