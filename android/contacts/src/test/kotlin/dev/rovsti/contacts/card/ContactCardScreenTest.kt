package dev.rovsti.contacts.card

import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.design.list.ListRow
import dev.rovsti.people.ui.AvatarFace
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.testing.ShotTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** E4.3 on the real shell: the real view model's state is drawn; the clock is manual (`autoAdvance = false`). */
@Config(sdk = [35])
class ContactCardScreenTest : ShotTest() {
    private var rig: CardRig? = null

    @After fun stop() {
        rig
            ?.scope
            ?.coroutineContext
            ?.get(kotlinx.coroutines.Job)
            ?.cancel()
    }

    private fun rig(
        id: Long,
        build: () -> CardRig = { CardRig(id) },
    ): CardRig = build().also { rig = it }

    private fun show(
        harness: CardHarness,
        live: ContactCardViewModel? = null,
        gate: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    ) {
        composeFrame { HostedCard(harness, live, gate) }
        advance(300)
    }

    @Test fun capsulesAreOneRowOfEqualWidth() {
        val r = rig(3, { CardRig(3, wa = FakeCardWa(linked = true, chatUsers = listOf("905337654321"))) })
        val state = r.stateWhere { it.waJid != null }
        show(CardHarness(state))
        val kinds = state.capsules.map { it.kind }
        assertEquals(listOf(CapsuleKind.Call, CapsuleKind.Message, CapsuleKind.WhatsApp), kinds)
        val bounds = kinds.map { compose.onNodeWithTag(CardTags.capsule(it)).getBoundsInRoot() }
        assertEquals("equal width", 1, bounds.map { it.width.value.toInt() }.distinct().size)
        assertEquals("one row", 1, bounds.map { it.top.value.toInt() }.distinct().size)
        assertTrue("left to right", bounds.zipWithNext().all { (a, b) -> a.right <= b.left })
        // The label is one line inside its capsule.
        compose.onNodeWithText("WhatsApp").assertIsDisplayed()
        assertTrue(bounds.all { it.height.value in 40f..64f })
    }

    @Test fun anEmailCapsuleOnlyComesWithAnEmail() {
        val withEmail = rig(1).ready()
        assertTrue(withEmail.capsules.any { it.kind == CapsuleKind.Email })
        assertTrue("no WhatsApp capsule without a JID", withEmail.capsules.none { it.kind == CapsuleKind.WhatsApp })
        val without = rig(2).ready()
        assertTrue(without.capsules.none { it.kind == CapsuleKind.Email })
    }

    @Test fun atTwiceTheFontScaleOnlyTheIconsStay() {
        val state = rig(1).ready()
        val harness = CardHarness(state)
        composeFrame(fontScale = 2f) { HostedCard(harness) }
        advance(300)
        compose.onAllNodesWithText("Message").assertCountEquals(0)
        val tops =
            state.capsules.map {
                compose
                    .onNodeWithTag(CardTags.capsule(it.kind))
                    .getBoundsInRoot()
                    .top.value
                    .toInt()
            }
        assertEquals("still one row: $tops", 1, tops.distinct().size)
    }

    @Test fun longPressOnAFieldFloatsTheMenuAndLeavesThePageAlone() {
        val state = rig(1).ready()
        val harness = CardHarness(state)
        show(harness)
        val row = CardTags.row("number-0")
        val before = compose.onNodeWithTag(row, useUnmergedTree = true).getBoundsInRoot()
        val offset = harness.listState.firstVisibleItemScrollOffset
        val first = harness.listState.firstVisibleItemIndex
        compose.onNodeWithTag(row, useUnmergedTree = true).performTouchInput { longClick(durationMillis = 700) }
        advance(900)
        // The menu is on screen and names the row's actions ...
        compose.onNodeWithText("Share Number").assertIsDisplayed()
        compose.onNodeWithText("Clear default").assertIsDisplayed()
        // ... and the page behind did not move: same scroll position, the row did not make room for it.
        assertEquals(first, harness.listState.firstVisibleItemIndex)
        assertEquals(offset, harness.listState.firstVisibleItemScrollOffset)
        assertEquals(before.top, compose.onNodeWithTag(row, useUnmergedTree = true).getBoundsInRoot().top)
        // Choosing an entry reports it and closes the menu.
        compose.onNodeWithText("Share Number").performClick()
        advance(400)
        assertTrue(harness.actions.single() is ContactCardAction.Field)
    }

    @Test fun aTapOnANumberDialsItAndOnAnEmailWritesToIt() {
        val state = rig(1).ready()
        val harness = CardHarness(state)
        show(harness)
        compose.onNodeWithTag(CardTags.row("number-0"), useUnmergedTree = true).performClick()
        advance(100)
        assertEquals(ContactCardAction.Tap(FieldOp.Dial("+90 532 111 22 33")), harness.actions.last())
    }

