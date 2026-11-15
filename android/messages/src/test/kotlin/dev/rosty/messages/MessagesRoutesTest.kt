package dev.rosty.messages

import android.content.Intent
import dev.rosty.design.text.UiText
import dev.rosty.design.transition.TabBarMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** E6.1: the tabs, the chrome of every route and the intents that open a conversation (pure). */
class MessagesRoutesTest {
    private val text: (UiText) -> String = { "t" }

    @Test fun theThreeTabsInTheGpuiOrder() {
        assertEquals(listOf("chats", "whatsapp", "settings"), MessagesTabs.map { it.id })
        assertEquals(MessagesRoute.Chats, MessagesRoots.getValue(ChatsTab))
        assertEquals(MessagesRoute.WhatsApp, MessagesRoots.getValue(WhatsAppTab))
        assertEquals(MessagesRoute.Settings, MessagesRoots.getValue(SettingsTab))
    }

    @Test fun rootsKeepTheBarAndConversationsHideIt() {
        for (root in MessagesRoots.values) assertEquals(TabBarMode.Visible, chromeFor(root, text).tabBar)
        assertEquals(TabBarMode.Hidden, chromeFor(MessagesRoute.SmsThread(7), text).tabBar)
        assertEquals(TabBarMode.Hidden, chromeFor(MessagesRoute.WaThread("1@s.whatsapp.net"), text).tabBar)
        assertEquals(TabBarMode.Hidden, chromeFor(MessagesRoute.NewConversation(), text).tabBar)
        // Pushed settings pages keep the full bar (F16), never folded.
        assertEquals(TabBarMode.Visible, chromeFor(MessagesRoute.About, text).tabBar)
        assertEquals(TabBarMode.Visible, chromeFor(MessagesRoute.Logs(), text).tabBar)
    }

    @Test fun aNotificationTagOpensItsConversation() {
        assertEquals(
            ChatsTab to listOf(MessagesRoute.SmsThread(12)),
            messagesRouteFor(IntentFacts(threadTag = "sms:12")),
        )
        assertEquals(
            ChatsTab to listOf(MessagesRoute.WaThread("100000000001@s.whatsapp.net")),
            messagesRouteFor(IntentFacts(threadTag = "wa:100000000001@s.whatsapp.net")),
        )
        assertNull(messagesRouteFor(IntentFacts(threadTag = "sms:0")))
        assertNull(messagesRouteFor(IntentFacts(threadTag = "wa:nojid")))
        assertNull(messagesRouteFor(IntentFacts(threadTag = "wa:abc@s.whatsapp.net")))
    }

    @Test fun aTagOrAHandoffThatNamesALidOrADeviceOpensTheChatOfThatId() {
        // The id goes into the route without its device; the thread resolves it to the chat of the person.
        assertEquals(
            ChatsTab to listOf(MessagesRoute.WaThread("777000000000001@lid")),
            messagesRouteFor(IntentFacts(threadTag = "wa:777000000000001:4@lid")),
        )
        assertEquals(
            ChatsTab to listOf(MessagesRoute.WaThread("777000000000001@lid")),
            messagesRouteFor(IntentFacts(action = ACTION_WA_CHAT, waJid = "777000000000001@lid")),
        )
        assertEquals(
            ChatsTab to listOf(MessagesRoute.WaThread("100000000001@s.whatsapp.net")),
            messagesRouteFor(IntentFacts(action = ACTION_WA_CHAT, waJid = "100000000001:2@c.us")),
        )
        // Text that is no JID falls back to the number, and to nothing without one.
        assertEquals(
            ChatsTab to listOf(MessagesRoute.WaThread("905550000001@s.whatsapp.net")),
            messagesRouteFor(IntentFacts(action = ACTION_WA_CHAT, waJid = "ada", waNumber = "+90 555 000 00 01")),
        )
        assertNull(messagesRouteFor(IntentFacts(action = ACTION_WA_CHAT, waJid = "ada")))
    }

    @Test fun theWhatsAppHandoffOpensTheChatByJidOrNumber() {
        assertEquals(
            ChatsTab to listOf(MessagesRoute.WaThread("100000000001@s.whatsapp.net")),
            messagesRouteFor(IntentFacts(action = ACTION_WA_CHAT, waJid = "100000000001@s.whatsapp.net")),
        )
        assertEquals(
            ChatsTab to listOf(MessagesRoute.WaThread("905550000001@s.whatsapp.net")),
            messagesRouteFor(IntentFacts(action = ACTION_WA_CHAT, waNumber = "+90 555 000 00 01")),
        )
        assertNull(messagesRouteFor(IntentFacts(action = ACTION_WA_CHAT, waNumber = "12")))
    }

    @Test fun smsLinksAndSharesOpenNewConversations() {
        val compose =
            ComposeRequest.build(
                Intent.ACTION_SENDTO,
                "+905550000001?body=hi%20there",
                null,
                null,
                null,
                null,
            )
        assertEquals(
            ChatsTab to listOf(MessagesRoute.SmsThread(0, listOf("+905550000001"), "hi there")),
            messagesRouteFor(IntentFacts(compose = compose)),
        )
        val share = ComposeRequest.build(Intent.ACTION_SEND, null, "look", null, null, "text/plain")
        assertEquals(
            ChatsTab to listOf(MessagesRoute.NewConversation(SharePayload("look", null, "text/plain"))),
            messagesRouteFor(IntentFacts(compose = share)),
        )
        assertNull(messagesRouteFor(IntentFacts(action = Intent.ACTION_MAIN)))
    }
}
