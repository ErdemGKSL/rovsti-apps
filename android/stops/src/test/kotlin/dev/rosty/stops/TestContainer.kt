package dev.rosty.stops

import android.app.Application
import android.content.Context
import dev.rosty.core.FakeRustCore
import dev.rosty.core.Outcome
import dev.rosty.core.RustCore
import dev.rosty.core.model.contacts.PinShortcut
import dev.rosty.core.model.core.Empty
import dev.rosty.core.model.phone.DirRequest
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.Endpoint
import dev.rosty.core.model.stops.Ends
import dev.rosty.core.model.stops.GtfsSearchAnswer
import dev.rosty.core.model.stops.GtfsSearchRequest
import dev.rosty.core.model.stops.History
import dev.rosty.core.model.stops.PrefsLoadAnswer
import dev.rosty.core.model.stops.PrefsSaveRequest
import dev.rosty.core.model.stops.SavedStop
import dev.rosty.core.model.stops.SavedStops
import dev.rosty.core.model.stops.ShortcutAnswer
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.core.model.stops.StoreReadAnswer
import dev.rosty.core.model.stops.StoreReadRequest
import dev.rosty.core.model.stops.StoreTripLoadAnswer
import dev.rosty.core.model.stops.StoreWriteRequest
import dev.rosty.core.model.stops.TilesOpenRequest
import dev.rosty.core.model.stops.TripRecord
import dev.rosty.core.ops.TileHandle
import dev.rosty.platform.contacts.ShortcutsRepository
import dev.rosty.platform.location.FakeLocationRepository
import dev.rosty.platform.location.FakeTripRepository
import dev.rosty.platform.location.Fix
import dev.rosty.platform.location.LocationRepository
import dev.rosty.platform.location.TripRepository
import dev.rosty.platform.system.FakeSpecialAccess
import dev.rosty.platform.system.permissions.SpecialAccess
import dev.rosty.shell.HasContainer
import dev.rosty.stops.map.FakeTileSource
import dev.rosty.stops.map.MapFixtures
import dev.rosty.stops.map.TileSource
import dev.rosty.stops.offline.MapArea
import dev.rosty.stops.settings.AlarmDevice
import dev.rosty.stops.settings.FakeAlarmDevice
import dev.rosty.stops.trip.AlarmControl
import dev.rosty.stops.trip.AlarmEvent
import dev.rosty.wa.FakeWaRepository
import dev.rosty.wa.WaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.rosty.core.model.stops.Outcome as TripOutcome

/**
 * Invented sample data for the home, on the fixture tile the map tests use (`MapFixtures.CITY_*`, a village east of
 * Istanbul): two saved stops, one past trip; made-up names.
 */
object SampleStops {
    private val lat0 = MapFixtures.cityLat
    private val lon0 = MapFixtures.cityLon

    val pierW = Endpoint("Pier West", Coord(lat0 + 0.002, lon0 - 0.006))
    val pierE = Endpoint("Pier East", Coord(lat0 + 0.002, lon0 + 0.002))
    val school = Endpoint("School Gate", Coord(lat0 - 0.004, lon0 - 0.003))
    val library = Endpoint("Library", Coord(lat0 - 0.0035, lon0 + 0.005))

