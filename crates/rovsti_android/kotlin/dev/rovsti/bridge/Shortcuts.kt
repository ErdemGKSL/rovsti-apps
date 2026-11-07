package dev.rovsti.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Icon
import org.json.JSONObject

/**
 * Pinned home screen shortcuts for a contact. The shortcut launches the app with
 * [BirthdayReminders.EXTRA_CONTACT_ID]; `RovstiBridge.deliverOpenContact` turns that into `open_contact`.
 */
object Shortcuts {
    /** Launch extra of a stop shortcut (Long); `RovstiBridge.deliverOpenStop` turns it into `open_stop`. */
    const val EXTRA_STOP_ID = "dev.rovsti.stops.STOP_ID"

    /** Adaptive icon canvas in pixels; the visible safe zone is 72/108 of it. */
    private const val CANVAS = 216

    fun canPin(ctx: Context): Boolean = try {
        ctx.getSystemService(ShortcutManager::class.java)?.isRequestPinShortcutSupported == true
    } catch (_: RuntimeException) {
        false
    }

    /** Pure: the side of the photo inside the adaptive canvas (the safe zone). */
    fun safeSide(canvas: Int): Int = canvas * 72 / 108

    /** [specJson] is `rovsti_core::contact_options::PinShortcut`. */
    fun pinContact(ctx: Context, specJson: String): Boolean {
        return try {
            val manager = ctx.getSystemService(ShortcutManager::class.java) ?: return false
            if (!manager.isRequestPinShortcutSupported) return false
            val spec = JSONObject(specJson)
            val contactId = spec.getLong("contact_id")
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return false
            launch.setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(BirthdayReminders.EXTRA_CONTACT_ID, contactId)
            val info = ShortcutInfo.Builder(ctx, spec.getString("id"))
                .setShortLabel(spec.getString("short_label"))
                .setLongLabel(spec.optString("long_label", spec.getString("short_label")))
                .setIcon(icon(ctx, if (spec.isNull("icon_path")) null else spec.optString("icon_path")))
                .setIntent(launch)
                .build()
            manager.requestPinShortcut(info, null)
        } catch (_: RuntimeException) {
            false
        }
    }

    /** [specJson] is `rovsti_geo::StopShortcut` (`id`, `short_label`, `long_label`, `stop_id`); app icon. */
    fun pinStop(ctx: Context, specJson: String): Boolean = try {
        val manager = ctx.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false
        val spec = JSONObject(specJson)
        val stopId = spec.getLong("stop_id")
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return false
        launch.setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_STOP_ID, stopId)
        val info = ShortcutInfo.Builder(ctx, spec.getString("id"))
            .setShortLabel(spec.getString("short_label"))
            .setLongLabel(spec.optString("long_label", spec.getString("short_label")))
            .setIcon(icon(ctx, null))
            .setIntent(launch)
            .build()
        manager.requestPinShortcut(info, null)
    } catch (_: RuntimeException) {
        false
    }

    private fun icon(ctx: Context, path: String?): Icon {
        val bitmap = path?.takeIf { it.isNotEmpty() }?.let(::adaptiveBitmap)
        return if (bitmap != null) Icon.createWithAdaptiveBitmap(bitmap) else Icon.createWithResource(ctx, ctx.applicationInfo.icon)
    }

    /** The decoded photo, centre-cropped square, drawn into the safe zone of a transparent-free canvas. */
    private fun adaptiveBitmap(path: String): Bitmap? {
        val decoded = BitmapFactory.decodeFile(path) ?: return null
        val crop = minOf(decoded.width, decoded.height)
        val out = Bitmap.createBitmap(CANVAS, CANVAS, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.parseColor("#2C2C2E"))
        val side = safeSide(CANVAS)
        val pad = (CANVAS - side) / 2
        val src = Rect((decoded.width - crop) / 2, (decoded.height - crop) / 2, (decoded.width + crop) / 2, (decoded.height + crop) / 2)
        canvas.drawBitmap(decoded, src, Rect(pad, pad, pad + side, pad + side), null)
        return out
    }
}
