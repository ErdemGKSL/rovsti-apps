package dev.rosty.shell

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The WhatsApp vault's part of an app's `BootReceiver` (`plans/wa-vault.md` 13.2): after a reboot the passphrase is
 * needed again, so the daily blob and its K_daily key go. The vault's own boot-count check does the same at the next
 * unlock; this does not wait for it. Nothing to do (and no core started) when the app never made a vault.
 */
object VaultBoot {
    /** `rosty_app_core::wa_vault`'s daily file, in the data directory given to `Rust.init`. */
    const val DAILY_FILE = "wa-vault-daily.json"

    private const val BOOT_WORK_MS = 8_000L

    /** Blocks (a receiver's worker thread) for at most a few seconds. */
    fun dropDailyUnlock(app: Context) {
        if (!File(app.noBackupFilesDir, DAILY_FILE).exists()) return
        val container = (app.applicationContext as? HasContainer<*>)?.container ?: return
        runBlocking { withTimeoutOrNull(BOOT_WORK_MS) { container.waVault.onBoot() } }
    }
}
