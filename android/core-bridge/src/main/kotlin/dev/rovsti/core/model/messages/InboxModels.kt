@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.messages

import dev.rovsti.core.model.phone.ExternallyTagged
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

// Models of `inbox.*` (E2.11a), mirroring `crates/rovsti_ffi/fixtures/inbox.*`. The contact index lives in Rust: a
// request names its handle (`search.index_new`). Stability: `compose_stability.conf`.

/** Who an inbox row is about: a contact, a bare number, or a group that never merges (`{"contact": 1}`). */
@Serializable(with = PersonKeySerializer::class)
sealed interface PersonKey {
    /** `ContactsContract.Contacts._ID`. */
    data class Contact(
        val id: Long,
    ) : PersonKey

    /** No contact: the number itself. */
    data class Number(
        val number: PhoneNumber,
    ) : PersonKey

    /** A group or an unaddressable thread. */
    data class Group(
        val thread: ThreadId,
    ) : PersonKey
}

internal object PersonKeySerializer : ExternallyTagged<PersonKey>("PersonKey") {
    private val json = Json

    override fun fromTag(
        tag: String,
        value: JsonElement?,
    ): PersonKey {
        val body = value ?: unknown(tag)
        return when (tag) {
            "contact" -> PersonKey.Contact(body.jsonPrimitive.content.toLong())
            "number" -> PersonKey.Number(json.decodeFromJsonElement(PhoneNumber.serializer(), body))
            "group" -> PersonKey.Group(json.decodeFromJsonElement(ThreadId.serializer(), body))
            else -> unknown(tag)
        }
    }

    override fun toJson(value: PersonKey): JsonElement =
        when (value) {
            is PersonKey.Contact -> tagged("contact", JsonPrimitive(value.id))
            is PersonKey.Number -> tagged("number", json.encodeToJsonElement(PhoneNumber.serializer(), value.number))
            is PersonKey.Group -> tagged("group", json.encodeToJsonElement(ThreadId.serializer(), value.thread))
        }
}

/** One conversation as the inbox sees it, whatever channel it is on. */
@Serializable
data class InboxThread(
    val id: ThreadId,
    val last: Message,
    val unread: Int,
    /** Chat or group name when the channel knows one. */
    val title: String?,
    @SerialName("is_group") val isGroup: Boolean,
)

/** One inbox row: a person with their SMS thread(s) and WhatsApp chat folded together, newest first. */
@Serializable
data class InboxRow(
    val key: PersonKey,
    val title: String,
    val threads: List<InboxThread>,
    val last: Message,
    val unread: Int,
    /** Distinct channels, in the order of the newest thread of each. */
    val channels: List<Channel>,
)

/** `inbox.merge` request: SMS threads and WhatsApp chats, and the handle of the contact index to match against. */
@Serializable
data class MergeRequest(
    val contacts: Long,
    @EncodeDefault(NEVER) val sms: List<SmsThreadRow> = emptyList(),
    @EncodeDefault(NEVER) val wa: List<InboxThread> = emptyList(),
)

@Serializable
data class MergeAnswer(
    val rows: List<InboxRow>,
)

@Serializable
data class PreferredChannelRequest(
    val row: InboxRow,
)

@Serializable
data class PreferredChannelAnswer(
    val channel: Channel,
)

@Serializable
data class PersonForNumberRequest(
    val contacts: Long,
    val raw: String,
)

@Serializable
data class PersonForJidRequest(
    val contacts: Long,
    val jid: String,
)

/** `inbox.person_for_*` answer. */
@Serializable
data class PersonAnswer(
    /** `null` for senders that are not numbers (`"BANK"`) or JIDs that never merge by number. */
    val key: PersonKey?,
)

// ---- inbox.filter

/** One filter chip of the inbox. */
@Serializable
enum class InboxFilter {
    @SerialName("all")
    All,

    @SerialName("unread")
    Unread,

    @SerialName("whatsapp")
    WhatsApp,

    @SerialName("sms")
    Sms,

    @SerialName("groups")
    Groups,
}

/** What the filters look at on one row. */
@Serializable
data class RowFacts(
    @EncodeDefault(NEVER) val unread: Boolean = false,
    @EncodeDefault(NEVER) val whatsapp: Boolean = false,
    @EncodeDefault(NEVER) val sms: Boolean = false,
    @EncodeDefault(NEVER) val group: Boolean = false,
)

