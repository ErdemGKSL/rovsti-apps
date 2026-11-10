@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.phone

import dev.rovsti.core.model.search.NameFormat
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// `phone.*`: preferences, call notes files, small preference rules and the WhatsApp call tracker handle.
// See `crates/rovsti_ffi/src/ops/phone/store.rs`.

/** Request of the file ops: [dir] overrides the data directory `core.init` stored (tests, fixtures). */
@Serializable
data class DirRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
)

@Serializable
enum class HapticsLevel {
    @SerialName("off")
    Off,

    @SerialName("light")
    Light,

    @SerialName("normal")
    Normal,
}

@Serializable
enum class SortBy {
    @SerialName("first_name")
    FirstName,

    @SerialName("last_name")
    LastName,
}

/** The Phone settings page (`rovsti_core::settings::Settings`). */
@Serializable
data class PhoneSettings(
    val haptics: HapticsLevel = HapticsLevel.Normal,
    val proximity: Boolean = true,
    @SerialName("flip_to_silence") val flipToSilence: Boolean = false,
    @SerialName("vibrate_connect") val vibrateConnect: Boolean = false,
    @SerialName("vibrate_end") val vibrateEnd: Boolean = false,
    /** Null follows the system setting. */
    @SerialName("keypad_tones") val keypadTones: Boolean? = null,
    @SerialName("sort_by") val sortBy: SortBy = SortBy.FirstName,
    @SerialName("name_format") val nameFormat: NameFormat = NameFormat.FirstLast,
    @SerialName("show_frequent") val showFrequent: Boolean = true,
)

@Serializable
data class UiState(
    @SerialName("last_tab") val lastTab: Int? = null,
    @SerialName("missed_seen_ms") val missedSeenMs: Long = 0,
    @SerialName("app_version") val appVersion: String = "",
    @SerialName("xiaomi_confirmed") val xiaomiConfirmed: Map<String, String> = emptyMap(),
    @SerialName("hint_counters") val hintCounters: Map<String, Int> = emptyMap(),
)

/** `prefs.json` (schema 2). */
@Serializable
data class PhonePrefs(
    @SerialName("schema_version") val schemaVersion: Int = 2,
    val ui: UiState = UiState(),
    val settings: PhoneSettings = PhoneSettings(),
    @SerialName("quick_replies") val quickReplies: List<String> = emptyList(),
    @SerialName("speed_dial") val speedDial: SpeedDialMap = emptyMap(),
    @SerialName("favorites_order") val favoritesOrder: List<String> = emptyList(),
)

@Serializable
data class PrefsLoadAnswer(
    val prefs: PhonePrefs,
    /** `missing`, `current`, `upgraded_from_v1`, `newer` or `corrupt`. */
    val origin: String,
    @SerialName("newer_version") val newerVersion: Int?,
)

@Serializable
data class PrefsSaveRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    val prefs: PhonePrefs,
)

@Serializable
data class NotesLoadAnswer(
    val notes: Notes,
)

@Serializable
data class NotesSaveRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    val notes: Notes,
)

@Serializable
data class ReplyChoicesRequest(
    @EncodeDefault(NEVER) val replies: List<String> = emptyList(),
)

@Serializable
data class ReplyChoicesAnswer(
    /** The decline-with-message choices: the saved ones, else the defaults. */
    val choices: List<String>,
    val max: Int,
    @SerialName("max_chars") val maxChars: Int,
    /** The character counter shows from this many characters. */
    @SerialName("counter_at") val counterAt: Int,
    @SerialName("can_add") val canAdd: Boolean,
)

@Serializable
data class KeypadTonesRequest(
    /** The setting; null follows the system. */
    val setting: Boolean? = null,
    val system: Boolean,
)

@Serializable
data class KeypadTonesAnswer(
    val on: Boolean,
)

// ---- WhatsApp call tracker

@Serializable
enum class CallDirection {
    @SerialName("in")
    In,

    @SerialName("out")
    Out,
}

@Serializable
enum class EndReasonKind {
    @SerialName("hangup")
    Hangup,

    @SerialName("rejected")
    Rejected,

    @SerialName("missed")
    Missed,

    @SerialName("remote")
    Remote,

    @SerialName("elsewhere_answered")
    ElsewhereAnswered,
}

/** Where a WhatsApp call is: `ringing`, `connecting`, `active` or `{"ended": reason}`. */
@Serializable(with = CallPhaseSerializer::class)
sealed interface CallPhase {
    data object Ringing : CallPhase

    data object Connecting : CallPhase

    data object Active : CallPhase

    /** [reason] is an [EndReasonKind] wire name, or the text of `failed`. */
    data class Ended(
        val reason: String,
        val failed: Boolean = false,
    ) : CallPhase
}

internal object CallPhaseSerializer : ExternallyTagged<CallPhase>("CallPhase") {
    override fun fromTag(
        tag: String,
        value: JsonElement?,
    ): CallPhase =
        when (tag) {
            "ringing" -> {
                CallPhase.Ringing
            }

            "connecting" -> {
                CallPhase.Connecting
            }

            "active" -> {
                CallPhase.Active
            }

            "ended" -> {
                when (value) {
                    is JsonPrimitive -> {
                        CallPhase.Ended(value.content)
                    }

                    is JsonObject -> {
                        CallPhase.Ended(
                            value.getValue("failed").let { (it as JsonPrimitive).content },
                            true,
                        )
                    }

                    else -> {
                        unknown(tag)
                    }
                }
            }

            else -> {
                unknown(tag)
            }
        }

