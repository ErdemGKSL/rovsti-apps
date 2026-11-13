package dev.rovsti.design.transition

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import dev.rovsti.design.chrome.TabItem
import dev.rovsti.design.icons.RovstiIcons
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

/**
 * Scrolling down folds the tab bar into the circle of the selected tab. A tap on that circle only unfolds the bar, as on
 * iOS: it must not reach the page as a reselect (no [ScrollToTop], the list stays where the owner left it). Reselecting a
 * tab in the open bar still scrolls to the top.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FoldedBarTapTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val tab = Tab("alpha")
    private val other = Tab("beta")
    private val tabs =
        persistentListOf(
            TabItem(tab.id, "Alpha", RovstiIcons.Star),
            TabItem(other.id, "Beta", RovstiIcons.Clock),
        )
    private lateinit var navigator: TabNavigator<String>
    private lateinit var list: LazyListState
    private val events = mutableListOf<ScrollToTop>()

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
            RovstiTheme(darkTheme = false) {
                val nav =
                    remember {
                        TabNavigator(listOf(tab, other), mapOf(tab to "root", other to "other")).also { navigator = it }
                    }
                val overlays = remember { OverlayController() }
                RovstiNavHost(
                    navigator = nav,
                    tabs = tabs,
                    chromeFor = { ChromeSpec(TabBarMode.Visible) },
                    overlays = overlays,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    entry<String> {
                        val state = rememberLazyListState().also { list = it }
                        LaunchedEffect(nav, state) {
                            nav.scrollToTop.collect {
                                events += it
                                state.scrollToItem(0)
                            }
                        }
                        Box(Modifier.fillMaxSize()) {
                            LazyColumn(Modifier.fillMaxSize().testTag("list"), state = state) {
                                items(200) { BasicText("Row $it", Modifier.height(56.dp)) }
                            }
                        }
                    }
                }
            }
        }
        settle()
    }

    /** The folded circle: the one Button that carries the selected tab's name (the open cells are tabs, not buttons). */
    private val foldedCircle =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and hasContentDescription("Alpha")

    private fun foldedCircles() = compose.onAllNodes(foldedCircle).fetchSemanticsNodes().size

    private fun scrollDown() {
        repeat(3) {
            compose.onNodeWithTag("list").performTouchInput { swipeUp(durationMillis = 200) }
            settle()
        }
    }

    @Test
    fun tappingTheFoldedBarUnfoldsItWithoutScrollingToTheTop() {
        host()
        assertEquals("the open bar has no folded circle", 0, foldedCircles())
        scrollDown()
        val scrolledTo = list.firstVisibleItemIndex
        assertTrue("the list scrolled down ($scrolledTo)", scrolledTo > 0)
        assertEquals("scrolling down folds the bar", 1, foldedCircles())

        compose.onNode(foldedCircle).performClick()
        settle()

        assertEquals("a tap on the folded bar is not a reselect", emptyList<ScrollToTop>(), events)
        assertEquals("the list stays where it was", scrolledTo, list.firstVisibleItemIndex)
        assertEquals("the tap unfolds the bar", 0, foldedCircles())
        assertEquals(listOf("root"), navigator.stack(tab))
    }

    @Test
    fun reselectingTheOpenTabStillScrollsToTheTop() {
        host()
        // Moved without a drag, so the bar stays open.
        compose.runOnUiThread { kotlinx.coroutines.runBlocking { list.scrollToItem(50) } }
        settle()
        assertEquals(50, list.firstVisibleItemIndex)
        assertEquals("the bar is open", 0, foldedCircles())
        compose.onNodeWithTag("tab-${tab.id}").performClick()
        settle()
        assertEquals(listOf(ScrollToTop(tab)), events)
        assertEquals(0, list.firstVisibleItemIndex)
    }
}
