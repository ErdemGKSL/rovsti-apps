package dev.rosty.stops

import android.app.Application
import android.content.Context
import dev.rosty.core.FakeRustCore
import dev.rosty.core.Outcome
import dev.rosty.core.RustCore
import dev.rosty.core.model.core.Empty
import dev.rosty.core.model.phone.DirRequest
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.Endpoint
import dev.rosty.core.model.stops.GtfsSearchAnswer
import dev.rosty.core.model.stops.GtfsSearchRequest
import dev.rosty.core.model.stops.History
import dev.rosty.core.model.stops.PrefsLoadAnswer
import dev.rosty.core.model.stops.PrefsSaveRequest
import dev.rosty.core.model.stops.SavedStop
import dev.rosty.core.model.stops.SavedStops
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.core.model.stops.StoreReadAnswer
import dev.rosty.core.model.stops.StoreReadRequest
import dev.rosty.core.model.stops.StoreTripLoadAnswer
import dev.rosty.core.model.stops.TripRecord
import dev.rosty.platform.location.FakeLocationRepository
import dev.rosty.platform.location.FakeTripRepository
import dev.rosty.platform.location.LocationRepository
import dev.rosty.platform.location.TripRepository
import dev.rosty.platform.system.FakeSpecialAccess
import dev.rosty.platform.system.permissions.SpecialAccess
import dev.rosty.shell.HasContainer
import dev.rosty.wa.FakeWaRepository
import dev.rosty.wa.WaRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.rosty.core.model.stops.Outcome as TripOutcome

/** Invented sample data for the home (public places of Istanbul, made-up names). */
object SampleStops {
    val pierW = Endpoint("Pier West", Coord(41.0, 28.994))
    val pierE = Endpoint("Pier East", Coord(41.0, 29.006))
    val school = Endpoint("School Gate", Coord(40.99, 29.02))
    val library = Endpoint("Library", Coord(40.992, 29.03))

    val saved =
        SavedStops(
            version = 2,
            nextId = 3,
            stops =
                listOf(
                    SavedStop(1, "Pier", pierW, pierE, 1_789_000_000_000L, favourite = true),
                    SavedStop(2, "School", school, library, 1_789_100_000_000L),
                ),
        )

    val history =
        History(
            listOf(
                TripRecord(
                    id = 4,
                    label = "Work",
                    a = pierW,
                    b = pierE,
                    startedMs = 1_789_996_400_000L,
                    endedMs = 1_789_999_999_000L,
                    outcome = TripOutcome.Arrived,
                    travelledM = 3400.0,
                    snoozes = 0,
                    noticeSent = true,
                ),
            ),
        )
}

/**
 * The core's answers for the home's first frame, on a [FakeRustCore]: [prefs], an optional running trip, the saved
 * stops and history, and a GTFS feed when [transitImportedMs] is set. Every write of the preferences is kept.
 */
class FakeStopsCore(
    var prefs: StopsPrefs = StopsPrefs(),
    var trip: Pair<Endpoint, Endpoint>? = null,
    var saved: SavedStops? = SampleStops.saved,
    var history: History? = SampleStops.history,
    var transitImportedMs: Long? = 1_790_000_000_000L,
) {
    val core = FakeRustCore()
    val savedPrefs = mutableListOf<StopsPrefs>()

    init {
        core.onTyped("stops.prefs_load", DirRequest.serializer(), PrefsLoadAnswer.serializer()) {
            Outcome.Ok(PrefsLoadAnswer(prefs, found = true))
        }
        core.onTyped("stops.prefs_save", PrefsSaveRequest.serializer(), Empty.serializer()) {
            savedPrefs += it.prefs
            prefs = it.prefs
            Outcome.Ok(Empty)
        }
        core.onTyped("stops.store_trip_load", DirRequest.serializer(), StoreTripLoadAnswer.serializer()) {
            val now = trip
            Outcome.Ok(
                StoreTripLoadAnswer(
                    trip = now?.let { (a, b) -> tripJson(a, b) },
                    file = "trip.json",
                    label = now?.let { (a, b) -> "${a.label} – ${b.label}" },
                ),
            )
        }
        core.onTyped("stops.store_read", StoreReadRequest.serializer(), StoreReadAnswer.serializer()) { req ->
            val value: JsonElement? =
                when (req.file) {
                    "saved" -> saved?.let { Json.encodeToJsonElement(SavedStops.serializer(), it) }
                    "history" -> history?.let { Json.encodeToJsonElement(History.serializer(), it) }
                    else -> null
                }
            Outcome.Ok(StoreReadAnswer(req.file, value, discarded = false))
        }
        core.onTyped("stops.gtfs_search", GtfsSearchRequest.serializer(), GtfsSearchAnswer.serializer()) {
            Outcome.Ok(GtfsSearchAnswer(transitImportedMs != null, transitImportedMs, emptyList()))
        }
    }

    private fun tripJson(
        a: Endpoint,
        b: Endpoint,
    ): JsonElement =
        JsonObject(
            mapOf(
                "id" to JsonPrimitive(7),
                "a" to Json.encodeToJsonElement(Endpoint.serializer(), a),
                "b" to Json.encodeToJsonElement(Endpoint.serializer(), b),
            ),
        )
}

/** The app on fakes: no native library, no location provider, no service. */
open class TestStopsContainer(
    context: Context,
    val fake: FakeStopsCore = FakeStopsCore(),
) : StopsContainer(context) {
    val fakeLocation = FakeLocationRepository()
    val fakeTrips = FakeTripRepository()

    override fun createRust(): RustCore = fake.core

    override fun createWa(): WaRepository = FakeWaRepository()

    override fun createSpecialAccess(): SpecialAccess = FakeSpecialAccess()

    override fun createLocation(): LocationRepository = fakeLocation

    override fun createTrips(): TripRepository = fakeTrips
}

/** An `Application` whose container is a [TestStopsContainer] (the activity tests). */
class TestStopsApp :
    Application(),
    HasContainer<TestStopsContainer> {
    override val container: TestStopsContainer by lazy { TestStopsContainer(this) }
}
