package dev.rosty.stops.data

import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.core.model.stops.TilesOpenRequest
import dev.rosty.core.ops.TileHandle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The map's tile source: one [TileHandle] for the server the user chose in the preferences (null `tile_url` is the
 * public VersaTiles server) with the cache cap they chose. [handle] opens it on first use and reopens it (closing the
 * old one) when the server or the cap changed; the map engine (E7.2a) asks it for tiles. Nothing is fetched here.
 *
 * The cache lives where the GPUI app kept it, `<data dir>/tiles/<server key>/` ([cacheRoot]), so the tiles and
 * offline areas the user already downloaded stay.
 */
class StopsTiles(
    private val prefs: () -> StopsPrefs,
    private val cacheRoot: String,
    private val open: suspend (TilesOpenRequest) -> Outcome<TileHandle>,
) {
    private val mutex = Mutex()
    private var current: Pair<TilesOpenRequest, TileHandle>? = null

    /** The open source for the current preferences. */
    suspend fun handle(): Outcome<TileHandle> =
        mutex.withLock {
            val wanted = tilesOpenRequest(prefs(), cacheRoot)
            current?.let { (req, handle) -> if (req == wanted) return@withLock Outcome.Ok(handle) }
            current?.second?.close()
            current = null
            when (val opened = open(wanted)) {
                is Outcome.Err -> {
                    opened
                }

                is Outcome.Ok -> {
                    current = wanted to opened.value
                    opened
                }
            }
        }

    /** Closes the open source (the process is going away or the tests are done). */
    suspend fun close() =
        mutex.withLock {
            current?.second?.close()
            current = null
        }
}

/** Bytes in one MB of `tile_cache_mb` (`rosty_geo::prefs::StopsPrefs::tile_cache_bytes`). */
private const val BYTES_PER_MB = 1024L * 1024L

/** The open request for [prefs] (pure): the chosen server (null is VersaTiles), the cap in bytes, the cache root. */
fun tilesOpenRequest(
    prefs: StopsPrefs,
    cacheRoot: String,
): TilesOpenRequest =
    TilesOpenRequest(
        serverUrl = prefs.tileUrl?.trim()?.takeIf { it.isNotEmpty() },
        cacheCap = prefs.tileCacheMb.toLong() * BYTES_PER_MB,
        dir = cacheRoot,
    )
