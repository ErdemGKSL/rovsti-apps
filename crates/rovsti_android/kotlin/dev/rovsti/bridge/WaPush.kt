package dev.rovsti.bridge

import org.json.JSONObject

/**
 * FCM wake for WhatsApp (AD-WA6). Firebase is optional: the token is read by reflection so a
 * host without `google-services.json` still compiles and gets an empty token (the gateway then
 * sends no push to this app).
 */
object WaPush {
    private const val MESSAGING = "com.google.firebase.messaging.FirebaseMessaging"

    /** The FCM token, or "" when Firebase is not packaged or the token is not ready. */
    fun token(): String = try {
        val messaging = Class.forName(MESSAGING).getMethod("getInstance").invoke(null)
        val task = messaging.javaClass.getMethod("getToken").invoke(messaging)
        // Reflection keeps Play Services off the compile classpath; poll the finished Task.
        val done = task.javaClass.getMethod("isComplete")
        val ok = task.javaClass.getMethod("isSuccessful")
        val result = task.javaClass.getMethod("getResult")
        val deadline = System.currentTimeMillis() + 3_000
        while (!(done.invoke(task) as Boolean) && System.currentTimeMillis() < deadline) Thread.sleep(25)
        if (done.invoke(task) as Boolean && ok.invoke(task) as Boolean) (result.invoke(task) as? String).orEmpty() else ""
    } catch (_: ReflectiveOperationException) {
        ""
    } catch (_: RuntimeException) {
        ""
    }

    /** Pure: the JSON handed to Rust for an FCM data message. */
    fun payload(data: Map<String, String>): String = JSONObject(data as Map<*, *>).toString()

    /**
     * Call from the host's `FirebaseMessagingService.onMessageReceived` with `remoteMessage.data`.
     * A `kind=call` message shows the incoming-call notification first (the process may have no UI), then
     * Rust gets a `wa_push` event. A `kind=call_end` message cancels the ringing notification and, when
     * `missed=1`, posts a missed-call notification.
     */
    @JvmStatic
    fun onMessage(data: Map<String, String>) {
        WaIncomingNotice.of(data)?.let { WaIncoming.show(RovstiBridge.application(), it) }
        if (data["kind"] == "call_end") {
            data["call_id"]?.takeIf { it.isNotBlank() }?.let { WaIncoming.cancel(RovstiBridge.application(), it) }
            WaMissedNotice.of(data)?.let { WaIncoming.showMissed(RovstiBridge.application(), it) }
        }
        RovstiBridge.emit("wa_push", payload(data))
    }
}
