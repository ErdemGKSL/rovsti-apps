package dev.rovsti.wa.settings

import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.rovsti.core.model.qr.QrMatrix
import dev.rovsti.core.model.wa.SettingsApplyAnswer
import dev.rovsti.core.model.wa.SettingsView
import dev.rovsti.design.chrome.RovstiBackdropRoot
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.wa.FakeQr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File

/** The `view` of a `wa.settings_apply` answer fixture written by Rust (`crates/rovsti_ffi/fixtures`). */
internal fun fixtureView(label: String): SettingsView {
    val dir = File(System.getProperty("rovsti.ffi.fixtures") ?: error("rovsti.ffi.fixtures not set"))
    val stem = if (label.isEmpty()) "wa.settings_apply" else "wa.settings_apply.$label"
    val envelope = Json.parseToJsonElement(File(dir, "$stem.answer.json").readText()).jsonObject
    return Json.decodeFromJsonElement(SettingsApplyAnswer.serializer(), envelope.getValue("ok")).view
}

/** A state the way the view model would publish it for [view]; [behind] is the page under a confirmation. */
internal fun stateOf(
    view: SettingsView,
    behind: SettingsView = view,
    keyQr: Boolean = false,
): WaSettingsUiState =
    WaSettingsUiState(
        view = view,
        page = behind,
        qr = view.qrPayload?.let { QrMatrix.fromAnswer(FakeQr.answer(it)) },
        keyQr = if (keyQr) QrMatrix.fromAnswer(FakeQr.answer(view.keyLines.joinToString(""))) else null,
    )

/** Holds the state the screen draws and records what it reports, like the view model would. */
internal class Harness(
    initial: WaSettingsUiState,
) {
    var ui by mutableStateOf(initial)
    val actions = mutableListOf<WaSettingsAction>()

    fun on(action: WaSettingsAction) {
        actions += action
        if (action is WaSettingsAction.PasteDone && ui.paste?.id == action.id) ui = ui.copy(paste = null)
    }
}

/** The screen on the app backdrop with an overlay host, so menus, alerts and toasts have somewhere to float. */
@Composable
internal fun HostedSettings(
    harness: Harness,
    toolbar: RecordingToolbar? = null,
) {
    val overlays = remember { OverlayController() }
    RovstiBackdropRoot(overlays) {
        if (toolbar != null) {
            CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar) {
                WhatsAppSettingsScreen(harness.ui, harness::on)
            }
        } else {
            WhatsAppSettingsScreen(harness.ui, harness::on)
        }
    }
}

internal const val LONG_URL = "https://a-very-long-gateway-name.my-home-network.example.com:8443/whatsapp/v1"
internal const val PASTED_KEY = "rwk_Q7mZ2xYb9TnV4aPcR8LdKf3GhJw6SeUo1iXyNtBvM"
