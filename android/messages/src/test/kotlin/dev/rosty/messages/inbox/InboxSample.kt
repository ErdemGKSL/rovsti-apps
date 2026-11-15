package dev.rosty.messages.inbox

import dev.rosty.core.model.messages.Channel
import dev.rosty.core.model.messages.InboxFilter
import dev.rosty.core.model.messages.ThreadId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Rows for the inbox screenshots and UI tests: names, no real numbers. */
internal object InboxSample {
    private fun row(
        key: String,
        title: String,
        snippet: String,
        time: String,
        channel: Channel,
        unread: Int = 0,
        pinned: Boolean = false,
        muted: Boolean = false,
        channels: List<Channel> = listOf(channel),
        group: Boolean = false,
        draft: Boolean = false,
        otp: String? = null,
        markedUnread: Boolean = false,
    ) = InboxRowUi(
        key = key,
        title = title,
        snippet = snippet,
        draft = draft,
        time = time,
        atMs = 0,
        unread = unread,
        markedUnread = markedUnread,
        pinned = pinned,
        muted = muted,
        archived = false,
        latest = channel,
        channels = channels.toImmutableList(),
        avatar = RowAvatar(group = group, colorKey = key),
        threads =
            persistentListOf(
                if (channel ==
                    Channel.Sms
                ) {
                    ThreadId.Sms(1)
                } else {
                    ThreadId.WhatsApp("$key@s.whatsapp.net")
                },
            ),
        smsThread = if (Channel.Sms in channels) 1 else null,
        smsSender = if (channel == Channel.Sms && !group) "+905550000001" else null,
        waChat = if (Channel.WhatsApp in channels) "$key@s.whatsapp.net" else null,
        group = group,
        tick = null,
        otp = otp,
    )

    val rows =
        listOf(
            row(
                "ada",
                "Ada Lovelace",
                "See you at the station at six",
                "14:05",
                Channel.WhatsApp,
                unread = 2,
                pinned = true,
                channels = listOf(Channel.WhatsApp, Channel.Sms),
            ),
            row(
                "bank",
                "BANK",
                "Your code is 482913. Do not share it.",
                "13:40",
                Channel.Sms,
                unread = 1,
                otp = "482913",
            ),
            row(
                "family",
                "Family",
                "Ece: Photo · from the picnic",
                "12:12",
                Channel.WhatsApp,
                unread = 14,
                muted = true,
                group = true,
            ),
            row("mert", "Mert Kaya", "You: On my way", "11:58", Channel.Sms),
            row("selin", "Selin Demir", "Draft: Call me when you land", "Yesterday", Channel.WhatsApp, draft = true),
            row("grace", "Grace Hopper", "Voice message (0:42)", "Yesterday", Channel.WhatsApp, markedUnread = true),
            row("deniz", "Deniz Aydın", "Thanks for the book!", "9 Nov", Channel.Sms),
            row("linus", "Linus", "This message was deleted", "8 Nov", Channel.WhatsApp),
            row("ece", "Ece Yıldız", "Location · Moda Sahili", "7 Nov", Channel.WhatsApp),
            row("kaan", "Kaan Öztürk", "Can I call you later?", "5 Nov", Channel.Sms),
            row("zeynep", "Zeynep Şahin", "Photo · beach", "1 Nov", Channel.WhatsApp),
            row("ali", "Ali Vural", "Happy birthday!", "28 Oct", Channel.Sms),
            row("barbara", "Barbara Liskov", "Contact · Ada Lovelace", "20 Oct", Channel.WhatsApp),
            row("cem", "Cem Arslan", "ok", "3 Oct", Channel.Sms),
        ).toImmutableList()

    val chips =
        persistentListOf(
            FilterChipUi(InboxFilter.All, rows.size),
            FilterChipUi(InboxFilter.Unread, 5),
            FilterChipUi(InboxFilter.WhatsApp, 8),
            FilterChipUi(InboxFilter.Sms, 7),
            FilterChipUi(InboxFilter.Groups, 1),
        )

    val state =
        InboxUiState(
            loaded = true,
            access = SmsAccess.Ready,
            rows = rows,
            chips = chips,
            archivedCount = 3,
            archivedUnread = 1,
            anyRows = true,
        )
}
