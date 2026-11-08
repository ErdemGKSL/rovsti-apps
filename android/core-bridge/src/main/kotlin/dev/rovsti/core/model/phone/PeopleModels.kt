@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.phone

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// `speed_dial.*`, `blocklist.*`, `dial.*`: numbers on lists and the dial string rules.
// See `crates/rovsti_ffi/src/ops/phone/{people,dial}.rs`.

// ---- speed_dial.*

/** A speed-dial slot (`rovsti_core::settings::SpeedDialEntry`). */
@Serializable
data class SpeedDialEntry(
    @SerialName("lookup_key") val lookupKey: String = "",
    @SerialName("number_raw") val numberRaw: String = "",
    val name: String = "",
)

/** Digit (2 to 9) to slot. */
typealias SpeedDialMap = Map<Int, SpeedDialEntry>

/** What a long press on a dialpad key does. */
@Serializable(with = HoldDigitSerializer::class)
sealed interface HoldDigit {
    /** Key 1: dial the voicemail number. */
    data object Voicemail : HoldDigit

    /** Dial the slot. */
    data class Call(
        val numberRaw: String,
        val name: String,
    ) : HoldDigit

    /** A free slot: offer to assign it. */
    data class Unassigned(
        val digit: Int,
    ) : HoldDigit

    /** Not a speed-dial press (field not empty, `0`, `*`, `#`). */
    data object Ignore : HoldDigit
}

internal object HoldDigitSerializer : ExternallyTagged<HoldDigit>("HoldDigit") {
    override fun fromTag(
        tag: String,
        value: JsonElement?,
    ): HoldDigit =
        when (tag) {
            "voicemail" -> {
                HoldDigit.Voicemail
            }

            "ignore" -> {
                HoldDigit.Ignore
            }

            "unassigned" -> {
                HoldDigit.Unassigned((value as? JsonPrimitive ?: unknown(tag)).content.toInt())
            }

            "call" -> {
                val body = (value as? JsonObject) ?: unknown(tag)
                HoldDigit.Call(
                    body.getValue("number_raw").jsonPrimitive.content,
                    body.getValue("name").jsonPrimitive.content,
                )
            }

            else -> {
                unknown(tag)
            }
        }

    override fun toJson(value: HoldDigit): JsonElement =
        when (value) {
            HoldDigit.Voicemail -> {
                JsonPrimitive("voicemail")
            }

            HoldDigit.Ignore -> {
                JsonPrimitive("ignore")
            }

            is HoldDigit.Unassigned -> {
                tagged("unassigned", JsonPrimitive(value.digit))
            }

            is HoldDigit.Call -> {
                tagged(
                    "call",
                    JsonObject(
                        mapOf("number_raw" to JsonPrimitive(value.numberRaw), "name" to JsonPrimitive(value.name)),
                    ),
                )
            }
        }
}

@Serializable
data class OnHoldRequest(
    /** One character: the key held. */
    val digit: String,
    /** What the number field held before the press. */
    @SerialName("dialed_before") val dialedBefore: String,
    val map: SpeedDialMap,
)

@Serializable
data class OnHoldAnswer(
    val result: HoldDigit,
)

@Serializable
data class AssignRequest(
    val map: SpeedDialMap,
    val digit: Int,
    val entry: SpeedDialEntry,
)

/** Answer of `speed_dial.assign`. */
@Serializable
data class SpeedDialMapAnswer(
    val map: SpeedDialMap,
)

@Serializable
data class UnassignRequest(
    val map: SpeedDialMap,
    val digit: Int,
)

@Serializable
data class UnassignAnswer(
    val removed: Boolean,
    val map: SpeedDialMap,
)

/** Request of `speed_dial.free_digits` and `speed_dial.badges`. */
@Serializable
data class SpeedDialMapRequest(
    val map: SpeedDialMap,
)

