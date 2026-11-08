package dev.rovsti.core.ops

import dev.rovsti.core.Outcome
import dev.rovsti.core.RustDispatcher
import dev.rovsti.core.RustError
import dev.rovsti.core.RustEvent
import dev.rovsti.core.RustEvents
import dev.rovsti.core.RustHandle
import dev.rovsti.core.RustNative
import dev.rovsti.core.RustThreading
import dev.rovsti.core.model.stops.OfflineProgress
import dev.rovsti.core.model.stops.TileBuffer
import dev.rovsti.core.model.stops.TilesCancelAnswer
import dev.rovsti.core.model.stops.TilesCancelRequest
import dev.rovsti.core.model.stops.TilesClearExceptRequest
import dev.rovsti.core.model.stops.TilesFreedAnswer
import dev.rovsti.core.model.stops.TilesHandleRequest
import dev.rovsti.core.model.stops.TilesOfflineRequest
import dev.rovsti.core.model.stops.TilesOpenAnswer
import dev.rovsti.core.model.stops.TilesOpenRequest
import dev.rovsti.core.model.stops.TilesSetCapRequest
import dev.rovsti.core.model.stops.TilesSourcesAnswer
import dev.rovsti.core.model.stops.TilesUsageAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reads one tile buffer of a native source handle; the real one is `RustNative.tile`. */
fun interface TileReader {
    fun read(
        handle: Long,
        z: Int,
        x: Int,
        y: Int,
        flags: Int,
    ): ByteArray?

    companion object {
        val Native = TileReader(RustNative::tile)
    }
}

/**
 * An open map tile source (a native `TileClient`, `DiskCache` and `TileSource`). [tile] answers decoded buffers,
 * the other calls manage the cache and the offline area. Close it when the map screen goes away; a closed handle
 * answers [RustError.NotFound] without a native call.
 */
class TileHandle internal constructor(
    id: Long,
    /** The source when it was opened. */
    val opened: TilesOpenAnswer,
    private val ops: TilesOps,
    private val reader: TileReader,
    private val events: RustEvents,
    private val releaseHook: (Long) -> Unit,
) : RustHandle(id, releaseHook) {
    private suspend fun <T> guarded(call: suspend () -> Outcome<T>): Outcome<T> =
        if (released) Outcome.Err(RustError.NotFound("tile handle $id is closed")) else call()

    /**
     * One tile. With [cacheOnly] no network is touched and a tile that is not cached is [RustError.NotFound]
     * (`detail.reason = "not_cached"`). [dark] picks the eclipse palette.
     */
    suspend fun tile(
        z: Int,
        x: Int,
        y: Int,
        dark: Boolean = false,
        cacheOnly: Boolean = false,
    ): Outcome<TileBuffer> =
        guarded {
            val flags =
                (if (dark) TileBuffer.FLAG_DARK else 0) or (if (cacheOnly) TileBuffer.FLAG_CACHE_ONLY else 0)
            withContext(RustDispatcher) {
                RustThreading.assertNotMain()
                TileBuffer.parse(reader.read(id, z, x, y, flags))
            }
        }

    suspend fun usage(): Outcome<TilesUsageAnswer> = guarded { ops.usage(TilesHandleRequest(id)) }

    suspend fun usageBySource(): Outcome<TilesSourcesAnswer> = guarded { ops.usageBySource(TilesHandleRequest(id)) }

    /** Deletes every cached tile of every server. */
    suspend fun clearAll(): Outcome<TilesFreedAnswer> = guarded { ops.clearAll(TilesHandleRequest(id)) }

    /** Deletes the caches of the other servers ([keepKey] null keeps the open one). */
    suspend fun clearExcept(keepKey: String? = null): Outcome<TilesFreedAnswer> =
        guarded { ops.clearExcept(TilesClearExceptRequest(id, keepKey)) }

    suspend fun setCap(bytes: Long): Outcome<TilesUsageAnswer> = guarded { ops.setCap(TilesSetCapRequest(id, bytes)) }

    /**
     * Starts an offline download of [area] (its `handle` is filled in). [scope] collects the progress events until the
     * job ends. The job is the caller's to close; closing it also cancels a download still running.
     */
    suspend fun startOffline(
        area: TilesOfflineRequest,
        scope: CoroutineScope,
    ): Outcome<OfflineJob> =
        guarded {
            // Collect before the start call: the first events can be faster than its answer.
            val seen = MutableStateFlow<Map<Long, OfflineProgress>>(emptyMap())
            val collector =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    events.events.collect { event ->
                        if (event is RustEvent.Message && event.kind == OFFLINE_EVENT) {
                            val progress = OfflineProgress.fromJson(event.json) ?: return@collect
                            seen.value = seen.value + (progress.job to progress)
                        }
                    }
                }
            when (val started = ops.offlineStart(area.copy(handle = id))) {
                is Outcome.Err -> {
                    collector.cancel()
                    started
                }

                is Outcome.Ok -> {
                    Outcome.Ok(OfflineJob(started.value.handle, started.value.total, ops, seen, collector, releaseHook))
                }
            }
        }

    companion object {
        const val OFFLINE_EVENT = "tiles.offline"

        /** Opens a source. The handle is the caller's to close. */
        suspend fun open(
            request: TilesOpenRequest = TilesOpenRequest(),
            ops: TilesOps = NativeTilesOps(),
            reader: TileReader = TileReader.Native,
            events: RustEvents = dev.rovsti.core.Rust.events,
            release: (Long) -> Unit = RustNative::release,
        ): Outcome<TileHandle> =
            when (val opened = ops.open(request)) {
                is Outcome.Err -> opened
                is Outcome.Ok -> Outcome.Ok(TileHandle(opened.value.handle, opened.value, ops, reader, events, release))
            }
    }
}

/** A running or finished offline area download. */
class OfflineJob internal constructor(
    id: Long,
    /** Tiles the area needs. */
    val total: Int,
    private val ops: TilesOps,
    private val seen: MutableStateFlow<Map<Long, OfflineProgress>>,
    private val collector: Job,
    release: (Long) -> Unit,
) : RustHandle(id, release) {
    /**
     * Progress: a `tiles.offline` event now and then, the last one has `finished` set (also after [cancel]). The flow
     * ends after that event. If the app lagged and events were dropped, ask the cache usage instead.
     */
    val progress: Flow<OfflineProgress> =
        seen
            .map { it[id] }
            .filterNotNull()
            .distinctUntilChanged()
            .transformWhile { event ->
                emit(event)
                !event.finished
            }

    /** Stops the queue. Downloads in flight finish and stay cached; the final event follows. */
    suspend fun cancel(): Outcome<TilesCancelAnswer> =
        if (released) {
            Outcome.Err(
                RustError.NotFound("download $id is closed"),
            )
        } else {
            ops.offlineCancel(TilesCancelRequest(id))
        }

    override fun close() {
        collector.cancel()
        super.close()
    }
}
