package dev.rovsti.catalog

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dev.rovsti.design.controls.ChipTagPrefix
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChipRowTest : ShotTest() {
    private val isChip =
        SemanticsMatcher("is a chip") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(ChipTagPrefix) ==
                true
        }

    @Test
    fun twelveChipsShareOneTopAndTheRowScrolls() {
        composeFrame { CatalogApp(initialPage = CatalogPage.Chips) }
        val chips = compose.onAllNodes(isChip).fetchSemanticsNodes()
        assertTrue("only ${chips.size} chips are laid out", chips.size >= 3)
        assertTrue("a lazy row composes only what is near the screen", chips.size < CatalogChips.size)
        val tops = chips.map { it.boundsInRoot.top }.toSet()
        assertEquals("chips sit on one line, tops were $tops", 1, tops.size)
        // The last visible chip is cut by the screen edge instead of wrapping below the others.
        val rootWidth =
            compose
                .onNodeWithTag("chip-row")
                .fetchSemanticsNode()
                .boundsInRoot.right
        assertTrue(chips.maxOf { it.boundsInRoot.right } > rootWidth - 1f)

        compose.onNodeWithTag("chip-row").assert(hasScrollAction())
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("chip-row").performScrollToNode(hasTestTag("chip-spam"))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("chip-spam").assertIsDisplayed()
        val afterScroll = compose.onAllNodes(isChip).fetchSemanticsNodes()
        assertEquals(1, afterScroll.map { it.boundsInRoot.top }.toSet().size)
    }

    @Test
    fun tappingSelectsExactlyOneChip() {
        composeFrame { CatalogApp(initialPage = CatalogPage.Chips) }
        compose.onNodeWithTag("chip-unread").assertIsSelected()
        compose.onNodeWithTag("chip-all").performClick()
        advance(300)
        compose.onNodeWithTag("chip-all").assertIsSelected()
        val selected =
            compose
                .onAllNodes(isChip)
                .fetchSemanticsNodes()
                .count { it.config.getOrNull(SemanticsProperties.Selected) == true }
        assertEquals(1, selected)
        check(SemanticsActions.OnClick in compose.onNodeWithTag("chip-all").fetchSemanticsNode().config)
    }
}
