package dev.rosty.stops.map

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.stops.TileBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToInt

/**
 * The tile pipeline of the map (ARCH 8.4 steps 2 and 3): decides which tiles the camera needs, asks the
 * [TileSource] for the missing ones, paints them into bitmaps off the main thread and keeps the bitmaps in a
 * byte-budgeted LRU that the draw node reads.
 *
 * - **When.** At once when the camera settles, and at most every [debounceMs] while it moves.
 * - **What.** The raster tiles (see [TileRasterizer]) of the settled zoom within [marginPx] of the viewport, nearest
 *   the centre first. While a pinch or a zoom animation runs nothing new is asked for: the draw node scales the
 *   bitmaps it has, and the tiles of the new zoom are painted when the zoom settles.
 * - **Cancel.** A tile that left the wanted set has its job cancelled; a data tile nobody waits for any more has its
 *   request to the source cancelled.
 * - **Failure.** A tile the source could not answer is not asked again for [retryAfterMs]; meanwhile its stand-in or
 *   the radar background shows.
 *
 * All bookkeeping runs on the dispatcher of [parentScope] (the main thread in the app); only [TileSource.tile] and
 * the painting leave it. Create it once per map screen (the app container) and [close] it with the screen.
 */
@Stable
class MapTileStore(
    private val source: TileSource,
    parentScope: CoroutineScope,
    bitmapBudgetBytes: Long = ByteLru.MIN_BITMAP_BUDGET,
    bufferBudgetBytes: Long = DEFAULT_BUFFER_BUDGET,
    private val rasterDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(RASTER_THREADS),
    private val debounceMs: Long = DEBOUNCE_MS,
    private val retryAfterMs: Long = RETRY_AFTER_MS,
    private val marginPx: Float = 0f,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job))

    private val pool = ArrayList<Bitmap>(POOL_SIZE)
    private val poolRetiredAt = ArrayList<Int>(POOL_SIZE)

    /** Tile bitmaps by raster [TileKey]. Read by the draw node on the main thread. */
    internal val bitmaps = ByteLru<Bitmap>(bitmapBudgetBytes) { _, bitmap -> retire(bitmap) }
    private val buffers = ByteLru<PreparedTile>(bufferBudgetBytes)

    private val jobs = HashMap<Long, Job>()
    private val loads = HashMap<Long, Load>()
    private val failedUntil = HashMap<Long, Long>()
    private val rasterizers = ConcurrentLinkedQueue<TileRasterizer>()

    private class Load(
        val request: Deferred<PreparedTile?>,
    ) {
        var waiters = 0
    }

    private val frame = mutableIntStateOf(0)
    private var draws = 0

    private var camera: MapCamera? = null
    private var planner: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val settled = Channel<Unit>(Channel.CONFLATED)
    private val cameraListener: () -> Unit = {
        wake.trySend(Unit)
        if (camera?.moving?.value == false) settled.trySend(Unit)
    }

    /** The dark (eclipse) palette is in use. */
    var dark = false
        private set

    /** The integer zoom the bitmaps are painted for: the pixel zoom when it last settled. -1 before the first plan. */
    var rasterZoom = -1
        private set

    /** Raster tiles being fetched or painted now. */
    val pending: Int get() = jobs.size

    /** Requests to the source in flight now. */
    val loading: Int get() = loads.size

    val cachedBitmaps: Int get() = bitmaps.count
    val cachedBitmapBytes: Long get() = bitmaps.bytes
    val bitmapLimitBytes: Long get() = bitmaps.limitBytes

    /** Bitmaps painted since the store was made. */
    var rastered = 0L
        private set

    /** Subscribes the calling draw scope to "a tile arrived or left". Do not call in composition. */
    fun observe(): Int = frame.intValue

    /** Whether the bitmap of a raster tile is cached (does not count as a use). */
    fun hasBitmap(key: Long): Boolean = key in bitmaps

    /** The draw node reports a finished frame, after which bitmaps it no longer shows may be reused. */
    internal fun onDrawn() {
        draws++
    }

    /** Starts following [camera]; the store plans tiles for one camera at a time. */
    fun attach(camera: MapCamera) {
        if (this.camera === camera && planner?.isActive == true) return
        detach()
        this.camera = camera
        camera.addListener(cameraListener)
        planner =
            scope.launch {
                while (true) {
                    wake.receive()
                    // Moving: wait out the debounce, unless the camera settles first.
                    if (camera.moving.value) withTimeoutOrNull(debounceMs) { settled.receive() }
                    settled.tryReceive()
                    plan()
                }
            }
        wake.trySend(Unit)
    }

    /** Stops following the camera and cancels what is in flight. Bitmaps stay cached. */
    fun detach() {
        camera?.removeListener(cameraListener)
        camera = null
        planner?.cancel()
        planner = null
        cancelAll()
    }

    /** Switches between the colorful (light) and eclipse (dark) palettes; every tile is painted again. */
    fun setDark(dark: Boolean) {
        if (this.dark == dark) return
        this.dark = dark
        reset()
    }

    /** Forgets every tile (the tile server or the cache changed) and asks again. */
    fun reset() {
        cancelAll()
        bitmaps.clear()
        buffers.clear()
        failedUntil.clear()
        invalidate()
        wake.trySend(Unit)
    }

    /** Asks failed tiles again now (the network came back, the user tapped retry). */
    fun retryFailed() {
        failedUntil.clear()
        wake.trySend(Unit)
    }

    /** Detaches and frees the bitmaps. The store cannot be used afterwards. */
    fun close() {
        detach()
        scope.cancel()
        bitmaps.clear()
        buffers.clear()
        pool.clear()
        poolRetiredAt.clear()
    }

    // ---- planning ----

    /** One planning pass; public so tests can drive it without the debounce. */
    fun plan() {
        val camera = camera ?: return
        if (camera.widthPx <= 0 || camera.heightPx <= 0) return
        if (camera.zooming && rasterZoom >= 0) return
        val zoom = rasterZoomFor(camera.pixelZoom)
        rasterZoom = zoom
        if (TileCovers.count(camera, zoom, marginPx) > MAX_WANTED) return
        val cover = TileCovers.cover(camera, zoom, marginPx)
        bitmaps.setFloor(cover.tiles.size * TileRasterizer.TILE_BYTES * FLOOR_NUM / FLOOR_DEN)

        val it = jobs.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key !in cover) {
                it.remove()
                entry.value.cancel()
            }
        }
        val now = nowMs()
        var retryIn = Long.MAX_VALUE
        val displayZoom = zoom - MapMath.log2(camera.density.toDouble())
        for (key in cover.tiles) {
            if (key in bitmaps || key in jobs) continue
            val data = dataTileFor(key, camera.density)
            val until = failedUntil[data]
            if (until != null) {
                if (until > now) {
                    retryIn = minOf(retryIn, until - now)
                    continue
                }
                failedUntil.remove(data)
            }
            start(key, data, displayZoom, camera.density)
        }
        if (retryIn != Long.MAX_VALUE) scheduleRetry(retryIn)
    }

    private var retryJob: Job? = null

    private fun scheduleRetry(inMs: Long) {
        if (retryJob?.isActive == true) return
        retryJob =
            scope.launch {
                delay(inMs)
                wake.trySend(Unit)
            }
    }

    private fun start(
        key: Long,
        data: Long,
        displayZoom: Double,
        density: Float,
    ) {
        val job =
            scope.launch {
                val prepared = prepared(data) ?: return@launch
                val bitmap = takeBitmap()
                var kept = false
                try {
                    withContext(rasterDispatcher) {
                        val rasterizer = rasterizers.poll() ?: TileRasterizer()
                        try {
                            rasterizer.render(
                                prepared,
                                TileKey.z(key),
                                TileKey.x(key),
                                TileKey.y(key),
                                displayZoom,
                                density,
                                bitmap,
                            )
                        } finally {
                            rasterizers.offer(rasterizer)
                        }
                        bitmap.prepareToDraw()
                    }
                    bitmaps.put(key, bitmap, TileRasterizer.TILE_BYTES)
                    kept = true
                    rastered++
                    invalidate()
                } finally {
                    if (!kept) retire(bitmap)
                }
            }
        jobs[key] = job
        job.invokeOnCompletion { if (jobs[key] === job) jobs.remove(key) }
    }

    /** The prepared data tile: cached, or loaded once for every raster tile that waits for it. */
    private suspend fun prepared(data: Long): PreparedTile? {
        buffers.get(data)?.let { return it }
        val load =
            loads.getOrPut(data) {
                Load(
                    scope.async {
                        val tile = load(data)
                        loads.remove(data)
                        if (tile != null) {
                            buffers.put(data, tile, tile.sizeBytes)
                        } else {
                            failedUntil[data] = nowMs() + retryAfterMs
                            scheduleRetry(retryAfterMs)
                        }
                        tile
                    },
                )
            }
        load.waiters++
        try {
            return load.request.await()
        } finally {
            load.waiters--
            if (load.waiters == 0 && !load.request.isCompleted) {
                // Nobody wants this tile any more: it left the viewport before it arrived.
                loads.remove(data)
                load.request.cancel()
            }
        }
    }

    private suspend fun load(data: Long): PreparedTile? {
        val outcome =
            try {
                source.tile(TileKey.z(data), TileKey.x(data), TileKey.y(data), dark)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                Outcome.Err(RustError.Unknown("source", e.message ?: e.javaClass.simpleName))
            }
        val buffer = (outcome as? Outcome.Ok<TileBuffer>)?.value ?: return null
        // Building the paths is the heavy half of painting a tile: off the main thread.
        return withContext(rasterDispatcher) { PreparedTile(buffer) }
    }

    private fun cancelAll() {
        val running = jobs.values.toList()
        jobs.clear()
        running.forEach { it.cancel() }
        val requests = loads.values.toList()
        loads.clear()
        requests.forEach { it.request.cancel() }
        retryJob?.cancel()
        retryJob = null
    }

    private fun invalidate() {
        frame.intValue++
    }

    // ---- bitmap reuse ----

    private fun takeBitmap(): Bitmap {
        // A retired bitmap may still be in the display list of the last frame: reuse it only once a newer frame was
        // drawn without it.
        for (i in pool.indices) {
            if (poolRetiredAt[i] < draws) {
                poolRetiredAt.removeAt(i)
                return pool.removeAt(i)
            }
        }
        return TileRasterizer.newBitmap()
    }

    private fun retire(bitmap: Bitmap) {
        if (pool.size < POOL_SIZE) {
            pool += bitmap
            poolRetiredAt += draws
        }
        invalidate()
    }

    companion object {
        /** While the camera moves, tiles are planned at most this often. */
        const val DEBOUNCE_MS = 80L

        /** `rosty_geo::tiles::queue::RETRY_AFTER_MS`. */
        const val RETRY_AFTER_MS = 30_000L

        const val DEFAULT_BUFFER_BUDGET = 24L * 1024 * 1024
        const val RASTER_THREADS = 2

        /** A plan that would need more raster tiles than this is skipped (a viewport larger than any tablet). */
        const val MAX_WANTED = 160
        private const val POOL_SIZE = 6

        /** The bitmap cache always has room for one and a half screens of tiles, whatever the budget says. */
        private const val FLOOR_NUM = 3
        private const val FLOOR_DEN = 2

        /** The raster zoom for a pixel zoom: the nearest integer, so bitmaps are drawn at 0.71x to 1.41x. */
        fun rasterZoomFor(pixelZoom: Double): Int =
            if (pixelZoom.isNaN()) 0 else pixelZoom.roundToInt().coerceIn(0, TileRasterizer.MAX_RASTER_ZOOM)

        /**
         * The data tile a raster tile is painted from. On a 1x screen that is the tile of `fetch_zoom`; a denser screen
         * needs `round(log2(density))` more raster zooms for the same ground, and nothing is fetched above zoom 14.
         */
        fun dataTileFor(
            raster: Long,
            density: Float,
        ): Long {
            val z = TileKey.z(raster)
            val dataZoom =
                (
                    z -
                        MapMath
                            .log2(
                                density.toDouble(),
                            ).roundToInt()
                ).coerceIn(0, minOf(z, MapMath.MAX_TILE_ZOOM))
            return TileKey.ancestor(raster, z - dataZoom)
        }

        /** The bitmap budget of this device: about 1/8 of `ActivityManager.memoryClass` (ARCH 8.4 step 3). */
        fun bitmapBudget(context: Context): Long {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            return ByteLru.bitmapBudgetBytes(manager?.memoryClass ?: 0)
        }
    }
}
