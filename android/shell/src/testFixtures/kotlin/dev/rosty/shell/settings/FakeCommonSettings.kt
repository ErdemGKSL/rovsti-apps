package dev.rosty.shell.settings

import dev.rosty.core.model.phone.HapticsLevel
import dev.rosty.core.model.stops.Appearance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An in-memory [CommonSettings]. Pass `null` for a preference the app under test does not have. */
class FakeCommonSettings(
    appearance: Appearance? = Appearance.Auto,
    haptics: HapticsLevel? = HapticsLevel.Normal,
) : CommonSettings {
    private val appearanceState = MutableStateFlow(appearance)
    private val hapticsState = MutableStateFlow(haptics)

    override val appearance: StateFlow<Appearance?> = appearanceState.asStateFlow()
    override val haptics: StateFlow<HapticsLevel?> = hapticsState.asStateFlow()

    /** Every value written through the setters, in order. */
    val writes = mutableListOf<Any>()

    override fun setAppearance(value: Appearance) {
        writes += value
        appearanceState.value = value
    }

    override fun setHaptics(value: HapticsLevel) {
        writes += value
        hapticsState.value = value
    }
}
