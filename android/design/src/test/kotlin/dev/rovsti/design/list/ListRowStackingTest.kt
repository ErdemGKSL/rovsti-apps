package dev.rovsti.design.list

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F35: a trailing action sits under the text at large font sizes or when it would squeeze the title. */
class ListRowStackingTest : ShotTest() {
    private val content: @Composable () -> Unit = {
        Column {
            ListRow(
                title = "Default phone app",
                secondary = "Make Rovsti the app that answers calls, so every call rings here",
                leading = { Spacer(Modifier.size(32.dp)) },
                trailing = { RovstiText("Set as default", Modifier.testTag("action")) },
                secondaryMaxLines = 2,
                modifier = Modifier.testTag("row"),
            )
        }
    }

    @Test
    fun atFontScale2TheActionIsUnderTheTextAndTheTitleGetsMostOfTheRow() {
        composeFrame(fontScale = 2f, content = content)
        val row = compose.onNodeWithTag("row").getUnclippedBoundsInRoot()
        val action = compose.onNodeWithTag("action", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val title =
            compose
                .onNodeWithText(
                    "Make Rovsti the app that answers calls, so every call rings here",
                    useUnmergedTree = true,
                ).getUnclippedBoundsInRoot()
        // The action starts below the middle of the row and is aligned with the text column, not at the end.
        assertTrue("action under the text: $action in $row", action.top > row.top + row.height / 2)
        assertTrue("action is at the text start, not the end: $action", action.left < row.left + row.width / 2)
        assertTrue("text is wide: ${title.width} of ${row.width}", title.width > row.width * 0.6f)
    }

    @Test
    fun atFontScale1TheActionStaysBesideTheTextOnOneLine() {
        composeFrame(content = content)
        val row = compose.onNodeWithTag("row").getUnclippedBoundsInRoot()
        val action = compose.onNodeWithTag("action", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("action at the end: $action in $row", action.left > row.left + row.width / 2)
        assertTrue("one row high: ${row.height}", row.height < 100.dp)
    }

    @Test
    fun atFontScale1ALongValueIsCutShortBesideTheTitleInsteadOfDroppingUnderIt() {
        composeFrame {
            ListRow(
                title = "Save To",
                leading = { Spacer(Modifier.size(32.dp)) },
                trailingShrinks = true,
                trailing = {
                    androidx.compose.foundation.layout.Row {
                        RovstiText(
                            "a.very.long.account.name.for.contacts@example.com",
                            Modifier.weight(1f, fill = false).testTag("value"),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        ListRowChevron()
                    }
                },
                onClick = {},
                modifier = Modifier.testTag("row"),
            )
        }
        val row = compose.onNodeWithTag("row").getUnclippedBoundsInRoot()
        val value = compose.onNodeWithTag("value", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("value beside the title: $value in $row", value.top < row.top + row.height / 2)
        assertTrue("value in the end half: $value in $row", value.left >= row.left + row.width / 2 - 32.dp)
        assertTrue("one row high: ${row.height}", row.height < 100.dp)
    }

    @Test
    fun atFontScale2AChevronStaysAtTheEndOfTheRow() {
        composeFrame(fontScale = 2f) {
            ListRow(
                title = "Deniz Aksoy",
                secondary = "+90 555 333 44 55",
                leading = { Spacer(Modifier.size(32.dp)) },
                trailing = { ListRowChevron(Modifier.testTag("chevron")) },
                onClick = {},
                modifier = Modifier.testTag("row"),
            )
        }
        val row = compose.onNodeWithTag("row").getUnclippedBoundsInRoot()
        val chevron = compose.onNodeWithTag("chevron", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("chevron at the end: $chevron in $row", chevron.left > row.left + row.width * 0.75f)
        assertTrue("chevron centred, not under the text: $chevron in $row", chevron.top < row.top + row.height / 2)
    }

    @Test
    fun theRuleIsPure() {
        assertTrue(RowStacking.shouldStack(fontScale = 1.5f, rowWidth = 1000, trailingWidth = 10))
        assertFalse(RowStacking.shouldStack(fontScale = 1f, rowWidth = 1000, trailingWidth = 400))
        assertTrue(
            "title would get under half",
            RowStacking.shouldStack(fontScale = 1f, rowWidth = 1000, trailingWidth = 600),
        )
        assertEquals(1.5f, RowStacking.STACK_FONT_SCALE)
        assertTrue(RowStacking.isAccessory(widthPx = 40, accessoryMaxPx = 48))
        assertEquals(480, RowStacking.besideMax(rowWidth = 1000, gapPx = 20))
        assertTrue(RowStacking.shrinksInPlace(heightAtHalf = 40, heightAtFull = 40))
        assertFalse(RowStacking.shrinksInPlace(heightAtHalf = 80, heightAtFull = 40))
        assertFalse(RowStacking.isAccessory(widthPx = 200, accessoryMaxPx = 48))
    }

    private val stackingRow: @Composable () -> Unit = {
        StackingTrailingRow(
            text = { RovstiText("Lets Rovsti Phone place and answer calls on this device", Modifier.testTag("text")) },
            trailing = { RovstiText("Set as default", Modifier.testTag("action")) },
            modifier = Modifier.testTag("row"),
            leading = { Spacer(Modifier.size(32.dp)) },
            leadingGap = 12.dp,
            trailingGap = 12.dp,
            stackGap = 2.dp,
        )
    }

    @Test
    fun theSettingsRowLayoutStacksAtFontScale2AndNotAtFontScale1() {
        composeFrame(fontScale = 2f, content = stackingRow)
        val row = compose.onNodeWithTag("row").getUnclippedBoundsInRoot()
        val text = compose.onNodeWithTag("text", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val action = compose.onNodeWithTag("action", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("action under the text: $action vs $text", action.top >= text.bottom)
        assertTrue("text gets most of the row: ${text.width} of ${row.width}", text.width > row.width * 0.6f)

        composeFrame(content = stackingRow)
        val action1 = compose.onNodeWithTag("action", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val text1 = compose.onNodeWithTag("text", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("side by side at 1x: $action1 vs $text1", action1.top < text1.bottom && action1.left >= text1.right)
    }

    @Test
    fun inRtlTheLeadingElementIsAtTheRightAndTheActionAtTheLeft() {
        composeFrame(rtl = true) {
            StackingTrailingRow(
                text = { RovstiText("Default phone app") },
                trailing = { RovstiText("Set as default", Modifier.testTag("action")) },
                modifier = Modifier.testTag("row"),
                leading = { Spacer(Modifier.size(32.dp).testTag("lead")) },
                leadingGap = 12.dp,
                trailingGap = 12.dp,
                stackGap = 2.dp,
            )
        }
        val row = compose.onNodeWithTag("row").getUnclippedBoundsInRoot()
        val lead = compose.onNodeWithTag("lead", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val action = compose.onNodeWithTag("action", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("leading at the right: $lead in $row", lead.left > row.left + row.width / 2)
        assertTrue("action at the left: $action in $row", action.right < row.left + row.width / 2)
    }
}
