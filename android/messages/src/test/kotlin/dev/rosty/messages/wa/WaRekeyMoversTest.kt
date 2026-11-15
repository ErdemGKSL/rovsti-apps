package dev.rosty.messages.wa

import android.app.NotificationManager
import android.graphics.Bitmap
import dev.rosty.messages.TestMessagesApp
import dev.rosty.messages.TestMessagesContainer
import dev.rosty.messages.notify.ContactNotificationAvatars
import dev.rosty.messages.notify.Digest
import dev.rosty.messages.notify.MessagesNotifier
import dev.rosty.messages.notify.NotificationAvatars
import dev.rosty.messages.notify.NotifyPolicy
import dev.rosty.messages.notify.OpenConversation
import dev.rosty.messages.spam.prefsWith
import dev.rosty.platform.PlatformEvents
import dev.rosty.platform.notify.MessageReminders
import dev.rosty.platform.notify.NotificationSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The movers of the app together ([messagesRekeyMovers]), on the test container: one step of the store's log moves
 * the draft, the mute, the notification, the conversation on screen and the reminders of a chat, in an order that
 * lets each judge by what the one before moved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestMessagesApp::class)
class WaRekeyMoversTest {
    private val app = RuntimeEnvironment.getApplication() as TestMessagesApp
    private val container: TestMessagesContainer get() = app.container
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val lid = "777000000000001@lid"
    private val number = "905550000001@s.whatsapp.net"
    private val from = WaRekeys.tagOf(lid)
    private val into = WaRekeys.tagOf(number)

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
        MessageReminders.armed(app).forEach { MessageReminders.disarm(app, it.id) }
    }

    private fun notify(tag: String) =
        MessagesNotifier.show(app, Digest(NotificationSpec(tag, "ada", listOf("Are you coming?"), null, true, false)))

    private fun remind(
        id: Long,
        tag: String,
        messageId: String,
    ) = MessageReminders.arm(
        app,
        JSONObject()
            .put("id", id)
            .put("tag", tag)
            .put("message_id", messageId)
            .put("message_at_ms", 1_789_999_000_000L)
            .put("at_ms", 4_102_444_800_000L)
            .put("notif_title", "Reminder: ada")
            .put("notif_body", "Are you coming?")
            .toString(),
    )

    private fun follow(step: Rekey) = runBlocking { messagesRekeyMovers(container).forEach { it.move(step) } }

    private fun tags(): Set<String> = nm.activeNotifications.map { it.tag }.toSet() - MessagesNotifier.SUMMARY_TAG

    @Test fun oneStepMovesEverythingKeptForTheChat() {
        val core =
            RekeyPrefsCore(
                prefs = prefsWith().copy(muted = listOf("sms:7")),
                drafts = mapOf(from to "half a thought", "sms:7" to "ok"),
                core = container.fakeRust,
            )
        assertTrue(notify(from))
        assertTrue(remind(41, from, "L1"))
        assertTrue(remind(42, "sms:7", "sms:12"))

        follow(Rekey(lid, number))

        assertEquals(mapOf(into to "half a thought", "sms:7" to "ok"), container.store.drafts.value)
        assertEquals(setOf(into), tags())
        assertEquals(listOf("Are you coming?"), MessagesNotifier.shown(app, into)?.spec?.lines)
        val reminders = MessageReminders.armed(app).associate { it.id to it.tag }
        assertEquals(mapOf(41L to into, 42L to "sms:7"), reminders)
        assertEquals(
            listOf("sms:7"),
            container.store.prefs.value
                ?.muted,
        )

        // The same step again (the cursor had not moved yet): nothing changes.
        follow(Rekey(lid, number))
        assertEquals(mapOf(into to "half a thought", "sms:7" to "ok"), container.store.drafts.value)
        assertEquals(setOf(into), tags())
        assertEquals(reminders, MessageReminders.armed(app).associate { it.id to it.tag })
        assertTrue(runBlocking { container.store.flush() })
        assertEquals(mapOf(into to "half a thought", "sms:7" to "ok"), core.drafts)
    }

    @Test fun theMuteMovesBeforeTheNotificationIsJudged() {
        // The LID chat was muted in a run before this one; a push posted its notification before the prefs were read.
        RekeyPrefsCore(prefs = prefsWith().copy(muted = listOf(from)), core = container.fakeRust)
        assertTrue(notify(from))

        follow(Rekey(lid, number))

        assertEquals(
            listOf(into),
            container.store.prefs.value
                ?.muted,
        )
        assertTrue(NotifyPolicy.current.isMuted(into, nowMs = 0))
        // The muted chat gets no notification back.
        assertTrue(tags().isEmpty())
    }

    @Test fun theChatOnScreenStaysTheOpenOneAndGetsNoNotification() {
        RekeyPrefsCore(core = container.fakeRust)
        assertTrue(notify(from))
        PlatformEvents.uiAttached = true
        OpenConversation.enter(from)

        follow(Rekey(lid, number))

        assertEquals(into, OpenConversation.tag)
        assertTrue(OpenConversation.isShowing(into))
        assertNull(MessagesNotifier.shown(app, into))
        assertTrue(tags().isEmpty())
    }
}
