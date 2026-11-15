package dev.rosty.phone.incoming

import android.app.Application
import android.os.ParcelUuid
import android.telecom.Call
import android.telecom.CallEndpoint
import dev.rosty.core.model.phone.AudioRoute
import dev.rosty.phone.CallActionReceiver
import dev.rosty.phone.MainActivity
import dev.rosty.platform.PlatformComponents
import dev.rosty.platform.telephony.CallRegistry
import dev.rosty.platform.telephony.FakeInCallHost
import dev.rosty.platform.telephony.fakeCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.UUID

/**
 * The call screens' snapshot follows the registry on every rebuild, not only when the call list changes: tapping
 * Speaker changes the audio route and nothing in the calls, and the Speaker button must still light up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [PhoneFullScreenShadow::class])
class RegistryCallSourceTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val earpiece = CallEndpoint("Phone", CallEndpoint.TYPE_EARPIECE, ParcelUuid(UUID.randomUUID()))
    private val speaker = CallEndpoint("Speaker", CallEndpoint.TYPE_SPEAKER, ParcelUuid(UUID.randomUUID()))

    @Before fun setUp() {
        PlatformComponents.install(
            PlatformComponents(mainActivity = MainActivity::class.java, callAction = CallActionReceiver::class.java),
        )
        CallRegistry.init(app)
        CallRegistry.onServiceBound(FakeInCallHost())
    }

    @After fun tearDown() {
        scope.cancel()
        CallRegistry.surface = null
        CallRegistry.onServiceUnbound()
    }

    private fun settle() = ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)

    @Test fun theRouteReachesTheScreenWhenOnlyTheEndpointChanges() {
        CallRegistry.onCallAdded(fakeCall("+905551112233", Call.STATE_ACTIVE))
        CallRegistry.onEndpointsChanged(listOf(earpiece, speaker))
        CallRegistry.onEndpointChanged(earpiece)
        settle()
        val source = RegistryCallSource(CallRegistry, CallRegistry.snapshotSeq, scope)
        assertEquals(AudioRoute.Earpiece, source.snapshot.value.audio.route)
        val calls = CallRegistry.state.value

        // Telecom confirms the speaker (what the Speaker tap asks for).
        CallRegistry.onEndpointChanged(speaker)
        settle()
        assertEquals("the calls themselves did not change", calls, CallRegistry.state.value)
        assertEquals("the Speaker button lights up", AudioRoute.Speaker, source.snapshot.value.audio.route)

        CallRegistry.onEndpointChanged(earpiece)
        settle()
        assertEquals(AudioRoute.Earpiece, source.snapshot.value.audio.route)
    }
}
