package dev.rosty.messages.inbox

import dev.rosty.core.model.messages.Channel
import dev.rosty.core.model.messages.InboxRow
import dev.rosty.core.model.messages.InboxThread
import dev.rosty.core.model.messages.Message
import dev.rosty.core.model.messages.MessageDirection
import dev.rosty.core.model.messages.PersonKey
import dev.rosty.core.model.messages.PhoneNumber
import dev.rosty.core.model.messages.SmsMarks
import dev.rosty.core.model.messages.SmsThreadRow
import dev.rosty.core.model.messages.ThreadId
import dev.rosty.core.model.wa.ChatMeta
import dev.rosty.core.model.wa.DeliveryStatus
import dev.rosty.core.model.wa.MediaKind
import dev.rosty.core.model.wa.MediaMeta
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.MessageMeta
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.WaMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** E6.2: the pure row view of the inbox (GPUI `inbox::build`). */
class InboxBuilderTest {
    private val now = 1_790_000_000_000L // 2026-09-21
    private val builder = InboxBuilder(EnglishInboxWords, RowTime(ZoneId.of("UTC"), Locale.US, true, "Yesterday"))
    private val jid = "905550000001@s.whatsapp.net"

    private fun sms(
        id: Long,
        at: Long,
        body: String = "hi",
        out: Boolean = false,
        unread: Int = 0,
    ) = InboxThread(
        ThreadId.Sms(id),
        Message(
            ThreadId.Sms(id),
            if (out) MessageDirection.Outgoing else MessageDirection.Incoming,
            null,
            body,
            at,
            unread == 0,
        ),
        unread,
        null,
        false,
    )

    private fun wa(
        at: Long,
        chat: String = jid,
        unread: Int = 0,
    ) = InboxThread(
        ThreadId.WhatsApp(chat),
        Message(ThreadId.WhatsApp(chat), MessageDirection.Incoming, PhoneNumber("+905550000001"), "x", at, unread == 0),
        unread,
        null,
        chat.endsWith("@g.us"),
    )

    private fun row(
        key: PersonKey,
        title: String,
        vararg threads: InboxThread,
    ) = InboxRow(
        key,
        title,
        threads.toList(),
        threads.first().last,
        threads.sumOf {
            it.unread
        },
        threads.map { it.id.channel }.distinct(),
    )

    private fun chat(
        body: MessageBody = MessageBody.Text("hello"),
        fromMe: Boolean = false,
        meta: MessageMeta? = null,
        chatMeta: ChatMeta = ChatMeta(),
        chatJid: String = jid,
        sender: String? = null,
    ) = RichChat(
        chatJid,
        "Ada",
        WaMessage("m1", chatJid, sender, fromMe, now - 1000, body, emptyList()),
        0,
        chatJid.endsWith("@g.us"),
        chatMeta,
        meta,
    )

    @Test fun aMergedPersonIsOneRowWithTheLatestChannelGlyph() {
        val merged = row(PersonKey.Contact(1), "Ada", wa(now - 1000), sms(7, now - 5000))
        val view = builder.build(listOf(merged), InboxInputs(wa = mapOf(jid to chat()), nowMs = now))
        val only = view.rows.single()
        assertEquals("c:1", only.key)
        assertEquals(Channel.WhatsApp, only.latest)
        assertEquals(listOf(Channel.WhatsApp, Channel.Sms), only.channels)
        assertEquals(7L, only.smsThread)
        assertEquals(jid, only.waChat)
        assertEquals("hello", only.snippet)
        assertTrue(only.avatar.numbers.contains("+905550000001"))
    }

    @Test fun pinsComeFirstAndArchivedRowsLeaveTheInbox() {
        val a = row(PersonKey.Number(PhoneNumber("+905550000002")), "A", sms(1, now - 100))
        val b = row(PersonKey.Number(PhoneNumber("+905550000003")), "B", sms(2, now - 200))
        val c = row(PersonKey.Number(PhoneNumber("+905550000004")), "C", sms(3, now - 300))
        val view =
            builder.build(
                listOf(a, b, c),
                InboxInputs(marks = SmsMarks(pinned = listOf(3), archived = listOf(2)), nowMs = now),
            )
        assertEquals(listOf("C", "A"), view.rows.map { it.title })
        assertEquals(listOf("B"), view.archived.map { it.title })
        assertTrue(view.rows.first().pinned)
    }

    @Test fun aRowIsArchivedOnlyWhenEveryChannelIs() {
        val merged = row(PersonKey.Contact(1), "Ada", wa(now - 1000), sms(7, now - 5000))
        val smsOnly = InboxInputs(wa = mapOf(jid to chat()), marks = SmsMarks(archived = listOf(7)), nowMs = now)
        assertEquals(1, builder.build(listOf(merged), smsOnly).rows.size)
        val both = smsOnly.copy(wa = mapOf(jid to chat(chatMeta = ChatMeta(archived = true))))
        assertEquals(1, builder.build(listOf(merged), both).archived.size)
    }

    @Test fun mineGroupDraftAndEditedSnippets() {
        val out = row(PersonKey.Contact(2), "Bo", sms(4, now - 10, body = "on my way", out = true))
        assertEquals(
            "You: on my way",
            builder
                .build(listOf(out), InboxInputs(nowMs = now))
                .rows
                .single()
                .snippet,
        )

        val drafted = builder.build(listOf(out), InboxInputs(drafts = mapOf("sms:4" to "almost\nthere"), nowMs = now))
        assertEquals("Draft: almost", drafted.rows.single().snippet)
        assertTrue(drafted.rows.single().draft)

        val group = "120363000000000001@g.us"
        val g = row(PersonKey.Group(ThreadId.WhatsApp(group)), "Family", wa(now - 10, group))
        val inGroup = chat(chatJid = group, meta = MessageMeta(senderName = "Ece", editedMs = now), sender = jid)
        assertEquals(
            "Ece: hello · Edited",
            builder
                .build(listOf(g), InboxInputs(wa = mapOf(group to inGroup), nowMs = now))
                .rows
                .single()
                .snippet,
        )
    }

