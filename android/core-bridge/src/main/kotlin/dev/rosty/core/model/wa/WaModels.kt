@file:OptIn(ExperimentalSerializationApi::class)

package dev.rosty.core.model.wa

import dev.rosty.core.model.phone.ExternallyTagged
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The WhatsApp gateway wire types (`rosty_wa_proto`: api, meta, status, lookup, event), with the field names of the
// gateway. A field Rust leaves out when it is empty (`skip_serializing_if`) is `@EncodeDefault(NEVER)` here, so a
// model re-encodes to the JSON Rust wrote. Stability: `compose_stability.conf`.

// ---- instance, keys, push

@Serializable
enum class AppId {
    @SerialName("phone")
    Phone,

    @SerialName("messages")
    Messages,

    @SerialName("contacts")
    Contacts,

    @SerialName("stops")
    Stops,
}

/** How a new instance links to the phone: a QR code, or a pairing code for this number. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface LinkRequest {
    @Serializable
    @SerialName("qr")
    data object Qr : LinkRequest

    @Serializable
    @SerialName("phone_number")
    data class PhoneNumber(
        val number: String,
    ) : LinkRequest
}

@Serializable
data class CreateInstance(
    val link: LinkRequest,
    val app: AppId,
)

/** The first connect key; it is shown once. */
@Serializable
data class CreatedInstance(
    @SerialName("instance_id") val instanceId: String,
    val key: String,
    @SerialName("key_id") val keyId: String,
)

@Serializable
enum class InstanceState {
    @SerialName("linking")
    Linking,

    @SerialName("connected")
    Connected,

    @SerialName("disconnected")
    Disconnected,

    @SerialName("logged_out")
    LoggedOut,

    @SerialName("banned")
    Banned,
}

@Serializable
data class InstanceStatus(
    @SerialName("instance_id") val instanceId: String,
    val state: InstanceState,
    @SerialName("own_jid") val ownJid: String? = null,
    @SerialName("push_name") val pushName: String? = null,
    /** The account's LID, the other form of [ownJid]; older gateways leave it out. */
    @SerialName("own_lid") @EncodeDefault(NEVER) val ownLid: String? = null,
    /**
     * The id of the gateway process: a new one after every restart. Event sequences start over with it, so a stored
     * `seq` of another epoch says nothing. Older gateways leave it out.
     */
    @EncodeDefault(NEVER) val epoch: String? = null,
    /** Raised each time another account (own number) is linked to the instance; older gateways leave it out. */
    @SerialName("account_epoch") @EncodeDefault(NEVER) val accountEpoch: Int? = null,
    /** How far WhatsApp's history sync got; older gateways leave it out. */
    @EncodeDefault(NEVER) val history: HistoryStatus? = null,
)

/** `InstanceStatus.history`: the history sync of the account and what the gateway holds of it. */
@Serializable
data class HistoryStatus(
    /** [IDLE], [SYNCING] or [DONE]; a newer gateway may name another state. */
    val state: String,
    /** 0 to 100 over the whole sync; null when WhatsApp gave none or nothing is running. */
    val progress: Int? = null,
    /** Chats and messages the gateway holds for the account. */
    @EncodeDefault val chats: Int = 0,
    @EncodeDefault val messages: Long = 0,
    /** Time of the oldest held message (unix ms). */
    @SerialName("oldest_ms") val oldestMs: Long? = null,
) {
    companion object {
        /** No history sync arrived since the account was linked. */
        const val IDLE = "idle"

        /** Chunks are arriving; more are expected. */
        const val SYNCING = "syncing"

        /** The last expected chunk arrived, or none came for a long while. */
        const val DONE = "done"
    }
}

/** A key minted for another app; the plain key is only in this answer. */
@Serializable
data class IssuedKey(
    @SerialName("key_id") val keyId: String,
    val key: String,
    val label: String,
)

@Serializable
data class KeyInfo(
    @SerialName("key_id") val keyId: String,
    val label: String,
    @SerialName("created_ms") val createdMs: Long,
    @SerialName("last_used_ms") val lastUsedMs: Long? = null,
    val current: Boolean = false,
)

