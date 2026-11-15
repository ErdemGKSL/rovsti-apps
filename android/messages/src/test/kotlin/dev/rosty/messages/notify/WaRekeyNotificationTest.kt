package dev.rosty.messages.notify

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import dev.rosty.messages.EXTRA_THREAD_TAG
import dev.rosty.messages.TestMessagesApp
import dev.rosty.messages.wa.WaRekeys
import dev.rosty.platform.PlatformEvents
import dev.rosty.platform.notify.NotificationSpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * E6-WA R1: when two WhatsApp chats become one, the notification of the chat that is gone moves to the tag of the
 * chat that stays (`wa:<from>` to `wa:<into>`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestMessagesApp::class)
class WaRekeyNotificationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private val from = WaRekeys.tagOf("777000000000001@lid")
    private val into = WaRekeys.tagOf("905550000001@s.whatsapp.net")
    private val other = WaRekeys.tagOf("905550000002@s.whatsapp.net")

    @Before fun setUp() {
        PlatformEvents.uiAttached = false
        NotifyPolicy.current = NotifyPolicy()
        MessagesNotifier.avatars = NotificationAvatars { _, _ -> Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888) }
    }

    @After fun tearDown() {
        PlatformEvents.uiAttached = false
        NotifyPolicy.current = NotifyPolicy()
        MessagesNotifier.avatars = ContactNotificationAvatars
        MessagesNotifier.onDismissed = null
        OpenConversation.tag?.let(OpenConversation::leave)
    }

    private fun spec(
        tag: String,
        title: String = "ada",
        lines: List<String> = listOf("Are you coming?", "We start at six"),
    ): String =
        JSONObject()
            .put("tag", tag)
            .put("title", title)
            .put("lines", JSONArray(lines))
            .put("otp", JSONObject.NULL)
            .put("reply", true)
            .put("silent", false)
            .toString()

    private fun tags(): Set<String> = nm.activeNotifications.map { it.tag }.toSet() - MessagesNotifier.SUMMARY_TAG

    private fun shown(tag: String): Notification = nm.activeNotifications.single { it.tag == tag }.notification

    @Test fun theNotificationOfTheMergedChatMovesToTheTagOfTheChatThatStays() {
        MessagesNotifier.show(context, spec(from))
        MessagesNotifier.show(context, spec(other, title = "Mert"))

        assertTrue(MessagesNotifier.retag(context, from, into))

        assertEquals(setOf(into, other), tags())
        val moved = MessagesNotifier.shown(context, into)!!
        assertEquals(into, moved.spec.tag)
        assertEquals("ada", moved.spec.title)
        assertEquals(listOf("Are you coming?", "We start at six"), moved.spec.lines)
        // The same lines again are no news: no second sound.
        assertTrue(moved.spec.silent)
        assertTrue(shown(into).flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        // A tap opens the chat that stays, and the summary is still there for the two conversations.
        val tap = shadowOf(shown(into).contentIntent).savedIntent
        assertEquals(into, tap.getStringExtra(EXTRA_THREAD_TAG))
        assertTrue(nm.activeNotifications.any { it.tag == MessagesNotifier.SUMMARY_TAG })
    }

    @Test fun aNotificationTheChatThatStaysAlreadyShowsIsKept() {
        MessagesNotifier.show(context, spec(from, lines = listOf("under the LID")))
        MessagesNotifier.show(context, spec(into, title = "Ada Lovelace", lines = listOf("under the number")))

        assertFalse(MessagesNotifier.retag(context, from, into))

        assertEquals(setOf(into), tags())
        val kept = MessagesNotifier.shown(context, into)!!
        assertEquals("Ada Lovelace", kept.spec.title)
        assertEquals(listOf("under the number"), kept.spec.lines)
        assertFalse(kept.spec.silent)
    }

    @Test fun withNothingShowingNothingIsPostedAndRunningItAgainChangesNothing() {
        assertFalse(MessagesNotifier.retag(context, from, into))
        assertTrue(nm.activeNotifications.isEmpty())

        MessagesNotifier.show(context, spec(from))
        assertTrue(MessagesNotifier.retag(context, from, into))
        // The step is followed again (a replay): the moved notification stays as it is.
        assertFalse(MessagesNotifier.retag(context, from, into))
        assertFalse(MessagesNotifier.retag(context, into, into))
        assertEquals(setOf(into), tags())
    }

    @Test fun theLastNotificationGoneTakesTheSummaryAway() {
        MessagesNotifier.show(context, spec(from))
        // The chat that stays is muted: the moved notification is not posted.
        NotifyPolicy.current = NotifyPolicy(muted = setOf(into))

        assertFalse(MessagesNotifier.retag(context, from, into))

        assertTrue(nm.activeNotifications.isEmpty())
    }

    @Test fun theChatOnScreenGetsNoNotificationBack() {
        MessagesNotifier.show(context, spec(from))
        PlatformEvents.uiAttached = true
        OpenConversation.enter(into)

        assertFalse(MessagesNotifier.retag(context, from, into))

        assertNull(MessagesNotifier.shown(context, into))
        assertNull(MessagesNotifier.shown(context, from))
    }

    @Test fun aPushThatNamesTheChatByAnOldIdIsPostedUnderTheKeyOfItsChat() {
        val lid = "777000000000001@lid"
        val pn = "905550000001@s.whatsapp.net"
        val pushed = Digest(NotificationSpec(from, "ada", listOf("hi"), null, true, false))

        assertEquals(into, NotificationNames.rekeyed(pushed, lid, pn).spec.tag)
        assertEquals(listOf("hi"), NotificationNames.rekeyed(pushed, lid, pn).spec.lines)
        // The same chat, or a digest of another conversation: untouched.
        assertEquals(pushed, NotificationNames.rekeyed(pushed, lid, lid))
        assertEquals(pushed, NotificationNames.rekeyed(pushed, "777000000000002@lid", pn))
    }
}
