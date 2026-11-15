package dev.rosty.messages.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.rosty.core.FakeRustCore
import dev.rosty.messages.TestMessagesContainer
import dev.rosty.platform.Headless
import dev.rosty.platform.PlatformEvents
import dev.rosty.platform.notify.MessageNotifications
import dev.rosty.platform.notify.MessageReminders
import dev.rosty.platform.notify.ReplyActions
import dev.rosty.shell.HasContainer
import org.json.JSONArray
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

/** The container of these tests: the SMS repository is the fake, the WhatsApp outbox a recorder. */
class ReplyTestContainer(
    context: Context,
) : TestMessagesContainer(context) {
    /** `jid to text` of every WhatsApp reply queued in the outbox. */
    val queued = ArrayList<Pair<String, String>>()
    var waLinked = true

    override fun createReplies(): ReplyRouter =
        ReplyRouter(
            sms = sms,
            wa = { jid, text ->
                if (waLinked) queued += jid to text
                waLinked
            },
            recipients = { thread -> if (thread == 7L) listOf("+905550000001") else emptyList() },
            sim = { 3 },
            now = { 1_000L },
        )
}

class ReplyTestApp :
    Application(),
    HasContainer<ReplyTestContainer> {
    override val container: ReplyTestContainer by lazy { ReplyTestContainer(this) }
}

