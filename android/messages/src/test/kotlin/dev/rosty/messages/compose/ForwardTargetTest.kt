package dev.rosty.messages.compose

import dev.rosty.core.model.messages.Channel
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.inbox.InboxSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardTargetTest {
    private val rows = InboxSample.state.rows

    @Test fun aPersonWithBothChannelsGivesTwoTargetsThatNameTheirChannel() {
        val ada = ForwardTarget.of(rows, "Ada", exclude = null)
        assertEquals(listOf(Channel.Sms, Channel.WhatsApp), ada.map { it.channel })
        assertTrue(ada.all { it.both })
    }

    @Test fun theSourceConversationIsLeftOut() {
        val ada = ForwardTarget.of(rows, "Ada", exclude = "wa:ada@s.whatsapp.net")
        assertEquals(listOf(Channel.Sms), ada.map { it.channel })
    }

    @Test fun archivedChatsAndOtherNamesAreNotOffered() {
        val all = ForwardTarget.of(rows, "", exclude = null)
        assertTrue(all.none { t -> rows.first { it.title == t.title }.archived })
        assertTrue(ForwardTarget.of(rows, "nobody by this name", exclude = null).isEmpty())
    }

    @Test fun aPickOpensTheConversationWithTheTextAsItsDraft() {
        val wa = ForwardTarget.of(rows, "Ada", exclude = null).first { it.channel == Channel.WhatsApp }
        assertEquals(MessagesRoute.WaThread("ada@s.whatsapp.net", draft = "hi"), wa.route("hi"))
    }
}
