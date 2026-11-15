package dev.rosty.stops.pick

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.stops.AppFixture
import dev.rosty.stops.StopsLink
import dev.rosty.stops.home.HomeTags
import dev.rosty.stops.saved.settleRust
import dev.rosty.testing.ShotTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** E7.8 screenshots on the real core: the pick sheet, the stop search, the stop sheet, the Confirm sheet, the chip. */
@Config(sdk = [35])
class E78ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = ShotTest.CHANGE_THRESHOLD))

    private fun pickShot(
        name: String,
        feed: Boolean = false,
        fontScale: Float = 1f,
        then: (AppFixture) -> Unit,
    ) {
        for (dark in listOf(false, true)) {
            compose.runOnUiThread { compose.activity.viewModelStore.clear() }
            val fixture = PickFixtures.app(feed)
            composeFrame(dark = dark, fontScale = fontScale) { fixture.Content() }
            settleRust()
            then(fixture)
            compose.onRoot().captureRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png", options)
        }
    }

    private fun openPick(large: Boolean = true) {
        compose.onNodeWithTag(HomeTags.Search, useUnmergedTree = true).performClick()
        settleRust()
        if (large) {
            compose.onAllNodesWithTag("sheet-grabber").onLast().performSemanticsAction(SemanticsActions.OnClick)
            settleRust()
        }
    }

    @Test fun pasteField() =
        pickShot("e7.8-pick-sheet-paste-field") {
            openPick()
            compose.onNodeWithTag(PickTags.Field).performTextInput("41°00'29.5\"N 28°58'42.2\"E")
            settleRust()
        }

    @Test fun pickSheetTurkish() {
        RuntimeEnvironment.setQualifiers("+tr")
        pickShot("e7.8-pick-sheet-tr") { openPick() }
    }

    @Test fun pickSheetLargeText() = pickShot("e7.8-pick-sheet-fs2", fontScale = 2f) { openPick() }

    @Test fun stopSearch() =
        pickShot("e7.8-stop-search", feed = true) {
            openPick()
            compose.onNodeWithTag(PickTags.Field).performTextInput("Pier")
            settleRust()
        }

    @Test fun stopSheet() =
        pickShot("e7.8-stop-sheet", feed = true) {
            openPick()
            compose.onNodeWithTag(PickTags.Field).performTextInput("Pier East")
            settleRust()
            compose
                .onNodeWithTag(
                    PickTags.row("stop-PE"),
                    useUnmergedTree = true,
                ).performSemanticsAction(SemanticsActions.OnClick)
            settleRust()
        }

    @Test fun confirmSharedLink() =
        pickShot("e7.8-confirm-shared-link") { fixture ->
            compose.runOnUiThread { fixture.container.pendingLink.value = StopsLink.Place(PickFixtures.SHARED) }
            settleRust()
        }

    @Test fun nearestChip() = pickShot("e7.8-nearest-chip") {}
}
