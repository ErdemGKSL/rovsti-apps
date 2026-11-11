package dev.rovsti.phone.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import dev.rovsti.phone.CallActionReceiver
import dev.rovsti.phone.MainActivity
import dev.rovsti.platform.PlatformComponents
import dev.rovsti.platform.R
import dev.rovsti.platform.calllog.MissedCalls
import dev.rovsti.platform.notify.PlatformChannels
import dev.rovsti.platform.telephony.MissedCallNotifications
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

/**
 * E5.12 missed-call notifications: one per caller with the missed-call icon and Call back / Message / Remind me, a
 * digest from the second caller on, and Recents (`MissedCalls.markRead`) clears everything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MissedCallNotificationTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Before fun install() {
        PlatformComponents.install(
            PlatformComponents(mainActivity = MainActivity::class.java, callAction = CallActionReceiver::class.java),
        )
    }

    private fun single(number: String): Notification? =
        manager.getNotification(MissedCallNotifications.TAG, MissedCallNotifications.notificationId(number))

    private fun summary(): Notification? =
        manager.getNotification(MissedCallNotifications.TAG, MissedCallNotifications.SUMMARY_ID)

    @Test fun oneMissedCallHasTheIconTheAccentAndThreeActions() {
        MissedCallNotifications.post(context, "+905551112233", 1_000L)
        val n = single("+905551112233")!!
        assertNotNull(n)
        assertEquals(PlatformChannels.MISSED_CALLS, n.channelId)
        assertEquals(Notification.CATEGORY_MISSED_CALL, n.category)
        assertEquals(R.drawable.ic_notif_call_missed, n.smallIcon.resId)
        assertEquals(0xFFFF3B30.toInt(), n.color)
        assertEquals(MissedCallNotifications.GROUP, n.group)
        assertEquals("+905551112233", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(listOf("Call back", "Message", "Remind me"), n.actions.map { it.title.toString() })
        // One caller: no digest yet.
        assertNull(summary())
    }

    @Test fun callBackAndRemindGoThroughTheCallActionReceiverAndMessageOpensTheSmsApp() {
        MissedCallNotifications.post(context, "+905551112233", 1_000L)
        val actions = single("+905551112233")!!.actions
        val back = shadowOf(actions[0].actionIntent)
        assertTrue(back.isBroadcastIntent)
        assertEquals(MissedCallNotifications.ACTION_CALL_BACK, back.savedIntent.action)
        assertEquals(CallActionReceiver::class.java.name, back.savedIntent.component?.className)
        assertEquals("+905551112233", back.savedIntent.getStringExtra(MissedCallNotifications.EXTRA_NUMBER))
        val sms = shadowOf(actions[1].actionIntent)
        assertTrue(sms.isActivityIntent)
        assertEquals(Intent.ACTION_SENDTO, sms.savedIntent.action)
        val remind = shadowOf(actions[2].actionIntent)
        assertTrue(remind.isBroadcastIntent)
        assertEquals(MissedCallNotifications.ACTION_REMIND, remind.savedIntent.action)
        assertEquals(CallActionReceiver::class.java.name, remind.savedIntent.component?.className)
    }

    @Test fun theSecondCallerAddsAGroupedDigest() {
        MissedCallNotifications.post(context, "+905551112233", 1_000L)
        MissedCallNotifications.post(context, "+905559990000", 2_000L)
        val digest = summary()!!
        assertTrue(digest.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals("2 missed calls", digest.extras.getCharSequence(Notification.EXTRA_TITLE_BIG).toString())
        assertEquals(2, digest.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.size)
        assertEquals(R.drawable.ic_notif_call_missed, digest.smallIcon.resId)
        assertNotNull(single("+905551112233"))
        assertNotNull(single("+905559990000"))
    }

    @Test fun theSameCallerTwiceReplacesItsOwnNotification() {
        MissedCallNotifications.post(context, "+905551112233", 1_000L)
        MissedCallNotifications.post(context, "+905551112233", 5_000L)
        assertEquals(1, MissedCallNotifications.unseen(context).size)
        assertNull(summary())
    }

    @Test fun openingRecentsClearsTheNotificationsAndTheDigest() {
        MissedCallNotifications.post(context, "+905551112233", 1_000L)
        MissedCallNotifications.post(context, "+905559990000", 2_000L)
        MissedCalls.markRead(context)
        assertNull(single("+905551112233"))
        assertNull(single("+905559990000"))
        assertNull(summary())
        assertTrue(MissedCallNotifications.unseen(context).isEmpty())
    }

    @Test fun anActionTakesItsNotificationDownAndTheDigestFollows() {
        MissedCallNotifications.post(context, "+905551112233", 1_000L)
        MissedCallNotifications.post(context, "+905559990000", 2_000L)
        MissedCallNotifications.dismiss(context, "+905559990000")
        assertNull(single("+905559990000"))
        assertNotNull(single("+905551112233"))
        assertNull(summary())
    }

    @Test fun aPrivateNumberHasNoActionsAndSaysMissedCall() {
        MissedCallNotifications.post(context, "", 1_000L)
        val n = single("")!!
        assertEquals("Missed call", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(n.actions == null || n.actions.isEmpty())
    }
}
