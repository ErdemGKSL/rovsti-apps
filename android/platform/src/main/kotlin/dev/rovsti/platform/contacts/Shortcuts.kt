package dev.rovsti.platform.contacts

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
import dev.rovsti.core.model.contacts.PinShortcut
import dev.rovsti.core.model.stops.ShortcutAnswer
import dev.rovsti.platform.PlatformComponents

/**
 * Pinned Home-screen shortcuts. A shortcut starts the app's main activity (from [PlatformComponents]) with
 * [Shortcuts.EXTRA_CONTACT_ID] or [Shortcuts.EXTRA_STOP_ID]; the host turns the extra into `open_contact` or
 * `open_stop`.
 */
interface ShortcutsRepository {
    fun canPin(): Boolean

    /** Asks the launcher to pin [spec]; false when pinning is unsupported or refused. */
    fun pinContact(spec: PinShortcut): Boolean

    fun pinStop(spec: ShortcutAnswer): Boolean
}

/** Intent extras of the shortcuts, unchanged from the GPUI build so shortcuts pinned there keep working. */
object Shortcuts {
    /** Launch extra of a contact shortcut (Long); the same one the birthday reminder notification carries. */
    const val EXTRA_CONTACT_ID = "open_contact_id"

    /**
     * Launch extra of a stop shortcut (Long). An extra name, not a class (the convention test bans app class names),
     * kept as the GPUI shortcuts wrote it.
     */
    const val EXTRA_STOP_ID = "dev.rovsti.stops" + ".STOP_ID"

    /** Adaptive icon canvas in pixels; the visible safe zone is 72/108 of it. */
    internal const val CANVAS = 216

    /** Pure: the side of the photo inside the adaptive canvas (the safe zone). */
    fun safeSide(canvas: Int): Int = canvas * 72 / 108
}

/** Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/Shortcuts.kt. */
class AndroidShortcutsRepository(
    private val context: Context,
    private val components: () -> PlatformComponents = { PlatformComponents.get() },
) : ShortcutsRepository {
    private fun manager(): ShortcutManager? =
        try {
            context.getSystemService(ShortcutManager::class.java)?.takeIf { it.isRequestPinShortcutSupported }
        } catch (_: RuntimeException) {
            null
        }

    override fun canPin(): Boolean = manager() != null

    override fun pinContact(spec: PinShortcut): Boolean =
        pin(spec.id, spec.shortLabel, spec.longLabel, Shortcuts.EXTRA_CONTACT_ID, spec.contactId, spec.iconPath)

    override fun pinStop(spec: ShortcutAnswer): Boolean =
        pin(spec.id, spec.shortLabel, spec.longLabel, Shortcuts.EXTRA_STOP_ID, spec.stopId, null)

    /** The launch intent: the main activity, cleared to top, with the id extra. */
    internal fun launchIntent(
        extra: String,
        id: Long,
    ): Intent =
        Intent(context, components().mainActivity)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(extra, id)

    private fun pin(
        id: String,
        shortLabel: String,
        longLabel: String,
        extra: String,
        target: Long,
        iconPath: String?,
    ): Boolean =
        try {
            val manager = manager() ?: return false
            val info =
                ShortcutInfo
                    .Builder(context, id)
                    .setShortLabel(shortLabel)
                    .setLongLabel(longLabel.ifEmpty { shortLabel })
                    .setIcon(icon(iconPath))
                    .setIntent(launchIntent(extra, target))
                    .build()
            manager.requestPinShortcut(info, null)
        } catch (_: RuntimeException) {
            false
        }

    private fun icon(path: String?): Icon {
        val bitmap = path?.takeIf { it.isNotEmpty() }?.let(::adaptiveBitmap)
        return if (bitmap !=
            null
        ) {
            Icon.createWithAdaptiveBitmap(bitmap)
        } else {
            Icon.createWithResource(context, context.applicationInfo.icon)
        }
    }

    /** The decoded photo, centre-cropped square, drawn into the safe zone of an opaque canvas. */
    private fun adaptiveBitmap(path: String): Bitmap? {
        val decoded = BitmapFactory.decodeFile(path) ?: return null
        val crop = minOf(decoded.width, decoded.height)
        val out = Bitmap.createBitmap(Shortcuts.CANVAS, Shortcuts.CANVAS, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.parseColor("#2C2C2E"))
        val side = Shortcuts.safeSide(Shortcuts.CANVAS)
        val pad = (Shortcuts.CANVAS - side) / 2
        val src =
            Rect(
                (decoded.width - crop) / 2,
                (decoded.height - crop) / 2,
                (decoded.width + crop) / 2,
                (decoded.height + crop) / 2,
            )
        canvas.drawBitmap(decoded, src, Rect(pad, pad, pad + side, pad + side), null)
        return out
    }
}
