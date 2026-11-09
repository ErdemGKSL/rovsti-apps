package dev.rovsti.platform.wa

import android.content.Context
import dev.rovsti.platform.PlatformEvents
import org.json.JSONObject
import java.util.concurrent.locks.LockSupport

/**
 * FCM wake for WhatsApp (AD-WA6, AD-WA9). Firebase is optional: the token is read by reflection so a host without
 * `google-services.json` still compiles and gets an empty token (the gateway then sends no push to this app).
 *
 * The app's `FirebaseMessagingService` calls [onMessage]; `:wa` collects the [EVENT] on `PlatformEvents` and calls
 * `WaEventStream.reconnectNow()` so the stream skips its backoff.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/WaPush.kt
 */
object WaPush {
    private const val TOKEN_WAIT_NS = 3_000_000_000L
    private const val POLL_NS = 25_000_000L
    private const val MESSAGING = "com.google.firebase.messaging.FirebaseMessaging"

    /** The `PlatformEvents` kind that means "the gateway has news: reconnect now". */
    const val EVENT = "wa_push"

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
        WaIncomingNotice.of(data)?.let { WaIncoming.show(app, it) }
        if (data["kind"] == "call_end") {
            data["call_id"]?.takeIf { it.isNotBlank() }?.let { WaIncoming.cancel(app, it) }
            WaMissedNotice.of(data)?.let { WaIncoming.showMissed(app, it) }
        }
        PlatformEvents.emit(EVENT, payload(data))
    }
}
