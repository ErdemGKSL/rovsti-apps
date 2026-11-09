package dev.rovsti.platform.location

import android.Manifest
import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocationRepositoryTest {
    private val app = RuntimeEnvironment.getApplication()
    private val lm = app.getSystemService(LocationManager::class.java)
    private val repo = AndroidLocationRepository(app)

    private fun fix(
        lat: Double,
        time: Long,
    ) = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = lat
        longitude = 29.0
        accuracy = 12f
        this.time = time
    }

    @Test fun noPermissionMeansNoFixAndAnEmptyStream() =
        runTest {
            assertNull(repo.lastKnown())
            assertNull(repo.fresh(1.seconds))
            assertEquals(emptyList<Fix>(), repo.updates().toList())
        }

    @Test fun lastKnownIsTheNewestFixOverTheProviders() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(lm).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix(41.0, 1_000))
        shadowOf(lm).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(42.0, 2_000))
        val last = checkNotNull(repo.lastKnown())
        assertEquals(Fix(42.0, 29.0, 12.0, 2_000), last)
        assertEquals("""{"lat":42,"lon":29,"accuracy_m":12,"time_ms":2000}""", last.toJson())
    }

    @Test fun liveFixesFlowWhileCollectedAndTheListenerIsRemovedAfter() =
        runTest(UnconfinedTestDispatcher()) {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            val seen = mutableListOf<Fix>()
            val job = launch { repo.updates().collect { seen += it } }
            assertTrue(shadowOf(lm).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isNotEmpty())
            shadowOf(lm).simulateLocation(fix(41.5, 3_000))
            org.robolectric.shadows.ShadowLooper
                .idleMainLooper()
            assertEquals(listOf(Fix(41.5, 29.0, 12.0, 3_000)), seen)
            job.cancel()
            job.join()
            assertTrue(shadowOf(lm).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
        }

    @Test fun aFreshFixTimesOutWithNullWhenTheProviderStaysSilent() =
        runTest {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            val result = async { repo.fresh(2.seconds) }
            advanceTimeBy(3.seconds)
            assertNull(result.await())
        }
}
