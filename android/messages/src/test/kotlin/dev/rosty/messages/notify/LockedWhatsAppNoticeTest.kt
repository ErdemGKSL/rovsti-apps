package dev.rosty.messages.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.rosty.platform.wa.OpenedPush
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
    private val chat = "100000000001@s.whatsapp.net"

    @Before fun clean() {
        shadowOf(manager).setNotificationsEnabled(true)
        NotifyPolicy.current = NotifyPolicy()
        LockedWhatsAppNotice.clear(context)
    }

    private fun push(
        count: String,
        kind: String = "message",
    ) = mapOf("kind" to kind, "chat" to chat, "count" to count)

    /** A push that is not sealed (no push key on the gateway), and no device contact owns the number. */
    private fun onPush(
        data: Map<String, String>,
        unlocked: Boolean,
    ) = WaPushNotices.onPush(context, OpenedPush(data, null, sealed = false), unlocked, contactName = { null })

    private fun shown(): List<Notification> =
        shadowOf(manager).activeNotifications.filter { it.tag == LockedWhatsAppNotice.TAG }.map { it.notification }

    @Test fun aLockedPushPostsOnlyTheCount() {
        assertTrue(onPush(push("3"), unlocked = false))
        val notice = shown().single()
        val extras = notice.extras
        assertEquals("WhatsApp", extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("3 new messages", extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull("no messaging style, so no lines", extras.getParcelableArray(Notification.EXTRA_MESSAGES))
        assertTrue("no action, so no inline reply", notice.actions.isNullOrEmpty())
        val all = extras.keySet().mapNotNull { extras.get(it)?.toString() }.joinToString(" ")
        assertFalse("the chat (a number) is not shown", all.contains("100000000001"))
        assertEquals(Notification.VISIBILITY_PUBLIC, notice.visibility)
    }

    @Test fun pushesAddUpIntoOneNotice() {
        onPush(push("1"), unlocked = false)
        onPush(push("4"), unlocked = false)
        onPush(push("x"), unlocked = false)
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
        assertFalse(onPush(push("2"), unlocked = true))
        assertTrue(shown().isEmpty())
    }

    @Test fun callsAreNotMessages() {
        assertFalse(onPush(push("1", kind = "call"), unlocked = false))
        assertTrue(shown().isEmpty())
    }

    @Test fun theUnlockClearsItAndTheCount() {
        onPush(push("2"), unlocked = false)
        LockedWhatsAppNotice.clear(context)
        assertTrue(shown().isEmpty())
        assertEquals(0, LockedWhatsAppNotice.count(context))
    }

    @Test fun notificationsOffPostNothing() {
        NotifyPolicy.current = NotifyPolicy(enabled = false)
        assertFalse(onPush(push("2"), unlocked = false))
        assertTrue(shown().isEmpty())
    }

    @Test
    @Config(sdk = [35], qualifiers = "+tr")
    fun inTurkish() {
        assertEquals("1 yeni mesaj", LockedWhatsAppNotice.text(context, 1))
        assertEquals("5 yeni mesaj", LockedWhatsAppNotice.text(context, 5))
    }
}
