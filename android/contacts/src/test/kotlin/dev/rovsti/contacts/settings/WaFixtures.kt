package dev.rovsti.contacts.settings

import dev.rovsti.core.model.wa.SettingsApplyAnswer
import dev.rovsti.core.model.wa.SettingsView
import dev.rovsti.wa.settings.WaSettingsUiState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File

/** The `view` of a `wa.settings_apply` answer fixture that Rust wrote (`crates/rovsti_ffi/fixtures`), as E3.10 draws it. */
internal fun fixtureView(label: String): SettingsView {
    val dir = File(System.getProperty("rovsti.ffi.fixtures") ?: error("rovsti.ffi.fixtures not set"))
    val stem = if (label.isEmpty()) "wa.settings_apply" else "wa.settings_apply.$label"
    val envelope = Json.parseToJsonElement(File(dir, "$stem.answer.json").readText()).jsonObject
    return Json.decodeFromJsonElement(SettingsApplyAnswer.serializer(), envelope.getValue("ok")).view
}

internal fun stateOf(view: SettingsView) = WaSettingsUiState(view = view, page = view)
