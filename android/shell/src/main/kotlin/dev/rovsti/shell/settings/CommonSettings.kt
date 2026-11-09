package dev.rovsti.shell.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.rovsti.core.model.phone.HapticsLevel
import dev.rovsti.core.model.stops.Appearance
import dev.rovsti.design.haptics.HapticMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The two preferences every app shows on its settings page. Each app container implements it over its own prefs ops
 * (E4 to E7). A flow that holds `null` means the app has no such preference: the row and the page are hidden.
 */
interface CommonSettings {
    /** Auto follows the system theme (`FOLLOW_SYSTEM_THEME`), Light and Dark force it. */
    val appearance: StateFlow<Appearance?>

    /** Off, Light or Normal, as in `rovsti_core::wire::HapticsLevel`. */
    val haptics: StateFlow<HapticsLevel?>

    fun setAppearance(value: Appearance)

    fun setHaptics(value: HapticsLevel)
}

/** Whether the theme is dark for this preference; [systemDark] is what Auto follows. A missing preference means Auto. */
fun Appearance?.isDark(systemDark: Boolean): Boolean =
    when (this) {
        Appearance.Light -> false
        Appearance.Dark -> true
        Appearance.Auto, null -> systemDark
    }

/** The value `RovstiTheme(darkTheme = ...)` takes, observed from the preference. */
@Composable
fun CommonSettings.rememberDarkTheme(): Boolean {
    val appearance by appearance.collectAsState()
    return appearance.isDark(isSystemInDarkTheme())
}

/**
 * How a level is played by `PlatformHaptics`: Light goes through `View.performHapticFeedback` (subtle, and it respects the
 * system's touch-feedback switch); Normal drives the vibrator with a predefined effect (needs VIBRATE in the app manifest).
 */
fun HapticsLevel.toMode(): HapticMode =
    when (this) {
        HapticsLevel.Off -> HapticMode.Off
        HapticsLevel.Light -> HapticMode.System
        HapticsLevel.Normal -> HapticMode.Strong
    }

/** [CommonSettings] of an app without either preference: both flows hold null (rows and pages hidden); the setters do nothing. */
object NoCommonSettings : CommonSettings {
    override val appearance: StateFlow<Appearance?> = MutableStateFlow(null)
    override val haptics: StateFlow<HapticsLevel?> = MutableStateFlow(null)

    override fun setAppearance(value: Appearance) = Unit

    override fun setHaptics(value: HapticsLevel) = Unit
}
