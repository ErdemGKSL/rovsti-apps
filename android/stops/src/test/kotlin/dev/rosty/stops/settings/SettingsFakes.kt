package dev.rosty.stops.settings

import dev.rosty.core.Outcome
import dev.rosty.core.RustEvents
import dev.rosty.core.model.stops.TileSourceRow
import dev.rosty.core.model.stops.TilesCancelAnswer
import dev.rosty.core.model.stops.TilesCancelRequest
import dev.rosty.core.model.stops.TilesClearExceptRequest
import dev.rosty.core.model.stops.TilesFreedAnswer
import dev.rosty.core.model.stops.TilesHandleRequest
import dev.rosty.core.model.stops.TilesOfflineAnswer
import dev.rosty.core.model.stops.TilesOfflineRequest
import dev.rosty.core.model.stops.TilesOpenAnswer
import dev.rosty.core.model.stops.TilesOpenRequest
import dev.rosty.core.model.stops.TilesSetCapRequest
import dev.rosty.core.model.stops.TilesSourcesAnswer
import dev.rosty.core.model.stops.TilesTestServerAnswer
import dev.rosty.core.model.stops.TilesTestServerRequest
import dev.rosty.core.model.stops.TilesUsageAnswer
import dev.rosty.core.ops.TileHandle
import dev.rosty.core.ops.TileReader
import dev.rosty.core.ops.TilesOps
import dev.rosty.stops.offline.MapArea

/** The alarm on the phone, recorded: what was played, buzzed and rung. */
class FakeAlarmDevice : AlarmDevice {
    val played = mutableListOf<String>()
    val vibrations = mutableListOf<List<Long>>()
    val rings = mutableListOf<String>()
    var stops = 0
    var ringing = false

    override fun playSound(planJson: String): Boolean {
        if (ringing) return false
        played += planJson
        return true
    }

    override fun stopSound() {
        stops++
    }

    override fun vibrate(pattern: List<Long>): Boolean = vibrations.add(pattern)

    override fun testRing(testRingJson: String): Boolean {
        if (ringing) return false
        rings += testRingJson
        return true
    }
}

/**
 * The tile source's cache and download ops, scripted; [real] (the host core) answers what is pure (`offline_plan`,
 * `test_server` of an address nothing serves). An offline start sends its progress through [events] on its own thread,
 * as Rust does: [progress] events, then the last one; with [hold] it waits for [offlineCancel].
 */
class FakeTilesOps(
    private val real: TilesOps? = null,
    val events: RustEvents = RustEvents(),
) : TilesOps {
    var sources = listOf(TileSourceRow("versatiles", 40L * MB, current = true), TileSourceRow("old", 12L * MB, false))
    var testAnswer: Outcome<TilesTestServerAnswer>? = null
    val caps = mutableListOf<Long>()
    var clears = 0
    var cleared = mutableListOf<String?>()
    val starts = mutableListOf<TilesOfflineRequest>()
    var cancels = 0
    var total = 4

    /** The `done` counts of the progress events before the last one. */
    var progress = listOf(1, 2)
    var hold = false
    private val cancelled = java.util.concurrent.CountDownLatch(1)

    private fun pure(): TilesOps = checkNotNull(real) { "no core behind these fake tile ops" }

    override suspend fun open(req: TilesOpenRequest) =
        Outcome.Ok(
            TilesOpenAnswer(
                7,
                "versatiles",
                req.serverUrl ?: "default",
                req.serverUrl == null,
                req.cacheCap ?: 0,
                0,
                0,
            ),
        )

    override suspend fun usage(req: TilesHandleRequest) =
        Outcome.Ok(TilesUsageAnswer(sources.sumOf { it.bytes }, sources.first { it.current }.bytes, 10, 128 * MB))

    override suspend fun usageBySource(req: TilesHandleRequest) = Outcome.Ok(TilesSourcesAnswer(sources))

    override suspend fun clearAll(req: TilesHandleRequest): Outcome<TilesFreedAnswer> {
        clears++
        val freed = sources.sumOf { it.bytes }
        sources = sources.map { it.copy(bytes = 0) }
        return Outcome.Ok(TilesFreedAnswer(freed))
    }

    override suspend fun clearExcept(req: TilesClearExceptRequest): Outcome<TilesFreedAnswer> {
        cleared += req.keepKey
        val freed = sources.filterNot { it.current }.sumOf { it.bytes }
        sources = sources.filter { it.current }
        return Outcome.Ok(TilesFreedAnswer(freed))
    }

    override suspend fun setCap(req: TilesSetCapRequest): Outcome<TilesUsageAnswer> {
        caps += req.capBytes
        return usage(TilesHandleRequest(req.handle))
    }

    override suspend fun testServer(req: TilesTestServerRequest) = testAnswer ?: pure().testServer(req)

    override suspend fun standIn(req: dev.rosty.core.model.stops.TilesStandInRequest) = pure().standIn(req)

    override suspend fun encodeMvt(req: dev.rosty.core.model.stops.TilesEncodeMvtRequest) = pure().encodeMvt(req)

    override suspend fun offlinePlan(req: dev.rosty.core.model.stops.TilesPlanRequest) = pure().offlinePlan(req)

    override suspend fun offlineStart(req: TilesOfflineRequest): Outcome<TilesOfflineAnswer> {
        starts += req
        val job = JOB
        Thread {
            Thread.sleep(PAUSE_MS)
            for (i in progress) {
                emit(job, done = i, finished = false, cancelled = false)
                Thread.sleep(PAUSE_MS)
            }
            if (hold) cancelled.await(HOLD_S, java.util.concurrent.TimeUnit.SECONDS)
            emit(job, done = if (hold) progress.last() else total, finished = true, cancelled = hold)
        }.start()
        return Outcome.Ok(TilesOfflineAnswer(job, total, topZoom = 14, capped = false, estimateBytes = 1_000L))
    }

    override suspend fun offlineCancel(req: TilesCancelRequest): Outcome<TilesCancelAnswer> {
        cancels++
        cancelled.countDown()
        return Outcome.Ok(TilesCancelAnswer(progress.last(), total))
    }

    private fun emit(
        job: Long,
        done: Int,
        finished: Boolean,
        cancelled: Boolean,
    ) {
        val fraction = done.toFloat() / total
        val json =
            """{"job":$job,"done":$done,"saved":$done,"failed":0,"total":$total,"fraction":$fraction,""" +
                """"text":"","finished":$finished,"cancelled":$cancelled}"""
        events.onEvent(TileHandle.OFFLINE_EVENT, json.toByteArray())
    }

    /** Opens a handle on these ops (no native library, nothing released natively). */
    suspend fun openHandle(req: TilesOpenRequest): Outcome<TileHandle> =
        TileHandle.open(req, this, TileReader { _, _, _, _, _ -> null }, events) { }

    companion object {
        const val MB = 1024L * 1024L
        const val JOB = 9L
        private const val PAUSE_MS = 30L
        private const val HOLD_S = 10L

        /** A small area around the fixture village. */
        val AREA = MapArea(40.995, 29.0, 41.005, 29.012)
    }
}
