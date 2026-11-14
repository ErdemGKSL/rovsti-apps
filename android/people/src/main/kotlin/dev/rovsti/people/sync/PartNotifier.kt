package dev.rovsti.people.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tells readers about committed parts of a sync without flooding them (people sync v2, D9): the first [part] fires at
 * once, later ones at most once per [minMs], and the last one is never lost (a trailing fire follows the window).
 * [fire] runs on [scope].
 */
class PartNotifier(
    private val scope: CoroutineScope,
    private val minMs: Long,
    private val fire: suspend () -> Unit,
) {
    private var job: Job? = null
    private var pending = false

    /** A committed part: asks for a notification; returns at once. */
    fun part() {
        synchronized(this) {
            if (job != null) {
                pending = true
                return
            }
            val window =
                scope.launch(start = CoroutineStart.LAZY) {
                    fire()
                    while (true) {
                        delay(minMs)
                        if (!takePending()) break
                        fire()
                    }
                }
            window.invokeOnCompletion { ended(window) }
            job = window
            window.start()
        }
    }

    private fun takePending(): Boolean =
        synchronized(this) {
            val was = pending
            pending = false
            // Ending here: the next part starts a new window at once.
            if (!was) job = null
            was
        }

    private fun ended(window: Job) {
        synchronized(this) {
            if (job === window) job = null
        }
    }
}
