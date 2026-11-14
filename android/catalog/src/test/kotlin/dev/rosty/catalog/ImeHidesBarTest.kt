package dev.rosty.catalog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import dev.rosty.testing.ShotTest
import org.junit.Test

class ImeHidesBarTest : ShotTest() {
    @Test
    fun barIsGoneWhileTheKeyboardIsUp() {
        composeFrame { TabBarPage(imeVisible = { true }) }
        compose.onNodeWithTag("tab-bar").assertDoesNotExist()
    }

    @Test
    fun barIsShownOtherwise() {
        composeFrame { TabBarPage(imeVisible = { false }) }
        compose.onNodeWithTag("tab-bar").assertIsDisplayed()
    }
}
