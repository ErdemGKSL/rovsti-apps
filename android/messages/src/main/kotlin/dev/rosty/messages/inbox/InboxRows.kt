package dev.rosty.messages.inbox

import androidx.compose.runtime.Immutable
import dev.rosty.core.model.messages.Channel
import dev.rosty.core.model.messages.InboxRow
import dev.rosty.core.model.messages.MessageDirection
import dev.rosty.core.model.messages.PersonKey
import dev.rosty.core.model.messages.SmsMarks
import dev.rosty.core.model.messages.SmsThreadRow
import dev.rosty.core.model.messages.ThreadId
import dev.rosty.core.model.search.WireContact
import dev.rosty.core.model.wa.DeliveryStatus
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.MessageMeta
import dev.rosty.core.model.wa.RichChat
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** My last WhatsApp message's state on a row (mine only; GPUI `ticks.rs`). */
enum class RowTick { Pending, Sent, Delivered, Read }

/** What the avatar of a row draws: own photo, else the people cache or the chat's WhatsApp picture, else initials. */
@Immutable
data class RowAvatar(
    val photoUri: String? = null,
    /** Numbers of the person, any spelling: the people cache finds their WhatsApp picture. */
    val numbers: ImmutableList<String> = persistentListOf(),
    /** The WhatsApp chat, for its picture when the people cache has none. */
    val waJid: String? = null,
    /** The picture id the chat list knows, so `WaAvatars` refetches only when it changed. */
    val pictureId: String? = null,
    val group: Boolean = false,
    val colorKey: String = "",
)

/** One row of the inbox as drawn (GPUI `inbox::RowView`). Built off the main thread by [InboxBuilder]. */
@Immutable
data class InboxRowUi(
    /** Stable across reloads: the person key. */
    val key: String,
    val title: String,
    /** The newest message, or the draft. */
    val snippet: String,
    val draft: Boolean,
    val time: String,
    val atMs: Long,
    val unread: Int,
    val markedUnread: Boolean,
    val pinned: Boolean,
    val muted: Boolean,
    val archived: Boolean,
    /** The channel of the newest thread: the one glyph after the name (never a pill, AC1). */
    val latest: Channel,
    val channels: ImmutableList<Channel>,
    val avatar: RowAvatar,
    /** Newest first; a tap opens the first. */
    val threads: ImmutableList<ThreadId>,
    /** The SMS thread of the row, when it has one. */
    val smsThread: Long?,
    /** The single address of a one-to-one SMS thread (block, call, spam rules need one). */
    val smsSender: String?,
    val waChat: String?,
    val group: Boolean,
    val tick: RowTick?,
    /** The passcode of an unread incoming message, for the "Copy code" chip. */
    val otp: String? = null,
    val contactId: Long? = null,
) {
    val isUnread: Boolean get() = unread > 0 || markedUnread
}

/** The inbox split as the GPUI app showed it: the normal rows (pins first), the archived ones and the spam. */
@Immutable
data class InboxView(
    val rows: ImmutableList<InboxRowUi>,
    val archived: ImmutableList<InboxRowUi>,
    val spam: ImmutableList<InboxRowUi>,
) {
    companion object {
        val Empty = InboxView(persistentListOf(), persistentListOf(), persistentListOf())
    }
}

/** Everything the builder looks at besides the merged rows (GPUI `inbox::Inputs`). */
data class InboxInputs(
    val sms: Map<Long, SmsThreadRow> = emptyMap(),
    val wa: Map<String, RichChat> = emptyMap(),
    val contacts: Map<Long, WireContact> = emptyMap(),
    val marks: SmsMarks = SmsMarks(),
    /** Notification tags (`sms:<id>`, `wa:<jid>`) the person silenced, with the end of a timed mute. */
    val muted: List<String> = emptyList(),
    val muteUntil: Map<String, Long> = emptyMap(),
    val drafts: Map<String, String> = emptyMap(),
    /** SMS threads judged spam (only one-to-one SMS-only rows can be, AD-MSG8). */
    val spamThreads: Set<Long> = emptySet(),
    val otps: Map<String, String> = emptyMap(),
    val nowMs: Long = 0,
)

