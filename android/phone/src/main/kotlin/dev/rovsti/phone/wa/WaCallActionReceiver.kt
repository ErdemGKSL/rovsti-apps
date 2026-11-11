package dev.rovsti.phone.wa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.rovsti.phone.PhoneContainer
import dev.rovsti.platform.wa.WaCallService
import dev.rovsti.platform.wa.WaIncoming
import dev.rovsti.shell.HasContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Answer and Decline of the incoming WhatsApp call notification and Hang up of the ongoing one. Broadcasts, not
 * activity intents, so they work from the lock screen without unlocking (the platform dialer does the same). The
 * action goes to `WaCallActions` (once per call, after `Rust.init`: a cold process woken by the FCM push gets here
 * before the core is up); Answer then opens the call screen over the keyguard (`WaIncoming.openAnswered`).
 *
 * The receiver keeps its process alive with `goAsync` until the gateway was told, for at most [BUDGET_MS].
 */
class WaCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // A receiver that throws kills the process.
        try {
            val app = context.applicationContext
            val container = (app as? HasContainer<*>)?.container as? PhoneContainer ?: return
            val actions = container.waActions
            val job =
                handle(
                    intent,
                    take = { WaIncoming.takeAction(app, it) },
                    submit = actions::submit,
                    hangUp = actions::hangUp,
                    open = { WaIncoming.openAnswered(app, it) },
                ) ?: return
            val pending = goAsync()
            container.scope.launch {
                try {
                    withTimeoutOrNull(BUDGET_MS) { job.join() }
                } finally {
                    pending.finish()
                }
            }
        } catch (e: RuntimeException) {
            Log.e("Rovsti", "WhatsApp call action failed", e)
        }
    }

    companion object {
        /** Under the ten seconds Android gives a receiver. */
        const val BUDGET_MS = 9_000L

        /**
         * Pure routing: an Answer or Decline is taken from [intent] ([take] also cancels the notification and stops the
         * ring), handed to [submit], and an Answer opens the call screen ([open]); a Hang up goes to [hangUp]. The job
         * that carries the action out, or null when there is nothing to do.
         */
        fun handle(
            intent: Intent,
            take: (Intent) -> String?,
            submit: (String) -> Job?,
            hangUp: (String) -> Job?,
            open: (String) -> Unit,
        ): Job? =
            when (intent.action) {
                WaIncoming.ACTION_CALL -> {
                    val id = intent.getStringExtra(WaIncoming.EXTRA_CALL_ID)
                    val answer = intent.getStringExtra(WaIncoming.EXTRA_ACTION) == WaIncoming.ANSWER
                    val payload = take(intent)
                    if (payload == null || id == null) {
                        null
                    } else {
                        val job = submit(payload)
                        if (answer) open(id)
                        job
                    }
                }

                WaCallService.ACTION_HANG_UP -> {
                    intent.getStringExtra(WaIncoming.EXTRA_CALL_ID)?.takeIf { it.isNotEmpty() }?.let(hangUp)
                }

                else -> {
                    null
                }
            }
    }
}
