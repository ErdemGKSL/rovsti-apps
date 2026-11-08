package dev.rovsti.catalog

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import dev.rovsti.design.list.GroupedSeparatorTag
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.TextStartWithLeading
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupedSectionTest : ShotTest() {
    @Test
    fun separatorsSitBetweenRowsAndNeverAfterTheLast() {
        composeFrame {
            Column {
                InsetGroupedSection(modifier = Modifier.testTag("three"), separatorInset = TextStartWithLeading) {
                    row { ListRow("One", modifier = Modifier.testTag("r1")) }
                    row { ListRow("Two", modifier = Modifier.testTag("r2")) }
                    row { ListRow("Three", modifier = Modifier.testTag("r3")) }
                }
                InsetGroupedSection(modifier = Modifier.testTag("single")) {
                    row { ListRow("Only") }
                }
            }
        }
        // 3 rows -> 2 separators, 1 row -> none: 2 in total.
        compose.onAllNodesWithTag(GroupedSeparatorTag).assertCountEquals(2)

        val separators = compose.onAllNodesWithTag(GroupedSeparatorTag).fetchSemanticsNodes()
        val lastRow = compose.onNodeWithTag("r3").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "a separator sits below the last row",
            separators.all { it.boundsInRoot.bottom <= lastRow.top + 0.5f },
        )

        // Inset: the line starts at the text start, measured from the card edge.
        val card = compose.onNodeWithTag("three").fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        val expectedInset = TextStartWithLeading.value * density
        for (separator in separators) {
            assertEquals(card.left + 16f * density + expectedInset, separator.boundsInRoot.left, 1f)
            assertEquals(0.33f * density, separator.boundsInRoot.height, 1f) // Convx IosMetrics.SeparatorThickness
        }
    }
}