    override fun toJson(value: CallPhase): JsonElement =
        when (value) {
            CallPhase.Ringing -> {
                JsonPrimitive("ringing")
            }

            CallPhase.Connecting -> {
                JsonPrimitive("connecting")
            }

            CallPhase.Active -> {
                JsonPrimitive("active")
            }

            is CallPhase.Ended -> {
                tagged(
                    "ended",
                    if (value.failed) {
                        JsonObject(mapOf("failed" to JsonPrimitive(value.reason)))
                    } else {
                        JsonPrimitive(value.reason)
                    },
                )
            }
        }
}

/** A gateway update about one WhatsApp call. */
@Serializable
data class CallUpdate(
    @SerialName("call_id") val callId: String,
    val peer: String,
    @SerialName("peer_name") val peerName: String?,
    val direction: CallDirection,
    val phase: CallPhase,
    @SerialName("is_video") val isVideo: Boolean,
)

/** What the WhatsApp call screen shows. */
@Serializable
data class WaCallView(
    @SerialName("call_id") val callId: String,
    val peer: String,
    @SerialName("peer_label") val peerLabel: String,
    val direction: CallDirection,
    val phase: CallPhase,
    @SerialName("is_video") val isVideo: Boolean,
    val muted: Boolean,
    @SerialName("elapsed_s") val elapsedS: Long,
)

@Serializable
data class TrackerHandleAnswer(
    val handle: Long,
)

@Serializable
data class TrackerUpdateRequest(
    val handle: Long,
    val update: CallUpdate,
    @SerialName("now_ms") val nowMs: Long,
)

/** Answer of the tracker ops that change the call; [change] is `ignored`, `shown` or `ended`. */
@Serializable
data class TrackerChangeAnswer(
    val change: String,
    val view: WaCallView?,
    @SerialName("status_line") val statusLine: String?,
)

@Serializable
data class TrackerPlacedRequest(
    val handle: Long,
    @SerialName("call_id") val callId: String,
    val peer: String,
    @SerialName("now_ms") val nowMs: Long,
)

@Serializable
data class TrackerViewRequest(
    val handle: Long,
    @SerialName("now_ms") val nowMs: Long,
)

@Serializable
data class TrackerViewAnswer(
    val view: WaCallView?,
    @SerialName("status_line") val statusLine: String?,
    @SerialName("started_ms") val startedMs: Long?,
    val empty: Boolean,
)

@Serializable
data class TrackerMuteRequest(
    val handle: Long,
    val muted: Boolean,
)

@Serializable
data class TrackerHandleRequest(
    val handle: Long,
)

@Serializable
data class CanPlaceRequest(
    val connected: Boolean,
    val busy: Boolean,
    val number: String,
)

@Serializable
data class ElapsedRequest(
    val seconds: Long,
)

@Serializable
data class ElapsedAnswer(
    val text: String,
)

// `phone.wa_arbitrate`, `phone.wa_screen`, `phone.wa_handoff_number` (`crates/rovsti_ffi/src/ops/phone/wa_screen.rs`).

/** The facts of the arbitration: a cellular call is live, one is ringing, and the newest WhatsApp call if any. */
@Serializable
data class WaArbitrateRequest(
    @SerialName("telecom_live") val telecomLive: Boolean,
    @SerialName("telecom_ringing") val telecomRinging: Boolean,
    @EncodeDefault(NEVER) val wa: CallUpdate? = null,
)

/** [decision] is `show_wa`, `wa_waiting`, `reject_wa_busy` or `telecom`. */
@Serializable
data class WaArbitrateAnswer(
    val decision: String,
)

@Serializable
data class WaScreenRequest(
    val update: CallUpdate,
    @EncodeDefault(NEVER) val speaker: Boolean = false,
    @EncodeDefault(NEVER) val muted: Boolean = false,
)

/** Which buttons of the WhatsApp call screen work. */
@Serializable
data class WaEnabled(
    val mute: Boolean,
    val speaker: Boolean,
    val keypad: Boolean,
    val hold: Boolean,
    @SerialName("add_call") val addCall: Boolean,
    val end: Boolean,
)

/** [phase] is `ringing`, `calling`, `connecting`, `active` or `ended`; [note] is the core's sentence for a dimmed control. */
@Serializable
data class WaScreenAnswer(
    val phase: String,
    val enabled: WaEnabled,
    val note: String,
)

/** A hand-off from a sibling app (`dev.rovsti.action.WA_CALL`): [action] is `wa_call`, `wa_chat` or anything else. */
@Serializable
data class WaHandoffRequest(
    val action: String,
    val jid: String,
    @EncodeDefault(NEVER) val name: String? = null,
)

/** The number to dial (`+<digits>`), null when the hand-off is no call to a phone JID. */
@Serializable
data class WaHandoffAnswer(
    val number: String?,
)
