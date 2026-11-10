package dev.rovsti.design.transition

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.rovsti.design.chrome.ScreenPage
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.theme.RovstiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** F16: the host gives every pushed page the glass back button; a tab root has none. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PageBackTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val tab = Tab("a")
    private lateinit var navigator: TabNavigator<String>
    private var explicitBackCalls = 0

    @Before
    fun manualClock() {
        compose.mainClock.autoAdvance = false
    }

    private fun settle() {
        repeat(40) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
    }

    private fun host(page: @Composable (route: String) -> Unit) {
        compose.setContent {
            RovstiTheme(darkTheme = false) {
                val nav = remember { TabNavigator(listOf(tab), mapOf(tab to "root")).also { navigator = it } }
                val overlays = remember { OverlayController() }
                RovstiNavHost(
                    navigator = nav,
                    tabs = persistentListOf(),
                    chromeFor = { ChromeSpec(TabBarMode.Hidden) },
                    overlays = overlays,
                ) {
                    entry<String> { route -> page(route) }
                }
            }
        }
        settle()
    }

    private fun push(route: String) {
        compose.runOnUiThread { navigator.push(route) }
        settle()
    }

    private fun backButtons() = compose.onAllNodesWithTagCount("back-button")

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(
            androidx.compose.ui.test
                .hasTestTag(tag),
        ).fetchSemanticsNodes().size

    @Composable
    private fun plain(route: String) = ScreenPage(title = route) { Box(Modifier.fillMaxSize()) }

    @Test
    fun aTabRootHasNoBackButton() {
        host { plain(it) }
        assertEquals(0, backButtons())
    }

    @Test
    fun aPushedPageWithoutItsOwnBackShowsOneAndClickPopsIt() {
        host { plain(it) }
        push("detail")
        assertEquals(
            1,
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("back-button"),
                ).fetchSemanticsNodes()
                .size,
        )
        compose.onNodeWithTag("back-button").performClick()
        settle()
        assertEquals(listOf("root"), navigator.stack(tab))
    }

    @Test
    fun aLongPressGoesToTheRoot() {
        host { plain(it) }
        push("one")
        push("two")
        assertEquals(3, navigator.stack(tab).size)
        compose.onNodeWithTag("back-button").performTouchInput { longClick() }
        settle()
        assertEquals(listOf("root"), navigator.stack(tab))
    }

    @Test
    fun anExplicitOnBackWins() {
        host { route ->
            ScreenPage(title = route, onBack = { explicitBackCalls++ }) { Box(Modifier.fillMaxSize()) }
        }
        push("detail")
        compose.onNodeWithTag("back-button").performClick()
        settle()
        assertEquals(1, explicitBackCalls)
        assertEquals("the page's own action replaces the pop", 2, navigator.stack(tab).size)
    }

    @Test
    fun showBackFalseHidesIt() {
        host { route -> ScreenPage(title = route, showBack = false) { Box(Modifier.fillMaxSize()) } }
        push("detail")
        assertEquals(0, backButtons())
        assertTrue(navigator.stack(tab).size == 2)
    }

    @Test
    fun theHostRejectsAFoldedBarOnAPushedPage() {
        val failure =
            runCatching {
                compose.setContent {
                    RovstiTheme(darkTheme = false) {
                        val nav = remember { TabNavigator(listOf(tab), mapOf(tab to "root")) }
                        val overlays = remember { OverlayController() }
                        RovstiNavHost(
                            navigator = nav.also { navigator = it },
                            tabs = persistentListOf(),
                            chromeFor = { ChromeSpec(if (it == "bad") TabBarMode.Folded else TabBarMode.Hidden) },
                            overlays = overlays,
                        ) {
                            entry<String> { route -> plain(route) }
                        }
                    }
                }
                settle()
                compose.runOnUiThread { navigator.push("bad") }
                settle()
            }
        assertTrue("a pushed page mapped to Folded must fail loudly", failure.isFailure)
    }
}
