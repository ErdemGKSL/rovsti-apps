package dev.rovsti.platform.telephony

// Ported from apps/phone/platforms/android/app/src/main/kotlin/dev/rovsti/phone/DialIntents.kt (ARCH 7.3).

import android.content.Intent
import android.net.Uri

/**
 * `GpuiSession.onOpenIntent` only forwards `ACTION_VIEW`. A dial intent is
 * rewritten to a view of the same `tel:` URI *before* the host sees it
 * (AD-12), so Rust gets one URL either way and places no call. `ACTION_DIAL`
 * without data becomes the empty `tel:` URI: the Keypad opens empty.
 */
object DialIntents {
    const val EMPTY_TEL = "tel:"

    /** The URI to forward for a dial intent, or null when [action] is not a dial. */
    fun forwardedUri(
        action: String?,
        data: String?,
    ): String? = if (action == Intent.ACTION_DIAL) data?.takeIf { it.isNotBlank() } ?: EMPTY_TEL else null

    fun rewrite(intent: Intent): Intent {
        val uri = forwardedUri(intent.action, intent.dataString) ?: return intent
        return Intent(intent).setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri))
    }
}
