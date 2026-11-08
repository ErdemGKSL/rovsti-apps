package dev.rovsti.design.motion

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** True while the user asked for less motion. Provided once at the root; defaults to false (previews, tests). */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Reads the system animator duration scale: 0 means animations are off. */
private fun animationsOff(resolver: android.content.ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** System "remove animations" state, updated when the setting changes. */
@Composable
fun rememberReduceMotion(): State<Boolean> {
    val context = LocalContext.current
    val state = remember(context) { mutableStateOf(animationsOff(context.contentResolver)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    state.value = animationsOff(resolver)
                }
            }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        state.value = animationsOff(resolver)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return state
}

/** Provides [LocalReduceMotion] = system setting OR the in-app preference. */
@Composable
fun ProvideReduceMotion(
    appPreference: Boolean = false,
    content: @Composable () -> Unit,
) {
    val system by rememberReduceMotion()
    CompositionLocalProvider(LocalReduceMotion provides (system || appPreference), content = content)
}

/** With reduce motion on a value jumps instead of animating. The one gate: call sites never branch. */
fun <T> FiniteAnimationSpec<T>.gated(reduceMotion: Boolean): FiniteAnimationSpec<T> = if (reduceMotion) snap() else this

/** With reduce motion on, an under-damped spring becomes critically damped at the same stiffness (no bounce). */
fun <T> SpringSpec<T>.bounceless(reduceMotion: Boolean): SpringSpec<T> =
    if (reduceMotion && dampingRatio < 1f) {
        spring(dampingRatio = 1f, stiffness = stiffness, visibilityThreshold = visibilityThreshold)
    } else {
        this
    }