@Serializable
data class FreeDigitsAnswer(
    val digits: List<Int>,
)

@Serializable
data class DigitForRequest(
    val map: SpeedDialMap,
    val numbers: List<String>,
)

@Serializable
data class DigitForAnswer(
    val digit: Int?,
)

@Serializable
data class SpeedDialBadge(
    val digit: Int,
    val name: String,
    val hue: Int?,
)

@Serializable
data class BadgesAnswer(
    val badges: List<SpeedDialBadge>,
)

// ---- blocklist.*

/** One row of the system blocklist. */
@Serializable
data class BlockedNumber(
    val id: Long,
    @SerialName("number_raw") val numberRaw: String,
    val normalized: String,
)

/** Numbers the telephony stack treats as emergency here; 112 and 911 always count. */
@Serializable
data class EmergencyList(
    val numbers: List<String> = emptyList(),
)

@Serializable
enum class BlockRefusal {
    @SerialName("emergency")
    Emergency,

    @SerialName("no_number")
    NoNumber,

    @SerialName("not_a_number")
    NotANumber,
}

@Serializable
data class BlockCheckRequest(
    val raw: String,
    @EncodeDefault(NEVER) val emergency: EmergencyList? = null,
    @EncodeDefault(NEVER) val blocked: List<BlockedNumber> = emptyList(),
)

@Serializable
data class BlockCheckAnswer(
    /** Null when the number may be blocked. */
    val refusal: BlockRefusal?,
    @SerialName("already_blocked") val alreadyBlocked: Boolean,
)

@Serializable
data class BlockParseRequest(
    val typed: String,
    @EncodeDefault(NEVER) val emergency: EmergencyList? = null,
)

/** The number to block, in dial form. Refusals answer `invalid` with detail `{"reason": "emergency" | "invalid"}`. */
@Serializable
data class BlockParseAnswer(
    val number: String,
)

@Serializable
data class BlockManyRequest(
    val text: String,
    @EncodeDefault(NEVER) val emergency: EmergencyList? = null,
    @EncodeDefault(NEVER) val blocked: List<BlockedNumber> = emptyList(),
)

@Serializable
data class BulkParse(
    @SerialName("to_block") val toBlock: List<String>,
    @SerialName("skipped_emergency") val skippedEmergency: Int,
    @SerialName("already_blocked") val alreadyBlocked: Int,
)

@Serializable
data class BlockManyAnswer(
    val parsed: BulkParse,
    /** The pill after blocking them all. */
    val text: String,
)

@Serializable
data class FileImport(
    val bulk: BulkParse,
    @SerialName("skipped_lines") val skippedLines: Int,
    val truncated: Boolean,
)

@Serializable
data class BlockFileAnswer(
    @SerialName("import") val result: FileImport,
    val text: String,
)

@Serializable
data class BlockLinesRequest(
    val rows: List<BlockedNumber>,
    @EncodeDefault(NEVER) val names: NamesByNumber = emptyMap(),
    @SerialName("tz_offset_s") val tzOffsetS: Int,
    @SerialName("now_ms") val nowMs: Long,
)

@Serializable
data class BlockedLine(
    val title: String,
    val subtitle: String,
    val aria: String,
)

@Serializable
data class BlockLinesAnswer(
    val lines: List<BlockedLine>,
    val csv: String,
    @SerialName("csv_file_name") val csvFileName: String,
    @SerialName("share_text") val shareText: String,
)

@Serializable
data class BlockCallLineRequest(
    val entry: CallLogEntry,
    val name: String? = null,
    val time: String,
)

// ---- dial.*

/** Request of every `dial.*` op that works on typed or pasted text. */
@Serializable
data class DialTextRequest(
    val text: String,
)

/** Answer of the `dial.*` ops that return a string (`validate` errors with `invalid` and a reason). */
@Serializable
data class DialTextAnswer(
    val text: String,
)

@Serializable
data class TelUriRequest(
    val uri: String,
)

