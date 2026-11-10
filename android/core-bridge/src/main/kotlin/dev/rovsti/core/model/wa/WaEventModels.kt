@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.wa

import dev.rovsti.core.model.phone.CallDirection
import dev.rovsti.core.model.phone.CallPhase
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

// The calls of the gateway, the events it pushes (`GET /v1/events`) and the shape `wa.events_start` forwards them in
// (`crates/rovsti_ffi/src/ops/wa/live.rs`).

/** A WhatsApp call as the gateway reports it. Reuses the phone tracker's [CallPhase] and [CallDirection]. */
@Serializable
data class CallUpdate(
    @SerialName("call_id") val callId: String,
    val peer: String,
    @SerialName("peer_name") val peerName: String? = null,
    val direction: CallDirection,
    val phase: CallPhase,
    @SerialName("is_video") val isVideo: Boolean,
)

/** One entry of `wa.calls` (`rovsti_wa_proto::CallSummary`, whose update fields are flat). */
@Serializable
data class CallSummary(
    @SerialName("call_id") val callId: String,
    val peer: String,
    @SerialName("peer_name") val peerName: String? = null,
    val direction: CallDirection,
    val phase: CallPhase,
    @SerialName("is_video") val isVideo: Boolean,
    @SerialName("started_ms") val startedMs: Long,
    @SerialName("ended_ms") val endedMs: Long? = null,
) {
    fun update() = CallUpdate(callId, peer, peerName, direction, phase, isVideo)
}

@Serializable
enum class ReceiptKind {
    @SerialName("delivered")
    Delivered,

    @SerialName("read")
    Read,
}

/**
 * What the gateway pushes. The variants that carry a whole record in Rust (`state`, `message`, `call`, `scheduled`,
 * `profile`) have that record's fields flat next to `type`.
 */
@Serializable
@JsonClassDiscriminator("type")
sealed interface GatewayEvent {
    @Serializable
    @SerialName("qr")
    data class Qr(
        val payload: String,
        @SerialName("valid_for_ms") val validForMs: Long,
    ) : GatewayEvent

    @Serializable
    @SerialName("pair_code")
    data class PairCode(
        val code: String,
        @SerialName("valid_for_ms") val validForMs: Long,
    ) : GatewayEvent

    @Serializable
    @SerialName("state")
    data class State(
        @SerialName("instance_id") val instanceId: String,
        val state: InstanceState,
        @SerialName("own_jid") val ownJid: String? = null,
        @SerialName("push_name") val pushName: String? = null,
    ) : GatewayEvent {
        fun status() = InstanceStatus(instanceId, state, ownJid, pushName)
    }

    @Serializable
    @SerialName("message")
    data class Message(
        val id: String,
        val chat: String,
        val sender: String? = null,
        @SerialName("from_me") val fromMe: Boolean,
        @SerialName("timestamp_ms") val timestampMs: Long,
        val body: MessageBody,
        val reactions: List<Reaction>,
    ) : GatewayEvent {
        fun message() = WaMessage(id, chat, sender, fromMe, timestampMs, body, reactions)
    }

    @Serializable
    @SerialName("message_update")
    data class MessageUpdate(
        val chat: String,
        val id: String,
        val reactions: List<Reaction>,
        val read: Boolean,
    ) : GatewayEvent

    @Serializable
    @SerialName("receipt")
    data class Receipt(
        val chat: String,
        val ids: List<String>,
        val kind: ReceiptKind,
    ) : GatewayEvent

    @Serializable
    @SerialName("presence")
    data class Presence(
        val jid: String,
        val available: Boolean,
        @SerialName("last_seen_ms") val lastSeenMs: Long? = null,
    ) : GatewayEvent

    @Serializable
    @SerialName("typing")
    data class Typing(
        val chat: String,
        val jid: String,
        val state: TypingState,
    ) : GatewayEvent

    @Serializable
    @SerialName("history_synced")
    data class HistorySynced(
        val chats: Int,
        val progress: Int? = null,
    ) : GatewayEvent

    @Serializable
    @SerialName("group_changed")
    data class GroupChanged(
        val jid: String,
    ) : GatewayEvent

