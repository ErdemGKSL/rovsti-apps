package dev.rovsti.design.text

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import dev.rovsti.design.overlay.LocalOverlayOrNull

/**
 * Who holds the keyboard while a search result is open in a sheet (F24): a result that opens a sheet hides the keyboard
 * and lets the field go, so the sheet is not drawn over a keyboard; when the sheet closes the field takes the focus
 * back with the caret at the end of the query and the keyboard returns, so the search carries on where it was.
 * Hand it to the search field (`SearchField(handoff = ...)`), which then reports its focus here.
 *
 * The focus only comes back when the field had it when the result opened (a row opened without searching must not pop
 * the keyboard up when its sheet closes), and not when the sheet was closed because the user left the screen (a call
 * was placed from it: the overlays' `dismissAll`), so no keyboard rises over the call.
 */
class SearchFocusHandoff internal constructor(
    val requester: FocusRequester,
    private val state: TextFieldState,
    private val focus: FocusManager,
    private val keyboard: SoftwareKeyboardController?,
    private val leaves: () -> Int = { 0 },
) {
    private var fieldFocused = false
    private var restore = false
    private var leavesAtOpen = 0

    /** Put on the search field: keeps track of whether it holds the focus. */
    val tracker: Modifier = Modifier.onFocusChanged { fieldFocused = it.hasFocus }

    /** A result was tapped and a sheet opens: unfocus the field and hide the keyboard. */
    fun onResultOpened() {
        restore = fieldFocused
        leavesAtOpen = leaves()
        focus.clearFocus(force = true)
        keyboard?.hide()
    }

    /** The sheet closed: focus the field again, caret at the end, keyboard up (when the search was on). */
    fun onSheetDismissed() {
        val back = restore && leaves() == leavesAtOpen
        restore = false
        if (!back) return
        runCatching { requester.requestFocus() }
        state.edit { placeCursorAtEnd() }
        keyboard?.show()
    }
}

/** A [SearchFocusHandoff] for the search field backed by [state]. */
@Composable
fun rememberSearchFocusHandoff(state: TextFieldState): SearchFocusHandoff {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val overlays = LocalOverlayOrNull.current
    val requester = remember { FocusRequester() }
    return remember(state, focus, keyboard, overlays) {
        SearchFocusHandoff(requester, state, focus, keyboard) { overlays?.leaveCount ?: 0 }
    }
}
