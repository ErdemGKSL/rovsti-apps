package dev.rosty.wa.vault

import android.content.Context
import dev.rosty.core.FakeVaultOps
import dev.rosty.core.RustError
import dev.rosty.core.model.wa.VaultLink
import dev.rosty.core.model.wa.VaultWipeRequest
import dev.rosty.platform.PlatformPrefs
import dev.rosty.platform.notify.MessageReminders
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The upgrade migration (`plans/wa-vault.md` 8, round 6) on the JVM: when it runs for each vault state, that the key
 * is never wiped before a vault holds it, that it runs once, and that the Kotlin leftovers go while everything else of
 * the app stays. Rust's own inventory (the files `vault.wipe_plaintext` deletes) is tested in `rosty_app_core`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaPlaintextMigrationTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val link = VaultLink("https://gw.example", "inst1", "rwk_" + "a".repeat(40))
    private val ops = FakeVaultOps()
    private val mark = MemoryWaMigrationMark()
    private var leftoverRuns = 0
    private var identityWaits = false
    private var leftoversDone = true

    private val migration =
        WaPlaintextMigration(
            ops = ops,
            request = { VaultWipeRequest(filesDir = context.filesDir.absolutePath) },
            mark = mark,
            leftovers = {
                leftoverRuns++
                WaLegacyLeftovers.drop(context) && leftoversDone
            },
            movesAtUnlock = { identityWaits },
        )

    private fun wipes() = ops.calls.count { it == "vault.wipe_plaintext" }

    @Test fun anOpenVaultNoLinkOrABrokenVaultMigrateAtOnce() =
        runBlocking {
            for (state in listOf(WaVaultState.Unlocked, WaVaultState.NoLink, WaVaultState.Broken)) {
                mark.done = false
                assertTrue(state.toString(), migration.run(state) is WaMigrationResult.Done)
                assertTrue(mark.done)
            }
            assertEquals(3, wipes())
        }

    @Test fun aLinkWaitingForItsVaultIsNeverWiped() =
        runBlocking {
            assertEquals(WaMigrationResult.Waiting, migration.run(WaVaultState.NeedsSetup))
            assertEquals(WaMigrationResult.Waiting, migration.run(WaVaultState.Checking))
            assertEquals("not even asked", 0, wipes())
            // Rust refuses too while `whatsapp-link.json` has no vault (a state read before the file appeared).
            ops.legacyLink = link
            assertEquals(WaMigrationResult.Waiting, migration.run(WaVaultState.NoLink))
            assertTrue(ops.legacyLink != null)
            assertFalse(mark.done)
        }

    @Test fun aLockedVaultWaitsOnlyForAFileThatMovesIntoIt() =
        runBlocking {
            identityWaits = true
            assertEquals(WaMigrationResult.Waiting, migration.run(WaVaultState.Locked(biometric = true)))
            assertEquals(0, wipes())
            assertTrue(migration.run(WaVaultState.Unlocked) is WaMigrationResult.Done)

            mark.done = false
            identityWaits = false
            assertTrue(migration.run(WaVaultState.Locked(biometric = false)) is WaMigrationResult.Done)
        }

    @Test fun itRunsOnceAndThenOnlyReadsTheMark() =
        runBlocking {
            assertTrue(migration.run(WaVaultState.Unlocked) is WaMigrationResult.Done)
            val calls = ops.calls.size
            assertEquals(WaMigrationResult.NotDue, migration.run(WaVaultState.Unlocked))
            assertEquals(WaMigrationResult.NotDue, migration.run(WaVaultState.NoLink))
            assertEquals(calls, ops.calls.size)
            assertEquals(1, leftoverRuns)
            assertFalse(migration.due)
        }

    @Test fun aFailureLeavesTheMarkForTheNextStart() =
        runBlocking {
            ops.failure = RustError.Io("disk")
            assertEquals(WaMigrationResult.Incomplete, migration.run(WaVaultState.Unlocked))
            assertFalse(mark.done)
            ops.failure = null
            assertTrue(migration.run(WaVaultState.Unlocked) is WaMigrationResult.Done)
        }

    @Test fun leftoversThatCouldNotBeDroppedAreTriedAgain() =
        runBlocking {
            leftoversDone = false
            assertEquals(WaMigrationResult.Incomplete, migration.run(WaVaultState.Unlocked))
            assertFalse(mark.done)
            leftoversDone = true
            assertTrue(migration.run(WaVaultState.Unlocked) is WaMigrationResult.Done)
            assertEquals(2, leftoverRuns)
        }

    @Test fun theLeftoversGoAndNothingElseIsTouched() =
        runBlocking {
            // `wa-link-share`: the one-way mark of the removed automatic hand-over (a key hash) and the current marks.
            val share = context.getSharedPreferences(WaLegacyLeftovers.LINK_SHARE_PREFS, 0)
            share
                .edit()
                .putString(WaLegacyLeftovers.DECLINED, "9f86d081884c7d65")
                .putString("adopted_instance", "inst1")
                .putString("adopted_from", "dev.rosty.messages")
                .commit()

            // A WhatsApp reminder of an earlier build with the message text, and an SMS one.
            fun reminder(
                id: Long,
                tag: String,
                text: String,
            ) = JSONObject()
                .put("id", id)
                .put("tag", tag)
                .put("message_id", "m$id")
                .put("at_ms", 1_900_000_000_000 + id)
                .put("preview", text)
                .put("notif_title", "Reminder: Ada")
                .put("notif_body", text)
                .toString()
            assertTrue(MessageReminders.arm(context, reminder(1, "wa:100000000001@s.whatsapp.net", "the door code")))
            assertTrue(MessageReminders.arm(context, reminder(2, "sms:7", "bring the keys")))
            // The full-size WhatsApp pictures, and caches and files that are not WhatsApp data.
            val full = File(context.cacheDir, WaLegacyLeftovers.FULL_PICTURES_DIR).apply { mkdirs() }
            File(full, "100000000001~p1.jpg").writeText("jpeg")
            val kept =
                listOf(
                    File(context.cacheDir, "contact-photos/1.jpg"),
                    File(context.filesDir, "people/100000000001.jpg"),
                    File(context.filesDir, "people.json"),
                    File(context.noBackupFilesDir, "wa-vault.json"),
                    File(context.noBackupFilesDir, "messages-prefs.json"),
                ).onEach {
                    it.parentFile?.mkdirs()
                    it.writeText("keep")
                }
            val other = context.getSharedPreferences(PlatformPrefs.CALL_REMINDERS, 0)
            other.edit().putString("armed", "[]").commit()

            assertTrue(migration.run(WaVaultState.Unlocked) is WaMigrationResult.Done)

            assertFalse(share.contains(WaLegacyLeftovers.DECLINED))
            assertEquals("inst1", share.getString("adopted_instance", null))
            assertEquals("dev.rosty.messages", share.getString("adopted_from", null))
            val armed =
                context.getSharedPreferences(PlatformPrefs.MESSAGE_REMINDERS, 0).getString("armed", "").orEmpty()
            assertFalse("the WhatsApp text is gone", "the door code" in armed)
            assertTrue("the SMS reminder keeps its text", "bring the keys" in armed)
            assertEquals(setOf(1L, 2L), MessageReminders.armed(context).map { it.id }.toSet())
            assertFalse(full.exists())
            for (file in kept) assertEquals(file.path, "keep", file.readText())
            assertEquals("[]", other.getString("armed", null))
        }

    @Test fun theLeftoversWriteNothingWhenThereIsNothing() {
        assertTrue(WaLegacyLeftovers.drop(context))
        assertFalse(
            context.getSharedPreferences(WaLegacyLeftovers.LINK_SHARE_PREFS, 0).contains(WaLegacyLeftovers.DECLINED),
        )
        assertTrue(context.getSharedPreferences(PlatformPrefs.MESSAGE_REMINDERS, 0).all.isEmpty())
        assertFalse(File(context.cacheDir, WaLegacyLeftovers.FULL_PICTURES_DIR).exists())
    }
}
