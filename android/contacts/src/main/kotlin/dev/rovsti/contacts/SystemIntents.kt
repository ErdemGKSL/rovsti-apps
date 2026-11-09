package dev.rovsti.contacts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/** Starts [intent] as a new task; [onFailure] gets the sentence to show when no app can handle it. */
internal fun startSafely(
    context: Context,
    intent: Intent,
    onFailure: (String) -> Unit,
) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        onFailure("No app can do this.")
    }
}
