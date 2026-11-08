package dev.rovsti.catalog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
