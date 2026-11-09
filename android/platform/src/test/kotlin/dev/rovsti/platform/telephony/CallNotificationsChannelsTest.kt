package dev.rovsti.platform.telephony

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The channel ids and importances of the GPUI build, pinned: a user's channel settings survive the port. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallNotificationsChannelsTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    @Test fun idsAndImportancesAreTodaysOnes() {
        CallNotifications.ensureChannels(app)
        val table = manager.notificationChannels.associate { it.id to it.importance }
        assertEquals(
            mapOf(
                "ongoing" to NotificationManager.IMPORTANCE_LOW,
                "incoming" to NotificationManager.IMPORTANCE_HIGH,
            ),
            table,
        )
    }

    @Test fun theConstantsAreTheIds() {
        assertEquals("ongoing", CallNotifications.CHANNEL_ONGOING)
        assertEquals("incoming", CallNotifications.CHANNEL_INCOMING)
        assertEquals(1001, CallNotifications.ONGOING_ID)
        assertEquals(1002, CallNotifications.INCOMING_ID)
    }

    @Test fun theIncomingChannelIsSilentBecauseTelecomRings() {
        CallNotifications.ensureChannels(app)
        val incoming = manager.getNotificationChannel(CallNotifications.CHANNEL_INCOMING)
        assertNull(incoming.sound)
        assertFalse(incoming.shouldVibrate())
        assertFalse(incoming.shouldShowLights())
        assertFalse(incoming.canShowBadge())
        assertEquals(Notification.VISIBILITY_PUBLIC, incoming.lockscreenVisibility)
    }

    @Test fun ensuringTwiceKeepsTheUsersChannel() {
        CallNotifications.ensureChannels(app)
        manager.getNotificationChannel(CallNotifications.CHANNEL_ONGOING).name
        CallNotifications.ensureChannels(app)
        assertEquals(2, manager.notificationChannels.size)
    }

    @Test fun theIntentActionsKeepTheirValues() {
        assertEquals("dev.rovsti.phone.END_CALL", CallNotifications.ACTION_END)
        assertEquals("dev.rovsti.phone.DECLINE_CALL", CallNotifications.ACTION_DECLINE)
        assertEquals("dev.rovsti.phone.ANSWER_CALL", CallNotifications.ACTION_ANSWER)
        assertEquals("dev.rovsti.phone.SHOW_INCOMING", CallNotifications.ACTION_SHOW_INCOMING)
        assertEquals("dev.rovsti.phone.MESSAGE_CALL", CallNotifications.ACTION_MESSAGE)
        assertEquals("answer_call_id", CallNotifications.EXTRA_ANSWER_CALL_ID)
        assertEquals("answer_token", CallNotifications.EXTRA_ANSWER_TOKEN)
    }
}
