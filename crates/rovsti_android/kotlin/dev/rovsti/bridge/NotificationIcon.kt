package dev.rovsti.bridge

import android.content.Context

/**
 * The small icon of a notification. Android draws it from the alpha channel only, so the launcher icon
 * (a full-colour picture) would show as a white square. Each app that posts notifications ships a white
 * glyph on transparent as the drawable `ic_stat_notification`; an app without one falls back to its
 * launcher icon, which is what these notifications used before.
 */
object NotificationIcon {
    private const val GLYPH = "ic_stat_notification"

    fun small(context: Context): Int {
        val glyph = context.resources.getIdentifier(GLYPH, "drawable", context.packageName)
        return if (glyph != 0) glyph else context.applicationInfo.icon
    }
}
