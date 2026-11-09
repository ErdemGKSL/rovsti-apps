@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.wa

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

// Request and answer shapes of the `wa.*` ops that are not a proto type of their own. Requests are flat:
// `{handle, ...fields}`; [WithHandle] and its serializer are that envelope. See `crates/rovsti_ffi/src/ops/wa`.

/** A request body together with the gateway handle it runs on. */
data class WithHandle<T>(
    val handle: Long,
    val body: T,
)

/** Writes `{handle, ...body}` and reads it back; the body's fields stay flat next to the handle. */
class WithHandleSerializer<T>(
    private val body: KSerializer<T>,
) : KSerializer<WithHandle<T>> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: WithHandle<T>,
    ) {
        val json = (encoder as? JsonEncoder)?.json ?: Json
        val fields = json.encodeToJsonElement(body, value.body) as? JsonObject ?: JsonObject(emptyMap())
        encoder.encodeSerializableValue(
            JsonElement.serializer(),
            JsonObject(fields + ("handle" to JsonPrimitive(value.handle))),
        )
    }

    override fun deserialize(decoder: Decoder): WithHandle<T> {
        val json = (decoder as? JsonDecoder)?.json ?: Json
        val all = decoder.decodeSerializableValue(JsonElement.serializer()) as? JsonObject
        val handle = (all?.get("handle") as? JsonPrimitive)?.long ?: throw SerializationException("no handle")
        return WithHandle(handle, json.decodeFromJsonElement(body, JsonObject(all - "handle")))
    }
}

/** The answer of an op that opens something: its handle. */
@Serializable
data class HandleAnswer(
    val handle: Long,
)

/** `wa.open`: [key] is left out for `wa.create_instance` (no key yet); [cacheDir] holds large downloads. */
@Serializable
data class WaOpenRequest(
    val url: String,
    @EncodeDefault(NEVER) val key: String? = null,
    @SerialName("cache_dir") @EncodeDefault(NEVER) val cacheDir: String? = null,
)

@Serializable
data class LabelBody(
    val label: String,
)

@Serializable
data class KeyIdBody(
    @SerialName("key_id") val keyId: String,
)

@Serializable
data class IdBody(
    val id: String,
)

@Serializable
data class IdsBody(
    val ids: List<String>,
)

@Serializable
data class MessageIdBody(
    @SerialName("message_id") val messageId: String,
)

@Serializable
data class JidBody(
    val jid: String,
)

@Serializable
data class JidsBody(
    val jids: List<String>,
)

@Serializable
data class PresenceBody(
    val available: Boolean,
)

@Serializable
data class PlaceCallBody(
    val to: String,
)

@Serializable
data class CallIdBody(
    @SerialName("call_id") val callId: String,
)

@Serializable
data class CallMuteBody(
    @SerialName("call_id") val callId: String,
    val muted: Boolean,
)

/** A page of messages, newest first; [beforeMs] pages back, [limit] defaults to 50. */
@Serializable
data class MessagesBody(
    val chat: String,
    @SerialName("before_ms") @EncodeDefault(NEVER) val beforeMs: Long? = null,
    @EncodeDefault(NEVER) val limit: Int? = null,
)

/** The bytes of an upload: [dataB64], or [path] of a file the app wrote (large videos). Exactly one. */
@Serializable
data class SendMediaBody(
    val chat: String,
    val kind: MediaKind,
    val mime: String,
    @EncodeDefault(NEVER) val caption: String? = null,
    @SerialName("data_b64") @EncodeDefault(NEVER) val dataB64: String? = null,
    @EncodeDefault(NEVER) val path: String? = null,
)

@Serializable
data class SendMediaWithBody(
    val chat: String,
    val kind: MediaKind,
    val mime: String,
    @EncodeDefault(NEVER) val caption: String? = null,
    @SerialName("data_b64") @EncodeDefault(NEVER) val dataB64: String? = null,
    @EncodeDefault(NEVER) val path: String? = null,
    val options: MediaOptions,
)

@Serializable
data class PictureBody(
    val jid: String,
    @EncodeDefault(NEVER) val full: Boolean = false,
    /** The picture id you hold: an unchanged picture answers `unchanged` instead of bytes. */
    @SerialName("known_id") @EncodeDefault(NEVER) val knownId: String? = null,
)

/** Media bytes: [dataB64] up to 1 MiB, else [path] of a temp file in the handle's cache directory (delete it). */
@Serializable
data class BytesAnswer(
    val size: Long,
    @SerialName("data_b64") @EncodeDefault(NEVER) val dataB64: String? = null,
    @EncodeDefault(NEVER) val path: String? = null,
)

