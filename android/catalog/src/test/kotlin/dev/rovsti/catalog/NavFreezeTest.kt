package dev.rovsti.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.rovsti.design.glass.LocalAppBackdropFrozen
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.transition.ChromeSpec
import dev.rovsti.design.transition.RovstiNavHost
import dev.rovsti.design.transition.Tab
import dev.rovsti.design.transition.TabBarMode
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * `RovstiNavHost` gives the backdrop the route and `lastNavWasPop`: the chrome glass is frozen for a push (the whole
 * screen changes, re-recording it every frame is what janks) and not for a pop (predictive back is gesture driven).
 */
class NavFreezeTest : ShotTest() {
    private val a = Tab("a")
    private lateinit var navigator: TabNavigator<String>
    private var frozen: () -> Boolean = { false }

    @Composable
    private fun Host() {
        val nav = remember { TabNavigator(listOf(a), mapOf(a to "root")).also { navigator = it } }
        val overlays = remember { OverlayController() }
        RovstiNavHost(
            navigator = nav,
            tabs = persistentListOf(),
            chromeFor = { ChromeSpec(TabBarMode.Hidden) },
            overlays = overlays,
            chrome = { frozen = LocalAppBackdropFrozen.current },
        ) {
            entry<String> { Box(Modifier.fillMaxSize()) }
        }
    }

    private fun letTheWindowPass() {
        Thread.sleep(450)
        ShadowSystemClock.advanceBy(Duration.ofMillis(450))
        advance(60)
    }

    @Test
    fun theBackdropIsFrozenDuringAPushAndThawsAfterwards() {
        composeFrame { Host() }
        letTheWindowPass()
        assertFalse("settled before the push", frozen())

        compose.runOnUiThread { navigator.push("detail") }
        advance(60)
        assertTrue("frozen while the page slides in", frozen())
        advance(100)
        assertTrue("still frozen mid-push", frozen())

        letTheWindowPass()
        assertFalse("thawed after the freeze window", frozen())
    }

    @Test
    fun aPopDoesNotFreezeTheBackdrop() {
        composeFrame { Host() }
        compose.runOnUiThread { navigator.push("detail") }
        advance(100)
        letTheWindowPass()
        compose.runOnUiThread { navigator.pop() }
        advance(60)
        assertFalse("a pop (predictive back) must keep the glass live", frozen())
        advance(100)
        assertFalse(frozen())
    }

    @Test
    fun aTabSwitchFreezesTheBackdrop() {
        val b = Tab("b")
        lateinit var nav: TabNavigator<String>
        composeFrame {
            nav = remember { TabNavigator(listOf(a, b), mapOf(a to "root-a", b to "root-b")) }
            val overlays = remember { OverlayController() }
            RovstiNavHost(
                navigator = nav,
                tabs = persistentListOf(),
                chromeFor = { ChromeSpec(TabBarMode.Hidden) },
                overlays = overlays,
                chrome = { frozen = LocalAppBackdropFrozen.current },
            ) {
                entry<String> { Box(Modifier.fillMaxSize()) }
            }
        }
        letTheWindowPass()
        assertFalse(frozen())
        compose.runOnUiThread { nav.select(b) }
        advance(60)
        assertTrue(frozen())
    }
}
