package dev.rovsti.wa.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import dev.rovsti.core.model.wa.SettingsInput
import dev.rovsti.core.model.wa.SettingsView
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * R9-6 / E3.10: the settings screen as a user touches it, with the manual clock stopped (`autoAdvance = false`, set by
 * [ShotTest]). The views are the ones Rust wrote (`wa.settings_apply.*` fixtures).
 */
@OptIn(ExperimentalTestApi::class)
@Config(sdk = [35])
class WhatsAppSettingsScreenTest : ShotTest() {
    private val toolbar = RecordingToolbar()

    private fun show(
        view: SettingsView,
        behind: SettingsView = view,
        withToolbar: Boolean = false,
        fontScale: Float = 1f,
    ): Harness {
        val harness = Harness(stateOf(view, behind))
        composeFrame(fontScale = fontScale) { HostedSettings(harness, if (withToolbar) toolbar else null) }
        return harness
    }

    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun theFirstPageHasExactlyTwoOptionsAndTheGatewayRow() {
        show(fixtureView(""))
        compose.onNodeWithTag("wa-link_new").assertIsDisplayed()
        compose.onNodeWithTag("wa-connect_existing").assertIsDisplayed()
        compose.onNodeWithTag("wa-edit_url").assertIsDisplayed()
        val clickable =
            compose
                .onAllNodes(
                    hasClickAction() and hasAnyAncestor(hasTestTag("wa-settings")),
                ).fetchSemanticsNodes()
        assertEquals("the address row and the two options, nothing else is tappable", 3, clickable.size)
    }

    @Test
    fun tappingAnOptionSendsItsInput() {
        val h = show(fixtureView(""))
        compose.onNodeWithTag("wa-link_new").performClick()
        advance(100)
        assertEquals(listOf<Any>(WaSettingsAction.Press(SettingsInput.unit("choose_link_new"))), h.actions)
    }

