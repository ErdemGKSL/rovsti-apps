package dev.rosty.stops.data

import dev.rosty.core.model.phone.HapticsLevel
import dev.rosty.core.model.stops.Appearance
import dev.rosty.shell.settings.CommonSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The common settings of Stops: `appearance` is a field of `stops-prefs.json` (S11, Auto / Light / Dark), so the row and
 * the page show and the activity follows it. The file has no haptics field (`rosty_geo::prefs::StopsPrefs`), so that
 * flow holds null and the Haptics row is hidden (the activity then plays the Light default).
 */
class StopsCommonSettings(
    private val store: StopsStore,
    private val scope: CoroutineScope,
) : CommonSettings {
    override val appearance: StateFlow<Appearance?> =
        store.prefs
            .map<_, Appearance?> { it.appearance }
            .stateIn(scope, SharingStarted.Eagerly, store.prefs.value.appearance)

    override val haptics: StateFlow<HapticsLevel?> = MutableStateFlow(null)

    init {
        scope.launch { store.load() }
    }

    override fun setAppearance(value: Appearance) {
        scope.launch { store.update { it.copy(appearance = value) } }
    }

    /** No haptics preference in the Stops file: the row is hidden, so nothing calls this. */
    override fun setHaptics(value: HapticsLevel) = Unit
}