    /** Where the fake location says the phone is: the middle of the tile. */
    val here = Fix(lat0, lon0, accuracyM = 25.0, timeMs = 0L)

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
    /** `draft.json`: the ends of the last session, put back on the map. */
    var draft: Ends? = null,
    /** False lets the real core answer `stops.gtfs_search` (the pick sheet's stop search over a test feed). */
    private val scriptTransit: Boolean = true,
    /** The real core behind the scripted ops ([HostCore.real]); null keeps the fake alone. */
    private val real: RustCore? = null,
) {
    val core = FakeRustCore()

    /** What the container calls: the fake, or the fake in front of the real core. */
    val rust: RustCore get() = real?.let { HybridCore(core, it) } ?: core
    val usesRealCore: Boolean get() = real != null
    val savedPrefs = mutableListOf<StopsPrefs>()
    val writes = mutableListOf<StoreWriteRequest>()

    init {
        core.onTyped("stops.prefs_load", DirRequest.serializer(), PrefsLoadAnswer.serializer()) {
            Outcome.Ok(PrefsLoadAnswer(prefs, found = true))
        }
        core.onTyped("stops.prefs_save", PrefsSaveRequest.serializer(), Empty.serializer()) {
            savedPrefs += it.prefs
            prefs = it.prefs
            Outcome.Ok(Empty)
        }
        // With the real core and no scripted trip, the real trip file answers (the trip tests start real trips).
        if (real != null) HostCore.reset()
        if (real == null || trip != null) {
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
        }
        core.onTyped("stops.store_read", StoreReadRequest.serializer(), StoreReadAnswer.serializer()) { req ->
            val value: JsonElement? =
                when (req.file) {
                    "saved" -> saved?.let { Json.encodeToJsonElement(SavedStops.serializer(), it) }
                    "history" -> history?.let { Json.encodeToJsonElement(History.serializer(), it) }
                    "draft" -> draft?.let { Json.encodeToJsonElement(Ends.serializer(), it) }
                    else -> null
                }
            Outcome.Ok(StoreReadAnswer(req.file, value, discarded = false))
        }
        core.onTyped("stops.store_write", StoreWriteRequest.serializer(), Empty.serializer()) {
            writes += it
            if (it.file == "saved") saved = Json.decodeFromJsonElement(SavedStops.serializer(), it.value)
            if (it.file == "history") history = Json.decodeFromJsonElement(History.serializer(), it.value)
            Outcome.Ok(Empty)
        }
        if (scriptTransit) {
            core.onTyped("stops.gtfs_search", GtfsSearchRequest.serializer(), GtfsSearchAnswer.serializer()) {
                Outcome.Ok(GtfsSearchAnswer(transitImportedMs != null, transitImportedMs, emptyList()))
            }
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
    val fakeLocation = FakeLocationRepository(last = SampleStops.here)
    val fakeTrips = FakeTripRepository()

    override fun createRust(): RustCore = fake.rust

    /** The real core's own directory when it answers (its trip file is the one the trip service would read). */
    override val dataDir: String get() = if (fake.usesRealCore) HostCore.dataDir.absolutePath else super.dataDir

    val fakeSpecial = FakeSpecialAccess()

    val fakeShortcuts = FakeShortcuts()

    val fakeAlarm = FakeAlarmControl()

    override fun createAlarm(): AlarmControl = fakeAlarm

    override fun createShortcuts(): ShortcutsRepository = fakeShortcuts

    override fun createWa(): WaRepository = FakeWaRepository()

    override fun createSpecialAccess(): SpecialAccess = fakeSpecial

    override fun createLocation(): LocationRepository = fakeLocation

    override fun createTrips(): TripRepository = fakeTrips

    /** E7.10: the alarm the settings pages play, recorded. */
    val fakeAlarmDevice = FakeAlarmDevice()

    override fun createAlarmDevice(): AlarmDevice = fakeAlarmDevice

    /** E7.10: what Settings > Map tiles opens; null keeps the core's own `tiles.open`. */
    var tileOpener: (suspend (TilesOpenRequest) -> Outcome<TileHandle>)? = null

    override suspend fun openTiles(request: TilesOpenRequest): Outcome<TileHandle> =
        tileOpener?.invoke(request) ?: super.openTiles(request)

    /** The offline downloads asked for (no WorkManager in these tests). */
    val scheduledAreas = mutableListOf<MapArea>()

    override fun scheduleOffline(area: MapArea) {
        scheduledAreas += area
    }

    override fun unscheduleOffline() = Unit

    /** The fixture village and nothing else: no network, deterministic tiles. */
    val tileSource = FakeTileSource.city()

    override fun createTileSource(): TileSource = tileSource
}

/** The alarm of `:platform` without sound: counts the answers, [push] plays an event. */
class FakeAlarmControl : AlarmControl {
    private val bus = MutableSharedFlow<AlarmEvent>(extraBufferCapacity = 16)
    var dismissed = 0
        private set
    var snoozed = 0
        private set

    override val events: Flow<AlarmEvent> = bus.asSharedFlow()

    override fun dismiss() {
        dismissed++
    }

    override fun snooze() {
        snoozed++
    }

    fun push(event: AlarmEvent) {
        bus.tryEmit(event)
    }
}

/** The launcher: says yes to every stop shortcut and keeps them. */
class FakeShortcuts : ShortcutsRepository {
    val pinned = mutableListOf<ShortcutAnswer>()

    override fun canPin(): Boolean = true

    override fun pinContact(spec: PinShortcut): Boolean = false

    override fun pinStop(spec: ShortcutAnswer): Boolean = pinned.add(spec)
}

/** An `Application` whose container is a [TestStopsContainer] (the activity tests). */
class TestStopsApp :
    Application(),
    HasContainer<TestStopsContainer> {
    override val container: TestStopsContainer by lazy { TestStopsContainer(this) }
}
