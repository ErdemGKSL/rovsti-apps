package dev.rosty.shell

import dev.rosty.core.Outcome
import dev.rosty.core.log.RostyLog
import dev.rosty.core.model.wa.AppId
import dev.rosty.core.model.wa.PushKey
import dev.rosty.core.model.wa.PushRegistration
import dev.rosty.wa.WaLinkState
import dev.rosty.wa.WaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tells the gateway where to wake this app (AD-WA6): the FCM token of the app, registered for [app] each time the
 * WhatsApp link comes up (once per instance and token in a process). Without it the gateway knows no token and sends
 * no push, so WhatsApp messages and calls only arrive while the app holds its event socket.
 *
 * [token] is the app's FCM token or "" (the `plain` flavor has no Firebase: nothing is registered). The gateway keeps
 * one token per instance and app and replaces an older one, so registering again is harmless.
 *
 * Round 7b: [pushKey] is the public half of this install's push key (made on first use, only once a token exists); the
 * same request carries it, and the gateway then seals what each push says to it. A new key (rotated on a vault reset or
 * a new link) is registered at the next link, since the key id is part of what was registered.
 */
class WaPushRegistrar(
    private val wa: WaRepository,
    private val app: AppId,
    private val scope: CoroutineScope,
    private val token: () -> String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pushKey: () -> PushKey? = { null },
) {
    /** The `instance:token:key id` registered last in this process. */
    @Volatile private var registered: String? = null

    /** Follows the link: every time it is connected, the token is registered unless it already was. */
    fun start(): Job =
        scope.launch {
            wa.linkState
                .map { (it as? WaLinkState.Connected)?.status?.instanceId }
                .distinctUntilChanged()
                .collect { instance -> if (instance != null) register(instance) }
        }

    /** A new token (Firebase's `onNewToken`): register it now if the link is up. */
    fun refresh() {
        val instance = (wa.linkState.value as? WaLinkState.Connected)?.status?.instanceId ?: return
        scope.launch { register(instance, force = true) }
    }

    private suspend fun register(
        instance: String,
        force: Boolean = false,
    ) {
        val fcm = withContext(io) { token() }
        if (fcm.isEmpty()) return
        val push = withContext(io) { pushKey() }
        val key = "$instance:$fcm:${push?.keyId.orEmpty()}"
        if (!force && registered == key) return
        val gateway = wa.gateway() ?: return
        when (val answer = gateway.registerPush(PushRegistration(app, fcm, push))) {
            is Outcome.Ok -> {
                registered = key
                val sealed = if (push != null) " with its push key" else ""
                RostyLog.i(TAG, "push token registered for ${app.name.lowercase()}$sealed")
            }

            is Outcome.Err -> {
                RostyLog.w(TAG, "push token not registered (${answer.error})")
            }
        }
    }

    companion object {
        private const val TAG = "wa-push"

        /** The [AppId] of a Rosty package (`dev.rosty.messages` and its flavors); null for an app the gateway does not know. */
        fun appOf(packageName: String): AppId? =
            when {
                packageName.startsWith("dev.rosty.messages") -> AppId.Messages
                packageName.startsWith("dev.rosty.phone") -> AppId.Phone
                packageName.startsWith("dev.rosty.contacts") -> AppId.Contacts
                else -> null
            }
    }
}
