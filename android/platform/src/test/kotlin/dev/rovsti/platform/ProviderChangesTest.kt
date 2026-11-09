package dev.rovsti.platform

import android.net.Uri
import android.provider.CallLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderChangesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val resolver = context.contentResolver
    private val uri: Uri = CallLog.Calls.CONTENT_URI

    @Test fun aNotifyChangeEmitsAndCancelUnregisters() =
        runTest(UnconfinedTestDispatcher()) {
            val hits = mutableListOf<Unit>()
            val job = launch { ProviderChanges.observe(resolver, uri).collect { hits += it } }
            resolver.notifyChange(uri, null)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, hits.size)
            job.cancel()
            resolver.notifyChange(uri, null)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, hits.size)
        }

    @Test fun descendantsAreObserved() =
        runTest(UnconfinedTestDispatcher()) {
            var seen = 0
            val job = launch { ProviderChanges.observe(resolver, uri).collect { seen++ } }
            resolver.notifyChange(Uri.withAppendedPath(uri, "5"), null)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            job.cancel()
            assertEquals(1, seen)
        }
}
