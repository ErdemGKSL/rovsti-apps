package dev.rosty.stops.offline

import androidx.compose.runtime.Immutable
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.stops.TilesBbox
import dev.rosty.core.model.stops.TilesOfflineRequest
import dev.rosty.core.ops.OfflineJob
import dev.rosty.stops.data.StopsTiles
import dev.rosty.stops.map.MapCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A map area by its edges (degrees), as the map showed it when it last came to rest. */
@Immutable
data class MapArea(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    val bbox: TilesBbox get() = TilesBbox(south, west, north, east)
}

/**
 * The area [camera] shows (pure over the camera): its corners, or null before it has a size. A view wider than the world
 * is cut to one world, which `tiles.offline_plan` accepts.
 */
fun visibleArea(camera: MapCamera): MapArea? {
    val w = camera.widthPx
    val h = camera.heightPx
    if (w <= 0 || h <= 0) return null
    val west = camera.lonAt(0f)
    val east = camera.lonAt(w.toFloat())
    val wide = east - west >= FULL_TURN
    return MapArea(
        south = camera.latAt(h.toFloat()),
        west = if (wide) -HALF_TURN else west,
        north = camera.latAt(0f),
        east = if (wide) HALF_TURN else east,
    )
}

private const val FULL_TURN = 360.0
private const val HALF_TURN = 180.0

/** Where the offline area download is (S10). Settings and the notification draw it. */
@Immutable
sealed interface OfflineState {
    data object Idle : OfflineState

    /** Asked for; the worker has not started the download yet. */
    data object Starting : OfflineState

    data class Running(
        /** Tiles handled (saved or failed). */
        val done: Int,
        val total: Int,
        val fraction: Float,
    ) : OfflineState

    data class Finished(
        val saved: Int,
        val failed: Int,
        val total: Int,
        val cancelled: Boolean,
    ) : OfflineState

    /** The download could not start: the core's sentence (a bad area, no tile source). */
    data class Failed(
        val message: String,
    ) : OfflineState

    /** A download is asked for or under way: the Cancel button shows, a second start is refused. */
    val active: Boolean get() = this is Starting || this is Running
}

/**
 * The offline area download of Stops (S10), shared by Settings, the foreground worker and its notification. [start] asks
 * the platform to run the work ([schedule]: a foreground `dataSync` worker on the phone, so the download goes on when
 * the app is closed); the worker then calls [run], which starts `tiles.offline_start` on the map's tile handle and
 * follows its `tiles.offline` events until the last one. [cancel] stops it the way the GPUI queue did: the tiles saved
 * so far stay cached. Lives as long as the process.
 */
class OfflineDownloads(
    private val tiles: StopsTiles,
    private val scope: CoroutineScope,
    private val schedule: (MapArea) -> Unit,
    private val unschedule: () -> Unit,
) {
    private val mutable = MutableStateFlow<OfflineState>(OfflineState.Idle)
    val state: StateFlow<OfflineState> = mutable.asStateFlow()

    @Volatile private var job: OfflineJob? = null

    /** Asks for [area]; false while another download is asked for or running. */
    fun start(area: MapArea): Boolean {
        synchronized(this) {
            if (mutable.value.active) return false
            mutable.value = OfflineState.Starting
        }
        schedule(area)
        return true
    }

    /** Stops the download (tiles in flight finish and stay); a request the worker has not taken yet is withdrawn. */
    fun cancel() {
        val running = job
        if (running != null) {
            scope.launch { running.cancel() }
        } else if (mutable.value == OfflineState.Starting) {
            unschedule()
            mutable.value = OfflineState.Idle
        }
    }

    /** Forgets a finished or failed download (the Settings row goes back to the estimate). */
    fun dismiss() {
        val now = mutable.value
        if (now is OfflineState.Finished || now is OfflineState.Failed) mutable.value = OfflineState.Idle
    }

    /**
     * The worker's part: downloads [area] and reports every step to [onState] (the notification) as well as [state].
     * Answers the final state. A cancellation of the caller (the system stopped the worker) cancels the download too.
     */
    suspend fun run(
        area: MapArea,
        onState: (OfflineState) -> Unit = {},
    ): OfflineState =
        coroutineScope {
            fun publish(next: OfflineState): OfflineState {
                mutable.value = next
                onState(next)
                return next
            }
            val handle =
                when (val opened = tiles.handle()) {
                    is Outcome.Err -> return@coroutineScope publish(OfflineState.Failed(opened.error.sentence()))
                    is Outcome.Ok -> opened.value
                }
            val started =
                when (val begun = handle.startOffline(TilesOfflineRequest(handle = 0, bbox = area.bbox), this)) {
                    is Outcome.Err -> return@coroutineScope publish(OfflineState.Failed(begun.error.sentence()))
                    is Outcome.Ok -> begun.value
                }
            job = started
            var last: OfflineState = publish(OfflineState.Running(0, started.total, 0f))
            var saved = 0
            try {
                started.progress.collect { p ->
                    saved = p.saved
                    last =
                        publish(
                            if (p.finished) {
                                OfflineState.Finished(p.saved, p.failed, p.total, p.cancelled)
                            } else {
                                OfflineState.Running(p.done, p.total, p.fraction)
                            },
                        )
                }
            } finally {
                job = null
                // Closing the job also cancels a download still running (the worker was stopped).
                started.close()
                if (last !is OfflineState.Finished) {
                    last = publish(OfflineState.Finished(saved, failed = 0, total = started.total, cancelled = true))
                }
            }
            last
        }

    private fun RustError.sentence(): String = message.orEmpty()
}