@Serializable
enum class FieldAction {
    @SerialName("copy")
    Copy,

    @SerialName("paste")
    Paste,

    @SerialName("clear")
    Clear,
}

@Serializable
data class FieldMenuRequest(
    val dialed: String,
    @SerialName("clipboard_has_text") val clipboardHasText: Boolean,
)

@Serializable
data class FieldMenuItem(
    val action: FieldAction,
    val label: String,
    /** The pill after the action; null for Paste, which reports through its own plan. */
    @SerialName("done_text") val doneText: String?,
)

@Serializable
data class FieldMenuAnswer(
    val items: List<FieldMenuItem>,
)

@Serializable
data class NumbersAnswer(
    val numbers: List<String>,
)

/** What pasting text into the dial field does. */
@Serializable(with = PastePlanSerializer::class)
sealed interface PastePlan {
    data object Nothing : PastePlan

    data class One(
        val number: String,
    ) : PastePlan

    data class Choose(
        val numbers: List<String>,
    ) : PastePlan
}

internal object PastePlanSerializer : ExternallyTagged<PastePlan>("PastePlan") {
    override fun fromTag(
        tag: String,
        value: JsonElement?,
    ): PastePlan =
        when (tag) {
            "nothing" -> {
                PastePlan.Nothing
            }

            "one" -> {
                PastePlan.One((value as? JsonPrimitive ?: unknown(tag)).content)
            }

            "choose" -> {
                PastePlan.Choose((value as? JsonArray ?: unknown(tag)).map { it.jsonPrimitive.content })
            }

            else -> {
                unknown(tag)
            }
        }

    override fun toJson(value: PastePlan): JsonElement =
        when (value) {
            PastePlan.Nothing -> JsonPrimitive("nothing")
            is PastePlan.One -> tagged("one", JsonPrimitive(value.number))
            is PastePlan.Choose -> tagged("choose", JsonArray(value.numbers.map(::JsonPrimitive)))
        }
}

@Serializable
data class PastePlanAnswer(
    val plan: PastePlan,
)

@Serializable
data class RedialRequest(
    val entries: List<CallLogEntry>,
)

@Serializable
data class RedialAnswer(
    val number: String?,
)

/** One step of a tone sequence: a DTMF tone, or the two-second pause a comma stands for. */
@Serializable(with = ToneStepSerializer::class)
sealed interface ToneStep {
    data class Tone(
        val key: Char,
    ) : ToneStep

    data object Pause : ToneStep
}

internal object ToneStepSerializer : ExternallyTagged<ToneStep>("ToneStep") {
    override fun fromTag(
        tag: String,
        value: JsonElement?,
    ): ToneStep =
        when (tag) {
            "pause" -> {
                ToneStep.Pause
            }

            "tone" -> {
                val text = (value as? JsonPrimitive ?: unknown(tag)).contentOrNull.orEmpty()
                if (text.length != 1) throw SerializationException("ToneStep: tone is one character, got \"$text\"")
                ToneStep.Tone(text[0])
            }

            else -> {
                unknown(tag)
            }
        }

    override fun toJson(value: ToneStep): JsonElement =
        when (value) {
            ToneStep.Pause -> JsonPrimitive("pause")
            is ToneStep.Tone -> tagged("tone", JsonPrimitive(value.key.toString()))
        }
}

@Serializable
data class TonePlan(
    val steps: List<ToneStep>,
    val tones: Int,
    val truncated: Boolean,
)

@Serializable
data class PasteTonesRequest(
    val clip: String? = null,
)

@Serializable
data class PasteTonesAnswer(
    val label: String?,
)

@Serializable
data class IsEmergencyRequest(
    val typed: String,
    @EncodeDefault(NEVER) val emergency: EmergencyList? = null,
)

@Serializable
data class IsEmergencyAnswer(
    val emergency: Boolean,
)