/**
 * `wa.register_push` (Rust `PushKeyedRegistration`): the FCM token and, from an app that has one, its push key (round
 * 7b). Without a key the gateway sends this app content-free pushes.
 */
@Serializable
data class PushRegistration(
    val app: AppId,
    val token: String,
    @SerialName("push_key") @EncodeDefault(NEVER) val pushKey: PushKey? = null,
)

/**
 * The public half of this install's push key: [publicKey] is the base64 X.509 `SubjectPublicKeyInfo` of a P-256
 * Keystore key, [version] the sealing scheme (1). The private half never leaves the Keystore.
 */
@Serializable
data class PushKey(
    @SerialName("key_id") val keyId: String,
    val version: Int,
    @SerialName("public_key") val publicKey: String,
)

// ---- messages

@Serializable
data class SendText(
    val chat: String,
    val text: String,
    @SerialName("quoted_id") val quotedId: String? = null,
    /** JIDs the text mentions with `@<digits>` tokens (`wa.mention_encode`); an older gateway ignores them. */
    @EncodeDefault(NEVER) val mentions: List<String> = emptyList(),
    /** The link card made on the phone; an older gateway ignores it. */
    @SerialName("link_preview") @EncodeDefault(NEVER) val linkPreview: LinkPreviewOut? = null,
)

/** The card of a link a text sends (`rosty_wa_proto::LinkPreviewOut`). */
@Serializable
data class LinkPreviewOut(
    /** The link as written in the text. */
    val url: String,
    @EncodeDefault(NEVER) val title: String? = null,
    @EncodeDefault(NEVER) val description: String? = null,
    /** A small JPEG, base64, at most [MediaOptions.MAX_THUMBNAIL_BYTES] decoded. */
    @SerialName("thumbnail_b64") @EncodeDefault(NEVER) val thumbnailB64: String? = null,
)

/** The card of the link a message carries (`rosty_wa_proto::LinkPreview`); [thumbnail]: the message has a picture. */
@Serializable
data class LinkPreview(
    val url: String,
    @EncodeDefault(NEVER) val title: String? = null,
    @EncodeDefault(NEVER) val description: String? = null,
    @EncodeDefault(NEVER) val thumbnail: Boolean = false,
)

/**
 * A line of the conversation, not a message (`rosty_wa_proto::SystemNote`): [type] `disappearing` (the chat's timer
 * became [seconds], 0 is off, set by [by]: [DELETED_BY_ME] or a JID); another type is one a newer gateway names.
 */
@Serializable
data class SystemNote(
    val type: String,
    @EncodeDefault(NEVER) val seconds: Long? = null,
    @EncodeDefault(NEVER) val by: String? = null,
    /** A group change (E6-WA R6): `add`, `remove`, `leave`, `join_link`, `promote`, `demote`, `subject`, ... */
    @EncodeDefault(NEVER) val action: String? = null,
    @EncodeDefault(NEVER) val participants: List<String> = emptyList(),
    /** The new subject or description, `on` / `off`, or `removed`. */
    @EncodeDefault(NEVER) val value: String? = null,
) {
    val isDisappearing: Boolean get() = type == DISAPPEARING

    /** A line of a group change (`wa.group_notice` reads it). */
    val isGroup: Boolean get() = type == GROUP

    /** The line as `wa.group_notice` takes it. */
    fun groupNote(): GroupNote = GroupNote(action.orEmpty(), by, participants, value)

    companion object {
        const val DISAPPEARING = "disappearing"
        const val GROUP = "group"
    }
}

/** `POST /v1/messages/forward`: send a copy of [messageId] of [chat] to [to], marked forwarded. */
@Serializable
data class ForwardMessage(
    val chat: String,
    @SerialName("message_id") val messageId: String,
    val to: String,
)

/** One person's receipts of a message of mine; a time of 0 is a state reached before the gateway kept times. */
@Serializable
data class ReceiptMember(
    val jid: String,
    @SerialName("jid_alt") @EncodeDefault(NEVER) val jidAlt: String? = null,
    @EncodeDefault(NEVER) val name: String? = null,
    @SerialName("delivered_ms") @EncodeDefault(NEVER) val deliveredMs: Long? = null,
    @SerialName("read_ms") @EncodeDefault(NEVER) val readMs: Long? = null,
    @SerialName("played_ms") @EncodeDefault(NEVER) val playedMs: Long? = null,
    /** No longer in the group. */
    @EncodeDefault(NEVER) val left: Boolean = false,
)

