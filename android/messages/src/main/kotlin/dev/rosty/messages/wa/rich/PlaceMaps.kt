package dev.rosty.messages.wa.rich

import android.graphics.Bitmap
import android.util.LruCache
import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.TilesOpenRequest
import dev.rosty.core.model.wa.StaticMap
import dev.rosty.core.model.wa.StaticMapRequest
import dev.rosty.core.ops.TileHandle
import dev.rosty.map.MapTiles
import dev.rosty.map.StaticMapPainter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The map pictures of place cards (E6-WA R5): the tiles `wa.static_map` names, fetched from the tile server through
 * the native tile source and painted by `:map`'s [StaticMapPainter], Stops' tile painting.
 *
 * The source is opened with `memory = true`: the tiles stay in memory and no file is written, so nothing on the phone
 * tells which places were shared (`plans/wa-vault.md` 13.1). The server is the public VersaTiles server, the default
 * of `rosty_geo::tiles` and of Stops (owner question 2 of `plans/e6wa-r5.md`). Pictures are kept for the session.
 */
class PlaceMaps(
    private val open: suspend (TilesOpenRequest) -> Outcome<TileHandle> = { TileHandle.open(it) },
    private val staticMap: suspend (StaticMapRequest) -> Outcome<StaticMap>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutex = Mutex()
    private var handle: TileHandle? = null
    private val painter =
        StaticMapPainter(
            MapTiles { z, x, y, dark ->
                val source =
                    source() ?: return@MapTiles Outcome.Err(
                        dev.rosty.core.RustError
                            .NotFound("no tiles"),
                    )
                source.tile(z, x, y, dark = dark)
            },
        )
    private val pictures = LruCache<String, Bitmap>(PICTURES)

    private suspend fun source(): TileHandle? {
        handle?.let { return it }
        // Where the native library is not there (screenshots, plain tests) there is no map, only its placeholder.
        val opened =
            try {
                open(TilesOpenRequest(cacheCap = CACHE_BYTES, memory = true))
            } catch (_: UnsatisfiedLinkError) {
                null
            }
        return (opened as? Outcome.Ok)?.value?.also { handle = it }
    }

    /** The picture of a [width] x [height] px map of the point, or null when no tile loaded (offline). */
    suspend fun picture(
        latitude: Double,
        longitude: Double,
        width: Int,
        height: Int,
        density: Float,
        dark: Boolean,
    ): Bitmap? {
        val key = "$latitude,$longitude,$width,$height,$dark"
        pictures.get(key)?.let { return it }
        return withContext(dispatcher) {
            mutex.withLock {
                pictures.get(key)?.let { return@withLock it }
                val tilePx = (TILE_DP * density).toInt().coerceAtLeast(1)
                val map =
                    (staticMap(StaticMapRequest(latitude, longitude, null, width, height, tilePx)) as? Outcome.Ok)
                        ?.value ?: return@withLock null
                painter.paint(map, width, height, dark, density)?.also { pictures.put(key, it) }
            }
        }
    }

    /** Closes the tile source (the process or the page goes away). */
    suspend fun close() =
        mutex.withLock {
            handle?.close()
            handle = null
            pictures.evictAll()
        }

    private companion object {
        /** A tile is drawn this wide, so labels read as they do in Stops. */
        const val TILE_DP = 256f

        /** The memory cache of tile data: a few dozen city tiles. */
        const val CACHE_BYTES = 8L * 1024 * 1024
        const val PICTURES = 24
    }
}
