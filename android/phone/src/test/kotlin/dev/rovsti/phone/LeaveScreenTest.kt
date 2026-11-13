package dev.rovsti.phone

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.OverlayHost
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.text.SearchField
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * Owner bug 2026-11-13: the call button in a sheet (person, search result, call details, favorites menu) started the
 * call but the sheet stayed on screen, over or behind the call. Placing a call (or opening the SMS composer) and a call
 * page opening now close every sheet and the keyboard; the call page's own sheets are left alone.
 */
class LeaveScreenTest : ShotTest() {
    private val overlays = OverlayController()
    private val leaves = ScreenLeaves()
    private var onCallPage by mutableStateOf(false)

    private fun run(ms: Long) = repeat((ms / 100).toInt()) { advance(100) }

    private fun start() {
        composeFrame {
            OverlayHost(overlays) {
                Box(Modifier.fillMaxSize()) {
                    SearchField(TextFieldState("mert"), modifier = Modifier.testTag("search"))
                }
                CloseOverlaysOnLeave(leaves.flow, overlays, onCallPage)
            }
        }
    }

    private fun sheet(text: String) {
        compose.runOnUiThread {
            overlays.present(SheetEntry(title = text, detents = persistentListOf(Detent.Medium)) { BasicText(text) })
        }
        run(1500)
        compose.onNodeWithText(text).assertExists()
    }

    private fun keyboardUp(): Boolean {
        val imm =
            ApplicationProvider.getApplicationContext<Context>().getSystemService(
                Context.INPUT_METHOD_SERVICE,
            ) as InputMethodManager
        return shadowOf(imm).isSoftInputVisible
    }

    @Test fun aPlacedCallClosesTheSheetsAndTheKeyboardAndKeepsTheToast() {
        start()
        compose.onNodeWithTag("search").performClick()
        run(300)
        compose.onNodeWithTag("search").assertIsFocused()
        sheet("Person sheet")
        sheet("Number menu")
        compose.runOnUiThread { overlays.showToast("Copied", durationMs = 60_000) }
        run(300)

        compose.runOnUiThread { leaves.leave() }
        run(2000)

        compose.onNodeWithText("Person sheet").assertDoesNotExist()
        compose.onNodeWithText("Number menu").assertDoesNotExist()
        compose.onNodeWithText("Copied").assertExists()
        compose.onNodeWithTag("search").assertIsNotFocused()
        assertFalse("the keyboard is down", keyboardUp())
    }

    @Test fun aCallPageOpeningClosesWhatWasOpenOverTheTabs() {
        start()
        sheet("Call details")
        compose.runOnUiThread { onCallPage = true }
        run(2000)
        compose.onNodeWithText("Call details").assertDoesNotExist()
        assertEquals(1, overlays.leaveCount)
    }

    @Test fun onTheCallPageItsOwnSheetsStay() {
        start()
        compose.runOnUiThread { onCallPage = true }
        run(300)
        sheet("Add call")
        // The Add call sheet places the second call itself: the leave must not close the note or route sheets.
        compose.runOnUiThread { leaves.leave() }
        run(2000)
        compose.onNodeWithText("Add call").assertExists()
        // The call ends and its page closes: a note being written is not thrown away.
        compose.runOnUiThread { onCallPage = false }
        run(2000)
        compose.onNodeWithText("Add call").assertExists()
    }
}
