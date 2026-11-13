package dev.rovsti.wa

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.rovsti.core.RustError
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

    /** The gateway could not be reached or refused the key. The UI words [kind] from resources (F33). */
    data class Error(
        val kind: WaLinkError,
    ) : WaLinkState
}

/**
 * Why the link failed, as a kind the UI words in the phone's language ([text], en + tr). Never a sentence from Rust
 * or the gateway: those are English and stay in the logs.
 */
enum class WaLinkError(
    @StringRes val text: Int,
) {
    /** The gateway no longer accepts this app's connect key (revoked, or the gateway answered 401). */
    KeyRefused(R.string.wa_link_key_refused),

    /** The gateway could not be reached. */
    Unreachable(R.string.wa_link_unreachable),

    /** Anything else: the stored link could not be read, the gateway answered something unexpected. */
    Failed(R.string.wa_link_failed),
    ;

    companion object {
        fun of(error: RustError): WaLinkError =
            when (error) {
                is RustError.Auth -> KeyRefused
                is RustError.Network -> Unreachable
                else -> Failed
            }
    }
}
