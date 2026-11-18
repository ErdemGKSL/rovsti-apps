package dev.rosty.platform.vault

import java.util.Base64

/**
 * A [VaultKeystore] for JVM tests: no hardware and no prompt. A key is a name in [keys]; sealing writes
 * `sealed:<alias>:<plain base64>` (base64 again), so opening with another alias fails as with real keys. [next] decides
 * what the next prompt answers (then it goes back to [VaultCrypto.Done]); [failedAttempts] wrong fingers are reported
 * before it.
 */
class FakeVaultKeystore(
    var device: VaultDevice = VaultDevice(secureLockScreen = true, strongBiometric = true, strongBox = false),
) : VaultKeystore {
    val keys = linkedSetOf<String>()

    /** Every prompt shown, by title. */
    val prompts = mutableListOf<String>()

    /** What the next prompt answers instead of success. */
    var next: VaultCrypto? = null

    /** Wrong fingers reported before the next prompt answers. */
    var failedAttempts: Int = 0

    private var counter = 0

    override fun device(): VaultDevice = device

    override fun newAlias(kind: VaultKeyKind): String {
        counter += 1
        return when (kind) {
            VaultKeyKind.Hardware -> VaultAlias.hardware("t$counter")
            VaultKeyKind.Daily -> VaultAlias.daily(VaultAlias.dailyAuth(device), "t$counter")
        }
    }

    private fun answer(
        prompt: VaultPrompt,
        onFailedAttempt: () -> Unit,
        ok: () -> VaultCrypto,
    ): VaultCrypto {
        prompts += prompt.title
        repeat(failedAttempts) { onFailedAttempt() }
        failedAttempts = 0
        val forced = next
        next = null
        return forced ?: ok()
    }

    override suspend fun seal(
        alias: String,
        plainB64: String,
        prompt: VaultPrompt,
        onFailedAttempt: () -> Unit,
    ): VaultCrypto {
        if (!device.secureLockScreen) return VaultCrypto.NoCredential
        keys += alias
        return answer(prompt, onFailedAttempt) { VaultCrypto.Done(encode("sealed:$alias:$plainB64")) }
    }

    override suspend fun open(
        alias: String,
        sealedB64: String,
        prompt: VaultPrompt,
        onFailedAttempt: () -> Unit,
    ): VaultCrypto {
        if (alias !in keys) return VaultCrypto.Invalidated
        return answer(prompt, onFailedAttempt) {
            val text = decode(sealedB64)
            val head = "sealed:$alias:"
            if (text?.startsWith(head) ==
                true
            ) {
                VaultCrypto.Done(text.removePrefix(head))
            } else {
                VaultCrypto.Failed("damaged")
            }
        }
    }

    override fun delete(alias: String) {
        keys -= alias
    }

    override fun aliases(): List<String> = keys.toList()

    private fun encode(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun decode(b64: String): String? =
        try {
            String(Base64.getDecoder().decode(b64))
        } catch (_: IllegalArgumentException) {
            null
        }
}
