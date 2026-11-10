package dev.rovsti.phone.data

import dev.rovsti.core.model.phone.CallNote
import dev.rovsti.core.model.phone.SpeedDialEntry
import dev.rovsti.phone.HostRust
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** E5.2 data layer: `prefs.json` and `call-notes.json` through the real Rust core, with its own directory per test. */
class PhoneStoreTest {
    private val host by lazy { HostRust("rovsti-phone-store") }
    private val counting by lazy { CountingCore(host.core) }
    private val ops by lazy {
        dev.rovsti.core.ops
            .NativePhoneOps(counting)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = tempDir("rovsti-store")

    @After fun stop() = scope.cancel()

    private fun store(debounce: Long = 50) = PhoneStore(ops, scope, dir, Dispatchers.IO, debounce)

    @Test fun editsRoundTripThroughTheFile() =
        runBlocking {
            val first = store()
            first.load()
            first.updatePrefs {
                it.copy(
                    quickReplies = listOf("Call you later"),
                    speedDial = mapOf(3 to SpeedDialEntry("k3", "+905550001122", "Deniz")),
                )
            }
            first.updateNotes { it.copy(notes = it.notes + CallNote("+905550001122", T0, "call back", T0)) }
            assertTrue(first.flush())

            val second = store()
            second.load()
            assertEquals(listOf("Call you later"), second.prefs.value.quickReplies)
            assertEquals(
                "Deniz",
                second.prefs.value.speedDial
                    .getValue(3)
                    .name,
            )
            assertEquals(
                listOf("call back"),
                second.notes.value.notes
                    .map { it.text },
            )
            assertEquals("current", second.origin)
        }

    @Test fun aBurstOfEditsIsOneWriteAfterTheDebounce() =
        runBlocking {
            val s = store(debounce = 400)
            s.load()
            val before = counting.count("phone.prefs_save")
            repeat(10) { i -> s.updatePrefs { it.copy(quickReplies = listOf("reply $i")) } }
            assertEquals("the value is there at once", listOf("reply 9"), s.prefs.value.quickReplies)
            assertEquals("nothing is written inside the window", before, counting.count("phone.prefs_save"))
            delay(1_200)
            assertEquals("one write for the burst", before + 1, counting.count("phone.prefs_save"))
            assertTrue(File(dir, "prefs.json").readText().contains("reply 9"))
        }

    @Test fun anEditThatChangesNothingWritesNothing() =
        runBlocking {
            val s = store()
            s.load()
            val before = counting.count("phone.prefs_save")
            s.updatePrefs { it }
            s.flush()
            assertEquals(before, counting.count("phone.prefs_save"))
        }

    @Test fun concurrentEditsLoseNothing() =
        runBlocking {
            val s = store()
            s.load()
            val n = 64
            (0 until n)
                .map { i ->
                    async(Dispatchers.Default) { s.updatePrefs { it.copy(favoritesOrder = it.favoritesOrder + "k$i") } }
                }.awaitAll()
            assertTrue(s.flush())
            val again = store()
            again.load()
            assertEquals(
                n,
                again.prefs.value.favoritesOrder
                    .toSet()
                    .size,
            )
        }

    @Test fun concurrentEditsAreOneDebouncedWrite() =
        runBlocking {
            val s = store(debounce = 400)
            s.load()
            val before = counting.count("phone.prefs_save")
            val n = 32
            (0 until n)
                .map { i ->
                    async(Dispatchers.Default) { s.updatePrefs { it.copy(favoritesOrder = it.favoritesOrder + "c$i") } }
                }.awaitAll()
            assertEquals("nothing is written inside the window", before, counting.count("phone.prefs_save"))
            delay(1_200)
            assertEquals("one write for all of them", before + 1, counting.count("phone.prefs_save"))
            val again = store()
            again.load()
            assertEquals(
                "nothing lost",
                n,
                again.prefs.value.favoritesOrder
                    .toSet()
                    .size,
            )
        }

    @Test fun aFlushWritesAtOnceAndTheDebouncedWriteDoesNotRepeatIt() =
        runBlocking {
            val s = store(debounce = 300)
            s.load()
            val before = counting.count("phone.prefs_save")
            s.updatePrefs { it.copy(quickReplies = listOf("bye")) }
            assertTrue(s.flush())
            assertEquals(before + 1, counting.count("phone.prefs_save"))
            delay(800)
            assertEquals("the cancelled timer wrote nothing more", before + 1, counting.count("phone.prefs_save"))
        }

    @Test fun aFileFromANewerAppIsNeverOverwritten() =
        runBlocking {
            val file = File(dir, "prefs.json")
            file.writeText("""{"schema_version":99,"future":true}""")
            val s = store()
            s.load()
            assertEquals("newer", s.origin)
            s.updatePrefs { it.copy(quickReplies = listOf("mine")) }
            s.flush()
            assertFalse(file.readText().contains("mine"))
            assertEquals("the edit still shows in the app", listOf("mine"), s.prefs.value.quickReplies)
        }
}
