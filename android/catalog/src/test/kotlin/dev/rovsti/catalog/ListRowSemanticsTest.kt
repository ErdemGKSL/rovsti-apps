package dev.rovsti.catalog

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.rovsti.design.controls.Badge
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.CustomAction
import dev.rovsti.design.list.ListRow
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListRowSemanticsTest : ShotTest() {
    private var deleted = 0
    private var clicked = 0

    private val content: @androidx.compose.runtime.Composable () -> Unit = {
        Column {
            ListRow(
                title = "Ayşe Yılmaz",
                secondary = "See you at seven",
                meta = "10:24",
                leading = { Avatar("Ayşe Yılmaz") },
                trailing = { Badge(2) },
                onClick = { clicked++ },
                actions = persistentListOf(CustomAction("Delete") { deleted++ }),
                modifier = Modifier.testTag("two-line"),
            )
            ListRow(title = "Mehmet Öz", modifier = Modifier.testTag("one-line"))
        }
    }

    @Test
    fun rowIsOneMergedNodeWithAllTextsAndTheCustomAction() {
        composeFrame(content = content)
        val node = compose.onNodeWithTag("two-line").fetchSemanticsNode()
        val texts =
            node.config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .map { it.text }
        assertTrue("texts were $texts", texts.containsAll(listOf("Ayşe Yılmaz", "See you at seven", "10:24", "2")))

        // The title, the secondary line and the meta all resolve to this same node, not to separate ones.
        for (text in listOf("Ayşe Yılmaz", "See you at seven", "10:24")) {
            val ids = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().map { it.id }
            assertEquals("'$text' is on one node", listOf(node.id), ids)
        }

        val actions = node.config[SemanticsActions.CustomActions]
        assertEquals(listOf("Delete"), actions.map { it.label })
        actions.single().action()
        assertEquals(1, deleted)

        compose.onNodeWithTag("two-line").assertHasClickAction().performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun heightIsAtLeastTheMinimumAndGrowsWithFontScale() {
        composeFrame(content = content)
        compose.onNodeWithTag("one-line").assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag("two-line").assertHeightIsAtLeast(64.dp)
        val oneLine = compose.onNodeWithTag("one-line").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        val twoLine = compose.onNodeWithTag("two-line").getUnclippedBoundsInRoot().let { it.bottom - it.top }

        composeFrame(fontScale = 2f, content = content)
        val oneLineBig = compose.onNodeWithTag("one-line").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        val twoLineBig = compose.onNodeWithTag("two-line").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        // A one-line row stays at its 56 dp minimum while its text still fits in it (34 dp + padding at 2x).
        assertTrue("one line never shrinks: $oneLine -> $oneLineBig", oneLineBig >= oneLine)
        assertTrue("two lines grow: $twoLine -> $twoLineBig", twoLineBig > twoLine)
    }
}
