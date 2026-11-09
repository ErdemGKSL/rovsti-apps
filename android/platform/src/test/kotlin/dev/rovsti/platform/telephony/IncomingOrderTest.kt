package dev.rovsti.platform.telephony

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.telecom.Call
import dev.rovsti.core.model.phone.WireCallState
import dev.rovsti.platform.PlatformComponents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/** `canUseFullScreenIntent` is a binder call Robolectric does not answer: the tests choose the answer. */
@Implements(NotificationManager::class)
class FullScreenShadow : ShadowNotificationManager() {
    @Implementation
    fun canUseFullScreenIntent(): Boolean = allowed

    companion object {
        @Volatile var allowed = true
    }
}

/**
 * ARCH 8.2 / E3 AC3: a ringing call posts its `CallStyle` notification first. Only then does a collector of
 * [CallRegistry.state] see the call, and nothing starts the app's activity from the service side while the
 * notification can still do it (the full-screen intent is the platform's job).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [FullScreenShadow::class])
class IncomingOrderTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val shadow get() = shadowOf(manager)
    private val callId get() =
        CallRegistry.state.value
            .single()
            .id
    private val timeline = mutableListOf<String>()
    private lateinit var collector: Job

    private val surface =
        object : CallRegistry.Surface {
            override fun onLiveCallChanged(live: Boolean) {
                timeline += "surface.live=$live"
            }

            override fun onIncomingChanged(call: IncomingCall?) {
                timeline += "surface.incoming=${call?.callId}"
            }
        }

    @Before fun setUp() {
        FullScreenShadow.allowed = true
        PlatformComponents.install(
            PlatformComponents(
                mainActivity = TestMainActivity::class.java,
                callAction = TestCallActionReceiver::class.java,
            ),
        )
        CallRegistry.init(app)
        CallRegistry.surface = surface
        CallRegistry.onServiceBound(FakeInCallHost())
        timeline.clear()
        // Unconfined: the collector runs inside the emit, so what it sees is the state of the world at that moment.
        collector =
            CoroutineScope(Dispatchers.Unconfined).launch {
                CallRegistry.state.collect { calls ->
                    if (calls.isNotEmpty()) {
                        timeline +=
                            "state.size=${calls.size} notifications=${shadow.size()} activityStarted=${shadowOf(
                                app,
                            ).peekNextStartedActivity() != null}"
                    }
                }
            }
    }

    @After fun tearDown() {
        collector.cancel()
        CallRegistry.surface = null
        CallRegistry.onServiceUnbound()
    }

    private fun ring(number: String = "+905551112233") = CallRegistry.onCallAdded(fakeCall(number))

    @Test fun theNotificationIsPostedBeforeAnyCollectorSeesTheCall() {
        ring()
        val seen = timeline.single { it.startsWith("state.") }
        assertEquals("state.size=1 notifications=1 activityStarted=false", seen)
        assertEquals(1, shadow.size())
        assertNotNull(shadow.getNotification(CallNotifications.INCOMING_ID))
    }

    @Test fun nothingStartsTheActivityWhileTheNotificationCanDoIt() {
        ring()
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun theSurfaceHearsFirstAndTheCollectorLast() {
        ring()
        val state = timeline.indexOfFirst { it.startsWith("state.") }
        assertTrue(timeline.toString(), timeline.indexOf("surface.live=true") in 0 until state)
        assertTrue(timeline.toString(), timeline.indexOf("surface.incoming=$callId") in 0 until state)
    }

    @Test fun theStateCarriesTheRingingWireCall() {
        ring("+905551112233")
        val call = CallRegistry.state.value.single()
        assertEquals(WireCallState.Ringing, call.state)
        assertEquals("+905551112233", call.numberRaw)
        assertTrue(call.isIncoming)
    }

    @Test fun theNotificationIsACallStyleOnTheIncomingChannel() {
        ring()
        val n = shadow.getNotification(CallNotifications.INCOMING_ID)
        assertEquals(CallNotifications.CHANNEL_INCOMING, n.channelId)
        assertEquals(Notification.CATEGORY_CALL, n.category)
        assertEquals(Notification.CallStyle::class.java.name, n.extras.getString(Notification.EXTRA_TEMPLATE))
    }

    @Test fun theFullScreenIntentTargetsTheMainActivityAndShowsTheCall() {
        ring()
        val n = shadow.getNotification(CallNotifications.INCOMING_ID)
        val intent = shadowOf(requireNotNull(n.fullScreenIntent)).savedIntent
        assertEquals(TestMainActivity::class.java.name, intent.component?.className)
        assertEquals(CallNotifications.ACTION_SHOW_INCOMING, intent.action)
        assertEquals(callId, intent.getIntExtra(CallNotifications.EXTRA_CALL_ID, -1))
    }

    @Test fun theAnswerIntentTargetsTheMainActivityWithTheAnswerExtras() {
        ring()
        val n = shadow.getNotification(CallNotifications.INCOMING_ID)
        val answer = n.extras.getParcelable("android.answerIntent", PendingIntent::class.java)
        val intent: Intent = shadowOf(requireNotNull(answer)).savedIntent
        assertEquals(TestMainActivity::class.java.name, intent.component?.className)
        assertEquals(CallNotifications.ACTION_ANSWER, intent.action)
        assertEquals(callId, intent.getIntExtra(CallNotifications.EXTRA_ANSWER_CALL_ID, -1))
        assertEquals(CallNotifications.answerToken, intent.getLongExtra(CallNotifications.EXTRA_ANSWER_TOKEN, 0L))
    }

    @Test fun theDeclineIntentGoesToTheCallActionReceiver() {
        ring()
        val n = shadow.getNotification(CallNotifications.INCOMING_ID)
        val decline = n.extras.getParcelable("android.declineIntent", PendingIntent::class.java)
        val intent = shadowOf(requireNotNull(decline)).savedIntent
        assertEquals(TestCallActionReceiver::class.java.name, intent.component?.className)
        assertEquals(CallNotifications.ACTION_DECLINE, intent.action)
    }

    @Test fun withoutTheFullScreenPermissionTheHeadsUpStillComesFirst() {
        FullScreenShadow.allowed = false
        ring()
        assertEquals("state.size=1 notifications=1 activityStarted=false", timeline.single { it.startsWith("state.") })
        val n = shadow.getNotification(CallNotifications.INCOMING_ID)
        assertNull(n.fullScreenIntent)
        assertEquals(2, n.actions.size)
    }

    @Test fun anEndedRingTakesTheNotificationDown() {
        val call = fakeCall("+905551112233")
        CallRegistry.onCallAdded(call)
        assertEquals(1, shadow.size())
        CallRegistry.onCallRemoved(call)
        assertEquals(0, shadow.size())
        assertTrue(CallRegistry.state.value.isEmpty())
        assertEquals(Call.STATE_RINGING, call.state)
    }
}
