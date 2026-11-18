package dev.rosty.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The lock rules of the WhatsApp vault (plan 6): pure, tested on the JVM. */
object VaultLockRules {
    /** True when the app went to the background at [backgroundAtMs] and [timeoutMs] have passed since. */
    fun expired(
        backgroundAtMs: Long?,
        nowMs: Long,
        timeoutMs: Long,
    ): Boolean = backgroundAtMs != null && nowMs - backgroundAtMs >= timeoutMs.coerceAtLeast(0)
}

/**
 * When WhatsApp locks (plan 6): the screen turns off; the app spends [timeoutMs] in the background (a timer, and a check
 * when an activity starts again, because a cached process is frozen and its timer may never fire); [lockNow] (the
 * settings' "Lock now"); and process death, which needs nothing: the keys only ever lived in memory.
 *
 * The activities report [onActivityStarted] and [onActivityStopped]. While [prompting] (a system prompt, which may be
 * the screen-lock activity of the system covering the app) the background timer does not start.
 */
class VaultLockLifecycle(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val timeoutMs: () -> Long,
    private val unlocked: () -> Boolean,
    private val prompting: () -> Boolean,
    private val lock: suspend () -> Unit,
    /** The app went to the background with WhatsApp unlocked (round 7a: Messages writes its history file then). */
    private val background: suspend () -> Unit = {},
) {
    private var started = 0
    private var backgroundAt: Long? = null
    private var timer: Job? = null
    private var screenOff: BroadcastReceiver? = null

    /** Locks on `ACTION_SCREEN_OFF` from now on (a receiver registered at run time: the broadcast has no manifest form). */
    fun watchScreen(context: Context) {
        synchronized(this) {
            if (screenOff != null) return
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        if (intent.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
                    }
                }
            screenOff = receiver
            context.applicationContext.registerReceiver(
                receiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                Context.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    @Synchronized
    fun onActivityStarted() {
        started += 1
        timer?.cancel()
        timer = null
        val at = backgroundAt
        backgroundAt = null
        // The timer may not have run (a frozen process): the time away decides.
        if (VaultLockRules.expired(at, now(), timeoutMs()) && unlocked()) lockNow()
    }

    @Synchronized
    fun onActivityStopped(changingConfigurations: Boolean) {
        if (changingConfigurations) return
        started = (started - 1).coerceAtLeast(0)
        if (started > 0 || prompting() || !unlocked()) return
        val at = now()
        backgroundAt = at
        val wait = timeoutMs().coerceAtLeast(0)
        if (wait > 0) scope.launch { background() }
        timer?.cancel()
        timer =
            scope.launch {
                delay(wait)
                val due = synchronized(this@VaultLockLifecycle) { backgroundAt == at && started == 0 }
                if (due && unlocked()) lock()
            }
    }

    fun onScreenOff() {
        if (unlocked()) lockNow()
    }

    fun lockNow() {
        scope.launch { lock() }
    }
}
