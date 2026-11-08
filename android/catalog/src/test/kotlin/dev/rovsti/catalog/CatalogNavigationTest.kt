package dev.rovsti.catalog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import dev.rovsti.testing.ShotTest
import org.junit.Test

class CatalogNavigationTest : ShotTest() {
    @Test
    fun rowOpensPageAndBackReturnsHome() {
        composeFrame { CatalogApp() }
        compose.onNodeWithText("Rovsti catalog").assertIsDisplayed()

        compose.onNodeWithText("Toolchain").performClick()
        advance()
        compose.onNodeWithText("Android API").assertIsDisplayed()

        compose.waitForIdle()
        var enabled = false
        compose.runOnUiThread { enabled = compose.activity.onBackPressedDispatcher.hasEnabledCallbacks() }
        check(enabled) { "BackHandler is not enabled on the Toolchain page" }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        advance()
        compose.onNodeWithText("Rovsti catalog").assertIsDisplayed()
        check(compose.onAllNodes(hasText("Android API")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun initialPageIsShown() {
        composeFrame { CatalogApp(initialPage = CatalogPage.Toolchain) }
        compose.onNodeWithText("Android API").assertIsDisplayed()
    }

    @Test
    fun buttonMenuPageOpens() {
        composeFrame { CatalogApp(initialPage = CatalogPage.ButtonMenu) }
        compose.onNodeWithText("Button menu").assertIsDisplayed()
    }

    @Test
    fun contextMenuPageOpens() {
        composeFrame { CatalogApp(initialPage = CatalogPage.ContextMenu) }
        compose.onNodeWithText("Context menu").assertIsDisplayed()
    }

    /** E1.22 AC1: every entry of [CatalogPage] opens from the home list and Back returns to it. */
    @Test
    fun everyPageOpensFromHomeAndBackReturns() =
        screenTest {
            val failures = mutableListOf<String>()
            for (page in CatalogPage.entries.filter { it != CatalogPage.Home }) {
                try {
                    composeFrame { CatalogApp() }
                    advance()
                    compose.onNodeWithTag("home-pages").assertIsDisplayed()
                    scrollIntoView(hasText(page.title) and hasClickAction())
                    compose.onNode(hasText(page.title) and hasClickAction()).performClick()
                    compose.waitForIdle()
                    // Animations start on the first frame after the tap: let it run before jumping a second ahead.
                    runTransition()
                    val opened = compose.onAllNodesWithTag("page-components-1").fetchSemanticsNodes().size
                    check(opened == 1) { "the page did not open after the tap ($opened page nodes)" }
                    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                    runTransition()
                    compose.onNodeWithTag("home-pages").assertIsDisplayed()
                    check(compose.onAllNodesWithTag("page-components-1").fetchSemanticsNodes().isEmpty()) {
                        "the page is still there after Back"
                    }
                } catch (e: Throwable) {
                    failures += "${page.name}: ${e.message?.lines()?.first()}"
                }
            }
            check(failures.isEmpty()) { failures.joinToString("\n") }
        }

    @Test
    fun overlayStatesPageTriggersEveryOverlay() =
        screenTest {
            composeFrame { CatalogApp(initialPage = CatalogPage.OverlayStates) }
            advance(600)
            for (tag in listOf("states-sheet-medium", "states-alert", "states-toast")) {
                scrollIntoView(hasTestTag(tag))
                compose.onNodeWithTag(tag).performClick()
                advance(800)
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                advance(800)
            }
        }

    /**
     * Brings the node into the upper 60% of the window by dragging the page, so it clears the tab bar. Compose's
     * `performScrollToNode` never settles for a node whose edge touches the window edge, and `performScrollTo` would
     * leave the node under the bar, so this scrolls like a finger. Stops once a drag no longer moves the node.
     */
    private fun ShotTest.scrollIntoView(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        val rootHeight =
            compose
                .onRoot()
                .fetchSemanticsNode()
                .boundsInRoot.height
        repeat(40) {
            // boundsInRoot is clipped to the scroll container (empty when off screen); the position is not.
            val node = compose.onNode(matcher).fetchSemanticsNode()
            val y = node.positionInRoot.y + node.size.height / 2f
            if (y <= rootHeight * 0.6f) return
            compose
                .onNode(
                    androidx.compose.ui.test
                        .hasScrollAction(),
                ).performSemanticsAction(SemanticsActions.ScrollBy) {
                    it(0f, y - rootHeight * 0.4f)
                }
            advance(100)
            advance(600)
        }
    }

    /** Runs a page transition (push or pop) to its end: stepwise, because one long jump skips the start frame. */
    private fun ShotTest.runTransition() {
        repeat(4) {
            advance(300)
            compose.waitForIdle()
        }
    }
}