/** `wa.picture`: [kind] is `image`, `unchanged` or `none`; an `image` has [id] and its bytes. */
@Serializable
data class PictureAnswer(
    val kind: String,
    @EncodeDefault(NEVER) val id: String? = null,
    @EncodeDefault(NEVER) val size: Long? = null,
    @SerialName("data_b64") @EncodeDefault(NEVER) val dataB64: String? = null,
    @EncodeDefault(NEVER) val path: String? = null,
)

/** `wa.events_start`: [instance] is echoed in every `wa.event`; [after] resumes after that `seq`. */
@Serializable
data class StartEventsBody(
    val instance: String,
    val after: Long? = null,
)

// ---- ops without a gateway

@Serializable
data class PushParseRequest(
    val json: String,
)

/** The FCM wake-up the gateway sent. */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface Wake {
    @Serializable
    @SerialName("call")
    data class Call(
        @SerialName("call_id") val callId: String,
        val from: String? = null,
        val name: String? = null,
    ) : Wake

    @Serializable
    @SerialName("message")
    data class Message(
        val chat: String,
    ) : Wake

    @Serializable
    @SerialName("call_end")
    data class CallEnd(
        @SerialName("call_id") val callId: String,
        val missed: Boolean,
    ) : Wake
}

/** [wake] is null for an unknown kind or malformed JSON. */
@Serializable
data class PushParseAnswer(
    val wake: Wake? = null,
)

/** What an app keeps to reach its instance (`whatsapp-link.json`); the key is a bearer secret. */
@Serializable
data class StoredLink(
    @SerialName("gateway_url") val gatewayUrl: String,
    @SerialName("instance_id") val instanceId: String,
    val key: String,
) {
    override fun toString() = "StoredLink(gatewayUrl=$gatewayUrl, instanceId=$instanceId, key=rwk_…)"
}

@Serializable
data class LinkReadRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
)

@Serializable
data class LinkReadAnswer(
    val link: StoredLink? = null,
)

/** [link] null removes the link; a bad URL or key is `RustError.Invalid`. */
@Serializable
data class LinkWriteRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    val link: StoredLink?,
)

@Serializable
data class DiskFile(
    val path: String,
    val bytes: Long,
    @SerialName("modified_ms") val modifiedMs: Long,
)

/** `wa.avatars_plan`: which picture files to delete over [maxBytes], and the name a picture is stored under. */
@Serializable
data class AvatarsPlanRequest(
    val files: List<DiskFile>,
    @SerialName("max_bytes") val maxBytes: Long,
    @EncodeDefault(NEVER) val jid: String? = null,
    @SerialName("picture_id") @EncodeDefault(NEVER) val pictureId: String? = null,
)

@Serializable
data class AvatarsPlanAnswer(
    val remove: List<String>,
    @SerialName("file_name") val fileName: String? = null,
    /** The picture id of the first file that belongs to [AvatarsPlanRequest.jid]. */
    @SerialName("held_id") val heldId: String? = null,
)

@Serializable
data class WaNamesRequest(
    @EncodeDefault(NEVER) val contact: String? = null,
    @SerialName("wa_name") @EncodeDefault(NEVER) val waName: String? = null,
    val jid: String,
)

@Serializable
data class WaNamesAnswer(
    /** The contact name, else WhatsApp's name, else the number. */
    val name: String,
    /** `+905321234567`, "WhatsApp user" for a LID, the group id for a group. */
    val number: String,
)

@Serializable
data class WaTargetRequest(
    val numbers: List<String>,
    @SerialName("chat_users") val chatUsers: List<String>,
)

/** Where a contact's WhatsApp actions go. */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface WaTarget {
    /** An existing 1:1 chat. */
    @Serializable
    @SerialName("chat")
    data class Chat(
        val jid: String,
    ) : WaTarget

    /** A number to reach by JID (look it up first). */
    @Serializable
    @SerialName("number")
    data class Number(
        val jid: String,
    ) : WaTarget

    @Serializable
    @SerialName("needs_country_code")
    data object NeedsCountryCode : WaTarget

    @Serializable
    @SerialName("none")
    data object None : WaTarget
}

@Serializable
data class WaTargetAnswer(
    val target: WaTarget,
    @SerialName("lookup_jids") val lookupJids: List<String>,
)

/** `wa.place_call` answer. */
@Serializable
data class CallStarted(
    @SerialName("call_id") val callId: String,
)
