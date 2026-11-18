package dev.rosty.wa.vault

import android.content.Context
import dev.rosty.core.Outcome
import dev.rosty.core.log.RostyLog
import dev.rosty.core.model.wa.VaultWipeReport
import dev.rosty.core.model.wa.VaultWipeRequest
import dev.rosty.core.ops.VaultOps
import dev.rosty.core.ops.vaultCode
import dev.rosty.platform.notify.MessageReminders
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Whether this app finished the plaintext migration (`plans/wa-vault.md` 8, round 6). No secret. */
interface WaMigrationMark {
    var done: Boolean
}

class MemoryWaMigrationMark(
    override var done: Boolean = false,
) : WaMigrationMark

/** [WaMigrationMark] in the app's `rosty_wa_migration` preferences. */
class SharedWaMigrationMark(
    context: Context,
) : WaMigrationMark {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    override var done: Boolean
        get() = prefs.getBoolean(KEY_DONE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DONE, value).apply()
        }

    companion object {
        const val NAME = "rosty_wa_migration"
        private const val KEY_DONE = "plaintext_wiped"
    }
}

/** What one [WaPlaintextMigration.run] did. */
sealed interface WaMigrationResult {
    /** Done earlier: nothing was read or touched. */
    data object NotDue : WaMigrationResult

    /** Not yet: the link has no vault yet, or a plaintext file must first move into the unlocked vault. */
    data object Waiting : WaMigrationResult

    /** Every plaintext file is gone; the mark is set. */
    data class Done(
        val report: VaultWipeReport,
    ) : WaMigrationResult

    /** Something could not be deleted (or the core did not answer); the next start tries again. */
    data object Incomplete : WaMigrationResult
}

/**
 * The upgrade migration (`plans/wa-vault.md` 8, round 6): once per app, the plaintext WhatsApp data an earlier build
 * left goes. Rust deletes its files (`vault.wipe_plaintext`: the offline store erased in place, `whatsapp-link.json`,
 * the names, rekeys and identity files, the `wa:` drafts, the media, Status and avatar folders); [leftovers] drops
 * what Rust cannot edit (preferences, the app's own files: [WaLegacyLeftovers] and the app's part).
 *
 * When ([run] with the vault's state):
 * - unlocked, no link, or a broken vault: now;
 * - locked: now, unless a plaintext file still waits to move into the vault cache ([movesAtUnlock], Phone's
 *   `wa-identity.json`): then at the next unlock;
 * - a link that waits for its vault (the setup, from `whatsapp-link.json` or a new link) or not read yet: later.
 *   Rust also refuses (`no_vault`) while `whatsapp-link.json` has no vault: the key is never lost, the setup reads it
 *   first. A person who never sets the vault up keeps that file until they do, or until "Forgot passphrase" / Unlink.
 *
 * Idempotent and cheap: after one complete run [mark] is set and a run only reads it. A run with a failure leaves the
 * mark unset and the next start tries again. Nothing of another kind of data is touched (SMS, people, settings).
 */
class WaPlaintextMigration(
    private val ops: VaultOps,
    private val request: () -> VaultWipeRequest,
    private val mark: WaMigrationMark,
    /** Drops the leftovers Rust cannot edit; false when something could not be dropped (the next start tries again). */
    private val leftovers: suspend () -> Boolean,
    /** True while a plaintext file must first move into the vault (needs DK): wait for an unlock. */
    private val movesAtUnlock: () -> Boolean = { false },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    /** False once the migration finished in this app. */
    val due: Boolean get() = !mark.done

    suspend fun run(state: WaVaultState): WaMigrationResult =
        mutex.withLock {
            if (mark.done) return@withLock WaMigrationResult.NotDue
            val now =
                when (state) {
                    WaVaultState.Unlocked, WaVaultState.NoLink, WaVaultState.Broken -> true
                    is WaVaultState.Locked -> !withContext(io) { movesAtUnlock() }
                    WaVaultState.NeedsSetup, WaVaultState.Checking -> false
                }
            if (!now) return@withLock WaMigrationResult.Waiting
            val kotlinDone = leftovers()
            when (val wiped = ops.wipePlaintext(request())) {
                is Outcome.Err -> {
                    if (wiped.error.vaultCode == NO_VAULT) {
                        WaMigrationResult.Waiting
                    } else {
                        RostyLog.w(TAG, "plaintext wipe failed: ${wiped.error}")
                        WaMigrationResult.Incomplete
                    }
                }

                is Outcome.Ok -> {
                    val report = wiped.value
                    RostyLog.event(
                        TAG,
                        "plaintext_wiped",
                        "deleted" to report.deleted.size,
                        "scrubbed" to report.scrubbed.size,
                        "drafts" to report.draftsRemoved,
                        "failed" to report.failed.size,
                        "kotlin" to kotlinDone,
                    )
                    if (report.failed.isEmpty() && kotlinDone) {
                        mark.done = true
                        WaMigrationResult.Done(report)
                    } else {
                        WaMigrationResult.Incomplete
                    }
                }
            }
        }

    companion object {
        const val TAG = "wa-vault"
        private const val NO_VAULT = "no_vault"

        /** Phone's identity table of an earlier build; the first unlock moves it into the vault cache. */
        const val IDENTITY_FILE = "wa-identity.json"
    }
}

/**
 * What an earlier build kept of WhatsApp outside the files `vault.wipe_plaintext` deletes, common to every app:
 * - the `declined` mark of the removed automatic hand-over (a SHA-256 of a connect key) in `wa-link-share`; the
 *   `adopted_*` entries stay (which app and instance a link came from, no key, still read);
 * - the message text of WhatsApp reminders in `rosty_message_reminders` (the reminders stay, naming the chat only);
 * - `cacheDir/wa-full`, the full-size WhatsApp pictures of the photo viewer (Phone, Contacts), which live only while
 *   the vault is open.
 * Not touched: `people.json`, `people-sync.json` and the people pictures (identity data the people provider hands
 * every app; plan round 5), `wa_calls_forgotten` (opaque gateway ids), call-back reminders, SMS data, settings.
 */
object WaLegacyLeftovers {
    const val LINK_SHARE_PREFS = "wa-link-share"
    const val DECLINED = "declined"
    const val FULL_PICTURES_DIR = "wa-full"

    /** Drops them; false when something could not be dropped. Safe to run again; writes nothing when nothing is left. */
    fun drop(context: Context): Boolean {
        val share = context.getSharedPreferences(LINK_SHARE_PREFS, Context.MODE_PRIVATE)
        val mark = !share.contains(DECLINED) || share.edit().remove(DECLINED).commit()
        MessageReminders.scrubWhatsAppText(context)
        val full = File(context.cacheDir, FULL_PICTURES_DIR)
        val pictures = !full.exists() || full.deleteRecursively()
        return mark && pictures
    }
}
