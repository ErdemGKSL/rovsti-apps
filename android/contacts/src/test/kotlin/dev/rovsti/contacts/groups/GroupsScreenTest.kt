package dev.rovsti.contacts.groups

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import dev.rovsti.contacts.people.PickerTags
import dev.rovsti.contacts.people.presentContactPicker
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.7 on the real shell; the clock is manual (`autoAdvance = false`). */
@Config(sdk = [35])
class GroupsScreenTest : ShotTest() {
    private fun show(
        h: GroupsHarness,
        openPage: Boolean = false,
    ) {
        composeFrame { HostedGroups(h, openPage = openPage) }
        advance(if (openPage) 900 else 300)
    }

    @Test fun rowsShowTheGroupAndItsCountAndTapOpens() {
        val h = GroupsHarness()
        show(h)
        compose.onNodeWithText("Family").assertIsDisplayed()
        compose.onNodeWithText("3 contacts").assertIsDisplayed()
        compose.onNodeWithTag(GroupsTags.row(11)).performClick()
        assertEquals(listOf<GroupsAction>(GroupsAction.Open(11)), h.actions)
    }

    @Test fun plusAsksForANewGroup() {
        val h = GroupsHarness()
        show(h)
        compose.onNodeWithTag("toolbar-add").performClick()
        assertEquals(1, h.newTapped)
    }

    @Test fun noGroupsShowsTheSentenceAndTheButton() {
        val h = GroupsHarness(GroupsUi.state(emptyList()))
        show(h)
        compose.onNodeWithTag(GroupsTags.Empty).assertIsDisplayed()
        compose.onNodeWithText("No Groups").assertIsDisplayed()
        compose.onNodeWithText("New Group").performClick()
        assertEquals(1, h.newTapped)
    }

    @Test fun theNameAlertTakesTextAndSaveHandsItOver() {
        val h = GroupsHarness()
        show(h)
        var saved: String? = null
        compose.runOnUiThread {
            h.overlays.presentNameAlert("New Group", "", null, "Save", "Cancel", "Group name") { saved = it }
        }
        advance(600)
        compose.onNodeWithTag(GroupsTags.NameField).performTextInput("Climbing")
        advance(200)
        compose.onNodeWithTag("alert-button-Save").performClick()
        advance(400)
        assertEquals("Climbing", saved)
    }

    @Test fun aRejectedNameShowsTheCoresSentenceInTheAlert() {
        val h = GroupsHarness()
        show(h)
        compose.runOnUiThread {
            h.overlays.presentNameAlert(
                "New Group",
                "Family",
                "A group named Family already exists.",
                "Save",
                "Cancel",
                "Group name",
            ) {
            }
        }
        advance(600)
        compose.onNodeWithText("A group named Family already exists.").assertIsDisplayed()
    }

    // ---- the page

    @Test fun theMenuListsTheSixActionsAndKeepsThePageStill() {
        val h = GroupsHarness()
        show(h, openPage = true)
        val before = compose.onNodeWithTag("group-member-1").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("toolbar-overflow").performClick()
        advance(900)
        for (label in listOf("Rename", "Add Members", "Message Group", "Email Group", "Export Group", "Delete Group")) {
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        assertEquals(before, compose.onNodeWithTag("group-member-1", useUnmergedTree = true).getUnclippedBoundsInRoot())
        compose.onNodeWithText("Message Group").performClick()
        advance(900)
        assertEquals(listOf<GroupPageAction>(GroupPageAction.MessageGroup), h.pageActions)
    }

    @Test fun renameGoesToTheScreensCallback() {
        val h = GroupsHarness()
        show(h, openPage = true)
        compose.onNodeWithTag("toolbar-overflow").performClick()
        advance(900)
        compose.onNodeWithText("Rename").performClick()
        advance(900)
        assertEquals(1, h.renameTapped)
    }

    @Test fun anEmptyGroupHasNoMessageOrExportRows() {
        val h = GroupsHarness(page = GroupsUi.family(emptyList()))
        show(h, openPage = true)
        compose.onNodeWithTag(GroupPageTags.Empty).assertIsDisplayed()
        compose.onNodeWithTag("toolbar-overflow").performClick()
        advance(900)
        compose.onNodeWithText("Rename").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Message Group").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Email Group").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Export Group").fetchSemanticsNodes().size)
    }

    @Test fun deleteGroupAsksFirst() {
        val h = GroupsHarness()
        show(h, openPage = true)
        compose.onNodeWithTag("toolbar-overflow").performClick()
        advance(900)
        compose.onNodeWithText("Delete Group").performClick()
        advance(900)
        compose.onNodeWithText("Delete “Family”?").assertIsDisplayed()
        assertTrue("nothing was deleted yet", h.pageActions.isEmpty())
        compose.onNodeWithTag("alert-button-Delete Group").performClick()
        advance(400)
        assertEquals(listOf<GroupPageAction>(GroupPageAction.ConfirmDelete), h.pageActions)
    }

    @Test fun addMembersOpensThePickerWithTheMembersTicked() {
        val h = GroupsHarness()
        show(h, openPage = true)
        compose.onNodeWithTag("toolbar-overflow").performClick()
        advance(900)
        compose.onNodeWithText("Add Members").performClick()
        advance(1000)
        compose.onNodeWithTag(PickerTags.Sheet).assertIsDisplayed()
        // The confirm button waits for a change.
        compose.onNodeWithTag(PickerTags.row(2)).performClick()
        compose.onNodeWithTag(PickerTags.row(1)).performClick()
        advance(200)
        compose.onNodeWithTag(PickerTags.Confirm).performClick()
        advance(800)
        assertEquals(listOf<GroupPageAction>(GroupPageAction.SetMembers(setOf(2L, 3L, 5L))), h.pageActions)
    }

    @Test fun pickerSearchNarrowsTheRows() {
        val h = GroupsHarness()
        show(h, openPage = true)
        compose.runOnUiThread { h.overlays.presentContactPickerForTest() }
        advance(1000)
        compose.onNodeWithTag(PickerTags.Search).performTextInput("zey")
        advance(400)
        compose.onNodeWithTag(PickerTags.row(6)).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Burak Demir").fetchSemanticsNodes().size)
    }

    @Test fun aFullSwipeOnAMemberRemovesIt() {
        val h = GroupsHarness()
        show(h, openPage = true)
        compose.onNodeWithTag("group-member-3").performTouchInput { swipeLeft() }
        advance(800)
        assertEquals(listOf<GroupPageAction>(GroupPageAction.RemoveMember(3)), h.pageActions)
    }
}

private fun OverlayController.presentContactPickerForTest() {
    presentContactPicker("Add Members", "Done", GroupsUi.everyone.toImmutableList(), emptySet()) {}
}
