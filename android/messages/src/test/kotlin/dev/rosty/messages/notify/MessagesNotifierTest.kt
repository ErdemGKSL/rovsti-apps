package dev.rosty.messages.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.graphics.Bitmap
import android.os.Bundle
import dev.rosty.messages.TestMessagesApp
import dev.rosty.platform.PlatformEvents
import dev.rosty.platform.notify.MessageNotifications
import dev.rosty.platform.notify.PlatformChannels
import dev.rosty.platform.notify.ReplyActions
import dev.rosty.platform.sms.IncomingNotice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** E6.9 AC1: the `Notification` built from a digest spec (style, people, actions, remote input, channel, tag, group). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestMessagesApp::class)
class MessagesNotifierTest {
    private val context = RuntimeEnvironment.getApplication()
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private val sms = "sms:7"
    private val group = "wa:120363000000000001@g.us"

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
        tag: String = sms,
        title: String = "Ada Lovelace",
        lines: List<String> = listOf("Are you coming?", "We start at six"),
        otp: String? = null,
        silent: Boolean = false,
    ): String =
        JSONObject()
            .put("tag", tag)
            .put("title", title)
            .put("lines", JSONArray(lines))
            .put("otp", otp ?: JSONObject.NULL)
            .put("reply", otp == null)
            .put("silent", silent)
            .toString()

    private fun shown(tag: String): Notification = nm.activeNotifications.single { it.tag == tag }.notification

    private fun messages(n: Notification): List<Bundle> =
        n.extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)!!.toList()

    private fun sender(message: Bundle): Person? = message.getParcelable("sender_person", Person::class.java)

    @Test fun aDigestBecomesAMessagingStyleNotificationOfItsConversation() {
        assertTrue(MessagesNotifier.show(context, spec()))

        val n = shown(sms)
        assertEquals(PlatformChannels.MESSAGES, n.channelId)
        assertEquals(Notification.MessagingStyle::class.java.name, n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(Notification.CATEGORY_MESSAGE, n.category)
        assertEquals(
            listOf("Are you coming?", "We start at six"),
            messages(n).map { it.getCharSequence("text").toString() },
        )
        val person = sender(messages(n).first())!!
        assertEquals("Ada Lovelace", person.name.toString())
        assertEquals(sms, person.key)
        assertNotNull("the person carries the avatar", person.icon)
        assertNotNull(n.getLargeIcon())
        assertFalse(n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION))
    }

    @Test fun theActionsAreReplyMarkAsReadAndRemindMe() {
        MessagesNotifier.show(context, spec())

        val n = shown(sms)
        assertEquals(listOf("Reply", "Mark as read", "Remind me in 1 hour"), n.actions.map { it.title.toString() })
        val reply = n.actions[0]
        assertEquals(MessageNotifications.KEY_REPLY, reply.remoteInputs.single().resultKey)
        assertEquals(Notification.Action.SEMANTIC_ACTION_REPLY, reply.semanticAction)
        val replyIntent = shadowOf(reply.actionIntent)
        assertEquals(MessageActionReceiver::class.java.name, replyIntent.savedIntent.component?.className)
        assertEquals(ReplyActions.REPLY, replyIntent.savedIntent.action)
        assertEquals(sms, replyIntent.savedIntent.getStringExtra(MessageNotifications.EXTRA_TAG))
        assertTrue("the system adds the typed text", replyIntent.flags and PendingIntent.FLAG_MUTABLE != 0)
        assertEquals(MessageNotifications.requestCode(sms, MessageNotifications.SLOT_REPLY), replyIntent.requestCode)
        val remind = shadowOf(n.actions[2].actionIntent).savedIntent
        assertEquals(ReplyActions.REMIND, remind.action)
        assertEquals("Ada Lovelace", remind.getStringExtra(ReplyActions.EXTRA_TITLE))
        assertEquals("We start at six", remind.getStringExtra(ReplyActions.EXTRA_BODY))
        assertTrue(shadowOf(n.actions[1].actionIntent).flags and PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test fun aTapOpensTheConversation() {
        MessagesNotifier.show(context, spec())

        val open = shadowOf(shown(sms).contentIntent)
        assertTrue(open.isActivityIntent)
        assertEquals(sms, open.savedIntent.getStringExtra(MessageNotifications.EXTRA_TAG))
    }

    @Test fun aPasscodeGetsCopyCodeAndNoReply() {
        MessagesNotifier.show(context, spec(lines = listOf("Your code is 482913"), otp = "482913"))

        val n = shown(sms)
        assertEquals(listOf("Copy code", "Mark as read"), n.actions.map { it.title.toString() })
        assertNull(n.actions[0].remoteInputs)
        val copy = shadowOf(n.actions[0].actionIntent).savedIntent
        assertEquals(ReplyActions.COPY, copy.action)
        assertEquals("482913", copy.getStringExtra(ReplyActions.EXTRA_CODE))
    }

    @Test fun aGroupChatKeepsItsSenders() {
        MessagesNotifier.show(
            context,
            spec(tag = group, title = "Book club", lines = listOf("Deniz: Hi all", "Ada: Hello")),
        )

        val n = shown(group)
        assertTrue(n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION))
        assertEquals("Book club", n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE).toString())
        assertEquals(listOf("Hi all", "Hello"), messages(n).map { it.getCharSequence("text").toString() })
        assertEquals(listOf("Deniz", "Ada"), messages(n).map { sender(it)!!.name.toString() })
    }

    @Test fun conversationsShareOneGroupWithASummaryThatNeverAlerts() {
        MessagesNotifier.show(context, spec())
        MessagesNotifier.show(context, spec(tag = "sms:9", title = "Grace Hopper", lines = listOf("Lunch?")))

        assertEquals(MessagesNotifier.GROUP, shown(sms).group)
        assertEquals(Notification.GROUP_ALERT_CHILDREN, shown(sms).groupAlertBehavior)
        val summary = shown(MessagesNotifier.SUMMARY_TAG)
        assertEquals(MessagesNotifier.GROUP, summary.group)
        assertTrue(summary.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals(Notification.GROUP_ALERT_CHILDREN, summary.groupAlertBehavior)
        assertEquals("2 conversations", summary.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val lines = summary.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() }
        assertEquals(listOf("Grace Hopper: Lunch?", "Ada Lovelace: We start at six"), lines)
    }

    @Test fun aSilentUpdateDoesNotAlert() {
        MessagesNotifier.show(context, spec(silent = true))

        val n = shown(sms)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, n.groupAlertBehavior)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test fun theOpenConversationGetsNoNotificationWhileTheUiIsAttached() {
        OpenConversation.enter(sms)
        PlatformEvents.uiAttached = true

        assertFalse(MessagesNotifier.show(context, spec()))
        assertTrue(nm.activeNotifications.isEmpty())
        // Another conversation still notifies, and so does this one once the app is in the background.
        assertTrue(MessagesNotifier.show(context, spec(tag = "sms:9")))
        PlatformEvents.uiAttached = false
        assertTrue(MessagesNotifier.show(context, spec()))
    }

    @Test fun theSwitchesAndTheMutesAreHonoured() {
        NotifyPolicy.current = NotifyPolicy(enabled = false)
        assertFalse(MessagesNotifier.show(context, spec()))
        NotifyPolicy.current = NotifyPolicy(muted = setOf(sms))
        assertFalse(MessagesNotifier.show(context, spec()))
        // A timed mute that has run out no longer silences.
        NotifyPolicy.current = NotifyPolicy(muted = setOf(sms), muteUntil = mapOf(sms to 1L))
        assertTrue(MessagesNotifier.show(context, spec()))
    }

    @Test fun withCopyCodeSwitchedOffAPasscodeHasNeitherChipNorReply() {
        NotifyPolicy.current = NotifyPolicy(otpCopy = false)
        MessagesNotifier.show(context, spec(lines = listOf("Your code is 482913"), otp = "482913"))

        assertEquals(listOf("Mark as read", "Remind me in 1 hour"), shown(sms).actions.map { it.title.toString() })
    }

    @Test fun dismissingTheLastConversationTakesTheSummaryAway() {
        val cleared = ArrayList<String>()
        MessagesNotifier.onDismissed = { cleared += it }
        MessagesNotifier.show(context, spec())
        MessagesNotifier.show(context, spec(tag = "sms:9"))

        MessagesNotifier.dismiss(context, sms)
        assertEquals(setOf("sms:9", MessagesNotifier.SUMMARY_TAG), nm.activeNotifications.map { it.tag }.toSet())
        MessagesNotifier.dismiss(context, "sms:9")
        assertTrue(nm.activeNotifications.isEmpty())
        assertEquals(listOf(sms, "sms:9"), cleared)
    }

    @Test fun withoutRustTheReceiversPlainSpecStillNotifiesWithAReply() {
        // What SmsDeliverReceiver posts when `sms_classify` gave no answer: no passcode chip, no spam filter.
        assertTrue(MessagesNotifier.show(context, IncomingNotice.spec(7, "+90 555 000 00 01", listOf("Hello"), null)))

        val n = shown(sms)
        assertEquals(listOf("Hello"), messages(n).map { it.getCharSequence("text").toString() })
        assertEquals("+90 555 000 00 01", sender(messages(n).single())!!.name.toString())
        assertNotNull(n.actions[0].remoteInputs)
    }

    @Test fun anUnusableSpecPostsNothing() {
        assertFalse(MessagesNotifier.show(context, "not json"))
        assertFalse(MessagesNotifier.show(context, spec(lines = emptyList())))
        assertTrue(nm.activeNotifications.isEmpty())
    }

    @Test fun theDigestOfAPostedNotificationCanBeReadBack() {
        MessagesNotifier.show(context, spec())

        val digest = MessagesNotifier.shown(context, sms)!!
        assertEquals(listOf("Are you coming?", "We start at six"), digest.spec.lines)
        assertEquals(Digest.parse(digest.copy(sent = listOf("On my way")).toJson())!!.sent, listOf("On my way"))
    }
}
