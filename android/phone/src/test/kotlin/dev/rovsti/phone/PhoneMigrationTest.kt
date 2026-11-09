package dev.rovsti.phone

import dev.rovsti.core.Outcome
import dev.rovsti.core.Rust
import dev.rovsti.core.model.phone.DirRequest
import dev.rovsti.core.model.phone.PrefsSaveRequest
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.core.ops.NativeWaOps
import dev.rovsti.wa.WaLinkStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * E5.1 migration: the files the GPUI Phone app wrote (`prefs.json`, `call-notes.json`, `wa-calls.json` with its
 * `wa-calls-forgotten.json`) and the shared `whatsapp-link.json` are read by the new app's Rust core unchanged. The
 * fixtures in `src/test/resources/migration` are copies of `crates/rovsti_app_core/tests/fixtures/dirs`; the first test
 * fails when a copy goes stale. The rest runs on the host `librovsti_ffi` that `:core-bridge:cargoBuildHost` builds
 * (skipped, with a message, when it is absent). Phone keeps no `sim-choices.json`: that is the Messages legacy file.
 */
class PhoneMigrationTest {
    private val files =
        listOf(
            "phone/prefs.json",
            "phone/call-notes.json",
            "phone/wa-calls.json",
            "phone/wa-calls-forgotten.json",
            "wa/whatsapp-link.json",
        )

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/migration/$path")) { "missing $path" }.readBytes()

    @Test fun theCopiedFixturesEqualTheRustOnes() {
        val dirs = File(System.getProperty("rovsti.rustFixtureDirs")!!)
        for (path in files) {
            assertTrue("$path is stale: copy it from $dirs", File(dirs, path).readBytes().contentEquals(resource(path)))
        }
    }

    @Test fun prefsNotesTheWhatsAppHistoryAndTheLinkLoad() =
        runBlocking {
            val lib = File(System.getProperty("rovsti.hostLib").orEmpty())
            assumeTrue(
                "host librovsti_ffi not built (run :core-bridge:cargoBuildHost)",
                lib.isDirectory && lib.listFiles().orEmpty().any { it.name.contains("rovsti_ffi") },
            )
            val root = Files.createTempDirectory("rovsti-phone-migration").toFile().apply { deleteOnExit() }
            // noBackupFilesDir of the installed app: the old and the new app share the files, and so does the link.
            val data = File(root, "no_backup").apply { mkdirs() }
            for (name in files) File(data, name.substringAfter('/')).writeBytes(resource(name))
            val started =
                Rust
                    .init(
                        File(root, "rust-data"),
                        File(root, "cache"),
                        "dev.rovsti.phone",
                        35,
                        CoroutineScope(Dispatchers.Default),
                    ).await()
            assertTrue("Rust.init: $started", started is Outcome.Ok)
            val dir = data.absolutePath
            val ops = NativePhoneOps(Rust.core())

            // prefs.json (schema 2): the quick reply and speed dial 2 of the old app are kept.
            val prefs = ok(ops.prefsLoad(DirRequest(dir)))
            assertEquals("current", prefs.origin)
            assertEquals(listOf("Can't talk now, I'll call back"), prefs.prefs.quickReplies)
            assertEquals(setOf(2), prefs.prefs.speedDial.keys)
            assertEquals(
                "Ayşe Yılmaz",
                prefs.prefs.speedDial
                    .getValue(2)
                    .name,
            )
            assertEquals(
                "k1",
                prefs.prefs.speedDial
                    .getValue(2)
                    .lookupKey,
            )
            // And it survives a write by the new app.
            ok(ops.prefsSave(PrefsSaveRequest(dir, prefs.prefs)))
            assertEquals(prefs.prefs, ok(ops.prefsLoad(DirRequest(dir))).prefs)

            // call-notes.json
            val notes = ok(ops.notesLoad(DirRequest(dir))).notes
            assertEquals(listOf("call back", "ask about the invoice"), notes.notes.map { it.text })
            assertEquals("+905551112233", notes.notes.first().number)

            // wa-calls.json: newest first, one missed call; the deleted id stays deleted (the Rust loader test pins the
            // rest; the data layer of the next step reads these records).
            val history =
                Json
                    .parseToJsonElement(
                        File(data, "wa-calls.json").readText(),
                    ).jsonArray
                    .map { it.jsonObject }
            assertEquals(listOf("b", "a"), history.map { it.getValue("call_id").jsonPrimitive.content })
            assertEquals(
                listOf(false, true),
                history.map {
                    it
                        .getValue("missed")
                        .jsonPrimitive.content
                        .toBoolean()
                },
            )
            val forgotten = Json.parseToJsonElement(File(data, "wa-calls-forgotten.json").readText()).jsonArray
            assertEquals(listOf("c"), forgotten.map { it.jsonPrimitive.content })

            // whatsapp-link.json: shared with the other apps.
            val link = ok(WaLinkStore(NativeWaOps(Rust.core()), dir).read())
            assertNotNull("the link written by the GPUI app is found", link)
            assertEquals("https://wa.example.com", link!!.gatewayUrl)
            assertEquals("inst1", link.instanceId)
        }

    private fun <T> ok(outcome: Outcome<T>): T = (outcome as? Outcome.Ok)?.value ?: error("failed: $outcome")
}
