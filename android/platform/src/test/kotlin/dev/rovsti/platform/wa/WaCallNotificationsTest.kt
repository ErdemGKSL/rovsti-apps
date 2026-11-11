package dev.rovsti.platform.wa

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.rovsti.platform.PlatformComponents
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * The WhatsApp call notifications: Answer and Decline are broadcasts (they work on the lock screen), the ring starts
 * with the notification and stops with it, and the ongoing notice is a localized `CallStyle` that leads back to the call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaCallNotificationsTest {
    class Main : Activity()

    class Receiver : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent,
        ) = Unit
    }

    private val context: Context = RuntimeEnvironment.getApplication()
    private var before: PlatformComponents? = null

    @Before fun install() {
        before = PlatformComponents.getOrNull()
        PlatformComponents.install(PlatformComponents(Main::class.java, waCallAction = Receiver::class.java))
    }

    @After fun restore() {
        before?.let(PlatformComponents::install)
    }

    @Test fun answerAndDeclineAreBroadcastsToTheAppsReceiver() {
        for (action in listOf(WaIncoming.ANSWER, WaIncoming.DECLINE)) {
            val pending = WaIncoming.actionPending(context, "c1", action)
            val shadow = shadowOf(pending)
            assertTrue("$action must not need an unlock", shadow.isBroadcastIntent)
            assertFalse(shadow.isActivityIntent)
            assertTrue(shadow.flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)
            val intent = shadow.savedIntent
            assertEquals(Receiver::class.java.name, intent.component?.className)
            assertEquals(WaIncoming.ACTION_CALL, intent.action)
            assertEquals("c1", intent.getStringExtra(WaIncoming.EXTRA_CALL_ID))
            assertEquals(action, intent.getStringExtra(WaIncoming.EXTRA_ACTION))
        }
        assertNotEquals(
            "distinct request codes",
            shadowOf(WaIncoming.actionPending(context, "c1", WaIncoming.ANSWER)).requestCode,
            shadowOf(WaIncoming.actionPending(context, "c1", WaIncoming.DECLINE)).requestCode,
        )
    }

    @Test fun withoutAReceiverTheActionsOpenTheActivity() {
        PlatformComponents.install(PlatformComponents(Main::class.java))
        val shadow = shadowOf(WaIncoming.actionPending(context, "c1", WaIncoming.ANSWER))
        assertTrue(shadow.isActivityIntent)
        assertEquals(WaIncoming.ANSWER, shadow.savedIntent.getStringExtra(WaIncoming.EXTRA_ACTION))
    }

    @Test fun theReceiversTakeActionStopsTheRingAndAnswerCanBeTakenOnce() {
        assertTrue(WaIncoming.show(context, "ring-1", "Ada"))
        assertTrue("the ring sounds", WaRinger.silence())
        assertNull("the push and the gateway event ring once", WaRinger.start(context, "ring-1"))
        val intent = shadowOf(WaIncoming.actionPending(context, "ring-1", WaIncoming.ANSWER)).savedIntent
        assertNotNull(WaIncoming.takeAction(context, intent))
        assertFalse("the ring is over", WaRinger.stop("ring-1"))
        assertTrue(WaIncoming.isOver("ring-1"))
        assertNull("a second tap does nothing", WaIncoming.takeAction(context, intent))
    }

    @Test fun aCallEndPushStopsTheRing() {
        assertTrue(WaIncoming.show(context, "ring-2", "Ada"))
        WaPush.onMessage(context, mapOf("kind" to "call_end", "call_id" to "ring-2"))
        assertFalse(WaRinger.stop("ring-2"))
        assertFalse(WaRinger.silence())
    }

    @Test fun answeredOnTheLockScreenTheCallScreenOpensWithTheAnsweredFlag() {
        WaIncoming.openAnswered(context, "ans-1")
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals("ans-1", started?.getStringExtra(WaIncoming.EXTRA_CALL_ID))
        assertTrue(started!!.getBooleanExtra(WaIncoming.EXTRA_ANSWERED, false))
        assertNull("no action: the receiver took it", started.getStringExtra(WaIncoming.EXTRA_ACTION))
        val manager = context.getSystemService(NotificationManager::class.java)
        // Robolectric's keyguard is unlocked and the screen on: no full-screen fallback is needed.
        assertTrue(manager.activeNotifications.none { it.id == WaIncoming.answeringNotificationId("ans-1") })
        assertNotEquals(WaIncoming.notificationId("ans-1"), WaIncoming.answeringNotificationId("ans-1"))
        assertNotEquals(WaIncoming.missedNotificationId("ans-1"), WaIncoming.answeringNotificationId("ans-1"))
    }

    @Test fun lockedTheAnswerAlsoPostsAFullScreenNotice() {
        shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(true)
        try {
            WaIncoming.openAnswered(context, "ans-2")
            val manager = context.getSystemService(NotificationManager::class.java)
            val notice = manager.activeNotifications.single { it.id == WaIncoming.answeringNotificationId("ans-2") }
            assertNotNull(notice.notification.fullScreenIntent)
            assertEquals(
                "Answering…",
                notice.notification.extras
                    .getCharSequence(Notification.EXTRA_TEXT)
                    ?.toString(),
            )
            WaIncoming.cancelAnswering(context, "ans-2")
            assertTrue(manager.activeNotifications.none { it.id == WaIncoming.answeringNotificationId("ans-2") })
        } finally {
            shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(false)
        }
    }

    @Test fun theOngoingNoticeIsACallStyleWithHangUpThatOpensTheCall() {
        val n = WaCallService.notification(context, WaOngoingCall("c7", "Ada", connectedMs = 5_000))
        assertEquals(Notification.CallStyle::class.java.name, n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals("Ongoing WhatsApp call", n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(5_000L, n.`when`)
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        val open = shadowOf(n.contentIntent)
        assertTrue(open.isActivityIntent)
        assertEquals("c7", open.savedIntent.getStringExtra(WaCallService.EXTRA_OPEN_CALL))
        val hangUp =
            shadowOf(n.extras.getParcelable(Notification.EXTRA_HANG_UP_INTENT, android.app.PendingIntent::class.java))
        assertTrue(hangUp.isBroadcastIntent)
        assertEquals(WaCallService.ACTION_HANG_UP, hangUp.savedIntent.action)
        assertEquals("c7", hangUp.savedIntent.getStringExtra(WaIncoming.EXTRA_CALL_ID))
    }

    @Test fun beforeTheAppNamesTheCallTheNoticeIsPlainAndGeneric() {
        val n = WaCallService.notification(context, null)
        assertNull(n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals("WhatsApp call", n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertNull(shadowOf(n.contentIntent).savedIntent.getStringExtra(WaCallService.EXTRA_OPEN_CALL))
    }

    @Test
    @Config(qualifiers = "tr")
    fun aTurkishPhoneReadsTurkish() {
        val n = WaCallService.notification(context, WaOngoingCall("c8", WaIncomingNotice.FALLBACK_TITLE))
        assertEquals("WhatsApp araması", n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("Devam eden WhatsApp araması", n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertTrue(WaIncoming.showMissed(context, WaMissedNotice("m1", WaIncomingNotice.FALLBACK_TITLE)))
        val missed =
            context
                .getSystemService(NotificationManager::class.java)
                .activeNotifications
                .single { it.id == WaIncoming.missedNotificationId("m1") }
                .notification
        assertEquals("Cevapsız WhatsApp araması", missed.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals("WhatsApp araması", missed.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }
}
