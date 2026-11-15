package dev.rosty.people

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class LiveWaNamesTest {
    @Test
    fun aVerifiedBusinessNameWinsOverThePushName() {
        assertEquals("Acme Ltd", LiveWaNames.nameOf(" Acme Ltd ", "acme"))
        assertEquals("Deniz", LiveWaNames.nameOf("  ", "Deniz"))
        assertNull(LiveWaNames.nameOf(null, " "))
    }

    @Test
    fun nothingIsKnownBeforeAnAnswerAndAMissingGatewayAsksNothing() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val asked = AtomicInteger()
            val names =
                LiveWaNames(
                    gateway = {
                        asked.incrementAndGet()
                        null
                    },
                    e164 = { "+905321234567" },
                    scope = scope,
                )
            assertTrue(names.lookup(listOf("05321234567")).isEmpty())
            names.ask("05321234567")
            names.ask("05321234567")
            scope.advanceUntilIdle()
            // One query at a time per number; without a gateway nothing is learned and the revision stays.
            assertEquals(1, asked.get())
            assertEquals(0L, names.revision.value)
            assertTrue(names.lookup(listOf("05321234567")).isEmpty())
        }

    @Test
    fun aWithheldOrShortNumberIsNeverAsked() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val asked = AtomicInteger()
            val names =
                LiveWaNames(
                    gateway = {
                        asked.incrementAndGet()
                        null
                    },
                    e164 = { if (it.length > 5) it else null },
                    scope = scope,
                )
            names.ask("112")
            names.ask("")
            scope.advanceUntilIdle()
            assertEquals(0, asked.get())
        }
}
