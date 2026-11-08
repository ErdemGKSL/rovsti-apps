package dev.rovsti.catalog

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabBarSemanticsTest : ShotTest() {
    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    @Test
    fun everyCellIsATabAndExactlyOneIsSelected() {
        composeFrame { TabBarPage(imeVisible = { false }) }
        assertEquals(4, compose.onAllNodes(isTab).fetchSemanticsNodes().size)
        compose.onNodeWithTag("tab-keypad").assertIsSelected()
        compose.onNodeWithTag("tab-messages").assertIsNotSelected()
        compose.onNodeWithTag("tab-contacts").assertIsNotSelected()
        compose.onNodeWithTag("tab-stops").assertIsNotSelected()
        assertEquals(1, compose.onAllNodes(hasContentDescription("Keypad")).fetchSemanticsNodes().size)
    }

    @Test
    fun theSemanticClickSelectsTheTab() {
        composeFrame { TabBarPage(imeVisible = { false }) }
        compose.onNodeWithTag("tab-stops").performSemanticsAction(SemanticsActions.OnClick)
        advance(800)
        compose.onNodeWithTag("tab-stops").assertIsSelected()
        compose.onNodeWithTag("tab-keypad").assertIsNotSelected()
    }

    @Test
    fun labelsAreDrawnAtNormalScaleAndDroppedAtFontScaleTwo() {
        composeFrame { TabBarPage(imeVisible = { false }) }
        assertTrue(compose.onAllNodes(hasText("Messages"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        composeFrame(fontScale = 2f) { TabBarPage(imeVisible = { false }) }
        assertTrue(compose.onAllNodes(hasText("Messages"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        // Icons only, but every tab still has its name.
        assertEquals(1, compose.onAllNodes(hasContentDescription("Messages")).fetchSemanticsNodes().size)
    }
}