/**
 * The pure port of GPUI `inbox::build`: titles, snippets (WhatsApp previews, "You: ", group sender, drafts, edited),
 * times, unread, pins first (stable otherwise), mutes, the archive and spam split, the avatar and the actions' targets.
 */
class InboxBuilder(
    private val words: InboxWords,
    private val time: RowTime,
) {
    fun build(
        merged: List<InboxRow>,
        inputs: InboxInputs,
    ): InboxView {
        val all = merged.map { row(it, inputs) }
        val spamKeys = all.filter { it.smsThread != null && it.smsThread in inputs.spamThreads && it.isSpamCandidate() }
        val spam = spamKeys.map { it.key }.toSet()
        val rest = all.filter { it.key !in spam }
        val (archived, normal) = rest.partition { it.archived }
        return InboxView(
            rows = pinsFirst(normal).toImmutableList(),
            archived = archived.toImmutableList(),
            spam = spamKeys.toImmutableList(),
        )
    }

    private fun InboxRowUi.isSpamCandidate() = channels == listOf(Channel.Sms) && smsSender != null

    private fun pinsFirst(rows: List<InboxRowUi>): List<InboxRowUi> =
        rows.filter { it.pinned } + rows.filterNot { it.pinned }

    private fun row(
        row: InboxRow,
        inputs: InboxInputs,
    ): InboxRowUi {
        val threads = row.threads.map { it.id }
        val sms = threads.filterIsInstance<ThreadId.Sms>().firstOrNull()?.id
        val smsRow = sms?.let(inputs.sms::get)
        val wa = threads.filterIsInstance<ThreadId.WhatsApp>().firstOrNull()?.jid
        val chat = wa?.let(inputs.wa::get)
        val group = (wa?.endsWith("@g.us") == true) || (smsRow != null && smsRow.addresses.size > 1)
        val sender = smsRow?.addresses?.singleOrNull()
        val contactId = (row.key as? PersonKey.Contact)?.id
        val contact = contactId?.let(inputs.contacts::get)
        val tags = threads.map(::tagOf)
        val title =
            row.title.ifBlank {
                smsRow?.addresses?.takeIf { it.isNotEmpty() }?.let(words::joined) ?: chat?.name.orEmpty()
            }
        val latest = row.channels.firstOrNull() ?: threads.first().channel
        val draft = tags.firstNotNullOfOrNull { tag -> inputs.drafts[tag]?.takeIf { it.isNotBlank() } }
        val snippet =
            when {
                draft != null -> words.draft(draft.lineSequence().first())
                latest == Channel.WhatsApp && chat != null -> waSnippet(chat, group)
                else -> smsSnippet(row, smsRow)
            }
        val markedUnread = (sms != null && sms in inputs.marks.unread) || chat?.meta?.markedUnread == true
        val pinned = (sms != null && sms in inputs.marks.pinned) || chat?.meta?.pinned == true
        val archivedSms = sms == null || sms in inputs.marks.archived
        val archivedWa = chat == null || chat.meta.archived
        val muted =
            tags.any { isMuted(it, inputs) } || chat?.meta?.mutedUntilMs?.let { it == -1L || it > inputs.nowMs } == true
        val numbers =
            (contact?.numbers?.map { it.raw }.orEmpty() + listOfNotNull(sender) + listOfNotNull(wa?.let(::numberOfJid)))
                .distinct()
        val key = keyOf(row.key)
        return InboxRowUi(
            key = key,
            title = title,
            snippet = snippet,
            draft = draft != null,
            time = time.label(row.last.timestampMs, inputs.nowMs),
            atMs = row.last.timestampMs,
            unread = row.unread,
            markedUnread = markedUnread,
            pinned = pinned,
            muted = muted,
            archived = archivedSms && archivedWa,
            latest = latest,
            channels = row.channels.toImmutableList(),
            avatar =
                RowAvatar(
                    photoUri = contact?.photoUri?.takeIf { it.isNotBlank() },
                    numbers = numbers.toImmutableList(),
                    waJid = wa,
                    pictureId = chat?.meta?.pictureId,
                    group = group,
                    colorKey = numbers.firstOrNull() ?: wa ?: title,
                ),
            threads = threads.toImmutableList(),
            smsThread = sms,
            smsSender = sender,
            waChat = wa,
            group = group,
            tick = if (latest == Channel.WhatsApp && chat?.last?.fromMe == true) tickOf(chat.lastMeta) else null,
            otp = inputs.otps[key],
            contactId = contactId,
        )
    }

    private fun smsSnippet(
        row: InboxRow,
        smsRow: SmsThreadRow?,
    ): String {
        val body = smsRow?.snippet?.takeIf { it.isNotBlank() } ?: row.last.body
        val line =
            body
                .lineSequence()
                .firstOrNull()
                .orEmpty()
                .take(SNIPPET_CHARS)
        return if (row.last.direction == MessageDirection.Outgoing) words.you(line) else line
    }

    /** GPUI `wa_snippet`: deleted, location, contact, voice, media and text previews, then the sender and "Edited". */
    private fun waSnippet(
        chat: RichChat,
        group: Boolean,
    ): String {
        val last = chat.last ?: return ""
        val meta = chat.lastMeta ?: MessageMeta()
        if (meta.deleted || meta.revoked) return words.deleted(last.fromMe)
        val preview = waPreview(last.body, meta, words)
        val withEdit = if (meta.editedMs != null) words.edited(preview) else preview
        return when {
            last.fromMe -> words.you(withEdit)
            group -> words.sender(meta.senderName ?: last.sender?.let(::numberOfJid) ?: "", withEdit)
            else -> withEdit
        }
    }

    private fun isMuted(
        tag: String,
        inputs: InboxInputs,
    ): Boolean {
        if (tag !in inputs.muted) return false
        val until = inputs.muteUntil[tag] ?: return true
        return until > inputs.nowMs
    }

    companion object {
        const val SNIPPET_CHARS = 120

        /** The notification and draft tag of a thread: `sms:<id>` or `wa:<jid>`. */
        fun tagOf(thread: ThreadId): String =
            when (thread) {
                is ThreadId.Sms -> "sms:${thread.id}"
                is ThreadId.WhatsApp -> "wa:${thread.jid}"
            }

        fun keyOf(key: PersonKey): String =
            when (key) {
                is PersonKey.Contact -> "c:${key.id}"
                is PersonKey.Number -> "n:${key.number.canonical}"
                is PersonKey.Group -> "g:${tagOf(key.thread)}"
            }
    }
}

