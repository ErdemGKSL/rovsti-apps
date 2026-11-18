package dev.rosty.messages.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.rosty.platform.notify.NotificationSpec
import dev.rosty.platform.wa.OpenedPush
import dev.rosty.platform.wa.push.PushContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Round 7b: a WhatsApp push sealed to this install's key, opened while the vault is locked, shows the sender and the
 * preview as the setting allows; the lock screen gets only "WhatsApp, N new messages". A push that is not sealed names
 * a device contact at most; one that does not open stays content-free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockedWhatsAppPreviewTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val chat = "100000000001@s.whatsapp.net"
    private val group = "100000000002-1600000000@g.us"

    @Before fun clean() {
        shadowOf(manager).setNotificationsEnabled(true)
        NotifyPolicy.current = NotifyPolicy()
        LockedWhatsAppNotice.clear(context)
    }

    private fun sealed(content: PushContent) =
        OpenedPush(mapOf("kind" to "message", "chat" to content.chat.orEmpty(), "count" to "1"), content, sealed = true)

    private fun onPush(
        opened: OpenedPush,
        mode: WaPreviewMode = WaPreviewMode.NameAndPreview,
        contacts: Map<String, String> = emptyMap(),
    ) = WaPushNotices.onPush(context, opened, unlocked = false, mode = mode, contactName = { contacts[it] }) { 1_000L }

    private fun shown(): List<Notification> =
        shadowOf(manager).activeNotifications.filter { it.tag == LockedWhatsAppNotice.TAG }.map { it.notification }

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    private fun Notification.all(): String = extras.keySet().mapNotNull { extras.get(it)?.toString() }.joinToString(" ")

    @Test fun aSealedPushShowsTheSenderAndThePreviewButNotOnTheLockScreen() {
        val content = PushContent(chat = chat, name = "Ada", text = "Lunch at one?", count = 1)
        assertTrue(onPush(sealed(content)))
        val notice = shown().single()
        assertEquals("Ada", notice.title())
        assertEquals("Lunch at one?", notice.text())
        assertTrue("no inline reply while locked", notice.actions.isNullOrEmpty())
        assertEquals(Notification.VISIBILITY_PRIVATE, notice.visibility)
        val public = notice.publicVersion!!
        assertEquals("WhatsApp", public.title())
        assertEquals("1 new message", public.text())
        assertFalse(public.all().contains("Ada") || public.all().contains("Lunch"))
    }

    @Test fun aDeviceContactNamesTheChat() {
        val content = PushContent(chat = chat, name = "ada wa", chatName = "Ada WA", text = "hi")
        onPush(sealed(content), contacts = mapOf("+100000000001" to "Ada Lovelace"))
        assertEquals("Ada Lovelace", shown().single().title())
    }

    @Test fun aGroupIsTitledBySubjectAndNamesTheAuthor() {
        val content =
            PushContent(
                chat = group,
                chatName = "Team",
                group = true,
                sender = "100000000003@s.whatsapp.net",
                name = "Bo",
                text = "standup?",
            )
        onPush(sealed(content))
        val notice = shown().single()
        assertEquals("Team", notice.title())
        assertEquals("Bo: standup?", notice.text())
    }

    @Test fun linesOfOneChatAddUpInItsNotice() {
        onPush(sealed(PushContent(chat = chat, name = "Ada", text = "one")))
        onPush(sealed(PushContent(chat = chat, name = "Ada", text = "two")))
        val notice = shown().single()
        assertEquals("two", notice.text())
        assertEquals(2, notice.number)
        val lines = notice.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() }
        assertEquals(listOf("one", "two"), lines)
        assertEquals(2, LockedWhatsAppNotice.count(context))
    }

    @Test fun nameOnlyShowsTheCountInsteadOfTheText() {
        onPush(sealed(PushContent(chat = chat, name = "Ada", text = "secret")), mode = WaPreviewMode.NameOnly)
        val notice = shown().single()
        assertEquals("Ada", notice.title())
        assertEquals("1 new message", notice.text())
        assertFalse(notice.all().contains("secret"))
    }

    @Test fun noContentIsTheContentFreeNotice() {
        onPush(sealed(PushContent(chat = chat, name = "Ada", text = "secret")), mode = WaPreviewMode.NoContent)
        val notice = shown().single()
        assertEquals("WhatsApp", notice.title())
        assertFalse(notice.all().contains("secret") || notice.all().contains("Ada"))
        assertEquals(Notification.VISIBILITY_PUBLIC, notice.visibility)
    }

    @Test fun aPushThatDoesNotOpenStaysContentFree() {
        val failed = OpenedPush(mapOf("kind" to "message"), null, sealed = true)
        onPush(failed, contacts = mapOf("+100000000001" to "Ada Lovelace"))
        val notice = shown().single()
        assertEquals("WhatsApp", notice.title())
        assertEquals("1 new message", notice.text())
    }

    @Test fun aPushWithoutAKeyNamesADeviceContactOnly() {
        val clear = OpenedPush(mapOf("kind" to "message", "chat" to chat, "count" to "2"), null, sealed = false)
        onPush(clear, contacts = mapOf("+100000000001" to "Ada Lovelace"))
        val notice = shown().single()
        assertEquals("Ada Lovelace", notice.title())
        assertEquals("2 new messages", notice.text())
        assertEquals(Notification.VISIBILITY_PRIVATE, notice.visibility)
        LockedWhatsAppNotice.clear(context)
        // Nobody in the address book: the content-free notice, the number never shown.
        onPush(clear)
        assertEquals("WhatsApp", shown().single().title())
        assertFalse(shown().single().all().contains("100000000001"))
    }

    @Test fun theUnlockTakesAwayEveryLockedNotice() {
        onPush(sealed(PushContent(chat = chat, text = "a")))
        onPush(sealed(PushContent(chat = group, group = true, text = "b")))
        onPush(OpenedPush(mapOf("kind" to "message"), null, sealed = true))
        assertEquals(3, shown().size)
        LockedWhatsAppNotice.clear(context)
        assertTrue(shown().isEmpty())
    }

    @Test fun theUnlockedNotificationFollowsThePreviewSetting() {
        val digest = Digest(NotificationSpec("wa:$chat", "Ada", listOf("hi", "secret"), null, true, false))
        val full = MessagesNotifier.forPreview(context, digest, WaPreviewMode.NameAndPreview)
        assertEquals(digest, full)
        val name = MessagesNotifier.forPreview(context, digest, WaPreviewMode.NameOnly)
        assertEquals("Ada", name.spec.title)
        // The number of lines stays, their text goes (also from the extras an app with notification access reads).
        assertEquals(listOf("", ""), name.spec.lines)
        assertEquals(name, MessagesNotifier.forPreview(context, name, WaPreviewMode.NameOnly))
        val shownName = MessagesNotifier.build(context, digest, 1_000L, WaPreviewMode.NameOnly)
        assertFalse(shownName.all().contains("secret"))
        assertTrue(shownName.all().contains("2 new messages"))
        val none = MessagesNotifier.forPreview(context, digest, WaPreviewMode.NoContent)
        assertEquals("WhatsApp", none.spec.title)
        assertFalse(none.spec.reply)
        val built = MessagesNotifier.build(context, digest, 1_000L, WaPreviewMode.NoContent)
        assertFalse(built.all().contains("secret"))
        assertEquals(Notification.VISIBILITY_PRIVATE, built.visibility)
        assertEquals("WhatsApp", built.publicVersion!!.title())
        // SMS is never changed.
        val sms = Digest(NotificationSpec("sms:7", "Bo", listOf("x"), null, true, false))
        assertEquals(sms, MessagesNotifier.forPreview(context, sms, WaPreviewMode.NoContent))
    }

    @Test
    @Config(sdk = [35], qualifiers = "+tr")
    fun inTurkish() {
        onPush(sealed(PushContent(chat = chat, name = "Ada", text = "x")), mode = WaPreviewMode.NameOnly)
        assertEquals("1 yeni mesaj", shown().single().text())
    }
}
