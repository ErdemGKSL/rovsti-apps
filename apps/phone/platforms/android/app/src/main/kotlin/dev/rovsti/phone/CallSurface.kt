package dev.rovsti.phone

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The one place the call activity is raised from the service side (AD-1). The normal
 * paths need no help: the full-screen intent and the notification's actions start the
 * activity themselves. This is the last resort for a ringing call when notifications
 * are switched off, so the screen still has a chance to appear. Android or HyperOS may
 * refuse a background start; that is logged, never thrown.
 */
object CallSurface {
    fun raise(context: Context): Boolean = try {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .setAction(CallNotifications.ACTION_SHOW_INCOMING)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        true
    } catch (e: RuntimeException) {
        Log.w("Rovsti", "raising the call screen was refused", e)
        false
    }
}
