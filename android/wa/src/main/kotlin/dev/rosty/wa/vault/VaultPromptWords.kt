package dev.rosty.wa.vault

import android.content.res.Resources
import dev.rosty.platform.vault.VaultPrompt
import dev.rosty.wa.R

/** The system prompts of the vault in the phone's language (en, tr). */
fun vaultPrompts(res: Resources): VaultPrompts {
    val confirm = res.getString(R.string.vault_prompt_confirm)
    val passphrase = res.getString(R.string.vault_use_passphrase)
    val cancel = res.getString(R.string.vault_cancel)
    return VaultPrompts(
        protect = VaultPrompt(res.getString(R.string.vault_prompt_protect), confirm, cancel),
        unlock = VaultPrompt(res.getString(R.string.vault_prompt_unlock), confirm, cancel),
        daily =
            VaultPrompt(
                res.getString(R.string.vault_prompt_unlock),
                res.getString(R.string.vault_prompt_biometric),
                passphrase,
            ),
        enableDaily =
            VaultPrompt(
                res.getString(R.string.vault_prompt_daily_title),
                res.getString(R.string.vault_prompt_daily_subtitle),
                res.getString(R.string.vault_prompt_not_now),
            ),
    )
}
