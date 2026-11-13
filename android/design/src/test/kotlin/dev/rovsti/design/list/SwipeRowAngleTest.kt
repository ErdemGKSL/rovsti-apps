package dev.rovsti.design.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.rovsti.design.theme.RovstiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A slanted scroll over a [SwipeRow] scrolls the list; only a near-horizontal drag opens the row's actions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SwipeRowAngleTest {
    @get:Rule
    val compose = createComposeRule()

    private val group = SwipeRowGroup()
    private var deleted = 0

    private fun show() {
        compose.setContent {
            RovstiTheme {
                SwipeRowGroup(group) {
                    LazyColumn(Modifier.testTag("list")) {
                        items(40) { i ->
                            SwipeRow(
                                leading = persistentListOf(SwipeAction("Message", Color.Blue) {}),
                                trailing = persistentListOf(SwipeAction("Delete", Color.Red) { deleted++ }),
                                rowKey = i,
                                modifier = Modifier.testTag("row-$i"),
                            ) {
                                Box(Modifier.fillMaxWidth().height(64.dp)) { BasicText("Row $i") }
                            }
                        }
                    }
                }
            }
        }
    }

    /** The row is open (or being dragged) in its group. */
    private fun deleteShown() = group.openKey == 3

    @Test
    fun aSlantedScrollDoesNotOpenDelete() {
        show()
        compose.onNodeWithTag("row-3").performTouchInput {
            down(center)
            // About 40° from vertical: sideways travel passes the slop, but the drag is mostly upwards.
            repeat(10) { moveBy(Offset(-width * 0.02f, -height * 0.3f), 16) }
            up()
        }
        compose.waitForIdle()
        assertFalse("a slanted scroll opened Delete", deleteShown())
    }

    @Test
    fun aFlatSwipeOpensDelete() {
        show()
        compose.onNodeWithTag("row-3").performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(-width * 0.06f, height * 0.01f), 60) }
            up()
        }
        compose.waitForIdle()
        assertTrue("a flat swipe did not open Delete", deleteShown())
    }

    @Test
    fun aLongLeftSwipeOnARowOpenOnTheRightOnlyClosesIt() {
        show()
        compose.onNodeWithTag("row-3").performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(width * 0.06f, 0f), 60) }
            up()
        }
        compose.waitForIdle()
        assertTrue("the right swipe did not open Message", deleteShown())
        compose.onNodeWithTag("row-3").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(-width * 0.08f, 0f), 30) }
            up()
        }
        compose.waitForIdle()
        assertFalse("the row did not come back to rest", deleteShown())
        assertEquals("a left swipe from an open row deleted it", 0, deleted)
    }
}
