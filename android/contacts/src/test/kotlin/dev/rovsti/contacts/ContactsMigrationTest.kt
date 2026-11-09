package dev.rovsti.contacts

import dev.rovsti.core.Outcome
import dev.rovsti.core.Rust
import dev.rovsti.core.model.contacts.DirRequest
import dev.rovsti.core.model.contacts.TrashListRequest
import dev.rovsti.core.model.phone.SortBy
import dev.rovsti.core.model.search.NameFormat
import dev.rovsti.core.ops.NativeContactsOps
import dev.rovsti.core.ops.NativeWaOps
import dev.rovsti.wa.WaLinkStore
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
 * E4.1 migration: the files the GPUI Contacts app wrote (`contacts-prefs.json`, `contacts-trash.json`) and the shared
 * `whatsapp-link.json` are read by the new app's Rust core unchanged. The fixtures in `src/test/resources/migration`
 * are copies of `crates/rovsti_app_core/tests/fixtures/dirs`; the first test fails when a copy goes stale. The rest runs
 * on the host `librovsti_ffi` that `:core-bridge:cargoBuildHost` builds (skipped, with a message, when it is absent).
 */
class ContactsMigrationTest {
    private val now = 1_789_600_000_000L

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/migration/$path")) { "missing $path" }.readBytes()

    @Test fun theCopiedFixturesEqualTheRustOnes() {
        val dirs = File(System.getProperty("rovsti.rustFixtureDirs")!!)
        for (path in listOf("contacts/contacts-prefs.json", "contacts/contacts-trash.json", "wa/whatsapp-link.json")) {
            assertTrue("$path is stale: copy it from $dirs", File(dirs, path).readBytes().contentEquals(resource(path)))
        }
    }

    @Test fun prefsTrashAndTheWhatsAppLinkLoad() =
        runBlocking {
            val lib = File(System.getProperty("rovsti.hostLib").orEmpty())
            assumeTrue(
                "host librovsti_ffi not built (run :core-bridge:cargoBuildHost)",
                lib.isDirectory && lib.listFiles().orEmpty().any { it.name.contains("rovsti_ffi") },
            )
            val root = Files.createTempDirectory("rovsti-contacts-migration").toFile().apply { deleteOnExit() }
            // noBackupFilesDir of the installed app: the old and the new app share the files, and so does the link.
            val data = File(root, "no_backup").apply { mkdirs() }
            for (name in listOf(
                "contacts/contacts-prefs.json",
                "contacts/contacts-trash.json",
                "wa/whatsapp-link.json",
            )) {
                File(data, name.substringAfter('/')).writeBytes(resource(name))
            }
            val started =
                Rust
                    .init(
                        File(root, "rust-data"),
                        File(root, "cache"),
                        "dev.rovsti.contacts",
                        35,
                        CoroutineScope(Dispatchers.Default),
                    ).await()
            assertTrue("Rust.init: $started", started is Outcome.Ok)
            val dir = data.absolutePath

            val ops = NativeContactsOps(Rust.core())
            val prefs = ok(ops.prefsLoad(DirRequest(dir)))
            assertEquals(NameFormat.LastFirst, prefs.prefs.nameFormat)
            assertEquals(SortBy.LastName, prefs.prefs.sortBy)
            assertEquals("com.google|ayse@example.org", prefs.prefs.saveTo)
            assertEquals(true, prefs.prefs.birthdayReminders)
            assertEquals(8, prefs.reminderHour)
            assertEquals("blank and repeated keys are dropped", listOf("k-zeynep"), prefs.favoritesOrder)
            assertEquals("k-ayse", prefs.prefs.myCard)
            assertEquals(listOf("k-mehmet"), prefs.emergency)
            assertEquals(listOf("k-cem"), prefs.prefs.viewed)

            val trash = ok(ops.trashList(TrashListRequest(dir, now)))
            assertEquals(listOf(1L, 2L), trash.entries.map { it.key })
            assertEquals(
                "Cem",
                trash.entries
                    .first()
                    .draft.given,
            )
            assertEquals("Deleted today", trash.entries.first().leftText)

            val link = ok(WaLinkStore(NativeWaOps(Rust.core()), dir).read())
            assertNotNull("the link written by the GPUI app is found", link)
            assertEquals("https://wa.example.com", link!!.gatewayUrl)
            assertEquals("inst1", link.instanceId)
        }

    private fun <T> ok(outcome: Outcome<T>): T = (outcome as? Outcome.Ok)?.value ?: error("failed: $outcome")
}
