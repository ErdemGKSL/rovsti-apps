package dev.rosty.design.transition

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import dev.rosty.design.chrome.TabItem
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.theme.RostyTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A [TabSelection] lets the bar show items that are not navigator tabs (Stops: one stack, Trip / Saved / Settings):
 * the item of the top route is selected, and a tap goes to the app instead of `navigator.select`. Without it the bar
 * follows the navigator's tabs as before (`FoldedBarTapTest`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TabSelectionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val only = Tab("trip")
    private val items =
        persistentListOf(
            TabItem("trip", "Trip", RostyIcons.Map),
            TabItem("saved", "Saved", RostyIcons.Star),
            TabItem("settings", "Settings", RostyIcons.Gear),
        )
    private lateinit var navigator: TabNavigator<String>
    private val taps = mutableListOf<String>()

    @Before
    fun manualClock() {
        compose.mainClock.autoAdvance = false
    }

    private fun settle() {
        repeat(60) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
    }

    private fun host() {
        compose.setContent {
            RostyTheme(darkTheme = false) {
                val nav = remember { TabNavigator(listOf(only), mapOf(only to "map")).also { navigator = it } }
                RostyNavHost(
                    navigator = nav,
                    tabs = items,
                    chromeFor = { ChromeSpec(if (it == "map") TabBarMode.Hidden else TabBarMode.Visible) },
                    overlays = remember { OverlayController() },
                    modifier = Modifier.fillMaxSize(),
                    tabSelection =
                        TabSelection(selectedId = {
                            if (it ==
                                "map"
                            ) {
                                "trip"
                            } else {
                                "settings"
                            }
                        }) { taps += it },
                ) {
                    entry<String> { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        settle()
    }

    private fun selected(id: String) =
        compose
            .onAllNodesWithTag("tab-$id", useUnmergedTree = true)
            .onFirst()
            .fetchSemanticsNode()
            .config
            .getOrElse(SemanticsProperties.Selected) { false }

    @Test fun aPushedPageShowsTheFullBarWithItsItemSelected() {
        host()
        compose.runOnUiThread { navigator.push("settings-page") }
        settle()
        assertEquals(true, selected("settings"))
        assertEquals(false, selected("trip"))
    }

    @Test fun aTapGoesToTheAppNotTheNavigator() {
        host()
        compose.runOnUiThread { navigator.push("settings-page") }
        settle()
        compose.onAllNodesWithTag("tab-saved", useUnmergedTree = true).onFirst().performClick()
        settle()
        compose.onAllNodesWithTag("tab-settings", useUnmergedTree = true).onFirst().performClick()
        settle()
        assertEquals(listOf("saved", "settings"), taps)
        assertEquals(only, navigator.currentTab)
        assertEquals(listOf("map", "settings-page"), navigator.stack(only))
    }
}
