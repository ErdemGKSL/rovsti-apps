package dev.rovsti.wa

import androidx.compose.runtime.Immutable
import dev.rovsti.core.model.wa.InstanceStatus

/** Where the link of this app to WhatsApp stands. The settings screens and the tab badges read it. */
@Immutable
sealed interface WaLinkState {
    /** No stored link (or the repository has not been started). */
    data object None : WaLinkState

    /** The instance waits for a phone: show the [qr] payload or the pairing [code], whichever the gateway sent last. */
    data class Linking(
        val qr: String? = null,
        val code: String? = null,
    ) : WaLinkState

    /**
     * Linked. [status] carries the number and name; its `state` may still say `Disconnected` while the gateway
     * reconnects, which is not a failure the user can fix.
     */
    data class Connected(
        val status: InstanceStatus,
    ) : WaLinkState

    /** The phone logged this device out: link again (`relink`). */
    data object LoggedOut : WaLinkState

    /** WhatsApp banned the number. Nothing the app can do. */
    data object Banned : WaLinkState

    /** The gateway could not be reached or refused the key. [message] is for the user. */
    data class Error(
        val message: String,
    ) : WaLinkState
}
