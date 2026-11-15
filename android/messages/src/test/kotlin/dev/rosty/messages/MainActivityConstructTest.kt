package dev.rosty.messages

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The system builds the activity with its no-argument constructor first, so that alone must not touch a context. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityConstructTest {
    @Test
    fun constructingTheActivityNeedsNoContext() {
        MainActivity()
    }
}