    @Serializable
    @SerialName("call")
    data class Call(
        @SerialName("call_id") val callId: String,
        val peer: String,
        @SerialName("peer_name") val peerName: String? = null,
        val direction: CallDirection,
        val phase: CallPhase,
        @SerialName("is_video") val isVideo: Boolean,
    ) : GatewayEvent {
        fun update() = CallUpdate(callId, peer, peerName, direction, phase, isVideo)
    }

    @Serializable
    @SerialName("scheduled")
    data class Scheduled(
        val id: String,
        val chat: String,
        val text: String,
        @SerialName("send_at_ms") val sendAtMs: Long,
        @SerialName("created_ms") val createdMs: Long,
        val state: ScheduledState,
    ) : GatewayEvent

    @Serializable
    @SerialName("message_meta")
    data class MessageMetaChanged(
        val chat: String,
        val id: String,
        @EncodeDefault(NEVER) val body: MessageBody? = null,
        val meta: MessageMeta,
    ) : GatewayEvent

    @Serializable
    @SerialName("message_deleted")
    data class MessageDeleted(
        val chat: String,
        val id: String,
        /** When it was deleted (unix ms); older gateways leave it out. */
        @SerialName("deleted_at") @EncodeDefault(NEVER) val deletedAt: Long? = null,
        /** Who deleted it ([DELETED_BY_ME] or a JID); older gateways leave it out. */
        @SerialName("deleted_by") @EncodeDefault(NEVER) val deletedBy: String? = null,
    ) : GatewayEvent

    @Serializable
    @SerialName("chat_meta")
    data class ChatMetaChanged(
        val jid: String,
        val meta: ChatMeta,
    ) : GatewayEvent

    @Serializable
    @SerialName("chat_deleted")
    data class ChatDeleted(
        val jid: String,
    ) : GatewayEvent

    @Serializable
    @SerialName("chat_cleared")
    data class ChatCleared(
        val jid: String,
    ) : GatewayEvent

    @Serializable
    @SerialName("profile")
    data class Profile(
        val jid: String,
        @SerialName("push_name") val pushName: String? = null,
        @SerialName("contact_name") val contactName: String? = null,
        val about: String? = null,
        @SerialName("picture_id") val pictureId: String? = null,
        val business: Boolean = false,
        @SerialName("verified_name") val verifiedName: String? = null,
        @SerialName("fetched_ms") val fetchedMs: Long = 0,
    ) : GatewayEvent {
        fun profile() = WaProfile(jid, pushName, contactName, about, pictureId, business, verifiedName, fetchedMs)
    }

    @Serializable
    @SerialName("status_changed")
    data class StatusChanged(
        @EncodeDefault(NEVER) val author: String? = null,
    ) : GatewayEvent

    @Serializable
    @SerialName("chat_merged")
    data class ChatMerged(
        val from: String,
        val into: String,
    ) : GatewayEvent

    /** The gateway lost track of what it sent: reload. */
    @Serializable
    @SerialName("resync")
    data object Resync : GatewayEvent

    /** This app's key was revoked; unlink. */
    @Serializable
    @SerialName("key_revoked")
    data object KeyRevoked : GatewayEvent
}

/** What `wa.events_start` forwards for one socket message, under `event` of `wa.event`. */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface WaStreamEvent {
    /** The socket is open. */
    @Serializable
    @SerialName("connected")
    data object Connected : WaStreamEvent

    @Serializable
    @SerialName("event")
    data class Event(
        val seq: Long,
        @SerialName("at_ms") val atMs: Long,
        val event: GatewayEvent,
    ) : WaStreamEvent

    /** The socket dropped; the stream reconnects on its own. */
    @Serializable
    @SerialName("disconnected")
    data class Disconnected(
        val reason: String,
    ) : WaStreamEvent

    /** The key was revoked or is wrong; the stream has stopped. */
    @Serializable
    @SerialName("unauthorized")
    data object Unauthorized : WaStreamEvent
}

/** The body of a `wa.event`. */
@Serializable
data class WaEventPayload(
    /** The `instance` the stream was started with. */
    val instance: String,
    val event: WaStreamEvent,
)
