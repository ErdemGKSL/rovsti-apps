package dev.rosty.contacts.cards

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.rosty.contacts.ContactsRoute
import dev.rosty.core.ops.CardPalette
import dev.rosty.core.ops.CardTemplate
import dev.rosty.testing.ShotTest
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** X.2 step 5, on the manual Compose clock: one render per switch, the debounce, cancel on close, back closes the sheet first. */
@Config(sdk = [35])
class CardStudioSheetTest : ShotTest() {
    private fun rig(): CardStudioRig =
        CardStudioRig(this).also {
            it.show()
            it.open()
        }

    private fun swatch(p: CardPalette) = compose.onNodeWithTag(CardStudioTags.swatch(p))

    @Test fun theSheetShowsEverythingAndRendersOnce() {
        val r = rig()
        compose.onNodeWithTag(CardStudioTags.Templates).assertIsDisplayed()
        compose.onNodeWithTag(CardStudioTags.Preview).assertIsDisplayed()
        compose.onNodeWithTag(CardStudioTags.Swatches).assertIsDisplayed()
        compose.onNodeWithTag(CardStudioTags.Animated).assertIsDisplayed()
        compose.onNodeWithTag(CardStudioTags.Share).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag(CardStudioTags.Save).assertIsDisplayed().assertIsEnabled()
        swatch(CardPalette.Dawn).assertIsSelected()
        assertEquals(1, r.ops.renders.size)
        assertEquals(
            CardTemplate.Birthday,
            r.ops.renders
                .single()
                .spec.template,
        )
    }

    @Test fun oneRenderPerSwitch() {
        val r = rig()
        swatch(CardPalette.Lagoon).performClick()
        advance(400)
        assertEquals(2, r.ops.renders.size)
        compose.onNodeWithText("Contact").performClick()
        advance(400)
        assertEquals(3, r.ops.renders.size)
        assertEquals(
            CardTemplate.Contact,
            r.ops.renders
                .last()
                .spec.template,
        )
        assertEquals(
            CardPalette.Lagoon,
            r.ops.renders
                .last()
                .spec.palette,
        )
    }

    @Test fun tapsUnder150MsApartCostOneRender() {
        val r = rig()
        swatch(CardPalette.Lagoon).performClick()
        advance(50)
        swatch(CardPalette.Meadow).performClick()
        advance(50)
        swatch(CardPalette.Dusk).performClick()
        advance(50)
        assertEquals("nothing is rendered while the taps keep coming", 1, r.ops.renders.size)
        advance(400)
        assertEquals(2, r.ops.renders.size)
        assertEquals(
            CardPalette.Dusk,
            r.ops.renders
                .last()
                .spec.palette,
        )
    }

    @Test fun closingTheSheetCancelsTheRunningRender() {
        val r = rig()
        r.ops.gate = CompletableDeferred()
        swatch(CardPalette.Ink).performClick()
        advance(400)
        assertEquals(2, r.ops.renders.size)
        compose.onNodeWithTag(CardStudioTags.Ring).assertIsDisplayed()
        compose.runOnUiThread { r.entry?.sheet?.dismiss() }
        r.settle(8)
        assertEquals(
            listOf(
                r.ops.renders
                    .last()
                    .out,
            ),
            r.ops.cancelled.toList(),
        )
    }

    @Test fun animatedSwitchAsksForAnAnimatedRenderAndNewNumberDisablesIt() {
        val r = rig()
        compose.onNodeWithTag(CardStudioTags.Animated).performTouchInput { click(centerRight.copy(x = right - 40f)) }
        advance(400)
        assertTrue(
            r.ops.renders
                .last()
                .spec.animated,
        )
        compose.onNodeWithText("Number").performClick()
        advance(400)
        compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag(CardStudioTags.Animated))).assertIsNotEnabled()
        assertFalse(
            r.ops.renders
                .last()
                .spec.animated,
        )
    }

    @Test fun backClosesTheSheetBeforeThePage() {
        val r = rig()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        r.settle(8)
        assertTrue("the sheet is gone", compose.onAllNodesWithTagCount(CardStudioTags.Sheet) == 0)
        assertTrue("the card page is still on top", r.harness.navigator.current is ContactsRoute.ContactCard)
        assertEquals(0, r.harness.backs)
        assertTrue(
            "closing the sheet closed the model",
            r.model.state.value.rendering
                .not(),
        )
    }

    @Test fun thePreviewBoxKeepsItsSizeWhileItRenders() {
        val r = rig()
        val before = compose.onNodeWithTag(CardStudioTags.Preview).getUnclippedBoundsInRoot()
        r.ops.gate = CompletableDeferred()
        swatch(CardPalette.Dusk).performClick()
        advance(400)
        val during = compose.onNodeWithTag(CardStudioTags.Preview).getUnclippedBoundsInRoot()
        assertEquals(before.width, during.width)
        assertEquals(before.height, during.height)
        assertEquals(CardStudioMetrics.Aspect, during.width.value / during.height.value, 0.01f)
    }

    @Test fun theMoreButtonOnlyExistsForAnAnimatedCardAndSharesAStill() {
        val r = rig()
        r.files.staged = android.net.Uri.parse("content://x/card.gif")
        compose.onNodeWithTag(CardStudioTags.ShareMore).assertDoesNotExist()
        compose
            .onNodeWithTag(CardStudioTags.Animated)
            .performTouchInput { click(centerRight.copy(x = right - 40f)) }
        r.settle(3)
        compose.onNodeWithTag(CardStudioTags.ShareMore).assertIsDisplayed().assertIsEnabled()

        compose.onNodeWithTag(CardStudioTags.ShareMore).performClick()
        advance(1200)
        compose.onNodeWithText("Share as still image").assertIsDisplayed().performClick()
        advance(800)
        r.settle(2)

        assertEquals(
            true,
            r.files.stagedCards
                .last()
                .second,
        )
    }
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagCount(tag: String): Int =
    onAllNodes(
        androidx.compose.ui.test
            .hasTestTag(tag),
    ).fetchSemanticsNodes().size
