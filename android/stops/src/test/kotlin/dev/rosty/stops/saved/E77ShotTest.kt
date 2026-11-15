package dev.rosty.stops.saved

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.stops.SavedStops
import dev.rosty.stops.AppFixture
import dev.rosty.stops.SampleStops
import dev.rosty.stops.home.HomeSheet
import dev.rosty.testing.ShotTest
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * E7.7 screenshots: the Saved sheet over the map on the real core (list texts, distances, usage), its search and
 * sort, the row menu, the reorder mode, the note field (S44)
 * and the end of a long list above the bottom edge. Light and dark.
 */
@Config(sdk = [35])
class E77ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = ShotTest.CHANGE_THRESHOLD))

    private fun savedShot(
        name: String,
        saved: SavedStops = SampleStops.saved,
        large: Boolean = false,
        then: () -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            // The view models belong to the activity: the second pass starts from a fresh list, as a new launch.
            compose.runOnUiThread { compose.activity.viewModelStore.clear() }
            composeFrame(dark = dark) { App(saved) }
            settleRust()
            compose.runOnUiThread { fixture!!.container.pendingSheet.value = HomeSheet.Saved }
            settleRust()
            if (large) {
                compose.onAllNodesWithTag("sheet-grabber").onLast().performSemanticsAction(SemanticsActions.OnClick)
                settleRust()
            }
            then()
            compose.onRoot().captureRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png", options)
        }
    }

    private var fixture: AppFixture? = null

    @Composable
    private fun App(saved: SavedStops) {
        val made = remember { AppFixture(SavedFixtures.core(saved)) }
        fixture = made
        made.Content()
    }

    @Test fun savedSheet() = savedShot("e7.7-saved-sheet")

    @Test fun searchSort() =
        savedShot("e7.7-saved-search-sort", SavedFixtures.many(8), large = true) {
            compose.onNodeWithTag(SavedTags.Search).performTextInput("a")
            settleRust()
            compose.onNodeWithTag(SavedTags.Sort, useUnmergedTree = true).performClick()
            settleRust(800)
        }

    @Test fun rowMenu() =
        savedShot("e7.7-saved-row-menu") {
            compose.onNodeWithTag(SavedTags.rowMore(2), useUnmergedTree = true).performClick()
            settleRust(800)
        }

    @Test fun reorder() =
        savedShot("e7.7-saved-reorder", SavedFixtures.many(5)) {
            compose.onNodeWithTag(SavedTags.Edit, useUnmergedTree = true).performClick()
            settleRust(800)
        }

    /** S44: the note field of a stop, a note being typed. */
    @Test fun editNoteField() =
        savedShot("e7.7-saved-edit-note-field") {
            compose.onNodeWithTag(SavedTags.row(2), useUnmergedTree = true).performClick()
            settleRust()
            compose
                .onNodeWithTag(SavedTags.stopRow("note"), useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            settleRust()
            compose
                .onNodeWithTag(
                    SavedTags.NoteField,
                ).performTextInput("Side gate, by the bakery. Wait under the awning.")
            settleRust(600)
        }

    @Test fun endAboveBar() =
        savedShot("e7.7-saved-end-above-bar", SavedFixtures.many(12), large = true) {
            compose.onNodeWithTag(SavedTags.List).performScrollToIndex(11)
            settleRust()
        }

    @Test fun stopSheet() =
        savedShot("e7.7-saved-stop-sheet") {
            compose.onNodeWithTag(SavedTags.row(1), useUnmergedTree = true).performClick()
            settleRust()
        }
}
