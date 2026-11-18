package dev.rosty.messages.notify

import android.content.Context
import android.content.pm.PackageManager

/**
 * Who rings for a WhatsApp call push. Phone owns WhatsApp calls (its locked-call page answers over the lock screen,
 * round 7b), so Messages ignores `kind=call` and `kind=call_end` whenever Phone is installed and handles them only when
 * it is absent. Phone is visible to Messages through the `dev.rosty.phone.walink` provider query in the manifest.
 */
object WaCallPushes {
    const val PHONE_PACKAGE = "dev.rosty.phone"
    private val CALL_KINDS = setOf("call", "call_end")

    /** Pure: whether Messages leaves a push of [kind] to Phone. */
    fun leftToPhone(
        kind: String?,
        phoneInstalled: Boolean,
    ): Boolean = phoneInstalled && kind in CALL_KINDS

    /** Whether the Phone app is installed. */
    fun phoneInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(PHONE_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
}
