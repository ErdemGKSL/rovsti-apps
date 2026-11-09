package dev.rovsti.phone

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity

/** The activity behind a composition's context (it owns the view models and the lifecycle). */
internal fun Context.findComponentActivity(): ComponentActivity {
    var current = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    error("No ComponentActivity around $this")
}
