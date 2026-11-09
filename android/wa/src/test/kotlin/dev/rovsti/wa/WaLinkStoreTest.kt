package dev.rovsti.wa

import dev.rovsti.core.Envelope
import dev.rovsti.core.FakeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.model.wa.StoredLink
import dev.rovsti.core.ops.NativeWaOps
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [WaLinkStore] against the `wa.link_*` goldens that Rust and the contract tests share (`crates/rovsti_ffi/fixtures`). */
class WaLinkStoreTest {
    private val fixtures =
        File(requireNotNull(System.getProperty("rovsti.ffi.fixtures")) { "rovsti.ffi.fixtures not set" })
    private val dir = "/data/no_backup"

    private fun text(name: String) = File(fixtures, name).readText().trim().replace("\$DIR", dir)

    private fun json(name: String): JsonElement = Envelope.json.parseToJsonElement(text(name))

    /** A core that answers [op] with the golden [answer] and keeps the request it saw. */
    private fun core(
        op: String,
        answer: String,
        seen: MutableList<JsonElement> = mutableListOf(),
    ) = FakeRustCore().also { core ->
        core.on(op) { request ->
            seen += request
            Envelope.decode(text(answer).encodeToByteArray())
        }
    }

    private val link = StoredLink("https://wa.example.com", "inst1", "rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU")

    @Test fun readsTheStoredLink() =
        runTest {
            val seen = mutableListOf<JsonElement>()
            val store = WaLinkStore(NativeWaOps(core("wa.link_read", "wa.link_read.answer.json", seen)), dir)
            assertEquals(Outcome.Ok(link), store.read())
            assertEquals(json("wa.link_read.request.json"), seen.single())
        }

    @Test fun noLinkReadsAsNull() =
        runTest {
            val store = WaLinkStore(NativeWaOps(core("wa.link_read", "wa.link_read.none.answer.json")), dir)
            assertNull((store.read() as Outcome.Ok).value)
        }

    @Test fun writeSendsTheLinkWithItsDirectory() =
        runTest {
            val seen = mutableListOf<JsonElement>()
            val store = WaLinkStore(NativeWaOps(core("wa.link_write", "wa.link_write.answer.json", seen)), dir)
            assertEquals(Outcome.Ok(Unit), store.write(link))
            assertEquals(json("wa.link_write.request.json"), seen.single())
        }

    @Test fun forgetWritesANullLink() =
        runTest {
            val seen = mutableListOf<JsonElement>()
            val store = WaLinkStore(NativeWaOps(core("wa.link_write", "wa.link_write.remove.answer.json", seen)), dir)
            assertEquals(Outcome.Ok(Unit), store.forget())
            assertEquals(json("wa.link_write.remove.request.json"), seen.single())
        }

    @Test fun aBadKeyIsInvalidAndWritesNothingMore() =
        runTest {
            val store = WaLinkStore(NativeWaOps(core("wa.link_write", "wa.link_write.bad_key.answer.json")), dir)
            val failed = store.write(link.copy(key = "short")) as Outcome.Err
            assertTrue(failed.error is RustError.Invalid)
        }

    @Test fun theKeyNeverReachesToString() {
        assertTrue("rwk_BQ" !in link.toString())
    }
}
