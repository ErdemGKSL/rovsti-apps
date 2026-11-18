package dev.rosty.wa.vault

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

/**
 * `FLAG_SECURE` on a window while anything asks for it (plan 6, 13.6): no screenshot, no screen recording, a blank
 * thumbnail in Recents. Holds are counted per window, so two screens that both ask never clear each other's.
 */
object SecureWindows {
    private val holds = WeakHashMap<Window, Int>()

    fun hold(window: Window) {
        val count = synchronized(holds) { (holds[window] ?: 0).plus(1).also { holds[window] = it } }
        if (count == 1) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(window: Window) {
        val count =
            synchronized(holds) {
                val left = ((holds[window] ?: 0) - 1).coerceAtLeast(0)
                if (left == 0) holds.remove(window) else holds[window] = left
                left
            }
        if (count == 0) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Whether [window] is secure right now. */
    fun isSecure(window: Window): Boolean = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
}

/** Keeps the activity's window secure while this is in the composition and [enabled]. */
@Composable
fun SecureWindow(enabled: Boolean = true) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val window = view.context.findActivity()?.window
        if (enabled && window != null) {
            SecureWindows.hold(window)
            onDispose { SecureWindows.release(window) }
        } else {
            onDispose { }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
