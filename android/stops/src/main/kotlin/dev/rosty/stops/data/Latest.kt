package dev.rosty.stops.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One piece of work at a time where the latest call wins (a list read again after a query, an edit or a refresh).
 * [launch] cancels the previous work, but cancelling cannot stop a core call that is already running, and the work
 * runs on a pool thread: without a guard an older answer could land after a newer one. Only the work of the latest
 * call may [Publisher.publish]; the check and the write happen under one lock. Callable from any thread.
 */
class Latest(
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var generation = 0
    private var job: Job? = null

    fun interface Publisher {
        /** Runs [write] only if no newer call was made since this work started. */
        fun publish(write: () -> Unit)
    }

    fun launch(block: suspend Publisher.() -> Unit) {
        synchronized(lock) {
            val mine = ++generation
            job?.cancel()
            val publisher = Publisher { write -> synchronized(lock) { if (mine == generation) write() } }
            job = scope.launch { publisher.block() }
        }
    }
}
