package dev.rosty.people

import dev.rosty.core.Envelope
import dev.rosty.core.Outcome
import dev.rosty.core.model.wa.JidsBody
import dev.rosty.core.model.wa.WaProfile
import dev.rosty.core.model.wa.WaUser
import dev.rosty.core.ops.WaGateway
import dev.rosty.wa.FakeWaGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The answers of [LiveWaNames] outlive the process, "not on WhatsApp" included, for the same lifetime. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LiveNameStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val ada = "905550000001"
    private val mert = "905550000002"
    private val fake = FakeWaGateway()
    private val lookups = ArrayList<String>()
    private var now = 1_000_000L

    init {
        // Ada is on WhatsApp under a push name; nobody else is.
        fake.core.on("wa.lookup_users") { request ->
            val body = JsonObject((request as JsonObject).filterKeys { it != "handle" })
            val jids = Envelope.json.decodeFromJsonElement(JidsBody.serializer(), body).jids
            lookups += jids
            Outcome.Ok(
                Envelope.json.encodeToJsonElement(
                    ListSerializer(WaUser.serializer()),
                    jids.map { WaUser(it, it.startsWith(ada)) },
                ),
            )
        }
        fake.profiles = { jids -> Outcome.Ok(jids.map { WaProfile(it, pushName = "Ada") }) }
    }

    private suspend fun gateway(): WaGateway? =
        (WaGateway.open("https://rosty.erdemdev.tr", "key", null, fake.ops, fake.audio, release = {}) as? Outcome.Ok)
            ?.value

    private fun names(
        scope: TestScope,
        store: LiveNameStore,
    ) = LiveWaNames(
        gateway = { gateway() },
        e164 = { "+$it" },
        scope = scope,
        clock = { now },
        store = store,
        io = StandardTestDispatcher(scope.testScheduler),
    )

    private class MemoryStore : LiveNameStore {
        var saved: Map<String, LiveName> = emptyMap()
        var loads = 0

        override fun load(): Map<String, LiveName> {
            loads++
            return saved
        }

        override fun save(answers: Map<String, LiveName>) {
            saved = answers
        }
    }

    @Test fun theFileKeepsNamesAndNotOnWhatsAppAnswers() {
        val store = FileLiveNameStore(File(folder.root, "names/wa-live-names.json"))
        assertTrue(store.load().isEmpty())
        val answers = mapOf(ada to LiveName("Ada", 10), mert to LiveName(null, 20))
        store.save(answers)
        assertEquals(answers, store.load())
        // A second write replaces the first; anything unreadable counts as empty.
        store.save(mapOf(ada to LiveName("Ada L.", 30)))
        assertEquals(mapOf(ada to LiveName("Ada L.", 30)), store.load())
        assertTrue(FileLiveNameStore.parse("{not json").isEmpty())
        assertTrue(FileLiveNameStore.parse("""{"v":9,"names":{}}""").isEmpty())
    }

    @Test fun theFileKeepsOnlyTheNewestAnswers() {
        val many =
            (0 until FileLiveNameStore.MAX_ENTRIES + 5).associate {
                "9055500$it" to
                    LiveName(
                        "n$it",
                        it.toLong(),
                    )
            }
        val kept = FileLiveNameStore.parse(FileLiveNameStore.render(many))
        assertEquals(FileLiveNameStore.MAX_ENTRIES, kept.size)
        assertNull(kept["90555000"])
    }

    @Test fun anAnswerIsWrittenAndTheNextRunKnowsItWithoutAsking() =
        runTest {
            val store = MemoryStore()
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val first = names(scope, store)
            first.ask(ada)
            first.ask(mert)
            scope.advanceUntilIdle()
            assertEquals(mapOf(ada to LiveName("Ada", now), mert to LiveName(null, now)), store.saved)
            assertEquals(2, lookups.size)

            // A new process an hour later: the names are there before anything is asked, and nothing is asked again.
            now += HOUR_MS
            val second = names(scope, store)
            assertEquals("Ada", second.lookup(listOf(ada))[ada]?.waPushName)
            assertEquals(1L, second.revision.value)
            assertTrue(second.lookup(listOf(mert)).isEmpty())
            second.ask(ada)
            second.ask(mert)
            scope.advanceUntilIdle()
            assertEquals(2, lookups.size)
            assertEquals(1, store.loads - 1)
        }

    @Test fun aStoredAnswerPastItsLifetimeIsAskedAgain() =
        runTest {
            val store = MemoryStore()
            store.saved = mapOf(ada to LiveName("Old name", now), mert to LiveName(null, now))
            now += LiveWaNames.TTL_MS
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val names = names(scope, store)
            assertTrue(names.lookup(listOf(ada, mert)).isEmpty())
            names.ask(mert)
            scope.advanceUntilIdle()
            assertEquals(listOf("$mert@s.whatsapp.net"), lookups)
            // The expired answers are gone from the file with the next write.
            assertEquals(mapOf(mert to LiveName(null, now)), store.saved)
        }

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
    }
}
