@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.wa

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The `wa.settings_apply` op (crates/rovsti_ffi/src/ops/wa/settings.rs): the WhatsApp settings state machine of
// `rovsti_app_core::wa_link::settings`, stateless. The ViewModel keeps the answer's `model` as opaque JSON (in the
// `SavedStateHandle`, so a recreated process restores the page), sends it back with the next input and draws the
// `view` without a decision of its own. These classes hold only immutable values (the Compose stability
// configuration lists them), so they are stable for recomposition.

/** The request: a `model` (absent on the first call), the app, the clock and one `input` (absent to only view). */
@Serializable
data class SettingsApplyRequest(
    @EncodeDefault(NEVER) val model: JsonElement? = null,
    /** The stored link of a linked app, used only when there is no `model` yet. */
    @EncodeDefault(NEVER) val link: SettingsLink? = null,
    val app: AppId,
    @SerialName("now_ms") val nowMs: Long,
    @EncodeDefault(NEVER) val input: SettingsInput? = null,
)

@Serializable
data class SettingsApplyAnswer(
    /** Opaque to Kotlin: send it back unchanged with the next input. It holds the connect key. */
    val model: JsonElement,
    val effects: List<SettingsEffect>,
    val view: SettingsView,
)

@Serializable
data class SettingsLink(
    @SerialName("gateway_url") val gatewayUrl: String,
    @SerialName("instance_id") val instanceId: String,
    val key: String,
)

/**
 * An input of the machine, `{"kind": "...", "value": ...}` (no `value` for the unit kinds). Build one with the
 * companion functions; a button of the view carries its own input already.
 */
@Serializable
data class SettingsInput(
    val kind: String,
    @EncodeDefault(NEVER) val value: JsonElement? = null,
) {
    /** The input of a button that takes the text of a field: the text replaces the empty string of the template. */
    fun withText(text: String): SettingsInput =
        when (val v = value) {
            is JsonPrimitive -> {
                copy(value = JsonPrimitive(text))
            }

            is JsonObject -> {
                copy(
                    value =
                        JsonObject(
                            v.mapValues { (k, e) ->
                                if (k ==
                                    "number"
                                ) {
                                    JsonPrimitive(text)
                                } else {
                                    e
                                }
                            },
                        ),
                )
            }

            else -> {
                this
            }
        }

    companion object {
        fun unit(kind: String) = SettingsInput(kind)

        fun text(
            kind: String,
            text: String,
        ) = SettingsInput(kind, JsonPrimitive(text))

        val back = unit("back")
        val cancel = unit("cancel")
        val retry = unit("retry")
        val copyKey = unit("copy_key")

        /** A gateway answer or event, as the gateway's own JSON (`StatusOk`, `Created`, `Event`, `KeysLoaded`, ...). */
        fun json(
            kind: String,
            value: JsonElement,
        ) = SettingsInput(kind, value)

        fun unauthorized() = unit("unauthorized")

        fun error(message: String) = text("error", message)

        fun revoked(keyId: String) = text("revoked", keyId)

        fun deleted() = unit("deleted")

        fun relinkQr() = SettingsInput("relink", buildJsonObject { put("type", "qr") })
    }
}

/** What the screen must run: `kind` is `create_instance`, `verify`, `save`, `forget`, `subscribe`, ... */
@Serializable
data class SettingsEffect(
    val kind: String,
    @EncodeDefault(NEVER) val value: JsonElement? = null,
)

@Serializable
data class SettingsRow(
    val label: String,
    val value: String?,
    /** `status` or `info`. */
    val role: String,
    /** The snake_case instance state of a `status` row. */
    val state: String?,
)

@Serializable
data class SettingsButton(
    val id: String,
    val label: String,
    val detail: String?,
    /** `option`, `primary`, `secondary` or `destructive`. */
    val role: String,
    val destructive: Boolean,
    /** Header of the group this button starts. */
    val section: String?,
    /** Sent back to the op; `null` for the platform actions `share_qr` and `paste_clipboard`. */
    val input: SettingsInput?,
    /** The id of the field whose text goes into [input] (see [SettingsInput.withText]). */
    val field: String?,
)

@Serializable
data class SettingsField(
    val id: String,
    val placeholder: String,
    val initial: String,
    /** `url`, `phone` or `key`. */
    val keyboard: String,
)

@Serializable
data class SettingsKey(
    @SerialName("key_id") val keyId: String,
    val label: String,
    /** "this app · 5 min ago", already formatted. */
    val detail: String,
    val current: Boolean,
    val revoke: SettingsInput,
)

/** A render-ready page. */
@Serializable
data class SettingsView(
    /** The screen: `choose`, `enter_url`, `link_new`, `show_qr`, `show_code`, `paste_key`, `connecting`, `connected`, ... */
    val kind: String,
    val title: String,
    val notes: List<String>,
    val rows: List<SettingsRow>,
    val buttons: List<SettingsButton>,
    val fields: List<SettingsField>,
    @SerialName("qr_payload") val qrPayload: String?,
    val code: String?,
    @SerialName("key_lines") val keyLines: List<String>,
    val keys: List<SettingsKey>,
    val error: String?,
    val busy: Boolean,
    /** What the system Back key sends; `null` leaves the settings. */
    val back: SettingsInput?,
)
