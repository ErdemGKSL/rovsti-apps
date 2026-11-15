package dev.rosty.stops

import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

/**
 * The work a screen test cannot see on its manual clock: calls into the real Rust core ([HybridCore]). They run on
 * the core's own threads and take as long as the machine lets them. A test that waits a fixed time for them, or lets
 * the answers arrive whenever the main looper happens to run, takes different pictures from run to run: the first
 * view was placed before or after the window insets came, before or after the test raised the sheet.
 *
 * So the answers of a screen test are *held*: a call runs at once, but its caller gets the answer only when the test
 * says so with [await], which first waits for the call itself. Every answer then lands in the same clock step in
 * every run, however busy the machine is. Map tiles are no such work here: `TestStopsContainer` paints them inline.
 */
internal object Background {
    /** One held call: who asked ([caller]), whether the caller has its answer in hand ([arrived]), and its gate. */
    private class Call(
        val caller: Job?,
    ) {
        @Volatile var arrived = false
        val release = CompletableDeferred<Unit>()
    }

    private val calls = ConcurrentLinkedQueue<Call>()

    /**
     * Runs [call] on the core. [held]: the caller goes on only at the next [await]; else as soon as it answers.
     * The call itself runs detached from the caller, so a caller whose screen is gone does not cancel a write.
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    suspend fun <T> counted(
        held: Boolean,
        call: suspend () -> T,
    ): T {
        // A test that calls the core itself inside `runBlocking` stands on the test thread: nobody could release it.
        val blocking = coroutineContext[ContinuationInterceptor]?.javaClass?.simpleName == "BlockingEventLoop"
        if (!held || blocking) return call()
        val entry = Call(coroutineContext[Job])
        calls += entry
        val value = GlobalScope.async(Dispatchers.Unconfined) { call() }.await()
        // Only now is the answer in the caller's hands: [await] must not count the call as done before this line,
        // or it would return while the caller is still on its way here and the answer would land at any later time.
        entry.arrived = true
        entry.release.await()
        return value
    }

    /**
     * Hands over the held answers: waits until every caller so far stands at its gate with its answer, opens the
     * gates in the order the calls were made, and runs what that wakes up ([pump]: the test's own coroutines; the
     * main looper is run too). An answer may ask the next question, so this repeats until [QUIET_ROUNDS] rounds in a
     * row found nothing held. A caller that was cancelled (its screen is gone) is forgotten. Fails after
     * [timeoutMs] of real time.
     */
    fun await(
        timeoutMs: Long = 30_000,
        pump: () -> Unit = {},
    ) {
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var quiet = 0
        while (quiet < QUIET_ROUNDS) {
            looper.idle()
            pump()
            calls.removeAll { it.caller?.isActive == false }
            if (calls.isEmpty()) {
                quiet++
                continue
            }
            quiet = 0
            if (calls.all { it.arrived }) {
                while (true) calls.poll()?.release?.complete(Unit) ?: break
            } else {
                check(
                    System.nanoTime() < deadline,
                ) { "the real core did not answer ${calls.size} calls in $timeoutMs ms" }
                Thread.sleep(1)
            }
        }
    }

    private const val QUIET_ROUNDS = 3
}