    @Test fun deletedVoiceAndMediaPreviews() {
        val r = row(PersonKey.Contact(1), "Ada", wa(now - 10))

        fun snippet(c: RichChat) =
            builder
                .build(listOf(r), InboxInputs(wa = mapOf(jid to c), nowMs = now))
                .rows
                .single()
                .snippet
        assertEquals("This message was deleted", snippet(chat(meta = MessageMeta(deleted = true))))
        assertEquals("You deleted this message", snippet(chat(fromMe = true, meta = MessageMeta(revoked = true))))
        assertEquals(
            "Voice message (0:42)",
            snippet(
                chat(
                    body = MessageBody.Media(MediaKind.Audio, null, "audio/ogg", 10),
                    meta = MessageMeta(media = MediaMeta(seconds = 42, voiceNote = true)),
                ),
            ),
        )
        assertEquals(
            "Photo · beach",
            snippet(chat(body = MessageBody.Media(MediaKind.Image, "beach", "image/jpeg", 10))),
        )
    }

    @Test fun mutesFollowTheTagsAndTheirEnd() {
        val r = row(PersonKey.Contact(1), "Ada", sms(7, now - 10))

        fun muted(inputs: InboxInputs) =
            builder
                .build(listOf(r), inputs)
                .rows
                .single()
                .muted
        assertTrue(muted(InboxInputs(muted = listOf("sms:7"), nowMs = now)))
        assertFalse(muted(InboxInputs(muted = listOf("sms:7"), muteUntil = mapOf("sms:7" to now - 1), nowMs = now)))
        assertTrue(muted(InboxInputs(muted = listOf("sms:7"), muteUntil = mapOf("sms:7" to now + 1), nowMs = now)))
    }

    @Test fun myLastWhatsAppMessageCarriesItsTick() {
        val r = row(PersonKey.Contact(1), "Ada", wa(now - 10))
        val view =
            builder.build(
                listOf(r),
                InboxInputs(
                    wa = mapOf(jid to chat(fromMe = true, meta = MessageMeta(status = DeliveryStatus.Read))),
                    nowMs = now,
                ),
            )
        assertEquals(RowTick.Read, view.rows.single().tick)
        assertNull(
            builder
                .build(listOf(r), InboxInputs(wa = mapOf(jid to chat()), nowMs = now))
                .rows
                .single()
                .tick,
        )
    }

    @Test fun anUnnamedSmsGroupShowsItsAddresses() {
        val t = sms(9, now - 10)
        val r = row(PersonKey.Group(ThreadId.Sms(9)), "", t)
        val rows = mapOf(9L to SmsThreadRow(9, listOf("+905550000005", "+905550000006"), "x", now, 0))
        val only = builder.build(listOf(r), InboxInputs(sms = rows, nowMs = now)).rows.single()
        assertEquals("+905550000005, +905550000006", only.title)
        assertTrue(only.group)
        assertNull(only.smsSender)
    }

    @Test fun bulkActionsFollowTheSelection() {
        val r = row(PersonKey.Contact(1), "Ada", sms(7, now - 10, unread = 2))
        val ui = builder.build(listOf(r), InboxInputs(nowMs = now)).rows.single()
        assertEquals(
            listOf(BulkKind.MarkRead, BulkKind.Mute, BulkKind.Pin, BulkKind.Archive, BulkKind.Delete),
            InboxViewModel.bulkActions(listOf(ui), InboxFolder.Inbox),
        )
        assertEquals(
            listOf(BulkKind.MarkRead, BulkKind.Mute, BulkKind.Unarchive, BulkKind.Delete),
            InboxViewModel.bulkActions(listOf(ui), InboxFolder.Archived),
        )
    }

    @Test fun theSearchFoldsCaseAndAccents() {
        assertEquals("ayse yilmaz", InboxViewModel.fold("Ayşe YILMAZ").replace('ş', 's'))
        assertEquals("istanbul", InboxViewModel.fold("İstanbul"))
    }
}

/** E6.2: the row time labels in English and Turkish. */
class RowTimeTest {
    private val now = 1_790_000_000_000L // 2026-09-21T14:13:20Z

    @Test fun clockTodayYesterdayDayMonthAndFullDate() {
        val en = RowTime(ZoneId.of("UTC"), Locale.US, true, "Yesterday")
        assertEquals("14:13", en.label(now, now))
        assertEquals("Yesterday", en.label(now - 86_400_000L, now))
        assertTrue(en.label(1788955200000L, now).startsWith("9 Sep"))
        assertEquals("9 Nov 2025", en.label(1_762_646_400_000L, now))
        val en12 = RowTime(ZoneId.of("UTC"), Locale.US, false, "Yesterday")
        assertEquals("2:13 PM", en12.label(now, now))
    }

    @Test fun turkishMonthNames() {
        val tr = RowTime(ZoneId.of("UTC"), Locale.forLanguageTag("tr-TR"), true, "Dün")
        assertEquals("Dün", tr.label(now - 86_400_000L, now))
        assertEquals("9 Kas 2025", tr.label(1_762_646_400_000L, now))
    }
}
