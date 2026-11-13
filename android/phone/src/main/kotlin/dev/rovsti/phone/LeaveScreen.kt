package dev.rovsti.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import dev.rovsti.design.overlay.OverlayController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * "The user is leaving the screen": a cellular call went to Telecom or to the system dialer, a WhatsApp call started,
 * or another app opened on a person (the SMS composer). One signal for every place that can do it (the person and call
 * sheets, Recents, Favorites, the keypad, a sibling app's hand-off), so no sheet, menu or keyboard is left behind.
 */
class ScreenLeaves {
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    val flow: SharedFlow<Unit> = events

    fun leave() {
        events.tryEmit(Unit)
    }
}

/**
 * Closes every overlay (sheets, popovers, menus, alerts; toasts stay) and lets the keyboard go when [leaves] fires or
 * when a call page ([onCallPage]: Incoming or In-Call) opens over the tabs, so a call always lands on a clean call
 * screen. While a call page is on show its own sheets are left alone (the Add call sheet closes itself, a note being
 * written survives the end of the call).
 */
@Composable
fun CloseOverlaysOnLeave(
    leaves: Flow<Unit>,
    overlays: OverlayController,
    onCallPage: Boolean,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val onCall by rememberUpdatedState(onCallPage)
    val close by rememberUpdatedState {
        overlays.dismissAll()
        focus.clearFocus(force = true)
        keyboard?.hide()
    }
    LaunchedEffect(leaves) { leaves.collect { if (!onCall) close() } }
    LaunchedEffect(onCallPage) { if (onCallPage) close() }
}
