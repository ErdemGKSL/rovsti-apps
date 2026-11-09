package dev.rovsti.platform.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import dev.rovsti.bridge.ReceiverWork
import dev.rovsti.bridge.ReplyReceiver
import dev.rovsti.core.FakeRustCore
import dev.rovsti.platform.Headless
import dev.rovsti.platform.PlatformEvent
import dev.rovsti.platform.PlatformEvents
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReplyReceiverTest {
    private val context = RuntimeEnvironment.getApplication()
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private val fake = FakeRustCore()
    private val tag = "wa:905551112233@s.whatsapp.net"

    private val spec =
        JSONObject()
            .put("tag", tag)
            .put("title", "Ayse")
            .put("lines", org.json.JSONArray().put("Are you coming?"))
            .put("reply", true)
            .put("silent", false)
            .toString()

    @Before fun setUp() {
        ReceiverWork.executor = { it.run() }
        Headless.useCore(fake)
        PlatformEvents.uiAttached = false
        MessageNotifications.show(context, spec)
    }

    @After fun tearDown() {
        ReceiverWork.executor = { Thread(it).start() }
        Headless.useCore(null)
        PlatformEvents.uiAttached = false
    }

    private fun replyIntent(text: String?): Intent {
        val intent = Intent(ReplyActions.REPLY).putExtra(MessageNotifications.EXTRA_TAG, tag)
        val input = RemoteInput.Builder(MessageNotifications.KEY_REPLY).build()
        RemoteInput.addResultsToIntent(
            arrayOf(input),
            intent,
            Bundle().apply { if (text != null) putCharSequence(MessageNotifications.KEY_REPLY, text) },
        )
        return intent
    }

    private fun shownLines(): List<String> {
        val shown = nm.activeNotifications.single { it.tag == tag }.notification
        val messages = shown.extras.getParcelableArray(Notification.EXTRA_MESSAGES)!!
        return messages.map { (it as Bundle).getCharSequence("text").toString() }
    }

    @Test fun theMessageNotificationHasReplyMarkReadAndRemind() {
        val shown = nm.activeNotifications.single { it.tag == tag }.notification
        assertEquals(PlatformChannels.MESSAGES, shown.channelId)
        assertEquals(listOf("Reply", "Mark read", "Remind me in 1 hour"), shown.actions.map { it.title.toString() })
        assertNotNull(shown.actions[0].remoteInputs)
        assertEquals(tag.hashCode() * 8, shadowOf(shown.actions[0].actionIntent).requestCode)
    }

    @Test fun withTheUiAttachedAReplyIsEmittedAsMsgReply() =
        runTest(UnconfinedTestDispatcher()) {
            PlatformEvents.uiAttached = true
            val seen = mutableListOf<PlatformEvent>()
            val job = launch { PlatformEvents.events.collect { seen += it } }

            ReplyReceiver().onReceive(context, replyIntent("  On my way  "))
            job.cancel()

            val reply = seen.single { it.kind == "msg_reply" }
            val body = JSONObject(reply.json)
            assertEquals(tag, body.getString("tag"))
            assertEquals("On my way", body.getString("text"))
            assertTrue("Rust is not asked while the UI is attached", fake.calls.isEmpty())
        }

    @Test fun anEmptyReplyDoesNothing() =
        runTest(UnconfinedTestDispatcher()) {
            PlatformEvents.uiAttached = true
            val seen = mutableListOf<PlatformEvent>()
            val job = launch { PlatformEvents.events.collect { seen += it } }
            ReplyReceiver().onReceive(context, replyIntent("   "))
            ReplyReceiver().onReceive(context, replyIntent(null))
            job.cancel()
            assertTrue(seen.none { it.kind == "msg_reply" })
        }

    @Test fun detachedAWhatsAppReplyGoesThroughHeadlessAndReposts() {
        var request: String? = null
        fake.onHeadless("wa_reply") {
            request = it
            """{"ok":true}"""
        }

        ReplyReceiver().onReceive(context, replyIntent("On my way"))

        val sent = JSONObject(request!!)
        assertEquals("905551112233@s.whatsapp.net", sent.getString("chat"))
        assertEquals("On my way", sent.getString("text"))
        assertEquals(context.noBackupFilesDir.absolutePath, sent.getString("dir"))
        assertEquals(listOf("You: On my way"), shownLines())
        val shown = nm.activeNotifications.single { it.tag == tag }.notification
        assertEquals("Ayse", shown.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE).toString())
    }

    @Test fun aNullAnswerFallsBackToThePlainNotification() {
        // FakeRustCore answers "" for an unscripted kind: Headless reports "no decision".
        ReplyReceiver().onReceive(context, replyIntent("On my way"))

        assertEquals(listOf("Are you coming?"), shownLines())
    }

    @Test fun anErrorEnvelopeIsAlsoNoDecision() {
        fake.onHeadless("wa_reply") { """{"ok":false}""" }
        ReplyReceiver().onReceive(context, replyIntent("On my way"))
        assertEquals(listOf("Are you coming?"), shownLines())
    }

    @Test fun remindMeAsksRustArmsTheAlarmAndClearsTheNotification() {
        var request: String? = null
        fake.onHeadless("msg_remind") {
            request = it
            """{"ok":true,"arm":{"id":1700000000000,"tag":"$tag","message_id":"","message_at_ms":1700000000000,
              |"at_ms":1700003600000,"title":"Ayse","preview":"Are you coming?","notif_title":"Reminder: Ayse",
              |"notif_body":"Are you coming?"},"toast":"Reminder set for 12:00"}
            """.trimMargin()
        }
        val intent =
            Intent(ReplyActions.REMIND)
                .putExtra(MessageNotifications.EXTRA_TAG, tag)
                .putExtra(ReplyActions.EXTRA_TITLE, "Ayse")
                .putExtra(ReplyActions.EXTRA_BODY, "Are you coming?")

        ReplyReceiver().onReceive(context, intent)

        assertEquals(60, JSONObject(request!!).getInt("minutes"))
        assertEquals(1, MessageReminders.armed(context).size)
        assertNull(nm.activeNotifications.firstOrNull { it.tag == tag })
        assertNotNull(shadowOf(context.getSystemService(android.app.AlarmManager::class.java)).peekNextScheduledAlarm())
    }

    @Test fun remindMeWithNoAnswerLeavesTheNotificationAndArmsNothing() {
        val intent =
            Intent(ReplyActions.REMIND)
                .putExtra(MessageNotifications.EXTRA_TAG, tag)
                .putExtra(ReplyActions.EXTRA_TITLE, "Ayse")
                .putExtra(ReplyActions.EXTRA_BODY, "Are you coming?")
        ReplyReceiver().onReceive(context, intent)
        assertTrue(MessageReminders.armed(context).isEmpty())
        assertNotNull(nm.activeNotifications.firstOrNull { it.tag == tag })
    }

    @Test fun copyCodeClearsTheNotification() {
        val intent =
            Intent(
                ReplyActions.COPY,
            ).putExtra(MessageNotifications.EXTRA_TAG, tag).putExtra(ReplyActions.EXTRA_CODE, "123456")
        ReplyReceiver().onReceive(context, intent)
        assertNull(nm.activeNotifications.firstOrNull { it.tag == tag })
        val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!
        assertEquals("123456", clip.getItemAt(0).text.toString())
    }
}