/** `wa.message_receipts`: who got, read or played one of my messages, and when. */
@Serializable
data class MessageReceipts(
    val chat: String,
    val id: String,
    @EncodeDefault(NEVER) val members: List<ReceiptMember> = emptyList(),
)

@Serializable
data class MessageReceiptsRequest(
    val chat: String,
    @SerialName("message_id") val messageId: String,
)

@Serializable
data class ScheduleText(
    val chat: String,
    val text: String,
    @SerialName("send_at_ms") val sendAtMs: Long,
)

@Serializable
@JsonClassDiscriminator("status")
sealed interface ScheduledState {
    @Serializable
    @SerialName("pending")
    data object Pending : ScheduledState

    @Serializable
    @SerialName("sent")
    data class Sent(
        @SerialName("message_id") val messageId: String,
    ) : ScheduledState

    @Serializable
    @SerialName("failed")
    data class Failed(
        val reason: String,
    ) : ScheduledState
}

@Serializable
data class ScheduledText(
    val id: String,
    val chat: String,
    val text: String,
    @SerialName("send_at_ms") val sendAtMs: Long,
    @SerialName("created_ms") val createdMs: Long,
    val state: ScheduledState,
)

@Serializable
data class Sent(
    @SerialName("message_id") val messageId: String,
    @SerialName("timestamp_ms") val timestampMs: Long,
)

@Serializable
data class SendReaction(
    val chat: String,
    @SerialName("message_id") val messageId: String,
    @SerialName("from_me") val fromMe: Boolean,
    val sender: String? = null,
    val emoji: String,
)

/** Read (and played) receipts: [ids] of the messages of [chat], [sender] in groups. */
@Serializable
data class MarkRead(
    val chat: String,
    val sender: String? = null,
    val ids: List<String>,
)

@Serializable
enum class TypingState {
    @SerialName("typing")
    Typing,

    @SerialName("recording")
    Recording,

    @SerialName("paused")
    Paused,
}

@Serializable
data class ChatState(
    val chat: String,
    val state: TypingState,
)

@Serializable
enum class MediaKind {
    @SerialName("image")
    Image,

    @SerialName("video")
    Video,

    @SerialName("audio")
    Audio,

    @SerialName("document")
    Document,

    @SerialName("sticker")
    Sticker,
}

@Serializable
data class Reaction(
    val sender: String? = null,
    @SerialName("from_me") val fromMe: Boolean,
    val emoji: String,
    /** The other form of [sender] (its LID next to a number, or the reverse), when the gateway knows it. */
    @SerialName("sender_alt") @EncodeDefault(NEVER) val senderAlt: String? = null,
)

/** The body of a message: `{"text": ...}`, `{"media": {...}}` or `"unsupported"`. */
@Serializable(with = MessageBodySerializer::class)
sealed interface MessageBody {
    data class Text(
        val text: String,
    ) : MessageBody

    data class Media(
        val kind: MediaKind,
        val caption: String?,
        val mime: String,
        val size: Long,
    ) : MessageBody

    data object Unsupported : MessageBody
}

