package dev.rovsti.design.backdrop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import dev.rovsti.design.backdrop.backdrops.NavTransitionFreeze
import dev.rovsti.design.backdrop.backdrops.rememberNavTransitionFreeze
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NavTransitionFreezeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unmarkedFreezeIsThawed() {
        assertFalse(NavTransitionFreeze().frozen())
    }

    @Test
    fun markFreezesForTheTransitionWindowOnly() {
        val freeze = NavTransitionFreeze()
        freeze.markTransitionStarted()
        assertTrue(freeze.frozen())
        Thread.sleep(360)
        assertFalse("330 ms window is over", freeze.frozen())
    }

    @Test
    fun pushFreezesAndPopDoesNot() {
        var route by mutableStateOf("a")
        var pop = false
        var freeze: NavTransitionFreeze? = null
        compose.setContent { freeze = rememberNavTransitionFreeze(route) { pop } }
        compose.waitForIdle()
        assertNotNull(freeze)
        assertTrue("first composition counts as a push", freeze!!.frozen())
        Thread.sleep(360)
        assertFalse(freeze!!.frozen())

        pop = true
        route = "b"
        compose.waitForIdle()
        assertFalse("a pop (predictive back) is gesture driven and must not freeze", freeze!!.frozen())

        pop = false
        route = "c"
        compose.waitForIdle()
        assertTrue("a push freezes", freeze!!.frozen())
    }
}
