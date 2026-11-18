package dev.rosty.platform.wa

import android.content.Context
import dev.rosty.platform.PlatformEvents
import dev.rosty.platform.identity.CallerLookup
import dev.rosty.platform.wa.push.PushContent
import dev.rosty.platform.wa.push.PushKeyAgreement
import dev.rosty.platform.wa.push.PushKeys
import dev.rosty.platform.wa.push.SealedPush
import org.json.JSONObject
import java.util.concurrent.locks.LockSupport

/**
 * FCM wake for WhatsApp (AD-WA6, AD-WA9). Firebase is optional: the token is read by reflection so a host without
 * `google-services.json` still compiles and gets an empty token (the gateway then sends no push to this app).
 *
 * The app's `FirebaseMessagingService` calls [onMessage]; `:wa` collects the [EVENT] on `PlatformEvents` and calls
 * `WaEventStream.reconnectNow()` so the stream skips its backoff. A push sealed to this install's push key (round 7b,
 * `wa/push`) is opened first, in memory; the event carries only its clear routing keys.
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
    ): OpenedPush {
        val app = context.applicationContext
        // A sealed push (round 7b) opens with this install's Keystore key, locked or not; in memory only.
        val opened = OpenedPush.open(data, PushKeys.store)
        val vaultLocked =
            try {
                locked()
            } catch (_: RuntimeException) {
                true
            }
        // The app's caller-ID when it installed one, else (a cold process) Android's own PhoneLookup (F28, F39).
        // Device contacts are not vault secrets: a locked ring is named by them too.
        val lookup = CallerLookup.of(app)
        val contactName = { number: String ->
            try {
                lookup.identify(number)?.name
            } catch (_: RuntimeException) {
                null
            }
        }
        if (vaultLocked) {
            onLockedMessage(app, opened, contactName)
            return opened
        }
        val lidNumber = { jid: String ->
            try {
                this.lidNumber(jid)
            } catch (_: RuntimeException) {
                null
            }
        }
        val plain = opened.data
        WaIncomingNotice.of(plain, contactName, lidNumber)?.let { WaIncoming.show(app, it) }
        if (plain["kind"] == "call_end") {
            plain["call_id"]?.takeIf { it.isNotBlank() }?.let { WaIncoming.cancel(app, it) }
            WaMissedNotice.of(plain, contactName, lidNumber)?.let { WaIncoming.showMissed(app, it) }
        }
        PlatformEvents.emit(EVENT, payload(opened.routing))
        return opened
    }

    /**
     * [onMessage] while the vault is locked: no lookup in the vault (the LID table is in it), no gateway request and
     * no wake of the stream (it is closed anyway). The ring and the missed call name the caller from what the push
     * could tell ([waLockedCallerTitle]): a device contact, else the decrypted name or number, else "WhatsApp call".
     */
    private fun onLockedMessage(
        app: Context,
        opened: OpenedPush,
        contactName: (String) -> String?,
    ) {
        val plain = opened.data
        WaIncomingNotice.contentFree(plain)?.let {
            WaIncoming.show(app, it.copy(title = waLockedCallerTitle(opened, contactName)), locked = true)
        }
        if (plain["kind"] == "call_end") {
            plain["call_id"]?.takeIf { it.isNotBlank() }?.let { WaIncoming.cancel(app, it) }
            WaMissedNotice.contentFree(plain)?.let {
                WaIncoming.showMissed(app, it.copy(title = waLockedCallerTitle(opened, contactName)))
            }
        }
    }
}

/**
 * A push as the app reads it (round 7b). [data] has the keys of the content-free push (`kind`, `call_id`, `missed`,
 * and from an opened sealed push `chat`, `count`, `sender`, `name`), so every existing reader keeps working; [content]
 * is everything the sealed push said, or null (not sealed, or it did not open: the content-free notice then). [routing]
 * is what may be logged or broadcast: the keys that were in clear anyway.
 */
class OpenedPush(
    val data: Map<String, String>,
    val content: PushContent?,
    val sealed: Boolean,
) {
    val routing: Map<String, String>
        get() = if (sealed) data.filterKeys { it in ROUTING_KEYS } else data

    override fun toString(): String = "OpenedPush(kind=${data["kind"]}, sealed=$sealed, opened=${content != null})"

    companion object {
        private val ROUTING_KEYS = setOf("kind", "call_id", "missed")

        /** Pure but for [keys]: opens [data] when it is sealed. */
        fun open(
            data: Map<String, String>,
            keys: PushKeyAgreement,
        ): OpenedPush {
            if (!SealedPush.isSealed(data)) return OpenedPush(data, null, sealed = false)
            val content = SealedPush.open(data, keys)
            val plain = data.filterKeys { it in ROUTING_KEYS }.toMutableMap()
            if (content != null) {
                content.chat?.let { plain["chat"] = it }
                content.count?.let { plain["count"] = it.toString() }
                content.sender?.let { plain["sender"] = it }
                content.name?.let { plain["name"] = it }
            }
            return OpenedPush(plain, content, sealed = true)
        }
    }
}

/**
 * Pure: the caller's title on a ring while the vault is locked. A device contact that owns the number (device
 * contacts are not vault secrets), else the WhatsApp name or the number the sealed push carried, else
 * [WaIncomingNotice.FALLBACK_TITLE]. A push that is not sealed is in clear on FCM, but its name is still not shown,
 * as before round 7b: only a device contact names it.
 */
fun waLockedCallerTitle(
    opened: OpenedPush,
    contactName: (String) -> String?,
): String {
    if (opened.content != null) return waCallerTitle(opened.data, contactName)
    if (opened.sealed) return WaIncomingNotice.FALLBACK_TITLE
    return waCallerNumber(opened.data)
        ?.let(contactName)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: WaIncomingNotice.FALLBACK_TITLE
}
