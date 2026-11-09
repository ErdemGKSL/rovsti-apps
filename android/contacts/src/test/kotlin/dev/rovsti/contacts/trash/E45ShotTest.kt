package dev.rovsti.contacts.trash

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.contacts.editor.EditorRig
import dev.rovsti.contacts.editor.EditorSession
import dev.rovsti.contacts.editor.EditorTags
import dev.rovsti.contacts.list.HostedList
import dev.rovsti.contacts.list.ListHarness
import dev.rovsti.contacts.list.SampleBook
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.testing.ShotTest
import org.junit.After
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.5 screenshots: the delete question, the undo pill over the list end, the Trash page full and empty. */
@Config(sdk = [35])
class E45ShotTest : ShotTest() {
    private var session: EditorSession? = null

    @After fun stop() {
        session?.rig?.stop()
    }

    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    @Test fun deleteConfirm() {
        for (dark in listOf(false, true)) {
            val s = EditorSession(this, EditorRig(id = 1)).also { session = it }
            s.open(dark = dark)
            s.scrollTo(EditorTags.Delete)
            compose
                .onNodeWithTag(
                    EditorTags.Delete,
                ).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            advance(200)
            s.until(
                "the question",
            ) { compose.onAllNodesWithText("Delete this contact?").fetchSemanticsNodes().isNotEmpty() }
            advance(800)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/e4.5-delete-confirm-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
            s.rig.stop()
        }
    }

    @Test fun undoPill() {
        for (dark in listOf(false, true)) {
            val h = ListHarness(SampleBook.state())
            composeFrame(dark = dark) { HostedList(h) }
            advance(300)
            compose.runOnUiThread { h.overlays.showUndo("Deleted Ayşe Yılmaz", onUndo = {}) }
            advance(900)
            compose.runOnUiThread {
                kotlinx.coroutines.runBlocking {
                    h.listState.scrollToItem(h.listState.layoutInfo.totalItemsCount - 1)
                }
            }
            advance(600)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/e4.5-undo-pill-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun trashList() = trash("e4.5-trash-list", SampleTrash)

    @Test fun trashEmpty() = trash("e4.5-trash-empty", TrashUiState(phase = TrashPhase.Ready))

    private fun trash(
        name: String,
        state: TrashUiState,
    ) {
        for (dark in listOf(false, true)) {
            val h = TrashHarness(state)
            composeFrame(dark = dark) { HostedTrash(h) }
            advance(1200)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }
}
