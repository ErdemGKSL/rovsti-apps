package dev.rovsti.core.rust

import dev.rovsti.core.MainThreadCheck
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.RustThreading
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.model.search.WireNumber
import dev.rovsti.core.ops.ContactIndexHandle
import dev.rovsti.core.ops.NativeSearchOps
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The contact index handle against the real host library: open, query, close, use after close, main thread. */
class ContactIndexTest {
    private val ops = NativeSearchOps(TestRust.core)

    private val contacts =
        listOf(
            WireContact(1, "Ayşe Yılmaz", listOf(WireNumber("0532 123 45 67", default = true)), starred = true),
            WireContact(2, "Mehmet Demir", listOf(WireNumber("+90 212 555 01 02", label = "Work"))),
            WireContact(3, "Борис Иванов", listOf(WireNumber("+7 495 123 45 67")), email = "boris@example.org"),
            WireContact(4, "Zeynep Kaya", emptyList(), email = "zeynep@example.org"),
        )

    @Before
    fun load() = TestRust.ensure()

    private fun open(): ContactIndexHandle = runBlocking { ContactIndexHandle.open(contacts, ops = ops) }.getOrOk()

    @Test
    fun createAndQuery() =
        runBlocking {
            val index = open()
            index.use {
                assertEquals(4, it.count)
                assertTrue(it.id > 0)
                val hit = it.query("ayse", limit = 10).getOrOk()
                assertEquals(listOf(1L), hit.ids)
                assertEquals("Ayşe Yılmaz", hit.rows.single().displayName)
                assertEquals(1, hit.total)
                assertEquals(listOf(3L), it.query("ИВАН").getOrOk().ids)
                assertEquals(listOf(2L), it.query("212 555").getOrOk().ids)
                assertEquals(4, it.query("", limit = 2).getOrOk().total)
                assertEquals(listOf(1L), it.queryLoose("yilmaz ayse").getOrOk().ids)
                assertEquals(1L, it.matchNumber("+90 532 123 45 67").getOrOk().id)
                assertEquals(null, it.matchNumber("+49 151 2345678").getOrOk().id)
                val t9 = it.t9("29").getOrOk()
                assertEquals(listOf(1L), t9.hits.map { h -> h.id })
            }
        }

    @Test
    fun replaceSwapsTheContacts() =
        runBlocking {
            open().use {
                val replaced =
                    it
                        .replace(
                            listOf(WireContact(9, "Cem Aksoy", listOf(WireNumber("0555 000 11 22")))),
                        ).getOrOk()
                assertEquals(1, replaced.count)
                assertEquals(emptyList<Long>(), it.query("ayse").getOrOk().ids)
                assertEquals(listOf(9L), it.query("cem").getOrOk().ids)
            }
        }

    @Test
    fun callCountsRankTheT9Strip() =
        runBlocking {
            val twins =
                listOf(
                    WireContact(10, "Ali Veli", listOf(WireNumber("0555 000 00 01"))),
                    WireContact(11, "Alp Vural", listOf(WireNumber("0555 000 00 02"))),
                )
            ContactIndexHandle.open(twins, ops = ops).getOrOk().use {
                it.setCallCounts(mapOf(11L to 5)).getOrOk()
                assertEquals(
                    11L,
                    it
                        .t9("25")
                        .getOrOk()
                        .hits
                        .first()
                        .id,
                )
            }
        }

    @Test
    fun queryAfterCloseIsNotFound() =
        runBlocking {
            val index = open()
            val id = index.id
            index.close()
            assertTrue(index.released)
            val closed = index.query("a") as Outcome.Err
            assertTrue(closed.error is RustError.NotFound)
            // The native side agrees: the released id is refused there too.
            val raw =
                TestRust.core.call(
                    "search.query",
                    buildJsonObject {
                        put("handle", id)
                        put("query", "a")
                    },
                )
            assertTrue((raw as Outcome.Err).error is RustError.NotFound)
            index.close() // closing twice is harmless
        }

    @Test
    fun aForeignHandleIsRefused() =
        runBlocking {
            val raw =
                TestRust.core.call(
                    "search.query",
                    buildJsonObject {
                        put("handle", 12345L)
                        put("query", "a")
                    },
                )
            assertTrue((raw as Outcome.Err).error is RustError.NotFound)
        }

    @Test
    fun nativeCallsOnTheMainThreadAreRefused() {
        val index = open()
        try {
            RustThreading.enable(MainThreadCheck { true })
            try {
                runBlocking { index.query("a") }
                fail("a native call on the main thread must throw")
            } catch (e: IllegalStateException) {
                assertNotNull(e.message)
                assertTrue(e.message.orEmpty().contains("main thread"))
            }
        } finally {
            RustThreading.enable(MainThreadCheck { false })
            index.close()
        }
    }

    private fun <T> Outcome<T>.getOrOk(): T = (this as? Outcome.Ok)?.value ?: error("failed: $this")
}
