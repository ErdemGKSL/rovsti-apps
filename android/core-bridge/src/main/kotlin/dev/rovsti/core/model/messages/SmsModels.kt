@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.messages

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Models of `sms.*`, `sms_timeline.build`, `mms_pdu.*` and `otp.find` (E2.11a), mirroring `crates/rovsti_ffi/fixtures/`.
// Stability: `compose_stability.conf`.

// ---- sms.*

@Serializable
data class SegmentsRequest(
    val body: String,
)

/** What a body costs: SMS parts, room left in the last one, and whether it needs UCS-2 (70/67 per part, not 160/153). */
@Serializable
data class SegmentInfo(
    val parts: Int,
    @SerialName("left_in_part") val leftInPart: Int,
    val unicode: Boolean,
)

@Serializable
data class ToMessageRequest(
    val row: SmsRow,
)

@Serializable
data class ToMessageAnswer(
    val message: Message,
)

/** `sms.key` request: give one of the two to get both (`"sms:12"` is the key the delete bridge uses). */
@Serializable
data class SmsKeyRequest(
    @SerialName("provider_id") @EncodeDefault(NEVER) val providerId: String? = null,
    @EncodeDefault(NEVER) val key: SmsKey? = null,
)

@Serializable
data class SmsKeyAnswer(
    val key: SmsKey?,
    @SerialName("provider_id") val providerId: String?,
)

// ---- sms_timeline.build

/**
 * One thing that happened to a thread, replayed in order by `sms_timeline.build`. [kind] picks the event and the other
 * fields are its arguments; build one with the factories on the companion.
 */
@Serializable
data class TimelineEvent(
    /** `page`, `pending`, `status`, `retry`, `discard`, `remove` or `adopt_thread`. */
    val kind: String,
    @EncodeDefault(NEVER) val page: SmsMessagesPayload? = null,
    @SerialName("local_id") @EncodeDefault(NEVER) val localId: Long? = null,
    @EncodeDefault(NEVER) val body: String? = null,
    @SerialName("sub_id") @EncodeDefault(NEVER) val subId: Int? = null,
    @SerialName("now_ms") @EncodeDefault(NEVER) val nowMs: Long? = null,
    @EncodeDefault(NEVER) val status: SmsStatus? = null,
    @EncodeDefault(NEVER) val key: SmsKey? = null,
    @SerialName("thread_id") @EncodeDefault(NEVER) val threadId: Long? = null,
) {
    companion object {
        /** A provider page. */
        fun page(page: SmsMessagesPayload) = TimelineEvent("page", page = page)

        /** An optimistic outgoing message. */
        fun pending(
            localId: Long,
            body: String,
            nowMs: Long,
            subId: Int? = null,
        ) = TimelineEvent("pending", localId = localId, body = body, subId = subId, nowMs = nowMs)

        /** An `sms_status` event. */
        fun status(status: SmsStatus) = TimelineEvent("status", status = status)

        /** Takes a failed local send back to pending; the result carries the request to send again. */
        fun retry(localId: Long) = TimelineEvent("retry", localId = localId)

        /** Drops a failed local send; the result carries its text. */
        fun discard(localId: Long) = TimelineEvent("discard", localId = localId)

        fun remove(key: SmsKey) = TimelineEvent("remove", key = key)

        /** A draft's first send created the provider thread. */
        fun adoptThread(threadId: Long) = TimelineEvent("adopt_thread", threadId = threadId)
    }
}

@Serializable
data class TimelineRequest(
    @SerialName("thread_id") val threadId: Long,
    /** Who a retry sends to. */
    @EncodeDefault(NEVER) val recipients: List<String> = emptyList(),
    @EncodeDefault(NEVER) val events: List<TimelineEvent> = emptyList(),
)

/** One bubble of a thread, ascending by time. */
@Serializable
data class TimelineItem(
    val key: SmsKey,
    val outgoing: Boolean,
    val body: String,
    val parts: List<MmsPart>,
    @SerialName("at_ms") val atMs: Long,
    val state: DeliveryState,
    @SerialName("sub_id") val subId: Int?,
    /** `"sms:12"` for the delete bridge; `null` for a send without a provider row (yet). */
    @SerialName("provider_id") val providerId: String?,
)

/** What one event did. */
@Serializable
data class EventResult(
    val changed: Boolean,
    /** `retry`: the request to send again. */
    val send: SmsSend?,
    /** `discard`: the text of the dropped message. */
    val text: String?,
)

@Serializable
data class TimelineAnswer(
    @SerialName("thread_id") val threadId: Long,
    val items: List<TimelineItem>,
    @SerialName("has_older") val hasOlder: Boolean,
    /** Timestamp of the oldest provider-backed item, for paging. */
    @SerialName("oldest_ms") val oldestMs: Long?,
    /** One per event, in order. */
    val results: List<EventResult>,
)

// ---- mms_pdu.*

@Serializable
data class PduParseRequest(
    /** The PDU bytes, standard base64. */
    val bytes: String,
    /** `retrieve_conf` (the downloaded message) or `notification_ind` (the push that announces one). */
    val kind: String,
)

/** The `m-notification-ind` push: where to download the message from. */
@Serializable
data class PduNotification(
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("content_location") val contentLocation: String,
    val from: String?,
    val size: Long,
    @SerialName("expiry_s") val expiryS: Long?,
)

/** One part of an MMS body; [data] is standard base64. */
@Serializable
data class PduPart(
    @SerialName("content_type") val contentType: String,
    val name: String?,
    val data: String,
)

/** The `m-retrieve-conf` message. */
@Serializable
data class PduRetrieve(
    @SerialName("transaction_id") val transactionId: String?,
    val from: String?,
    val to: List<String>,
    val subject: String?,
    @SerialName("date_s") val dateS: Long?,
    val parts: List<PduPart>,
)

@Serializable
data class PduParseAnswer(
    val notification: PduNotification?,
    val retrieve: PduRetrieve?,
)

@Serializable
data class SendReqRequest(
    val to: List<String>,
    @EncodeDefault(NEVER) val subject: String? = null,
    val parts: List<PduPart>,
    @SerialName("transaction_id") val transactionId: String,
)

@Serializable
data class NotifyRespRequest(
    @SerialName("transaction_id") val transactionId: String,
    val retrieved: Boolean,
)

/** A built PDU, standard base64. */
@Serializable
data class BytesAnswer(
    val bytes: String,
)

// ---- otp.find

@Serializable
data class OtpRequest(
    val body: String,
)

/** A passcode found in a message: the digits, and where they stand (UTF-16 offsets, separator included). */
@Serializable
data class OtpHit(
    val code: String,
    val start: Int,
    val end: Int,
)

@Serializable
data class OtpAnswer(
    val otp: OtpHit?,
)
