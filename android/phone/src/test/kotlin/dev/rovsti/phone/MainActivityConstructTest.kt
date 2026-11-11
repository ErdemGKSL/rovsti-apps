package dev.rovsti.phone

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Crash on open: a field of [MainActivity] read the keyguard while the activity was being constructed, before it had
 * a context, and `getSystemService` threw ("Unable to instantiate activity"). The system builds the activity with its
 * no-argument constructor first, so that alone must not touch a context.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityConstructTest {
    @Test
    fun constructingTheActivityNeedsNoContext() {
        MainActivity()
    }
}
