package dev.rovsti.contacts.data

import dev.rovsti.core.model.phone.HapticsLevel
import dev.rovsti.core.model.stops.Appearance
import dev.rovsti.shell.settings.CommonSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The appearance and haptics of Contacts: the two fields of `contacts-prefs.json`, as the flows the shell follows. The
 * first frame holds the defaults (Auto, Normal) until the stored file has been read; a change is written at once.
 */
class StoreCommonSettings(
    private val store: ContactsStore,
    private val scope: CoroutineScope,
) : CommonSettings {
    override val appearance: StateFlow<Appearance?> =
        store.prefs
            .map<_, Appearance?> { it.appearance }
            .stateIn(scope, SharingStarted.Eagerly, store.prefs.value.appearance)

    override val haptics: StateFlow<HapticsLevel?> =
        store.prefs
            .map<_, HapticsLevel?> { it.haptics }
            .stateIn(scope, SharingStarted.Eagerly, store.prefs.value.haptics)

    init {
        scope.launch { store.load() }
    }

    override fun setAppearance(value: Appearance) {
        scope.launch { store.update { it.copy(appearance = value) } }
    }

    override fun setHaptics(value: HapticsLevel) {
        scope.launch { store.update { it.copy(haptics = value) } }
    }
}
