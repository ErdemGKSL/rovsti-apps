package dev.rosty.platform.wa

import android.content.Context
import dev.rosty.platform.PlatformEvents
import dev.rosty.platform.identity.CallerLookup
import org.json.JSONObject
import java.util.concurrent.locks.LockSupport

/**
 * FCM wake for WhatsApp (AD-WA6, AD-WA9). Firebase is optional: the token is read by reflection so a host without
 * `google-services.json` still compiles and gets an empty token (the gateway then sends no push to this app).
 *
 * The app's `FirebaseMessagingService` calls [onMessage]; `:wa` collects the [EVENT] on `PlatformEvents` and calls
 * `WaEventStream.reconnectNow()` so the stream skips its backoff.
 *
 * Ported from crates/rosty_android/kotlin/dev/rosty/bridge/WaPush.kt
 */
object WaPush {
    private const val TOKEN_WAIT_NS = 3_000_000_000L
    private const val POLL_NS = 25_000_000L
    private const val MESSAGING = "com.google.firebase.messaging.FirebaseMessaging"

    /** The `PlatformEvents` kind that means "the gateway has news: reconnect now". */
    const val EVENT = "wa_push"

    /**
     * The phone number behind a `@lid` caller, from the app's own identity table (WA-R2); the app that keeps one
     * installs it. Called off the main thread and may block briefly; null (the default) for a LID nobody mapped.
     */
    @Volatile var lidNumber: (String) -> String? = { null }

    /**
     * Whether the app's WhatsApp vault is locked now (`plans/wa-vault.md` 13.3); the app with a vault installs it (a
     * cold process is locked: the keys only live in memory). While locked a call shows from the push alone and
     * content-free ([WaIncomingNotice.contentFree]): no name, no number, no lookup, no gateway request, and no wake of
     * the event stream. False (the default) for an app without a vault.
     */
    @Volatile var locked: () -> Boolean = { false }

    /** The FCM token, or "" when Firebase is not packaged or the token is not ready. Blocks up to 3 s: not the main thread. */
    fun token(): String =
        try {
            val messaging = Class.forName(MESSAGING).getMethod("getInstance").invoke(null)
            val task = messaging.javaClass.getMethod("getToken").invoke(messaging)
            // Reflection keeps Play Services off the compile classpath; poll the finished Task.
            val done = task.javaClass.getMethod("isComplete")
            val ok = task.javaClass.getMethod("isSuccessful")
            val result = task.javaClass.getMethod("getResult")
            val deadline = System.nanoTime() + TOKEN_WAIT_NS
            while (!(done.invoke(task) as Boolean) && System.nanoTime() < deadline) LockSupport.parkNanos(POLL_NS)
            if (done.invoke(task) as Boolean &&
                ok.invoke(task) as Boolean
            ) {
                (result.invoke(task) as? String).orEmpty()
            } else {
                ""
            }
        } catch (_: ReflectiveOperationException) {
            ""
        } catch (_: RuntimeException) {
            ""
        }

    /** Pure: the JSON for an FCM data message. */
    fun payload(data: Map<String, String>): String = JSONObject(data as Map<*, *>).toString()

    /**
     * Call from the host's `FirebaseMessagingService.onMessageReceived` with `remoteMessage.data`. A `kind=call`
     * message shows the incoming-call notification first (the process may have no UI), then a [EVENT] is emitted.
     * A `kind=call_end` message cancels the ringing notification and, when `missed=1`, posts a missed-call notification.
     */
    fun onMessage(
        context: Context,
        data: Map<String, String>,
    ) {
        val app = context.applicationContext
        val vaultLocked =
            try {
                locked()
            } catch (_: RuntimeException) {
                true
            }
        if (vaultLocked) {
            onLockedMessage(app, data)
            return
        }
        // The app's caller-ID when it installed one, else (a cold process) Android's own PhoneLookup (F28, F39).
        val lookup = CallerLookup.of(app)
        val contactName = { number: String ->
            try {
                lookup.identify(number)?.name
            } catch (_: RuntimeException) {
                null
            }
        }
        val lidNumber = { jid: String ->
            try {
                this.lidNumber(jid)
            } catch (_: RuntimeException) {
                null
            }
        }
        WaIncomingNotice.of(data, contactName, lidNumber)?.let { WaIncoming.show(app, it) }
        if (data["kind"] == "call_end") {
            data["call_id"]?.takeIf { it.isNotBlank() }?.let { WaIncoming.cancel(app, it) }
            WaMissedNotice.of(data, contactName, lidNumber)?.let { WaIncoming.showMissed(app, it) }
        }
        PlatformEvents.emit(EVENT, payload(data))
    }

    /**
     * [onMessage] while the vault is locked: the ring and the missed call show content-free, and nothing else happens
     * (the push's `sender` and `name` are not read; no event wakes the stream, which is closed anyway).
     */
    private fun onLockedMessage(
        app: Context,
        data: Map<String, String>,
    ) {
        WaIncomingNotice.contentFree(data)?.let { WaIncoming.show(app, it, locked = true) }
        if (data["kind"] == "call_end") {
            data["call_id"]?.takeIf { it.isNotBlank() }?.let { WaIncoming.cancel(app, it) }
            WaMissedNotice.contentFree(data)?.let { WaIncoming.showMissed(app, it) }
        }
    }
}
