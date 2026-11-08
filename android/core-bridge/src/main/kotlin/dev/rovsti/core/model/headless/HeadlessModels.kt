@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.headless

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// One request/answer pair per headless kind (E2.7), mirroring `crates/rovsti_ffi/fixtures/headless.<kind>.*.json`.
// Fields the fixture does not carry are `@EncodeDefault(NEVER)` so a fixture round-trips byte for byte in meaning.
// Answers of a failure (`{"ok":false,"error":..}`) decode into the same class: success fields default to null.

// ---- Messages

@Serializable
data class SmsClassifyRequest(
    val dir: String = "",
    val sender: String,
    val body: String,
    val known: Boolean = false,
    val blocked: Boolean = false,
)

@Serializable
data class SmsClassifyAnswer(
    val spam: Boolean,
    val reason: String? = null,
    val otp: String? = null,
)

@Serializable
data class MmsNotificationRequest(
    @SerialName("pdu_b64") val pduB64: String,
)

@Serializable
data class MmsNotificationAnswer(
    val ok: Boolean,
    @SerialName("transaction_id") val transactionId: String? = null,
    @SerialName("content_location") val contentLocation: String? = null,
    val from: String? = null,
    val size: Long? = null,
    @SerialName("expiry_s") val expiryS: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val error: String? = null,
)

@Serializable
data class MmsRetrievedRequest(
    val dir: String = "",
    @SerialName("pdu_b64") val pduB64: String,
    val known: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val blocked: Boolean = false,
)

/** A part of a retrieved MMS: text parts carry [text], every other part carries its bytes as [dataB64]. */
@Serializable
data class MmsPart(
    @SerialName("content_type") val contentType: String,
    val name: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val text: String? = null,
    @SerialName("data_b64") @EncodeDefault(EncodeDefault.Mode.NEVER) val dataB64: String? = null,
)

@Serializable
data class MmsRetrievedAnswer(
    val ok: Boolean,
    @SerialName("transaction_id") val transactionId: String? = null,
    val from: String? = null,
    val to: List<String> = emptyList(),
    val subject: String? = null,
    @SerialName("date_s") val dateS: Long? = null,
    val text: String? = null,
    val parts: List<MmsPart> = emptyList(),
    val spam: Boolean = false,
    val reason: String? = null,
    val otp: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val error: String? = null,
)

@Serializable
data class MmsSendReqRequest(
    @SerialName("local_id") val localId: Long,
    val to: List<String>,
    val text: String = "",
    @SerialName("image_path") @EncodeDefault(EncodeDefault.Mode.NEVER) val imagePath: String? = null,
)

@Serializable
data class MmsSendReqAnswer(
    val ok: Boolean,
    @SerialName("pdu_b64") val pduB64: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val error: String? = null,
)

@Serializable
data class WaDigestRequest(
    val dir: String,
    val chat: String,
)

/** A notification spec; `{}` (every field absent) means "nothing to show". */
@Serializable
data class WaDigestAnswer(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val tag: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val title: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lines: List<String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val otp: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val reply: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val silent: Boolean? = null,
)

@Serializable
data class WaReplyRequest(
    val dir: String,
    val chat: String,
    val text: String,
)

@Serializable
data class WaReplyAnswer(
    val ok: Boolean,
)

@Serializable
data class MsgRemindRequest(
    val tag: String,
    val title: String,
    val body: String,
    val minutes: Long = 60,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int = 0,
)

/** The object the reminder is armed with. */
@Serializable
data class ReminderArm(
    val id: Long,
    val tag: String,
    @SerialName("message_id") val messageId: String,
    @SerialName("message_at_ms") val messageAtMs: Long,
    @SerialName("at_ms") val atMs: Long,
    val title: String,
    val preview: String,
    @SerialName("notif_title") val notifTitle: String,
    @SerialName("notif_body") val notifBody: String,
)

@Serializable
data class MsgRemindAnswer(
    val ok: Boolean,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val arm: ReminderArm? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val toast: String? = null,
)

// ---- Stops

@Serializable
enum class TripState { Tracking, Arrived, Dismissed }

@Serializable
enum class Side {
    @SerialName("left")
    Left,

    @SerialName("right")
    Right,
}

@Serializable
data class FixPos(
    val lat: Double,
    val lon: Double,
    @SerialName("accuracy_m") val accuracyM: Double,
)

@Serializable
data class TripFixRequest(
    val dir: String,
    val lat: Double,
    val lon: Double,
    @SerialName("accuracy_m") val accuracyM: Double,
    @SerialName("speed_mps") val speedMps: Double? = null,
    @SerialName("at_ms") val atMs: Long,
)

@Serializable
data class TripFixAnswer(
    val state: TripState,
    @SerialName("distance_m") val distanceM: Double,
    @SerialName("next_fix_ms") val nextFixMs: Long,
    @SerialName("retry_ms") val retryMs: Long = 0,
    @SerialName("high_accuracy") val highAccuracy: Boolean,
    val ring: Boolean,
    val title: String,
    val text: String,
    @SerialName("notice_sent") val noticeSent: Boolean,
    @SerialName("notice_failed") val noticeFailed: Boolean = false,
    val side: Side? = null,
    val inside: Boolean = false,
    @SerialName("circle_m") val circleM: Double? = null,
    val at: FixPos? = null,
)

@Serializable
data class TripAlarmRequest(
    val dir: String,
    /** `dismiss`, `snooze` or `timeout`. */
    val action: String,
)

/** Exactly one field is set: the alarm was dismissed, rings again at [ringAtMs], is silenced, or the trip is over. */
@Serializable
data class TripAlarmAnswer(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val dismissed: Boolean? = null,
    @SerialName("ring_at_ms") @EncodeDefault(EncodeDefault.Mode.NEVER) val ringAtMs: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val silenced: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val state: String? = null,
)

@Serializable
data class TripEndRequest(
    val dir: String,
    /** `arrived`; anything else cancels. */
    val reason: String = "",
)

@Serializable
data class TripEndAnswer(
    val state: String,
)
