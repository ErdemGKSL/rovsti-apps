package dev.rovsti.platform.notify

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Icon
import dev.rovsti.platform.R
import java.util.concurrent.ConcurrentHashMap

/**
 * What a notification is about. Each kind has a white glyph for the status bar (Android draws the small icon from its
 * alpha channel only), an accent colour that tints that glyph and the app name, and a full-colour glass picture for the
 * large icon.
 */
enum class NotificationKind(
    val small: Int,
    val accent: Int,
    val large: Int,
) {
    CALL_INCOMING(R.drawable.ic_notif_call_incoming, 0xFF34C759.toInt(), R.drawable.ic_notif_large_call_incoming),
    CALL_ONGOING(R.drawable.ic_notif_call_ongoing, 0xFF1FAE74.toInt(), R.drawable.ic_notif_large_call_ongoing),
    CALL_MISSED(R.drawable.ic_notif_call_missed, 0xFFFF3B30.toInt(), R.drawable.ic_notif_large_call_missed),
    MESSAGE(R.drawable.ic_notif_message, 0xFF0A84FF.toInt(), R.drawable.ic_notif_large_message),
    WA_MESSAGE(R.drawable.ic_notif_wa_message, 0xFF25B860.toInt(), R.drawable.ic_notif_large_wa_message),
    WA_CALL(R.drawable.ic_notif_wa_call, 0xFF14A88C.toInt(), R.drawable.ic_notif_large_wa_call),
    BIRTHDAY(R.drawable.ic_notif_birthday, 0xFFFF2D78.toInt(), R.drawable.ic_notif_large_birthday),
    REMINDER(R.drawable.ic_notif_reminder, 0xFFFF9500.toInt(), R.drawable.ic_notif_large_reminder),
    TRIP(R.drawable.ic_notif_trip, 0xFF5856D6.toInt(), R.drawable.ic_notif_large_trip),
    ARRIVAL_ALARM(R.drawable.ic_notif_arrival_alarm, 0xFFFF5317.toInt(), R.drawable.ic_notif_large_arrival_alarm),
}

/**
 * Dresses a notification for its [NotificationKind]: glyph, accent and large icon. A person's photo always wins the
 * large icon; the coloured picture only stands in when there is none.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/NotificationIcon.kt
 */
object NotificationIcon {
    /** Corner radius of the large icon as a fraction of its side (the launcher icons' squircle, roughly). */
    private const val CORNER = 0.225f

    private val largeIcons = ConcurrentHashMap<NotificationKind, Icon>()

    fun small(
        context: Context,
        kind: NotificationKind,
    ): Icon = Icon.createWithResource(context, kind.small)

    fun apply(
        builder: Notification.Builder,
        context: Context,
        kind: NotificationKind,
        photo: Icon? = null,
    ): Notification.Builder {
        builder.setSmallIcon(small(context, kind)).setColor(kind.accent)
        (photo ?: large(context, kind))?.let { builder.setLargeIcon(it) }
        return builder
    }

    /** The kind's coloured picture with rounded corners, decoded once per process; null when it cannot be decoded. */
    fun large(
        context: Context,
        kind: NotificationKind,
    ): Icon? =
        largeIcons[kind] ?: BitmapFactory.decodeResource(context.resources, kind.large)?.let { source ->
            val icon = Icon.createWithBitmap(rounded(source))
            largeIcons.putIfAbsent(kind, icon) ?: icon
        }

    private fun rounded(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
        val radius = minOf(source.width, source.height) * CORNER
        Canvas(out).drawRoundRect(
            RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()),
            radius,
            radius,
            paint,
        )
        return out
    }
}