    @Test
    fun aLongGatewayAddressShortensInTheMiddleAndStaysInsideItsRow() {
        val base = fixtureView("")
        val long = base.copy(buttons = base.buttons.map { if (it.id == "edit_url") it.copy(detail = LONG_URL) else it })
        show(long)
        val row = bounds("wa-edit_url")
        val value = bounds("wa-gateway-value")
        assertTrue("the value is inside its row: $value in $row", value.left >= row.left && value.right <= row.right)
        assertTrue(
            "it is capped at 200 dp",
            value.width <= 200f * compose.activity.resources.displayMetrics.density + 1f,
        )
        val layout = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithTag("wa-gateway-value", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action!!
            .invoke(layout)
        assertTrue("the URL does not fit on one line, so it is shortened", layout.single().isLineEllipsized(0))
        assertEquals("one line", 1, layout.single().lineCount)
    }

    @Test
    fun largeFontRowsGrowWithoutOverlapping() {
        show(fixtureView(""), fontScale = 2f)
        val rows = listOf("wa-edit_url", "wa-link_new", "wa-connect_existing").map { bounds(it) }
        for ((a, b) in rows.zipWithNext()) {
            assertTrue("rows must not overlap at 200 %: $a above $b", a.bottom <= b.top + 1f)
        }
        val dp = compose.activity.resources.displayMetrics.density
        assertTrue("an option row grows with the font", rows[1].height > 64f * dp)
    }

    @Test
    fun theQrIsASquareOf240DpInsideAWhiteCard() {
        show(fixtureView("show_qr"))
        val qr = bounds("wa-qr")
        val dp = compose.activity.resources.displayMetrics.density
        assertEquals("square", qr.width, qr.height, 0.5f)
        assertEquals("240 dp", 240f * dp, qr.width, 1f)
        val screen = bounds("wa-settings")
        assertTrue("not clipped by the screen", qr.left >= screen.left && qr.right <= screen.right)
    }

    @Test
    fun theQrPageOffersShareAndCancel() {
        val h = show(fixtureView("show_qr"))
        compose.onNodeWithTag("wa-share_qr").performClick()
        advance(100)
        compose.onNodeWithTag("wa-cancel").performClick()
        advance(100)
        assertEquals(listOf<Any>(WaSettingsAction.ShareQr, WaSettingsAction.Press(SettingsInput.cancel)), h.actions)
    }

    @Test
    fun theCodeIsInTwoGroupsOfFour() {
        show(fixtureView("show_code"))
        compose.onNodeWithTag("wa-code").assertIsDisplayed()
        compose.onNodeWithText("ABCD").assertIsDisplayed()
        compose.onNodeWithText("1234").assertIsDisplayed()
    }

    @Test
    fun theMenuOfAKeyRowFloatsOverTheListAndMovesNothing() {
        val h = show(fixtureView("keys_loaded"))
        val rowA = bounds("wa-key-a")
        val rowB = bounds("wa-key-b")
        val list = bounds("wa-keys")
        compose.onNodeWithTag("wa-key-menu-a", useUnmergedTree = true).performClick()
        advance(16)
        assertEquals("+16 ms", rowB, bounds("wa-key-b"))
        advance(1200)
        compose.onNodeWithTag("menu-card").assertIsDisplayed()
        assertEquals("row a stays", rowA, bounds("wa-key-a"))
        assertEquals("row b stays", rowB, bounds("wa-key-b"))
        assertEquals("the list keeps its size", list, bounds("wa-keys"))
        val card = bounds("menu-card")
        assertTrue("the card overlaps the rows instead of making room: $card over $list", card.overlaps(list))
        compose.onNodeWithTag("menu-row-0").performClick()
        advance(600)
        assertEquals(listOf<Any>(WaSettingsAction.Press(SettingsInput.text("request_revoke", "a"))), h.actions)
    }

    @Test
    fun aConfirmationIsAnAlertOverThePageItCameFrom() {
        val connected = fixtureView("connected")
        val h = show(fixtureView("confirm_unlink"), behind = connected)
        advance(800)
        compose.onNodeWithTag("alert").assertIsDisplayed()
        compose.onNodeWithTag("wa-status", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Unlink").performClick()
        advance(400)
        assertEquals(listOf<Any>(WaSettingsAction.Press(SettingsInput.unit("unlink"))), h.actions)
    }

    @Test
    fun cancelOnTheAlertIsTheBackInput() {
        val h = show(fixtureView("confirm_delete"), behind = fixtureView("connected"))
        advance(800)
        compose.onNodeWithText("Cancel").performClick()
        advance(400)
        assertEquals(listOf<Any>(WaSettingsAction.Press(SettingsInput.back)), h.actions)
    }

    @Test
    fun aFailureIsAPageWithTryAgain() {
        val h = show(fixtureView("failed"))
        compose.onNodeWithText("no route to host").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        advance(100)
        assertEquals(listOf<Any>(WaSettingsAction.Press(SettingsInput.retry)), h.actions)
    }

    @Test
    fun theConnectedPageShowsAStatusRowWithADot() {
        show(fixtureView("connected"))
        compose.onNodeWithTag("wa-status-dot", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Ada").assertIsDisplayed()
        for (id in listOf("show_key", "keys", "unlink", "delete")) compose.onNodeWithTag("wa-$id").assertIsDisplayed()
    }

    @Test
    fun theKeyFieldSelectsWordsPastesAndOffersTheToolbar() {
        val view = fixtureView("paste_key")
        val h = Harness(stateOf(view).let { it.copy(paste = PasteRequest(1, PASTED_KEY)) })
        composeFrame { HostedSettings(h, toolbar) }
        advance(300)
        // The Paste request was consumed once and the field holds the key.
        assertEquals(listOf<Any>(WaSettingsAction.PasteDone(1)), h.actions)
        val node = compose.onNodeWithTag("wa-field-key")
        node.assertTextContainsKey()

        // Long press selects a word: the platform toolbar is requested and offers copy and (with a clip) paste.
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("k", "rwk_PASTED"))
        val before = toolbar.shown
        node.performTouchInput { longClick(pointAt(8)) }
        advance(800)
        assertTrue("toolbar requested", toolbar.shown > before)
        assertTrue("copy is offered", toolbar.offers(toolbar.copyKey))
        assertTrue("paste is offered", toolbar.offers(toolbar.pasteKey))

        // Paste from the toolbar replaces the selection with the clipboard text.
        toolbar.press(toolbar.pasteKey)
        advance(300)
        assertTrue("pasted: ${fieldText()}", fieldText().contains("rwk_PASTED"))

        // The Paste button asks the view model; the field itself is not touched until the request comes back.
        compose.onNodeWithTag("wa-paste").performClick()
        advance(100)
        assertEquals(WaSettingsAction.PasteClipboard, h.actions.last())
        assertFalse("no press was sent", h.actions.any { it is WaSettingsAction.Press })
    }

    @Test
    fun connectSendsTheTrimmedTextOfTheField() {
        val h = Harness(stateOf(fixtureView("paste_key")).copy(paste = PasteRequest(1, "  $PASTED_KEY \n")))
        composeFrame { HostedSettings(h) }
        advance(300)
        compose.onNodeWithTag("wa-connect").performClick()
        advance(100)
        val press = h.actions.last() as WaSettingsAction.Press
        assertEquals(SettingsInput.text("key_pasted", PASTED_KEY), press.input)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextContainsKey() {
        assertTrue("the field holds the pasted key: ${fieldText()}", fieldText() == PASTED_KEY)
    }

    private fun fieldText(): String {
        val node = compose.onNodeWithTag("wa-field-key").fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results
            .first()
            .layoutInput.text.text
    }

    private fun androidx.compose.ui.test.TouchInjectionScope.pointAt(offset: Int): Offset {
        val results = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithTag("wa-field-key").fetchSemanticsNode()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val density = compose.activity.resources.displayMetrics.density
        return Offset(14 * density + results.first().getCursorRect(offset).left, node.size.height / 2f)
    }

    private fun Rect.overlaps(other: Rect) =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}
