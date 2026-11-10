package dev.rovsti.design.haptics

import android.annotation.SuppressLint
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/** The one app preference for haptics. */
enum class HapticMode {
    /** Nothing vibrates. */
    Off,

    /** Through `View.performHapticFeedback`: respects the system's touch-feedback setting. The default. */
    System,

    /** Drives the vibrator directly with a predefined effect, which some OEM skins do not silence. */
    Strong,
}

/**
 * What can be felt (ORIGINAL). Each event pairs a platform haptic constant (System mode) with a predefined
 * vibration effect (Strong mode).
 */
enum class HapticEvent(
    internal val constant: Int,
    internal val effect: Int,
) {
    /** Tab reselect, a sheet snapping to a detent, a swipe row crossing its full-swipe line. */
    Tick(HapticFeedbackConstants.CLOCK_TICK, VibrationEffect.EFFECT_TICK),

    /** A puck or slider landing on a new value. */
    Selection(HapticFeedbackConstants.SEGMENT_TICK, VibrationEffect.EFFECT_TICK),
    ToggleOn(HapticFeedbackConstants.TOGGLE_ON, VibrationEffect.EFFECT_TICK),
    ToggleOff(HapticFeedbackConstants.TOGGLE_OFF, VibrationEffect.EFFECT_TICK),

    /** A text selection handle or the slide over a new menu row. */
    TextHandle(HapticFeedbackConstants.TEXT_HANDLE_MOVE, VibrationEffect.EFFECT_TICK),

    /** A long press opening a menu. */
    LongPress(HapticFeedbackConstants.LONG_PRESS, VibrationEffect.EFFECT_HEAVY_CLICK),

    /** A destructive action was confirmed. */
    Confirm(HapticFeedbackConstants.CONFIRM, VibrationEffect.EFFECT_CLICK),

    /** An invalid action. */
    Reject(HapticFeedbackConstants.REJECT, VibrationEffect.EFFECT_DOUBLE_CLICK),

    /** A key of the dial pad going down. */
    KeyboardTap(HapticFeedbackConstants.KEYBOARD_TAP, VibrationEffect.EFFECT_CLICK),
}

/**
 * Haptic feedback for UI code. Fire on discrete moments only, never on scroll and never continuously during a drag.
 * Implementations decide how [HapticEvent]s are played; the named functions are the vocabulary screens use.
 */
interface RovstiHaptics {
    fun play(event: HapticEvent)

    fun tick() = play(HapticEvent.Tick)

    fun selection() = play(HapticEvent.Selection)

    fun toggle(on: Boolean) = play(if (on) HapticEvent.ToggleOn else HapticEvent.ToggleOff)

    fun textHandle() = play(HapticEvent.TextHandle)

    fun longPress() = play(HapticEvent.LongPress)

    fun confirm() = play(HapticEvent.Confirm)

    fun reject() = play(HapticEvent.Reject)

    fun keyboardTap() = play(HapticEvent.KeyboardTap)
}

/** Plays nothing. The default of [LocalRovstiHaptics] (previews, tests). */
object NoHaptics : RovstiHaptics {
    override fun play(event: HapticEvent) = Unit
}

/** The device implementation: [mode] is read on every event, so changing the preference needs no recreation. */
@SuppressLint("MissingPermission") // The app manifest holds VIBRATE; libraries declare no permissions.
class PlatformHaptics(
    private val view: View,
    private val vibrator: Vibrator?,
    private val mode: () -> HapticMode,
) : RovstiHaptics {
    override fun play(event: HapticEvent) {
        when (mode()) {
            HapticMode.Off -> Unit
            HapticMode.System -> view.performHapticFeedback(event.constant)
            HapticMode.Strong -> vibrator?.vibrate(VibrationEffect.createPredefined(event.effect))
        }
    }
}

val LocalRovstiHaptics = staticCompositionLocalOf<RovstiHaptics> { NoHaptics }

/** A [PlatformHaptics] for the current window. Strong mode needs the VIBRATE permission in the app manifest. */
@Composable
fun rememberRovstiHaptics(mode: HapticMode): RovstiHaptics {
    val view = LocalView.current
    val context = LocalContext.current
    val current by rememberUpdatedState(mode)
    return remember(view, context) {
        PlatformHaptics(view, context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator) { current }
    }
}
