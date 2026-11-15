package dev.rosty.stops.notice

import dev.rosty.core.model.stops.NoticePrefs
import dev.rosty.core.model.stops.NoticeWhen
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.WaProfile
import dev.rosty.wa.WaLinkState

/** Made-up WhatsApp people and chats for the arriving-soon tests. */
internal object NoticeFixtures {
    val connected = WaLinkState.Connected(InstanceStatus("inst-stops", InstanceState.Connected, pushName = "Deniz"))

    const val AYSE = "905550001122@s.whatsapp.net"
    const val SHOP = "905550003344@s.whatsapp.net"
    const val STRANGER = "905550005566@s.whatsapp.net"
    const val FAMILY = "120363000000000001@g.us"

    /** As the gateway lists them, newest first. */
    val chats =
        listOf(
            RichChat(jid = AYSE, name = "ayse", unread = 0, isGroup = false),
            RichChat(jid = FAMILY, name = "Family", unread = 2, isGroup = true),
            RichChat(jid = SHOP, name = null, unread = 0, isGroup = false),
            RichChat(jid = STRANGER, name = null, unread = 0, isGroup = false),
        )

    /** The same chats as the gateway lists them when it knows the names itself (the app-level tests). */
    val listedByName =
        listOf(
            chats[0].copy(name = "Ayşe Demir"),
            chats[1],
            chats[2].copy(name = "Corner Bakery"),
            chats[3],
        )

    /** What WhatsApp told about them: a saved contact, a verified business, nothing about the stranger. */
    val profiles =
        mapOf(
            AYSE to WaProfile(AYSE, pushName = "ayse \uD83C\uDF3B", contactName = "Ayşe Demir"),
            SHOP to WaProfile(SHOP, pushName = "shop", verifiedName = "Corner Bakery", business = true),
        )

    val notice =
        NoticePrefs(
            chat = AYSE,
            chatName = "Ayşe Demir",
            template = "Arriving at {stop} in about {min} min.",
            sendWhen = NoticeWhen.MinutesBefore(10),
        )
}
