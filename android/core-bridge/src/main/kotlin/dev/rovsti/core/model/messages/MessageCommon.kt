@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.messages

import dev.rovsti.core.model.phone.ExternallyTagged
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

// Types several Messages ops share: threads, messages, SMS/MMS provider rows. Mirrors `rovsti_core::{thread, sms}`.
// Stability: `compose_stability.conf`.

/** Where a conversation lives (`rovsti_core::Channel`, written with the Rust variant names). */
@Serializable
enum class Channel {
    @SerialName("Sms")
    Sms,

    @SerialName("WhatsApp")
    WhatsApp,
}

@Serializable
enum class MessageDirection {
    @SerialName("Incoming")
    Incoming,

    @SerialName("Outgoing")
    Outgoing,
}

/** A phone number as the core keeps it: digits, `+` first when international. */
@Serializable
data class PhoneNumber(
    val canonical: String,
)

/** The conversation behind a message: an SMS thread id or a WhatsApp JID (`{"Sms": 5}`, `{"WhatsApp": "...@g.us"}`). */
@Serializable(with = ThreadIdSerializer::class)
sealed interface ThreadId {
    /** `Telephony.Threads._ID`. */
    data class Sms(
        val id: Long,
    ) : ThreadId

    /** A JID such as `905321234567@s.whatsapp.net` or `...@g.us`. */
    data class WhatsApp(
        val jid: String,
    ) : ThreadId

    val channel: Channel get() = if (this is Sms) Channel.Sms else Channel.WhatsApp
}

internal object ThreadIdSerializer : ExternallyTagged<ThreadId>("ThreadId") {
    override fun fromTag(
        tag: String,
        value: JsonElement?,
    ): ThreadId =
        when (tag) {
            "Sms" -> ThreadId.Sms((value ?: unknown(tag)).jsonPrimitive.content.toLong())
            "WhatsApp" -> ThreadId.WhatsApp((value ?: unknown(tag)).jsonPrimitive.content)
            else -> unknown(tag)
        }

    override fun toJson(value: ThreadId): JsonElement =
        when (value) {
            is ThreadId.Sms -> tagged("Sms", JsonPrimitive(value.id))
            is ThreadId.WhatsApp -> tagged("WhatsApp", JsonPrimitive(value.jid))
        }
}

/** One message in the unified model (`rovsti_core::Message`). */
@Serializable
data class Message(
    val thread: ThreadId,
    val direction: MessageDirection,
    /** The sender of an incoming message; `null` for our own. */
    val sender: PhoneNumber?,
    val body: String,
    @SerialName("timestamp_ms") val timestampMs: Long,
    val read: Boolean,
)

/** How far an outgoing message got. */
@Serializable
enum class DeliveryState {
    @SerialName("pending")
    Pending,

    @SerialName("sent")
    Sent,

    @SerialName("delivered")
    Delivered,

    @SerialName("failed")
    Failed,

    @SerialName("none")
    None,
}

/** One row of `Telephony.Sms`. [box] is `Telephony.TextBasedSmsColumns.MESSAGE_TYPE_*` (1 inbox, 2 sent, 3 draft...). */
@Serializable
data class SmsRow(
    val id: Long,
    @SerialName("thread_id") val threadId: Long,
    val address: String,
    val body: String,
    @SerialName("date_ms") val dateMs: Long,
    val box: Int,
    val read: Boolean,
    @SerialName("sub_id") val subId: Int?,
    val delivery: DeliveryState,
)

/** One MMS part; binary parts are files Kotlin wrote, only the path travels. */
@Serializable
data class MmsPart(
    @SerialName("content_type") val contentType: String,
    val text: String?,
    val path: String?,
)

/** One MMS of a thread. */
@Serializable
data class MmsRow(
    val id: Long,
    @SerialName("thread_id") val threadId: Long,
    val addresses: List<String>,
    val from: String?,
    @SerialName("date_ms") val dateMs: Long,
    val outgoing: Boolean,
    val read: Boolean,
    val parts: List<MmsPart>,
    @SerialName("sub_id") val subId: Int?,
)

/** One conversation of `Telephony.Threads`. */
@Serializable
data class SmsThreadRow(
    @SerialName("thread_id") val threadId: Long,
    val addresses: List<String>,
    val snippet: String,
    @SerialName("date_ms") val dateMs: Long,
    val unread: Int,
)

/** One pull of a thread: SMS and MMS rows in any order, and whether older ones exist. */
@Serializable
data class SmsMessagesPayload(
    val sms: List<SmsRow>,
    val mms: List<MmsRow>,
    @SerialName("has_older") val hasOlder: Boolean,
)

/** A send request, Rust to Kotlin: what the platform sends and the status events answer to. */
@Serializable
data class SmsSend(
    @SerialName("local_id") val localId: Long,
    val to: List<String>,
    val body: String,
    @SerialName("sub_id") val subId: Int?,
)

/** The `sms_status` event: a send moved along. */
@Serializable
data class SmsStatus(
    @SerialName("local_id") val localId: Long,
    @EncodeDefault(NEVER) val uri: String? = null,
    val state: DeliveryState,
    @SerialName("error_code") @EncodeDefault(NEVER) val errorCode: Int? = null,
)

/** The key of an item of a timeline: a provider row or a send this app started (`{"sms": 12}`, `{"local": 3}`). */
@Serializable(with = SmsKeySerializer::class)
sealed interface SmsKey {
    /** `content://sms/<id>`. */
    data class Sms(
        val id: Long,
    ) : SmsKey

    /** `content://mms/<id>`. */
    data class Mms(
        val id: Long,
    ) : SmsKey

    /** A send before (or without) a provider row. */
    data class Local(
        val id: Long,
    ) : SmsKey
}

internal object SmsKeySerializer : ExternallyTagged<SmsKey>("SmsKey") {
    override fun fromTag(
        tag: String,
        value: JsonElement?,
    ): SmsKey {
        val id = (value ?: unknown(tag)).jsonPrimitive.content.toLong()
        return when (tag) {
            "sms" -> SmsKey.Sms(id)
            "mms" -> SmsKey.Mms(id)
            "local" -> SmsKey.Local(id)
            else -> unknown(tag)
        }
    }

    override fun toJson(value: SmsKey): JsonElement =
        when (value) {
            is SmsKey.Sms -> tagged("sms", JsonPrimitive(value.id))
            is SmsKey.Mms -> tagged("mms", JsonPrimitive(value.id))
            is SmsKey.Local -> tagged("local", JsonPrimitive(value.id))
        }
}
