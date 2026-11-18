package dev.rosty.messages.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The WhatsApp notification while the vault is locked (`plans/wa-vault.md` 13.6): one notice with the count, no
 * sender, no text, no reply, and it never asks the gateway (the push decision takes no gateway at all).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockedWhatsAppNoticeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val chat = "905550000001@s.whatsapp.net"

    @Before fun clean() {
        shadowOf(manager).setNotificationsEnabled(true)
        NotifyPolicy.current = NotifyPolicy()
        LockedWhatsAppNotice.clear(context)
    }

    private fun push(
        count: String,
        kind: String = "message",
    ) = mapOf("kind" to kind, "chat" to chat, "count" to count)

    private fun shown(): List<Notification> =
        shadowOf(manager).activeNotifications.filter { it.tag == LockedWhatsAppNotice.TAG }.map { it.notification }

    @Test fun aLockedPushPostsOnlyTheCount() {
        assertTrue(WaPushNotices.onPush(context, push("3"), unlocked = false))
        val notice = shown().single()
        val extras = notice.extras
        assertEquals("WhatsApp", extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("3 new messages", extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull("no messaging style, so no lines", extras.getParcelableArray(Notification.EXTRA_MESSAGES))
        assertTrue("no action, so no inline reply", notice.actions.isNullOrEmpty())
        val all = extras.keySet().mapNotNull { extras.get(it)?.toString() }.joinToString(" ")
        assertFalse("the chat (a number) is not shown", all.contains("905550000001"))
        assertEquals(Notification.VISIBILITY_PUBLIC, notice.visibility)
    }

    @Test fun pushesAddUpIntoOneNotice() {
        WaPushNotices.onPush(context, push("1"), unlocked = false)
        WaPushNotices.onPush(context, push("4"), unlocked = false)
        WaPushNotices.onPush(context, push("x"), unlocked = false)
        assertEquals(1, shown().size)
        assertEquals(6, LockedWhatsAppNotice.count(context))
        assertEquals(
            "6 new messages",
            shown()
                .single()
                .extras
                .getCharSequence(Notification.EXTRA_TEXT)
                .toString(),
        )
    }

    @Test fun anOpenVaultLeavesTheMessageToTheApp() {
        assertFalse(WaPushNotices.onPush(context, push("2"), unlocked = true))
        assertTrue(shown().isEmpty())
    }

    @Test fun callsAreNotMessages() {
        assertFalse(WaPushNotices.onPush(context, push("1", kind = "call"), unlocked = false))
        assertTrue(shown().isEmpty())
    }

    @Test fun theUnlockClearsItAndTheCount() {
        WaPushNotices.onPush(context, push("2"), unlocked = false)
        LockedWhatsAppNotice.clear(context)
        assertTrue(shown().isEmpty())
        assertEquals(0, LockedWhatsAppNotice.count(context))
    }

    @Test fun notificationsOffPostNothing() {
        NotifyPolicy.current = NotifyPolicy(enabled = false)
        assertFalse(WaPushNotices.onPush(context, push("2"), unlocked = false))
        assertTrue(shown().isEmpty())
    }

    @Test
    @Config(sdk = [35], qualifiers = "+tr")
    fun inTurkish() {
        assertEquals("1 yeni mesaj", LockedWhatsAppNotice.text(context, 1))
        assertEquals("5 yeni mesaj", LockedWhatsAppNotice.text(context, 5))
    }
}
