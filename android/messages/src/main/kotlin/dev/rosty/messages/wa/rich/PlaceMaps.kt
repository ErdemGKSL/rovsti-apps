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
import java.io.File

/**
 * The map pictures of place cards (E6-WA R5): the tiles `wa.static_map` names, fetched from the tile server through
 * the native tile source and painted by `:map`'s [StaticMapPainter], Stops' tile painting.
 *
 * The tiles go to a small bounded disk cache in [dir] (owner decision of 2026-11-20, `plans/e6wa-r6.md`): the
 * `rosty_tiles` LRU cache, [CACHE_BYTES] at most, under the app's `cacheDir` so the system may evict it. These
 * tiles show which areas were shared in chats, which sits against the vault rule (`plans/wa-vault.md` 13.1): the
 * cap, the cache directory and [forget] (run when the WhatsApp data is forgotten, unlinked or wiped) bound it. A null
 * [dir] keeps the tiles in memory only (tests). The server is the public VersaTiles server, the default of
 * `rosty_geo::tiles` and of Stops; Messages has no setting of its own. Pictures are kept for the session.
 */
class PlaceMaps(
    private val dir: File?,
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
                open(openRequest())
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

    /** How the tile source opens: the bounded disk cache in [dir], or memory only without one. */
    internal fun openRequest(): TilesOpenRequest =
        if (dir == null) {
            TilesOpenRequest(cacheCap = CACHE_BYTES, memory = true)
        } else {
            TilesOpenRequest(cacheCap = CACHE_BYTES, dir = dir.absolutePath)
        }

    /**
     * Drops the cached tiles and pictures: the WhatsApp data was forgotten, unlinked or wiped. The source opens again
     * on the next card, empty.
     */
    suspend fun forget() {
        close()
        withContext(Dispatchers.IO) { dir?.deleteRecursively() }
    }

    companion object {
        /** A tile is drawn this wide, so labels read as they do in Stops. */
        private const val TILE_DP = 256f

        /** The cap of the tile cache: a few hundred city tiles. */
        const val CACHE_BYTES = 20L * 1024 * 1024

        /** The cache directory's name under the app's `cacheDir`. */
        const val DIR = "wa-place-tiles"
        private const val PICTURES = 24
    }
}