/**
 * E6.9 AC1: an inline reply goes through the repository (SMS) or the outbox (WhatsApp) while the UI is attached, and
 * through `headless` when it is not; plus the other actions of the notification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ReplyTestApp::class)
class MessageActionReceiverTest {
    private val context = RuntimeEnvironment.getApplication()
    private val container get() = (context as ReplyTestApp).container
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private val rust = FakeRustCore()
    private val sms = "sms:7"
    private val wa = "wa:905550000002@s.whatsapp.net"

    @Before fun setUp() {
        NotifyWork.executor = { it.run() }
        Headless.useCore(rust)
        PlatformEvents.uiAttached = false
        NotifyPolicy.current = NotifyPolicy()
        MessagesNotifier.avatars = NotificationAvatars { _, _ -> null }
        MessagesNotifier.show(context, spec(sms))
        MessagesNotifier.show(context, spec(wa))
    }

    @After fun tearDown() {
        NotifyWork.executor = { Thread(it).start() }
        Headless.useCore(null)
        PlatformEvents.uiAttached = false
        MessagesNotifier.avatars = ContactNotificationAvatars
    }

    private fun spec(
        tag: String,
        otp: String? = null,
    ): String =
        JSONObject()
            .put("tag", tag)
            .put("title", "Ada Lovelace")
            .put("lines", JSONArray().put("Are you coming?"))
            .put("otp", otp ?: JSONObject.NULL)
            .put("reply", otp == null)
            .put("silent", false)
            .toString()

    private fun reply(
        tag: String,
        text: String?,
    ) {
        val intent = Intent(ReplyActions.REPLY).putExtra(MessageNotifications.EXTRA_TAG, tag)
        val input = RemoteInput.Builder(MessageNotifications.KEY_REPLY).build()
        RemoteInput.addResultsToIntent(
            arrayOf(input),
            intent,
            Bundle().apply { if (text != null) putCharSequence(MessageNotifications.KEY_REPLY, text) },
        )
        MessageActionReceiver().onReceive(context, intent)
    }

    private fun action(
        action: String,
        tag: String,
        extras: Intent.() -> Unit = {},
    ) {
        MessageActionReceiver().onReceive(
            context,
            Intent(action).putExtra(MessageNotifications.EXTRA_TAG, tag).apply(extras),
        )
    }

    private fun tags(): Set<String?> = nm.activeNotifications.map { it.tag }.toSet()

    private fun lines(tag: String): List<Pair<String, Boolean>> {
        val n = nm.activeNotifications.single { it.tag == tag }.notification
        return n.extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)!!.map {
            it.getCharSequence("text").toString() to
                (it.getParcelable("sender_person", android.app.Person::class.java) == null)
        }
    }

    private fun headlessKinds(): List<String> = asked

    private val asked = ArrayList<String>()

    private fun onHeadless(
        kind: String,
        answer: (String) -> String,
    ) = rust.onHeadless(kind) {
        asked += kind
        answer(it)
    }

    @Test fun attachedAnSmsReplyGoesThroughTheRepository() {
        PlatformEvents.uiAttached = true
        onHeadless(ReplyActions.KIND_WA_REPLY) { """{"ok":true}""" }

        reply(sms, "  On my way  ")

        val sent = container.fakeSms.sent.single()
        assertEquals(listOf("+905550000001"), sent.to)
        assertEquals("On my way", sent.body)
        assertEquals(3, sent.subId)
        assertTrue("Rust is not asked while the UI is attached", headlessKinds().isEmpty())
        // Answered: the conversation's notification goes, the other one stays.
        assertEquals(setOf(wa, MessagesNotifier.SUMMARY_TAG), tags())
    }

    @Test fun attachedAWhatsAppReplyGoesIntoTheOutbox() {
        PlatformEvents.uiAttached = true
        onHeadless(ReplyActions.KIND_WA_REPLY) { """{"ok":true}""" }

        reply(wa, "On my way")

        assertEquals(listOf("905550000002@s.whatsapp.net" to "On my way"), container.queued)
        assertTrue(container.fakeSms.sent.isEmpty())
        assertTrue("the outbox sends it, not headless", headlessKinds().isEmpty())
        assertEquals(setOf(sms, MessagesNotifier.SUMMARY_TAG), tags())
    }

    @Test fun detachedAWhatsAppReplyGoesThroughHeadless() {
        var request: String? = null
        onHeadless(ReplyActions.KIND_WA_REPLY) {
            request = it
            """{"ok":true}"""
        }

        reply(wa, "On my way")

        val asked = JSONObject(request!!)
        assertEquals("905550000002@s.whatsapp.net", asked.getString("chat"))
        assertEquals("On my way", asked.getString("text"))
        assertEquals(context.noBackupFilesDir.absolutePath, asked.getString("dir"))
        assertTrue("no repository and no outbox in a process without UI", container.queued.isEmpty())
        assertTrue(container.fakeSms.sent.isEmpty())
        // Posted again with the reply as my own line, silently, so the reply spinner stops.
        assertEquals(listOf("Are you coming?" to false, "On my way" to true), lines(wa))
        val n = nm.activeNotifications.single { it.tag == wa }.notification
        assertEquals(Notification.GROUP_ALERT_SUMMARY, n.groupAlertBehavior)
    }

    @Test fun detachedWithoutAnAnswerTheNotificationStaysAsItWas() {
        // FakeRustCore answers "" for an unscripted kind: no decision, as when the native library is missing.
        reply(wa, "On my way")

        assertEquals(listOf("Are you coming?" to false), lines(wa))
        assertTrue(container.queued.isEmpty())
    }

    @Test fun attachedARefusedReplyLeavesTheNotification() {
        PlatformEvents.uiAttached = true
        container.waLinked = false

        reply(wa, "On my way")

        assertEquals(listOf("Are you coming?" to false), lines(wa))
    }

    @Test fun anEmptyReplyDoesNothing() {
        PlatformEvents.uiAttached = true
        reply(sms, "   ")
        reply(sms, null)

        assertTrue(container.fakeSms.sent.isEmpty())
        assertEquals(setOf(sms, wa, MessagesNotifier.SUMMARY_TAG), tags())
    }

    @Test fun copyCodePutsTheCodeOnTheClipboardAndDismisses() {
        MessagesNotifier.show(context, spec(sms, otp = "482913"))

        action(ReplyActions.COPY, sms) { putExtra(ReplyActions.EXTRA_CODE, "482913") }

        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("482913", clip.getItemAt(0).text.toString())
        assertEquals(setOf(wa, MessagesNotifier.SUMMARY_TAG), tags())
    }

    @Test fun markAsReadDismissesAndTheSummaryGoesWithTheLastOne() {
        action(ReplyActions.MARK_READ, sms)
        action(ReplyActions.MARK_READ, wa)

        assertTrue(nm.activeNotifications.isEmpty())
    }

    @Test fun remindMeArmsTheReminderRustBuiltAndDismisses() {
        var request: String? = null
        onHeadless(ReplyActions.KIND_REMIND) {
            request = it
            val arm =
                JSONObject()
                    .put("id", 5)
                    .put("tag", wa)
                    .put("message_id", "")
                    .put("at_ms", 9_999_999_999_999L)
                    .put("notif_title", "Ada Lovelace")
                    .put("notif_body", "Are you coming?")
            JSONObject()
                .put("ok", true)
                .put("arm", arm)
                .put("toast", "Reminder set")
                .toString()
        }

        action(ReplyActions.REMIND, wa) {
            putExtra(ReplyActions.EXTRA_TITLE, "Ada Lovelace").putExtra(ReplyActions.EXTRA_BODY, "Are you coming?")
        }

        val asked = JSONObject(request!!)
        assertEquals(wa, asked.getString("tag"))
        assertEquals(60, asked.getInt("minutes"))
        assertEquals(listOf(5L), MessageReminders.armed(context).map { it.id })
        assertEquals(setOf(sms, MessagesNotifier.SUMMARY_TAG), tags())
    }

    @Test fun remindMeWithoutRustLeavesTheNotification() {
        action(ReplyActions.REMIND, wa)

        assertTrue(MessageReminders.armed(context).isEmpty())
        assertEquals(setOf(sms, wa, MessagesNotifier.SUMMARY_TAG), tags())
        assertNull(MessagesNotifier.shown(context, "sms:404"))
    }
}
