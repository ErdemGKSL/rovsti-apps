package dev.rovsti.contacts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.rovsti.design.text.UiText

/** Starts [intent] as a new task; [onFailure] gets the sentence to show when no app can handle it. */
internal fun startSafely(
    context: Context,
    intent: Intent,
    onFailure: (String) -> Unit,
) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        onFailure(context.getString(R.string.contacts_no_app))
    }
}
