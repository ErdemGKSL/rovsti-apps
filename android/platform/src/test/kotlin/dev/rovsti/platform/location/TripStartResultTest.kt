package dev.rovsti.platform.location

import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import dev.rovsti.bridge.TripService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A refused foreground start never crashes the caller: it becomes [StartResult.NotAllowed] (ARCH 7.5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TripStartResultTest {
    private val app: Context = RuntimeEnvironment.getApplication()
    private val spec = TripSpec("/data/stops", "Home - Work", "Tracking", 10_000L, true)

    /** A context whose `startForegroundService` throws [failure], like Android does in the background. */
    private fun refusing(failure: RuntimeException) =
        object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? = throw failure
        }

    @Test fun theBackgroundStartExceptionIsNotAllowed() {
        val failure = ForegroundServiceStartNotAllowedException("app is in the background")
        assertEquals(StartResult.NotAllowed, startFailure(failure))
        assertEquals(StartResult.NotAllowed, TripService.startResult(refusing(failure), spec.toJson()))
    }

    @Test fun aSecurityExceptionIsNotAllowed() {
        val failure = SecurityException("no location permission")
        assertEquals(StartResult.NotAllowed, startFailure(failure))
        assertEquals(StartResult.NotAllowed, TripService.startResult(refusing(failure), spec.toJson()))
    }

    @Test fun anyOtherRuntimeFailureIsFailedNotACrash() {
        val failure = IllegalStateException("boom")
        assertEquals(StartResult.Failed, startFailure(failure))
        assertEquals(StartResult.Failed, TripService.startResult(refusing(failure), spec.toJson()))
    }

    @Test fun theRepositoryAnswersTheResultAndNeverThrows() {
        val repo = AndroidTripRepository(refusing(ForegroundServiceStartNotAllowedException("no")))
        assertEquals(StartResult.NotAllowed, repo.start(spec))
        assertEquals(StartResult.NotAllowed, AndroidTripRepository(refusing(SecurityException())).start(spec))
    }

    @Test fun aMalformedSpecIsInvalidAndStartsNothing() {
        assertEquals(StartResult.Invalid, TripService.startResult(app, "not json"))
        assertEquals(StartResult.Invalid, TripService.startResult(app, """{"title":"x"}"""))
        assertFalse(TripService.start(app, "{}"))
    }

    @Test fun anAllowedStartIsStartedAndCarriesTheSpec() {
        assertEquals(StartResult.Started, AndroidTripRepository(app).start(spec))
        val started =
            org.robolectric.Shadows
                .shadowOf(RuntimeEnvironment.getApplication())
                .nextStartedService
        assertEquals(TripService::class.java.name, started.component?.className)
        assertEquals(spec, TripSpec.parse(started.getStringExtra(TripService.EXTRA_SPEC)))
        assertTrue(TripService.start(app, spec.toJson()))
    }
}
