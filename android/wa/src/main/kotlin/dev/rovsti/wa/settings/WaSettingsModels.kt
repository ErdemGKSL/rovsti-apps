package dev.rovsti.wa.settings

import android.net.Uri
import androidx.compose.runtime.Immutable
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.qr.QrMatrix
import dev.rovsti.core.model.wa.SettingsInput
import dev.rovsti.core.model.wa.SettingsView
import dev.rovsti.core.ops.WaGateway
import dev.rovsti.design.text.UiText

/** Opens a gateway for [url]; [key] is null only to create an instance. The caller closes what it opens. */
fun interface WaUrlOpener {
    suspend fun open(
        url: String,
        key: String?,
    ): Outcome<WaGateway>
}

/** A request to put [text] into the key field. [id] changes with every request, so the screen runs each once. */
@Immutable
data class PasteRequest(
    val id: Int,
    val text: String,
)

/**
 * What the settings screen draws. [view] is the page the state machine is on; [page] is what is drawn behind it: the
 * same page, except while [view] is a confirmation (`confirm_*`), when the page the confirmation came from stays
 * visible and the confirmation is an alert on top. [qr] is the matrix of `view.qrPayload`, [keyQr] the matrix of the
 * connect key when the user asked for it.
 */
@Immutable
data class WaSettingsUiState(
    val view: SettingsView,
    val page: SettingsView,
    val qr: QrMatrix? = null,
    val keyQr: QrMatrix? = null,
    val paste: PasteRequest? = null,
)

/** What the screen reports. A button of the view carries its own [SettingsInput]; the rest are platform actions. */
sealed interface WaSettingsAction {
    /** A button, a key row's menu or the Back key: send [input] to the state machine. */
    data class Press(
        val input: SettingsInput,
    ) : WaSettingsAction

    /** "Share QR to PC...": stage the QR as a PNG and open the share sheet. */
    data object ShareQr : WaSettingsAction

    /** "Paste": put the clipboard text into the key field. */
    data object PasteClipboard : WaSettingsAction

    /** The screen has put [PasteRequest.id] into the field. */
    data class PasteDone(
        val id: Int,
    ) : WaSettingsAction

    /** "Share" on the connect key page: the key as text to the share sheet. */
    data object ShareKey : WaSettingsAction

    /** "Show as QR" on the connect key page, toggled. */
    data object ToggleKeyQr : WaSettingsAction
}

/** One-shot things the screen host does: a toast, a share sheet. */
sealed interface WaSettingsEvent {
    data class Toast(
        val message: UiText,
    ) : WaSettingsEvent

    data class ShareImage(
        val uri: Uri,
    ) : WaSettingsEvent

    data class ShareText(
        val text: String,
    ) : WaSettingsEvent
}
