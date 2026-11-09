package dev.rovsti.phone

import android.content.Intent
import android.telecom.InCallService
import dev.rovsti.platform.telephony.CallRegistry
import dev.rovsti.platform.telephony.InCallHost
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * E5.1: the `InCallService` is the [InCallHost] of the call registry. A bound service hands itself over, an unbound one
 * takes itself back, and no callback may throw (an exception in the service kills the process and Telecom then gives the
 * call to the system dialer).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InCallServiceTest {
    private lateinit var service: RovstiInCallService

    @Before fun create() {
        CallRegistry.init(RuntimeEnvironment.getApplication())
        service = Robolectric.buildService(RovstiInCallService::class.java).create().get()
    }

    @After fun release() {
        service.onUnbind(Intent(InCallService.SERVICE_INTERFACE))
    }

    @Test fun theServiceIsTheHostTheRegistryDrives() {
        assertTrue(service is InCallHost)
        assertTrue(service is InCallService)
    }

    @Test fun aBoundServiceHandsAnInCallHostToTheRegistryAndAnUnboundOneTakesItBack() {
        // Nothing to mute, route or hold without a service: the registry answers false.
        assertFalse(CallRegistry.mute(true))
        assertNotNull(service.onBind(Intent(InCallService.SERVICE_INTERFACE)))
        assertTrue("bound: the registry accepts a command", CallRegistry.mute(true))
        service.onUnbind(Intent(InCallService.SERVICE_INTERFACE))
        assertFalse("unbound: it is gone again", CallRegistry.mute(true))
    }

    @Test fun noCallbackThrowsWhateverTheRegistryIsDoing() {
        service.onBind(Intent(InCallService.SERVICE_INTERFACE))
        // Callbacks for a call the registry never saw, or in a state it cannot use, are logged and ignored.
        service.onSilenceRinger()
        service.onCanAddCallChanged(true)
        service.onMuteStateChanged(true)
        service.onAvailableCallEndpointsChanged(emptyList())
        service.stopForegroundRemove()
        service.onUnbind(Intent(InCallService.SERVICE_INTERFACE))
        service.onUnbind(Intent(InCallService.SERVICE_INTERFACE))
    }
}
