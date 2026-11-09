package dev.rovsti.platform.location

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A [TripRepository] for screen tests: [startResult] is what `start` answers, [push] reports a status. */
class FakeTripRepository(
    var startResult: StartResult = StartResult.Started,
) : TripRepository {
    private val bus = MutableSharedFlow<TripStatus>(extraBufferCapacity = 16)
    val started = mutableListOf<TripSpec>()
    var running = false
        private set

    override val status: Flow<TripStatus> = bus.asSharedFlow()

    override fun start(req: TripSpec): StartResult {
        started += req
        if (startResult == StartResult.Started) running = true
        return startResult
    }

    override fun stop(): Boolean = running.also { running = false }

    fun push(status: TripStatus) {
        bus.tryEmit(status)
    }
}
