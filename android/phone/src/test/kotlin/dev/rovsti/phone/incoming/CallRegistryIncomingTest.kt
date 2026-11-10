package dev.rovsti.phone.incoming

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import dev.rovsti.phone.CallActionReceiver
import dev.rovsti.phone.MainActivity
import dev.rovsti.phone.PhoneRoute
import dev.rovsti.phone.RecentsTab
import dev.rovsti.phone.phoneLinkFor
import dev.rovsti.platform.PlatformComponents
import dev.rovsti.platform.telephony.AnswerOnce
import dev.rovsti.platform.telephony.CallNotifications
import dev.rovsti.platform.telephony.CallRegistry
import dev.rovsti.platform.telephony.FakeInCallHost
import dev.rovsti.platform.telephony.fakeCall
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/** `canUseFullScreenIntent` is a binder call Robolectric does not answer. */
@Implements(NotificationManager::class)
class PhoneFullScreenShadow : ShadowNotificationManager() {
    @Implementation
    fun canUseFullScreenIntent(): Boolean = true
}

/**
 * E5 AC1 with the app's own components: a ringing call posts its notification before any UI exists, the notification's
 * Answer and Decline reach `CallRegistry` through the app's link and receiver with no activity running, and Answer is
 * honoured exactly once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [PhoneFullScreenShadow::class])
class CallRegistryIncomingTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val shadow get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val callId get() =
        CallRegistry.state.value
            .single()
            .id

    @Before fun setUp() {
        PlatformComponents.install(
            PlatformComponents(mainActivity = MainActivity::class.java, callAction = CallActionReceiver::class.java),
        )
        CallRegistry.init(app)
        CallRegistry.onServiceBound(FakeInCallHost())
    }

    @After fun tearDown() {
        CallRegistry.surface = null
        CallRegistry.onServiceUnbound()
    }

    private fun ring() = CallRegistry.onCallAdded(fakeCall("+905551112233"))

    private fun answerIntent(): Intent {
        val n = shadow.getNotification(CallNotifications.INCOMING_ID)
        val pending = n.extras.getParcelable("android.answerIntent", PendingIntent::class.java)
        return shadowOf(requireNotNull(pending)).savedIntent
    }

    @Test fun theNotificationIsUpBeforeAnyActivityExists() {
        ring()
        assertNotNull(shadow.getNotification(CallNotifications.INCOMING_ID))
        assertNull("no UI was started for the ring", shadowOf(app).nextStartedActivity)
        assertNull("the activity was never created", CallRegistry.surface)
    }

    @Test fun theCallStyleNotificationCarriesAnswerAndDeclineForTheApp() {
        ring()
        val n = shadow.getNotification(CallNotifications.INCOMING_ID)
        assertEquals(Notification.CallStyle::class.java.name, n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(MainActivity::class.java.name, answerIntent().component?.className)
        val decline =
            shadowOf(requireNotNull(n.extras.getParcelable("android.declineIntent", PendingIntent::class.java)))
        assertEquals(CallActionReceiver::class.java.name, decline.savedIntent.component?.className)
    }

    @Test fun theNotificationsAnswerOpensIncomingAndAnswersWithTheTokenOnlyOnce() {
        ring()
        val intent = answerIntent()
        val link = phoneLinkFor(intent.action, intent.dataString, intent.extras, liveCall = true)!!
        assertEquals(RecentsTab, link.tab)
        assertEquals(listOf<PhoneRoute>(PhoneRoute.Incoming(callId)), link.routes)
        assertEquals(callId, link.answerCallId)
        assertEquals(
            "the token proves the intent came from our notification",
            CallNotifications.answerToken,
            link.answerToken,
        )

        val once = AnswerOnce()
        assertTrue(once.accept(callId, CallRegistry.isRinging(callId)))
        assertTrue("no activity: the registry takes the command", CallRegistry.answerRinging(callId))
        assertFalse("a redelivered intent answers nothing", once.accept(callId, CallRegistry.isRinging(callId)))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun aStaleAnswerForAnotherCallIsRefused() {
        ring()
        assertFalse(CallRegistry.answerRinging(callId + 1))
        assertFalse(AnswerOnce().accept(callId + 1, CallRegistry.isRinging(callId + 1)))
    }

    @Test fun theNotificationsDeclineWorksWithNoActivityAndNoRust() {
        ring()
        val n = shadow.getNotification(CallNotifications.INCOMING_ID)
        val decline =
            shadowOf(requireNotNull(n.extras.getParcelable("android.declineIntent", PendingIntent::class.java)))
        CallActionReceiver().onReceive(app, decline.savedIntent)
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals("the ring is still the registry's until Telecom removes it", 1, CallRegistry.state.value.size)
    }

    @Test fun aNotificationMessageTapOpensIncomingOnTheReplySheet() {
        val intent =
            Intent(CallNotifications.ACTION_SHOW_INCOMING).putExtra(CallNotifications.EXTRA_REPLY_CALL_ID, 7)
        val link = phoneLinkFor(intent.action, null, intent.extras)!!
        assertEquals(7, link.replyCallId)
        assertEquals(listOf<PhoneRoute>(PhoneRoute.Incoming(7)), link.routes)
    }
}
