package dev.rosty.wa.vault

import dev.rosty.core.Outcome
import dev.rosty.core.model.wa.StoredLink
import dev.rosty.wa.WaRepository

/**
 * The WhatsApp settings' repository in every app with a vault (`plans/wa-vault.md` 5, 13.3): a new link (pasted, made
 * by the link flow, or another key or gateway) goes to [WaVault.adopt], never to `whatsapp-link.json`, and the vault's
 * setup follows. A vault that exists is reset first (it holds the old key); "Unlink" resets it too. Everything else is
 * [real]'s own (Messages passes the repository whose unlink also drops its offline store).
 */
class VaultSettingsRepository(
    private val real: WaRepository,
    private val vault: WaVault,
) : WaRepository by real {
    private fun hasVault(): Boolean =
        vault.state.value.let { it != WaVaultState.NoLink && it != WaVaultState.NeedsSetup }

    override suspend fun relinkTo(link: StoredLink): Outcome<Unit> {
        if (hasVault()) vault.reset()
        return vault.adopt(link)
    }

    override suspend fun forget(): Outcome<Unit> {
        if (!hasVault()) return real.forget()
        return if (vault.reset() == VaultOutcome.Done) Outcome.Ok(Unit) else real.forget()
    }
}