@Serializable
data class FilterRequest(
    val facts: List<RowFacts>,
    val filter: InboxFilter,
)

/** A chip to draw: [text] with an optional count [badge] ("999+" above the cap); [label] is the accessibility text. */
@Serializable
data class Chip(
    val filter: InboxFilter,
    val count: Int,
    val label: String,
    val text: String,
    val badge: String?,
)

@Serializable
data class FilterAnswer(
    /** Whether each row passes the selected filter, in request order. */
    val matches: List<Boolean>,
    /** The count of every chip in chip order (All, Unread, WhatsApp, SMS, Groups). */
    val counts: List<Int>,
    /** The chips to show; the selected filter is always among them. */
    val chips: List<Chip>,
    @SerialName("empty_text") val emptyText: String,
)

// ---- inbox.bulk

/** An action applicable to a whole selection. */
@Serializable
enum class BulkAction {
    @SerialName("mark_read")
    MarkRead,

    @SerialName("mark_unread")
    MarkUnread,

    @SerialName("mute")
    Mute,

    @SerialName("unmute")
    Unmute,

    @SerialName("pin")
    Pin,

    @SerialName("unpin")
    Unpin,

    @SerialName("archive")
    Archive,

    @SerialName("unarchive")
    Unarchive,

    @SerialName("delete")
    Delete,
}

/** What the inbox knows about one selected row. */
@Serializable
data class BulkFacts(
    @EncodeDefault(NEVER) val unread: Boolean = false,
    @EncodeDefault(NEVER) val muted: Boolean = false,
    @EncodeDefault(NEVER) val pinned: Boolean = false,
    @EncodeDefault(NEVER) val archived: Boolean = false,
    @EncodeDefault(NEVER) val whatsapp: Boolean = false,
    @EncodeDefault(NEVER) val sms: Boolean = false,
    /** No WhatsApp side, so the conversation can be deleted locally. */
    @SerialName("sms_only") @EncodeDefault(NEVER) val smsOnly: Boolean = false,
)

@Serializable
data class BulkRequest(
    val selected: List<BulkFacts>,
    @SerialName("in_archive") @EncodeDefault(NEVER) val inArchive: Boolean = false,
    @SerialName("pinned_wa") @EncodeDefault(NEVER) val pinnedWa: Int = 0,
    @SerialName("pinned_sms") @EncodeDefault(NEVER) val pinnedSms: Int = 0,
    /** The action the user picked, for the notices. */
    @EncodeDefault(NEVER) val action: BulkAction? = null,
    /** Rows the action reached; all of [selected] when absent. */
    @EncodeDefault(NEVER) val done: Int? = null,
)

@Serializable
data class ActionRow(
    val action: BulkAction,
    val label: String,
    val destructive: Boolean,
)

@Serializable
data class BulkAnswer(
    val actions: List<ActionRow>,
    /** The pill text when pinning the unpinned rows would pass a cap. */
    @SerialName("pin_refusal") val pinRefusal: String?,
    @SerialName("done_notice") val doneNotice: String?,
    /** The confirmation before a destructive action. */
    val confirm: String?,
    @SerialName("partial_notice") val partialNotice: String?,
)

// ---- inbox.pending_delete

/** A delete waiting out its Undo window. */
@Serializable
data class Waiting(
    val key: String,
    @SerialName("at_ms") val atMs: Long,
)

@Serializable
data class PendingDeleteRequest(
    /** The delayed deletes so far, oldest first. */
    @EncodeDefault(NEVER) val pending: List<Waiting> = emptyList(),
    @SerialName("now_ms") @EncodeDefault(NEVER) val nowMs: Long = 0,
    /** `add`, `undo`, `due`, `drain` or `none` (state only). */
    @EncodeDefault(NEVER) val action: String = "",
    @EncodeDefault(NEVER) val key: String = "",
)

@Serializable
data class PendingDeleteAnswer(
    val pending: List<Waiting>,
    /** `undo`: the key was pending. */
    val ok: Boolean,
    /** `due` and `drain`: the keys to delete for real now. */
    val keys: List<String>,
    val notice: String,
    @SerialName("undo_ms") val undoMs: Long,
)
