package dev.rosty.platform.vault

import java.util.Base64

/**
 * A [VaultKeystore] for JVM tests: no hardware and no prompt. A key is a name in [keys]; sealing writes
 * `sealed:<alias>:<plain base64>` (base64 again), so opening with another alias fails as with real keys. [next] decides
 * what the next prompt answers (then it goes back to [VaultCrypto.Done]); [failedAttempts] wrong fingers are reported
 * before it. K_stay (round 7c) shows no prompt: [stayNext] decides what its next seal or open answers, and a locked
 * [device] answers [VaultCrypto.DeviceLocked].
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

    /** What the next K_stay seal or open answers instead of success (no prompt). */
    var stayNext: VaultCrypto? = null

    /** K_stay seals and opens done, in order (`seal`, `open`). */
    val stayOps = mutableListOf<String>()

    private var counter = 0

    override fun device(): VaultDevice = device

    override fun newAlias(kind: VaultKeyKind): String {
        counter += 1
        return when (kind) {
            VaultKeyKind.Hardware -> VaultAlias.hardware("t$counter")
            VaultKeyKind.Daily -> VaultAlias.daily(VaultAlias.dailyAuth(device), "t$counter")
            VaultKeyKind.Stay -> VaultAlias.stay("t$counter")
        }
    }

    private fun stay(
        op: String,
        ok: () -> VaultCrypto,
    ): VaultCrypto {
        stayOps += op
        val forced = stayNext
        stayNext = null
        return when {
            forced != null -> forced
            device.locked -> VaultCrypto.DeviceLocked
            else -> ok()
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
        if (VaultAlias.kindOf(alias) == VaultKeyKind.Stay) {
            return stay("seal") {
                keys += alias
                VaultCrypto.Done(encode("sealed:$alias:$plainB64"))
            }
        }
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
        if (VaultAlias.kindOf(alias) == VaultKeyKind.Stay) {
            if (!device.secureLockScreen) return VaultCrypto.Invalidated
            return stay("open") { opened(alias, sealedB64) }
        }
        return answer(prompt, onFailedAttempt) { opened(alias, sealedB64) }
    }

    private fun opened(
        alias: String,
        sealedB64: String,
    ): VaultCrypto {
        val text = decode(sealedB64)
        val head = "sealed:$alias:"
        return if (text?.startsWith(head) == true) {
            VaultCrypto.Done(text.removePrefix(head))
        } else {
            VaultCrypto.Failed("damaged")
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
