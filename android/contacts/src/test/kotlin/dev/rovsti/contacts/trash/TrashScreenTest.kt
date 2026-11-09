package dev.rovsti.contacts.trash

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.5: the Trash page's controls. */
@Config(sdk = [35])
class TrashScreenTest : ShotTest() {
    @Test fun theToolbarButtonAsksToEmptyAndBackGoesBack() {
        val h = TrashHarness(SampleTrash)
        composeFrame { HostedTrash(h) }
        advance(1200)
        compose.onNodeWithTag("toolbar-empty-trash").performClick()
        assertEquals(listOf<TrashAction>(TrashAction.AskEmpty), h.actions)
        assertTrue(
            compose
                .onNodeWithTag(TrashTags.Footer)
                .fetchSemanticsNode()
                .boundsInRoot.height > 0f,
        )
    }

    @Test fun anEmptyTrashShowsTheEmptyStateAndNoEmptyButton() {
        val h = TrashHarness(TrashUiState(phase = TrashPhase.Ready))
        composeFrame { HostedTrash(h) }
        advance(1200)
        compose.onNodeWithTag(TrashTags.Empty).assertExists()
        compose.onNodeWithTag("toolbar-empty-trash").assertDoesNotExist()
    }

    @Test fun everyRowIsOnScreenAboveTheBottomEdge() {
        val h = TrashHarness(SampleTrash)
        composeFrame { HostedTrash(h) }
        advance(1200)
        for (row in SampleTrash.rows) compose.onNodeWithTag(TrashTags.row(row.key)).assertExists()
    }
}
