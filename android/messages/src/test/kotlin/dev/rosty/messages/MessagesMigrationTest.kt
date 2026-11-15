package dev.rosty.messages

import dev.rosty.core.Outcome
import dev.rosty.core.Rust
import dev.rosty.core.model.messages.DirRequest
import dev.rosty.core.model.messages.PrefsSaveRequest
import dev.rosty.core.model.messages.ScheduleLoadRequest
import dev.rosty.core.model.messages.VoiceSpeed
import dev.rosty.core.ops.NativeMessagesOps
import dev.rosty.core.ops.NativeWaOps
import dev.rosty.wa.WaLinkStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * E6.1 AC2 migration: the files the GPUI Messages app wrote (`messages-prefs.json`, `drafts.json`, `scheduled.json`) and
 * the shared `whatsapp-link.json` are read by the new app's Rust core unchanged. The fixtures in
 * `src/test/resources/migration` are copies of `crates/rosty_app_core/tests/fixtures/dirs` (the first test fails when a
 * copy goes stale), except `scheduled.json`, which the Rust core keeps as an opaque string the app stores (`schedule.*`
 * is stateless). The rest runs on the host `librosty_ffi` that `:core-bridge:cargoBuildHost` builds.
 */
class MessagesMigrationTest {
    private val rustCopies =
        listOf(
            "messages/messages-prefs.json",
            "messages/drafts.json",
            "wa/whatsapp-link.json",
        )
    private val files = rustCopies + "messages/scheduled.json"

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/migration/$path")) { "missing $path" }.readBytes()

    @Test fun theCopiedFixturesEqualTheRustOnes() {
        val dirs = File(System.getProperty("rosty.rustFixtureDirs")!!)
        for (path in rustCopies) {
            assertTrue("$path is stale: copy it from $dirs", File(dirs, path).readBytes().contentEquals(resource(path)))
        }
    }

    @Test fun prefsDraftsTheScheduleAndTheLinkLoad() =
        runBlocking {
            val lib = File(System.getProperty("rosty.hostLib").orEmpty())
            assumeTrue(
                "host librosty_ffi not built (run :core-bridge:cargoBuildHost)",
                lib.isDirectory && lib.listFiles().orEmpty().any { it.name.contains("rosty_ffi") },
            )
            val root = Files.createTempDirectory("rosty-messages-migration").toFile().apply { deleteOnExit() }
            // noBackupFilesDir of the installed app: the old and the new app share the files, and so does the link.
            val data = File(root, "no_backup").apply { mkdirs() }
            for (name in files) File(data, name.substringAfter('/')).writeBytes(resource(name))
            val started =
                Rust
                    .init(
                        File(root, "rust-data"),
                        File(root, "cache"),
                        "dev.rosty.messages",
                        35,
                        CoroutineScope(Dispatchers.Default),
                    ).await()
            assertTrue("Rust.init: $started", started is Outcome.Ok)
            val dir = data.absolutePath
            val ops = NativeMessagesOps(Rust.core())

            // messages-prefs.json: the spam rules, the SIM of thread 7, the mute and the quick replies are kept.
            val prefs = ok(ops.prefsLoad(DirRequest(dir))).prefs
            assertTrue(prefs.spam.enabled)
            assertEquals(3, prefs.spam.rules.size)
            assertEquals(mapOf(7L to 2), prefs.simChoices)
            assertEquals(listOf("sms:7"), prefs.muted)
            assertEquals(5, prefs.quickReplies.size)
            assertEquals(VoiceSpeed.X2, prefs.voiceSpeed)
            // And it survives a write by the new app.
            ok(ops.prefsSave(PrefsSaveRequest(dir, prefs)))
            assertEquals(prefs, ok(ops.prefsLoad(DirRequest(dir))).prefs)

            // drafts.json: the two real drafts stay; the malformed key and the blank draft are dropped by the loader.
            val drafts = ok(ops.draftsLoad(DirRequest(dir))).drafts
            assertEquals("On my way", drafts["sms:7"])
            assertEquals("Hi there", drafts["wa:905321234567@s.whatsapp.net"])
            assertTrue("nonsense" !in drafts)

            // scheduled.json: the queue the app keeps as a string.
            val queue = ok(ops.scheduleLoad(ScheduleLoadRequest(File(data, "scheduled.json").readText())))
            assertEquals(listOf("Happy birthday"), queue.items.map { it.body })

            // whatsapp-link.json: shared with the other apps.
            val link = ok(WaLinkStore(NativeWaOps(Rust.core()), dir).read())
            assertNotNull("the link written by the GPUI app is found", link)
            assertEquals("https://wa.example.com", link!!.gatewayUrl)
            assertEquals("inst1", link.instanceId)
        }

    private fun <T> ok(outcome: Outcome<T>): T = (outcome as? Outcome.Ok)?.value ?: error("failed: $outcome")
}