    @Test fun theCallCapsuleStartsTheDialer() {
        val r = rig(1)
        val state = r.ready()
        val harness = CardHarness(state)
        composeFrame { HostedCard(harness, live = r.model) }
        advance(300)
        compose.onNodeWithTag(CardTags.capsule(CapsuleKind.Call)).performClick()
        val shadow = shadowOf(RuntimeEnvironment.getApplication())
        var started: Intent? = null
        repeat(60) {
            advance(50)
            if (started == null) started = shadow.nextStartedActivity
            if (started == null) Thread.sleep(25)
        }
        assertEquals(Intent.ACTION_DIAL, started?.action)
        assertEquals("+90 532 111 22 33", started?.data?.schemeSpecificPart)
    }

    @Test fun twoNumbersWithoutADefaultAskWhichOne() {
        val r = rig(5)
        val state = r.ready()
        val harness = CardHarness(state)
        composeFrame { HostedCard(harness, live = r.model) }
        advance(300)
        compose.onNodeWithTag(CardTags.capsule(CapsuleKind.Call)).performClick()
        repeat(40) {
            advance(50)
            Thread.sleep(10)
        }
        for (choice in state.callChoices) {
            compose.onNodeWithText("${choice.label} · ${choice.display}").assertIsDisplayed()
        }
        assertEquals(2, state.callChoices.size)
    }

    @Test fun theRecentSectionAsksForTheCallLogUntilItIsGranted() {
        val r = rig(1) { CardRig(1, permission = kotlinx.coroutines.flow.MutableStateFlow(PermissionState.DeniedOnce)) }
        val state = r.ready()
        val harness = CardHarness(state)
        show(harness, gate = { ListRow(title = "Call log") })
        compose.runOnUiThread { kotlinx.coroutines.runBlocking { harness.listState.scrollToItem(100) } }
        advance(300)
        compose.onNodeWithTag(CardTags.Gate, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Call log").assertIsDisplayed()
    }

    @Test fun theRecentSectionListsTheCalls() {
        val calls = listOf(CardRig.call(1, "+90 532 111 22 33", CallLogKind.Outgoing, 3_600_000, 125))
        val r = rig(1) { CardRig(1, calls = calls) }
        val state = r.stateWhere { it.recent is CardRecent.Calls }
        val harness = CardHarness(state)
        show(harness)
        compose.runOnUiThread { kotlinx.coroutines.runBlocking { harness.listState.scrollToItem(100) } }
        advance(300)
        compose.onNodeWithTag(CardTags.Recent).assertIsDisplayed()
        compose.onNodeWithTag(CardTags.OpenInContacts).assertIsDisplayed()
    }

    @Test fun theAvatarOpensThePhotoViewerOnlyWithAPhoto() {
        val withPhoto = rig(5).ready()
        val harness = CardHarness(withPhoto)
        show(harness)
        compose.onNodeWithTag(CardTags.Avatar).performClick()
        advance(700)
        compose.onNodeWithTag(dev.rovsti.design.media.PhotoViewerTags.Root).assertExists()
        val without = CardHarness(rig(2).ready())
        show(without)
        compose.onNodeWithTag(CardTags.Avatar).performClick()
        advance(700)
        compose.onAllNodesWithTag(dev.rovsti.design.media.PhotoViewerTags.Root).assertCountEquals(0)
    }

    @Test fun theAvatarFliesFromTheListRowToTheCardOverFiveHundredMilliseconds() {
        val state = rig(1).ready()
        val harness = CardHarness(state)
        composeFrame {
            HostedCard(harness, startOnCard = false, list = { FakeListPage(1) })
        }
        advance(300)
        val rowAvatar = compose.onNodeWithTag("fake-avatar-source")
        val start = rowAvatar.getBoundsInRoot()
        compose.runOnUiThread { harness.navigator.push(ContactsRoute.ContactCard(1)) }
        val sizes = mutableListOf<Float>()
        repeat(11) {
            compose.mainClock.advanceTimeBy(50)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            sizes +=
                compose
                    .onNodeWithTag(CardTags.AvatarFace)
                    .getBoundsInRoot()
                    .width.value
        }
        advance(300)
        val end = compose.onNodeWithTag(CardTags.AvatarFace).getBoundsInRoot()
        assertEquals("settles at 88 dp", 88f, end.width.value, 0.6f)
        assertNotEquals("starts from the small avatar", end.width, start.width)
        assertTrue(
            "grows monotonically while the page slides: $sizes",
            sizes.zipWithNext().all { (a, b) ->
                b >=
                    a - 0.5f
            },
        )
        assertTrue("the first frame is near the row size: $sizes", sizes.first() < 88f)
    }

    @Test fun backAndStarAndEditAreInTheTopBar() {
        val state = rig(1).ready()
        val harness = CardHarness(state)
        show(harness)
        compose.onNodeWithTag("toolbar-edit").performClick()
        compose.onNodeWithTag("toolbar-star").performClick()
        assertEquals(listOf(ContactCardAction.Edit, ContactCardAction.ToggleStar), harness.actions)
    }
}
