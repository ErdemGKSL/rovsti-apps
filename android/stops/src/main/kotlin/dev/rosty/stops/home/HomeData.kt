package dev.rosty.stops.home

import dev.rosty.core.Outcome
import dev.rosty.core.model.phone.DirRequest
import dev.rosty.core.model.stops.Endpoint
import dev.rosty.core.model.stops.GtfsSearchRequest
import dev.rosty.core.model.stops.History
import dev.rosty.core.model.stops.SavedStops
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.core.model.stops.StoreReadRequest
import dev.rosty.core.ops.StopsOps
import dev.rosty.stops.data.StopsStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** What the map home reads on its first frame: the files the GPUI app left in the data directory (ARCH 7.1). */
data class HomeSnapshot(
    val prefs: StopsPrefs,
    /** The trip in progress (`trip.json`); null when none, or when an old-format file was moved aside (AD-SX8). */
    val trip: TripSummary?,
    val saved: SavedStops?,
    val history: History?,
    /** When the GTFS feed (`gtfs.json`) was imported; null without a feed. */
    val transitImportedMs: Long?,
)

/** The trip in progress as the home shows it before the live card (E7.5) takes over. */
data class TripSummary(
    val label: String,
    val a: Endpoint?,
    val b: Endpoint?,
)

/** Reads the [HomeSnapshot]; the real one is [CoreHomeData], tests answer a fixed one. */
fun interface HomeData {
    suspend fun load(): HomeSnapshot
}

/**
 * [HomeData] over the Rust core: `stops.prefs_load` (through [store], so the preferences flow the rest of the app
 * follows is filled), `stops.store_trip_load` (which renames an unreadable old `trip.json` to `trip.legacy.json`,
 * AD-SX8), `stops.store_read` for `saved.json` (v2) and `history.json`, and `stops.gtfs_search` for the feed. A file
 * that cannot be read counts as absent: the home still opens. [dir] is the data directory (null: the core's).
 */
class CoreHomeData(
    private val ops: StopsOps,
    private val store: StopsStore,
    private val dir: String? = null,
) : HomeData {
    override suspend fun load(): HomeSnapshot {
        val prefs = (store.load() as? Outcome.Ok)?.value ?: store.prefs.value
        val trip = (ops.storeTripLoad(DirRequest(dir)) as? Outcome.Ok)?.value
        val saved = read("saved")?.let { decode(SavedStops.serializer(), it) }
        val history = read("history")?.let { decode(History.serializer(), it) }
        val feed = (ops.gtfsSearch(GtfsSearchRequest(query = "", limit = 1, dir = dir)) as? Outcome.Ok)?.value
        return HomeSnapshot(
            prefs = prefs,
            trip = trip?.trip?.let { tripSummary(it, trip.label) },
            saved = saved,
            history = history,
            transitImportedMs = feed?.takeIf { it.hasFeed }?.importedMs,
        )
    }

    private suspend fun read(file: String): JsonElement? =
        (ops.storeRead(StoreReadRequest(file, dir)) as? Outcome.Ok)?.value?.value

    private fun <T> decode(
        serializer: kotlinx.serialization.KSerializer<T>,
        element: JsonElement,
    ): T? =
        try {
            Lenient.decodeFromJsonElement(serializer, element)
        } catch (_: IllegalArgumentException) {
            null
        }
}

private val Lenient = Json { ignoreUnknownKeys = true }

/** The ends of a `trip.json` object (pure): its `a` and `b` endpoints when present; [label] is the core's title. */
internal fun tripSummary(
    trip: JsonElement,
    label: String?,
): TripSummary {
    val obj = trip as? JsonObject

    fun end(key: String): Endpoint? =
        obj?.get(key)?.let {
            try {
                Lenient.decodeFromJsonElement(Endpoint.serializer(), it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    val a = end("a")
    val b = end("b")
    val title = label?.takeIf { it.isNotBlank() } ?: listOfNotNull(a?.label, b?.label).joinToString(" – ")
    return TripSummary(title, a, b)
}
