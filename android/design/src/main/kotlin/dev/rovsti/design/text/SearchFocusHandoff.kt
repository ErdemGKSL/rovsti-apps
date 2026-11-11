package dev.rovsti.design.text

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController

/**
 * Who holds the keyboard while a search result is open in a sheet (F24): a result that opens a sheet hides the keyboard
 * and lets the field go, so the sheet is not drawn over a keyboard; when the sheet closes the field takes the focus
 * back with the caret at the end of the query and the keyboard returns, so the search carries on where it was.
 * Hand [requester] to the search field (`SearchField(focusRequester = ...)`).
 */
class SearchFocusHandoff internal constructor(
    val requester: FocusRequester,
    private val state: TextFieldState,
    private val focus: FocusManager,
    private val keyboard: SoftwareKeyboardController?,
) {
    /** A result was tapped and a sheet opens: unfocus the field and hide the keyboard. */
    fun onResultOpened() {
        focus.clearFocus(force = true)
        keyboard?.hide()
    }

    /** The sheet closed: focus the field again, caret at the end, keyboard up. */
    fun onSheetDismissed() {
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
    val requester = remember { FocusRequester() }
    return remember(state, focus, keyboard) { SearchFocusHandoff(requester, state, focus, keyboard) }
}
