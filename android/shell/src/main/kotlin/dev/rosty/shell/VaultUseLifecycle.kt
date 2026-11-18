package dev.rosty.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the app's activities tell the WhatsApp vault (round 7c: the vault stays unlocked). There is no auto-lock delay
 * and no screen-off lock any more; the only lock rules are "Lock now" and 90 days without a use.
 *
 * - [onActivityStarted]: the app is in use. When the app has a vault ([hasVault], a file check, so an app that never
 *   made one starts no core for it), [use] runs: an open vault moves its 90-day use clock on (Rust writes it at most
 *   once an hour), a locked one reopens with its stay wrap, with no prompt.
 * - [onActivityStopped]: when the last activity stops with WhatsApp open, [background] runs (Messages writes its
 *   history file then, round 7a). A system prompt over the app ([prompting]) is not the background.
 */
class VaultUseLifecycle(
    private val scope: CoroutineScope,
    private val hasVault: () -> Boolean,
    private val unlocked: () -> Boolean,
    private val prompting: () -> Boolean,
    private val use: suspend () -> Unit,
    private val background: suspend () -> Unit = {},
) {
    private var started = 0

    @Synchronized
    fun onActivityStarted() {
        started += 1
        if (hasVault()) scope.launch { use() }
    }

    @Synchronized
    fun onActivityStopped(changingConfigurations: Boolean) {
        if (changingConfigurations) return
        started = (started - 1).coerceAtLeast(0)
        if (started > 0 || prompting() || !unlocked()) return
        scope.launch { background() }
    }
}
