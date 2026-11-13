package dev.rovsti.phone.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.showToast
import dev.rovsti.phone.R

/**
 * The setup-complete moment (E5.14): the last checklist row turning done while the app is open gives one confirm haptic
 * and the "You're all set" pill. Opening the app with everything already done is silent.
 */
internal object SetupMoment {
    /** [previous] is the last known `complete` (null before the checklist loaded). */
    fun shouldPlay(
        previous: Boolean?,
        complete: Boolean,
    ): Boolean = previous == false && complete
}

@Composable
internal fun SetupCompleteMoment(
    setup: SetupUiState,
    overlays: OverlayController,
) {
    val haptics = LocalRovstiHaptics.current
    val allSet = stringResource(R.string.setup_all_set)
    var previous by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(setup.loaded, setup.complete) {
        if (!setup.loaded) return@LaunchedEffect
        if (SetupMoment.shouldPlay(previous, setup.complete)) {
            haptics.confirm()
            overlays.showToast(allSet)
        }
        previous = setup.complete
    }
}
