package dev.rovsti.design.backdrop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import dev.rovsti.design.backdrop.backdrops.BackdropFreeze
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The clock is `System.nanoTime`, so these tests wait out the real windows (300 ms entry, 900 ms safety). */
class BackdropFreezeTest {
    private fun settled(): BackdropFreeze {
        val freeze = BackdropFreeze()
        assertTrue("a new freeze covers the entry animation", freeze.frozen())
        Thread.sleep(330)
        assertFalse("entry window over, nothing scrolling", freeze.frozen())
        return freeze
    }

    @Test
    fun preScrollFreezesAndPostFlingClears() {
        val freeze = settled()
        freeze.connection.onPreScroll(Offset(0f, -10f), NestedScrollSource.UserInput)
        assertTrue(freeze.frozen())
        runBlocking { freeze.connection.onPostFling(Velocity.Zero, Velocity.Zero) }
        assertFalse(freeze.frozen())
    }

    @Test
    fun aGestureThatNeverFlingsThawsAfterTheSafetyWindow() {
        val freeze = settled()
        freeze.connection.onPreScroll(Offset(0f, -10f), NestedScrollSource.UserInput)
        Thread.sleep(500)
        assertTrue("still inside 900 ms", freeze.frozen())
        Thread.sleep(450)
        assertFalse("safety timeout elapsed", freeze.frozen())
    }
}
