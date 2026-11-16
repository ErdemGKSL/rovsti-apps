package dev.rosty.people

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.ops.WaGateway
import dev.rosty.wa.FakeWaGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A 429 from the gateway pauses every live-name question for the wait it names, not only the number asked. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LiveWaNamesRateLimitTest {
    private val fake = FakeWaGateway()
    private var lookups = 0
    private var now = 1_000_000L

    private fun limited(retryAfterMs: Long?): Outcome<kotlinx.serialization.json.JsonElement> {
        val detail =
            buildMap {
                put("code", JsonPrimitive("rate_limited"))
                put("source", JsonPrimitive("whatsapp"))
                retryAfterMs?.let { put("retry_after_ms", JsonPrimitive(it)) }
            }
        return Outcome.Err(RustError.Invalid("rate limited", JsonObject(detail)))
    }

    private suspend fun gateway(): WaGateway? =
        (WaGateway.open("https://rosty.erdemdev.tr", "key", null, fake.ops, fake.audio, release = {}) as? Outcome.Ok)
            ?.value

    private fun names(scope: TestScope) =
        LiveWaNames(gateway = { gateway() }, e164 = { "+$it" }, scope = scope, clock = { now })

    private fun answerLimited(retryAfterMs: Long?) {
        fake.core.on("wa.lookup_users") {
            lookups++
            limited(retryAfterMs)
        }
    }

    @Test
    fun aRateLimitPausesEveryNumberForTheNamedWait() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            answerLimited(550_000L)
            val names = names(scope)
            names.ask("100000000001")
            scope.advanceUntilIdle()
            assertEquals(1, lookups)
            // Other numbers wait too, while the pause lasts.
            for (n in 2..20) names.ask("1000000000%02d".format(n))
            scope.advanceUntilIdle()
            assertEquals(1, lookups)
            now += 549_000L
            names.ask("100000000002")
            scope.advanceUntilIdle()
            assertEquals(1, lookups)
            // The wait is over: questions go out again.
            now += 2_000L
            names.ask("100000000002")
            scope.advanceUntilIdle()
            assertEquals(2, lookups)
        }

    @Test
    fun aRateLimitWithoutAHintPausesAMinute() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            answerLimited(null)
            val names = names(scope)
            names.ask("100000000001")
            scope.advanceUntilIdle()
            names.ask("100000000002")
            scope.advanceUntilIdle()
            assertEquals(1, lookups)
            now += LiveWaNames.RATE_LIMIT_PAUSE_MS
            names.ask("100000000002")
            scope.advanceUntilIdle()
            assertEquals(2, lookups)
        }

    @Test
    fun anotherErrorDoesNotPause() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            fake.core.on("wa.lookup_users") {
                lookups++
                Outcome.Err(RustError.Network("offline"))
            }
            val names = names(scope)
            names.ask("100000000001")
            scope.advanceUntilIdle()
            names.ask("100000000002")
            scope.advanceUntilIdle()
            assertEquals(2, lookups)
        }
}