/** The text a WhatsApp message stands for in one line (rows, quotes, notifications). */
fun waPreview(
    body: MessageBody,
    meta: MessageMeta,
    words: InboxWords,
): String {
    meta.location?.let { return words.location(it.live, it.name ?: it.address) }
    meta.contact?.let { return words.contact(it.displayName) }
    return when (body) {
        is MessageBody.Text -> {
            body.text
                .lineSequence()
                .firstOrNull()
                .orEmpty()
                .take(InboxBuilder.SNIPPET_CHARS)
        }

        is MessageBody.Media -> {
            meta.media.let { m ->
                if (m?.voiceNote ==
                    true
                ) {
                    words.voice(m.seconds)
                } else {
                    words.media(body.kind, body.caption)
                }
            }
        }

        MessageBody.Unsupported -> {
            words.unsupported()
        }
    }
}

/** `+<user>` of a user JID (`905550000001@s.whatsapp.net`), null for groups, LIDs and broadcasts. */
fun numberOfJid(jid: String): String? {
    if (!jid.endsWith("@s.whatsapp.net")) return null
    val user = jid.substringBefore('@').substringBefore(':')
    return user.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { "+$it" }
}

private fun tickOf(meta: MessageMeta?): RowTick? =
    when (meta?.status) {
        null -> RowTick.Sent
        DeliveryStatus.Sent -> RowTick.Sent
        DeliveryStatus.Delivered -> RowTick.Delivered
        else -> RowTick.Read
    }