internal object MessageBodySerializer : ExternallyTagged<MessageBody>("MessageBody") {
    override fun fromTag(
        tag: String,
        value: JsonElement?,
    ): MessageBody =
        when (tag) {
            "text" -> {
                MessageBody.Text((value as? JsonPrimitive)?.content ?: unknown(tag))
            }

            "media" -> {
                val media = value as? JsonObject ?: unknown(tag)
                MessageBody.Media(
                    kind =
                        MediaKind.entries.firstOrNull { kindName(it) == (media["kind"] as? JsonPrimitive)?.content }
                            ?: throw SerializationException("MessageBody: unknown media kind"),
                    caption = (media["caption"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                    mime = (media["mime"] as? JsonPrimitive)?.content ?: unknown(tag),
                    size = (media["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: unknown(tag),
                )
            }

            "unsupported" -> {
                MessageBody.Unsupported
            }

            else -> {
                unknown(tag)
            }
        }

    override fun toJson(value: MessageBody): JsonElement =
        when (value) {
            is MessageBody.Text -> {
                tagged("text", JsonPrimitive(value.text))
            }

            is MessageBody.Media -> {
                tagged(
                    "media",
                    JsonObject(
                        mapOf(
                            "kind" to JsonPrimitive(kindName(value.kind)),
                            "caption" to
                                (value.caption?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull),
                            "mime" to JsonPrimitive(value.mime),
                            "size" to JsonPrimitive(value.size),
                        ),
                    ),
                )
            }

            MessageBody.Unsupported -> {
                JsonPrimitive("unsupported")
            }
        }

    private fun kindName(kind: MediaKind) = kind.name.lowercase()
}

@Serializable
data class WaMessage(
    val id: String,
    val chat: String,
    val sender: String? = null,
    @SerialName("from_me") val fromMe: Boolean,
    @SerialName("timestamp_ms") val timestampMs: Long,
    val body: MessageBody,
    val reactions: List<Reaction>,
    /** The other form of [chat] in a one-to-one chat, when the gateway knows it. The store learns the pair. */
    @SerialName("chat_alt") @EncodeDefault(NEVER) val chatAlt: String? = null,
    /** The other form of [sender], when the gateway knows it. */
    @SerialName("sender_alt") @EncodeDefault(NEVER) val senderAlt: String? = null,
)

@Serializable
data class ChatSummary(
    val jid: String,
    val name: String? = null,
    val last: WaMessage? = null,
    val unread: Int,
    @SerialName("is_group") val isGroup: Boolean,
    /** The other form of [jid] in a one-to-one chat, when the gateway knows it. */
    @EncodeDefault(NEVER) val alt: String? = null,
)

// ---- message and chat meta

@Serializable
enum class DeliveryStatus {
    @SerialName("sent")
    Sent,

    @SerialName("delivered")
    Delivered,

    @SerialName("read")
    Read,

    @SerialName("played")
    Played,
}

@Serializable
data class Quoted(
    val id: String,
    val sender: String? = null,
    val preview: String? = null,
)

@Serializable
data class MediaMeta(
    @SerialName("file_name") @EncodeDefault(NEVER) val fileName: String? = null,
    @EncodeDefault(NEVER) val seconds: Int? = null,
    @EncodeDefault(NEVER) val width: Int? = null,
    @EncodeDefault(NEVER) val height: Int? = null,
    @EncodeDefault(NEVER) val pages: Int? = null,
    @SerialName("voice_note") @EncodeDefault(NEVER) val voiceNote: Boolean = false,
    @EncodeDefault(NEVER) val animated: Boolean = false,
    @SerialName("view_once") @EncodeDefault(NEVER) val viewOnce: Boolean = false,
    /** A preview image can be fetched with `thumbnail`. */
    @EncodeDefault(NEVER) val thumbnail: Boolean = false,
    /**
     * The waveform of a voice note as WhatsApp carries it: base64 of 64 loudness samples, each 0 to 100. Read it
     * with [waveform], which checks it.
     */
    @SerialName("waveform_b64") @EncodeDefault(NEVER) val waveformB64: String? = null,
) {
    /** The 64 samples (0 to 100) of a voice note; null when the message has none or what came is not one. */
    fun waveform(): ByteArray? = MediaWaveform.decode(waveformB64)
}

/** The waveform of a voice note on the wire: exactly [SAMPLES] bytes, each 0 to [MAX], as standard base64. */
object MediaWaveform {
    const val SAMPLES = 64
    const val MAX = 100

    /** The samples of [b64]; null when it is missing, not base64, not [SAMPLES] long or out of range. */
    fun decode(b64: String?): ByteArray? {
        val text = b64?.takeIf { it.isNotEmpty() } ?: return null
        val bytes =
            try {
                java.util.Base64
                    .getDecoder()
                    .decode(text)
            } catch (_: IllegalArgumentException) {
                return null
            }
        return bytes.takeIf(::valid)
    }

    /** [samples] for `waveform_b64`; null when they are not a waveform (the gateway would refuse them). */
    fun encode(samples: ByteArray): String? =
        if (valid(samples)) {
            java.util.Base64
                .getEncoder()
                .encodeToString(samples)
        } else {
            null
        }

    fun valid(samples: ByteArray): Boolean = samples.size == SAMPLES && samples.all { it in 0..MAX }
}

/**
 * Why a message reached this linked device as a placeholder only (`rosty_wa_proto::Unavailable`): WhatsApp never
 * shares it with a companion, only the phone can open it. [Other] is a reason a newer gateway names.
 */
@Serializable
enum class Unavailable {
    @SerialName("view_once")
    ViewOnce,

    @SerialName("hosted")
    Hosted,

    @SerialName("bot")
    Bot,

    @SerialName("other")
    Other,
}

@Serializable
data class Location(
    val latitude: Double,
    val longitude: Double,
    @EncodeDefault(NEVER) val name: String? = null,
    @EncodeDefault(NEVER) val address: String? = null,
    /** A live location: only its first point is known. */
    @EncodeDefault(NEVER) val live: Boolean = false,
    /** A link the sender attached (E6-WA R5). */
    @EncodeDefault(NEVER) val url: String? = null,
    /** A note the sender wrote with it (E6-WA R5). */
    @EncodeDefault(NEVER) val comment: String? = null,
)

@Serializable
data class SharedContact(
    @SerialName("display_name") val displayName: String,
    val vcard: String,
)

@Serializable
data class MessageMeta(
    @EncodeDefault(NEVER) val quoted: Quoted? = null,
    @EncodeDefault(NEVER) val forwarded: Boolean = false,
    @SerialName("edited_ms") @EncodeDefault(NEVER) val editedMs: Long? = null,
    @EncodeDefault(NEVER) val revoked: Boolean = false,
    @EncodeDefault(NEVER) val status: DeliveryStatus? = null,
    @SerialName("sender_name") @EncodeDefault(NEVER) val senderName: String? = null,
    @EncodeDefault(NEVER) val media: MediaMeta? = null,
    @EncodeDefault(NEVER) val location: Location? = null,
    @EncodeDefault(NEVER) val contact: SharedContact? = null,
    @EncodeDefault(NEVER) val mentions: List<String> = emptyList(),
    @EncodeDefault(NEVER) val starred: Boolean = false,
    /** Deleted (for everyone or for me) and kept by the gateway; `revoked` is set too. */
    @EncodeDefault(NEVER) val deleted: Boolean = false,
    /** When it was deleted (unix ms); null when unknown. */
    @SerialName("deleted_at") @EncodeDefault(NEVER) val deletedAt: Long? = null,
    /** Who deleted it: [DELETED_BY_ME] or the JID of the sender or group admin. */
    @SerialName("deleted_by") @EncodeDefault(NEVER) val deletedBy: String? = null,
    /** What a deleted message said, for "show content" (`rosty_wa_proto::KeptContent`). */
    @EncodeDefault(NEVER) val original: KeptContent? = null,
    /** Earlier texts of an edited message, oldest first. */
    @SerialName("edit_history") @EncodeDefault(NEVER) val editHistory: List<EditRecord> = emptyList(),
    /** A placeholder only (a view-once message someone sent): the body is `unsupported`, nothing to download. */
    @EncodeDefault(NEVER) val unavailable: Unavailable? = null,
    /** How often it was forwarded before; from 5 on show "Forwarded many times". */
    @SerialName("forwarding_score") @EncodeDefault(NEVER) val forwardingScore: Int? = null,
    @SerialName("link_preview") @EncodeDefault(NEVER) val linkPreview: LinkPreview? = null,
    /** The disappearing timer it carries, in seconds. */
    @SerialName("expires_s") @EncodeDefault(NEVER) val expiresS: Long? = null,
    /** A line of the conversation, not a message. */
    @EncodeDefault(NEVER) val system: SystemNote? = null,
    /** A poll with its votes and my choice (E6-WA R5); the body is `unsupported`. */
    @EncodeDefault(NEVER) val poll: Poll? = null,
    /** Every card of a contacts message; [contact] is the first (E6-WA R5). */
    @EncodeDefault(NEVER) val contacts: List<SharedContact> = emptyList(),
    /** Kept in a disappearing chat: it does not disappear (E6-WA R5). */
    @EncodeDefault(NEVER) val kept: Boolean = false,
)

/** `rosty_wa_proto::DELETED_BY_ME`. */
const val DELETED_BY_ME: String = "me"

/** The content of a deleted message the gateway kept (`rosty_wa_proto::KeptContent`). */
@Serializable
data class KeptContent(
    val body: MessageBody,
    @EncodeDefault(NEVER) val meta: MessageMeta = MessageMeta(),
)

/** One earlier text of an edited message (`rosty_wa_proto::EditRecord`). */
@Serializable
data class EditRecord(
    val text: String,
    @SerialName("edited_at") val editedAt: Long,
)

/** A message with its meta (`rosty_wa_proto::RichMessage`, whose message fields are flat). */
@Serializable
data class RichMessage(
    val id: String,
    val chat: String,
    val sender: String? = null,
    @SerialName("from_me") val fromMe: Boolean,
    @SerialName("timestamp_ms") val timestampMs: Long,
    val body: MessageBody,
    val reactions: List<Reaction>,
    @EncodeDefault(NEVER) val meta: MessageMeta = MessageMeta(),
    @SerialName("chat_alt") @EncodeDefault(NEVER) val chatAlt: String? = null,
    @SerialName("sender_alt") @EncodeDefault(NEVER) val senderAlt: String? = null,
) {
    fun message() = WaMessage(id, chat, sender, fromMe, timestampMs, body, reactions, chatAlt, senderAlt)
}

@Serializable
data class ChatMeta(
    @SerialName("picture_id") @EncodeDefault(NEVER) val pictureId: String? = null,
    @SerialName("push_name") @EncodeDefault(NEVER) val pushName: String? = null,
    @EncodeDefault(NEVER) val archived: Boolean = false,
    @EncodeDefault(NEVER) val pinned: Boolean = false,
    /** `-1` is muted forever. */
    @SerialName("muted_until_ms") @EncodeDefault(NEVER) val mutedUntilMs: Long? = null,
    @SerialName("marked_unread") @EncodeDefault(NEVER) val markedUnread: Boolean = false,
    /** The disappearing timer in seconds; null when off. */
    @SerialName("ephemeral_s") @EncodeDefault(NEVER) val ephemeralS: Long? = null,
    /** A group I left or was removed from (E6-WA R6): readable, nothing can be sent. */
    @EncodeDefault(NEVER) val left: Boolean = false,
)

/** A chat with its meta (`rosty_wa_proto::RichChat`, whose summary fields are flat). */
@Serializable
data class RichChat(
    val jid: String,
    val name: String? = null,
    val last: WaMessage? = null,
    val unread: Int,
    @SerialName("is_group") val isGroup: Boolean,
    @EncodeDefault(NEVER) val meta: ChatMeta = ChatMeta(),
    @SerialName("last_meta") @EncodeDefault(NEVER) val lastMeta: MessageMeta? = null,
    /** The other form of [jid] in a one-to-one chat, when the gateway knows it. */
    @EncodeDefault(NEVER) val alt: String? = null,
) {
    fun summary() = ChatSummary(jid, name, last, unread, isGroup, alt)
}

@Serializable
data class WaProfile(
    val jid: String,
    @SerialName("push_name") val pushName: String? = null,
    @SerialName("contact_name") val contactName: String? = null,
    val about: String? = null,
    @SerialName("picture_id") val pictureId: String? = null,
    val business: Boolean = false,
    @SerialName("verified_name") val verifiedName: String? = null,
    @SerialName("fetched_ms") val fetchedMs: Long = 0,
    /** The person's LID when [jid] is their number and the gateway knows it; older gateways leave it out. */
    @EncodeDefault(NEVER) val lid: String? = null,
) {
    /** The name to show: saved name, verified business name, push name. */
    fun displayName(): String? =
        listOf(contactName, verifiedName, pushName).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
}

@Serializable
data class EditMessage(
    val chat: String,
    @SerialName("message_id") val messageId: String,
    val text: String,
)

@Serializable
data class RevokeMessage(
    val chat: String,
    @SerialName("message_id") val messageId: String,
    val sender: String? = null,
)

@Serializable
data class DeleteForMe(
    val chat: String,
    @SerialName("message_id") val messageId: String,
)

@Serializable
data class StarMessage(
    val chat: String,
    @SerialName("message_id") val messageId: String,
    val starred: Boolean,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ChatActionKind {
    @Serializable
    @SerialName("archive")
    data class Archive(
        val archived: Boolean,
    ) : ChatActionKind

    @Serializable
    @SerialName("pin")
    data class Pin(
        val pinned: Boolean,
    ) : ChatActionKind

    /** [untilMs] null mutes for good. */
    @Serializable
    @SerialName("mute")
    data class Mute(
        @SerialName("until_ms") val untilMs: Long? = null,
    ) : ChatActionKind

    @Serializable
    @SerialName("unmute")
    data object Unmute : ChatActionKind

    @Serializable
    @SerialName("mark_read")
    data class MarkRead(
        val read: Boolean,
    ) : ChatActionKind

    /** The chat's disappearing timer: one of [CHOICES] seconds (0 turns it off). An older gateway refuses it. */
    @Serializable
    @SerialName("disappearing")
    data class Disappearing(
        val seconds: Long,
    ) : ChatActionKind {
        companion object {
            /** Off, 24 hours, 7 days, 90 days. */
            val CHOICES: List<Long> = listOf(0L, 86_400L, 604_800L, 7_776_000L)
        }
    }
}

@Serializable
data class ChatAction(
    val chat: String,
    val action: ChatActionKind,
)

/**
 * Extras of a media upload. The first four are always sent; the rest (E6-WA R3) only when set. The gateway puts
 * each on the message when the kind has a field for it and makes nothing up when it is missing: [thumbnailB64] on
 * a photo, video or document, [width] and [height] on a photo, video or sticker, [waveformB64] on audio,
 * [animated] on a video (it plays as a GIF) or a sticker. [viewOnce] is for a photo, a video or audio only: on a
 * document or sticker the call is refused. A bad preview or waveform is refused too, before anything is uploaded.
 */
@Serializable
data class MediaOptions(
    @SerialName("quoted_id") val quotedId: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    val seconds: Int? = null,
    @SerialName("voice_note") val voiceNote: Boolean = false,
    /** A small JPEG preview, base64; at most [MAX_THUMBNAIL_BYTES] decoded. */
    @SerialName("thumbnail_b64") @EncodeDefault(NEVER) val thumbnailB64: String? = null,
    @EncodeDefault(NEVER) val width: Int? = null,
    @EncodeDefault(NEVER) val height: Int? = null,
    /** [MediaWaveform.encode]: exactly 64 samples, each 0 to 100. */
    @SerialName("waveform_b64") @EncodeDefault(NEVER) val waveformB64: String? = null,
    @EncodeDefault(NEVER) val animated: Boolean = false,
    @SerialName("view_once") @EncodeDefault(NEVER) val viewOnce: Boolean = false,
) {
    companion object {
        /** The largest preview the gateway takes. */
        const val MAX_THUMBNAIL_BYTES = 64 * 1024
    }
}

// ---- groups, lookup

@Serializable
data class Participant(
    val jid: String,
    val admin: Boolean,
    @EncodeDefault(NEVER) val name: String? = null,
    /** Their LID, when the gateway knows it. */
    @EncodeDefault(NEVER) val lid: String? = null,
    /** Their phone-number JID, when the gateway knows it; a member who hides their number has none. */
    @EncodeDefault(NEVER) val pn: String? = null,
    /** `superadmin`, `admin` or `member` (E6-WA R6); older gateways leave it out ([admin] stays). */
    @EncodeDefault(NEVER) val role: String? = null,
)

@Serializable
data class GroupInfo(
    val jid: String,
    val subject: String,
    val participants: List<Participant>,
    @EncodeDefault(NEVER) val description: String? = null,
    @EncodeDefault(NEVER) val announce: Boolean = false,
    /** How WhatsApp names the members on the wire: `pn` or `lid`; older gateways leave it out. */
    @SerialName("addressing_mode") @EncodeDefault(NEVER) val addressingMode: String? = null,
    // E6-WA R6 from here on; older gateways leave every field out.
    @SerialName("created_ms") @EncodeDefault(NEVER) val createdMs: Long? = null,
    @EncodeDefault(NEVER) val creator: String? = null,
    @SerialName("description_id") @EncodeDefault(NEVER) val descriptionId: String? = null,
    /** Only admins edit the subject, description and picture. */
    @EncodeDefault(NEVER) val locked: Boolean = false,
    /** New members need an admin's approval. */
    @EncodeDefault(NEVER) val approval: Boolean = false,
    /** Only admins add members. */
    @SerialName("admins_add") @EncodeDefault(NEVER) val adminsAdd: Boolean = false,
    @SerialName("ephemeral_s") @EncodeDefault(NEVER) val ephemeralS: Long? = null,
    @EncodeDefault(NEVER) val size: Int? = null,
    @EncodeDefault(NEVER) val community: CommunityRef? = null,
    /** I left the group or was removed; the members may be empty. */
    @EncodeDefault(NEVER) val left: Boolean = false,
)

// ---- identity: LID and phone-number pairs

/** One pair the gateway learned: [lid] and [pn] are the same person (`rosty_wa_proto::IdentityMapping`). */
@Serializable
data class IdentityMapping(
    val lid: String,
    val pn: String,
    /** Where the pair comes from (`peer_message`, `usync`, ...); a name a newer gateway adds still decodes. */
    val source: String,
    @SerialName("learned_ms") val learnedMs: Long,
)

/**
 * `wa.identity_mappings`: one page of the gateway's pairs, to hand to the offline store
 * (`WaSyncStore.identityCatchUp`). [supported] false is a gateway from before the list: not an error, and nothing
 * to hand in.
 */
@Serializable
data class IdentityMappingsAnswer(
    val supported: Boolean,
    val items: List<IdentityMapping> = emptyList(),
    /** The cursor of the page after this one; null when this page is empty. */
    val next: String? = null,
)

/** The body of `wa.identity_mappings`: the cursor of the page before; left out for every pair. */
@Serializable
data class IdentityMappingsBody(
    @EncodeDefault(NEVER) val since: String? = null,
)

@Serializable
data class WaUser(
    val jid: String,
    @SerialName("on_whatsapp") val onWhatsapp: Boolean,
    val business: Boolean = false,
    @SerialName("verified_name") val verifiedName: String? = null,
)

// ---- statuses (stories)

@Serializable
enum class StatusKind {
    @SerialName("text")
    Text,

    @SerialName("image")
    Image,

    @SerialName("video")
    Video,

    @SerialName("other")
    Other,
}

@Serializable
data class StatusMedia(
    val mime: String = "",
    val size: Long = 0,
    @EncodeDefault(NEVER) val seconds: Int? = null,
    @EncodeDefault(NEVER) val width: Int? = null,
    @EncodeDefault(NEVER) val height: Int? = null,
    @EncodeDefault(NEVER) val thumbnail: Boolean = false,
)

@Serializable
data class StatusItem(
    val id: String,
    val author: String,
    @SerialName("from_me") @EncodeDefault(NEVER) val fromMe: Boolean = false,
    @SerialName("timestamp_ms") val timestampMs: Long,
    @SerialName("expires_ms") val expiresMs: Long = 0,
    val kind: StatusKind = StatusKind.Text,
    @EncodeDefault(NEVER) val text: String? = null,
    @SerialName("background_argb") @EncodeDefault(NEVER) val backgroundArgb: Long? = null,
    @SerialName("text_argb") @EncodeDefault(NEVER) val textArgb: Long? = null,
    @EncodeDefault(NEVER) val font: Int? = null,
    @EncodeDefault(NEVER) val media: StatusMedia? = null,
    @SerialName("author_name") @EncodeDefault(NEVER) val authorName: String? = null,
    @EncodeDefault(NEVER) val viewed: Boolean = false,
)

@Serializable
data class StatusAuthor(
    val author: String,
    @EncodeDefault(NEVER) val name: String? = null,
    val items: List<StatusItem> = emptyList(),
)

@Serializable
data class StatusList(
    val mine: List<StatusItem> = emptyList(),
    val authors: List<StatusAuthor> = emptyList(),
)
