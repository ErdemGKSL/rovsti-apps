package dev.rovsti.platform.location

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.time.Duration

/** A scripted [LocationRepository] for screen tests: set [last] and [next], push live fixes with [push]. */
class FakeLocationRepository(
    var last: Fix? = null,
    var next: Fix? = null,
) : LocationRepository {
    private val live = MutableSharedFlow<Fix>(extraBufferCapacity = 16)

    override fun lastKnown(): Fix? = last

    override suspend fun fresh(timeout: Duration): Fix? = next

    override fun updates(): Flow<Fix> = live.asSharedFlow()

    fun push(fix: Fix) {
        live.tryEmit(fix)
    }
}
