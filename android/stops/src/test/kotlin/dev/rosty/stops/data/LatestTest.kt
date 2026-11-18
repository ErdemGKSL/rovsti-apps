package dev.rosty.stops.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The latest call wins even when an older one's answer comes back last (a core call cannot be cancelled). */
class LatestTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val latest = Latest(scope)

    @After fun tearDown() = scope.cancel()

    @Test fun anOlderAnswerThatComesLastIsDropped() =
        runBlocking {
            val shown = mutableListOf<String>()
            val oldGate = CompletableDeferred<Unit>()
            val oldDone = CompletableDeferred<Unit>()
            val oldStarted = CompletableDeferred<Unit>()
            val newDone = CompletableDeferred<Unit>()
            latest.launch {
                // Like a core call already running: cancelling the job does not stop it.
                withContext(NonCancellable) {
                    oldStarted.complete(Unit)
                    oldGate.await()
                }
                publish { shown += "old" }
                oldDone.complete(Unit)
            }
            // The old work must be running before it is superseded; a job cancelled before it starts never runs.
            withTimeout(TIMEOUT) { oldStarted.await() }
            latest.launch {
                publish { shown += "new" }
                newDone.complete(Unit)
            }
            withTimeout(TIMEOUT) { newDone.await() }
            oldGate.complete(Unit)
            withTimeout(TIMEOUT) { oldDone.await() }
            assertEquals(listOf("new"), shown)
        }

    @Test fun callsFromManyThreadsLeaveTheLastOnePublished() =
        runBlocking {
            val threads = (1..8).map { Thread { repeat(50) { latest.launch { publish { } } } } }
            threads.forEach(Thread::start)
            threads.forEach(Thread::join)
            val seen = CompletableDeferred<Int>()
            latest.launch { publish { seen.complete(FINAL) } }
            assertEquals(FINAL, withTimeout(TIMEOUT) { seen.await() })
        }

    private companion object {
        const val TIMEOUT = 5_000L
        const val FINAL = 99
    }
}
