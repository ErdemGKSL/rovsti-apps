package dev.rosty.wa.share

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.rosty.platform.vault.VaultAlias
import dev.rosty.platform.vault.VaultKeyKind
import dev.rosty.platform.vault.VaultKeystore
import java.io.File

/**
 * "Lock now" in one Rosty app locks WhatsApp in all of them (round 7a). Since a sibling whose vault is unlocked opens
 * this app's vault with no prompt ([WaLinkShare], `vault.unlock_link`), and since round 7c every app reopens its own
 * vault with its stay wrap, a "Lock now" that left the siblings alone would be undone at the next start. So the app
 * that locks tells the others:
 *
 * - [send] broadcasts [ACTION] to each sibling package, receivable only by apps that hold [WaLinkShare.PERMISSION]
 *   (signature);
 * - each app declares [WaLockAllReceiver] in its manifest with that permission (round 7c; before, a receiver
 *   registered at run time heard it, so an app whose process was not running kept what it had). A running app locks
 *   ([WaLockAllHost]); an app that is not running deletes its stay wrap ([forgetStay]) without starting the core.
 *
 * The intent carries nothing: no key, no instance.
 */
object WaLockAll {
    const val ACTION = "dev.rosty.action.WA_LOCK_ALL"

    /** `rosty_app_core::wa_vault`'s files, in the data directory given to `Rust.init` (`noBackupFilesDir`). */
    const val VAULT_FILE = "wa-vault.json"
    const val STAY_FILE = "wa-vault-stay.json"

    /** Tells every sibling of [own] to lock WhatsApp now. */
    fun send(
        context: Context,
        own: String = context.packageName,
    ) {
        for (sibling in WaLinkShare.siblingsOf(own)) {
            try {
                context.sendBroadcast(Intent(ACTION).setPackage(sibling), WaLinkShare.PERMISSION)
            } catch (_: RuntimeException) {
                // A sibling that is not installed or refuses: nothing of it is unlocked by this app either.
            }
        }
    }

    /**
     * Ends the silent unlock of the vault in [dataDir] with no core: the stay file (and its temp file) and every K_stay
     * key go. The next unlock needs the biometric, a sibling or the passphrase, and seals a new stay wrap.
     */
    fun forgetStay(
        dataDir: File,
        keystore: VaultKeystore,
    ) {
        File(dataDir, STAY_FILE).delete()
        File(dataDir, "$STAY_FILE.tmp").delete()
        for (alias in keystore.aliases()) if (VaultAlias.kindOf(alias) == VaultKeyKind.Stay) keystore.delete(alias)
    }
}

/** The app whose `Application` hears a sibling's "Lock now". `:shell`'s `HasContainer` implements it. */
interface WaLockAllHost {
    /** Locks WhatsApp in this app (a running vault) or drops its stay wrap (no core started). Must not block. */
    fun lockWhatsAppForSibling()
}

/**
 * Hears [WaLockAll.ACTION] from a sibling Rosty app. Each app declares it with `android:exported="true"` and
 * `android:permission="dev.rosty.permission.WA_LINK"` (signature), so only a Rosty app signed like this one can send it;
 * `:wa`'s library manifest declares nothing (ARCH 7.2).
 */
class WaLockAllReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != WaLockAll.ACTION) return
        (context.applicationContext as? WaLockAllHost)?.lockWhatsAppForSibling()
    }
}
