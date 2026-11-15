package dev.rosty.messages.notify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.provider.ContactsContract
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.inbox.numberOfJid
import dev.rosty.people.PersonAvatar
import dev.rosty.platform.notify.NotificationSpec
import dev.rosty.platform.sms.SmsQuery
import dev.rosty.shell.HasContainer

/** The picture of the person a notification is from, already round; null draws the app's own icon. */
fun interface NotificationAvatars {
    fun load(
        context: Context,
        tag: String,
    ): Bitmap?
}

/**
 * The avatar of a one-to-one conversation, by the rule of every other avatar in the app (own contact photo, else the
 * WhatsApp picture of the people cache). It runs on a receiver's worker thread, possibly in a process with no UI: the
 * contact photo needs only the contacts provider; the people cache is read only when the app already filled it. A
 * group has no single person and gets none.
 */
object ContactNotificationAvatars : NotificationAvatars {
    override fun load(
        context: Context,
        tag: String,
    ): Bitmap? =
        try {
            val number = numbersOf(context, tag).singleOrNull()
            val source = number?.let { contactPhoto(context, it) } ?: peoplePhoto(context, tag, number)
            source?.let(::round)
        } catch (_: RuntimeException) {
            null
        }

    private fun numbersOf(
        context: Context,
        tag: String,
    ): List<String> {
        NotificationSpec.smsThreadId(tag)?.let { return SmsQuery.addressesFor(context, it) }
        return listOfNotNull(NotificationSpec.waChat(tag)?.let(::numberOfJid))
    }

    private fun contactPhoto(
        context: Context,
        number: String,
    ): Bitmap? {
        if (number.isBlank()) return null
        val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val photo =
            context.contentResolver
                .query(lookup, arrayOf(ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        return photo?.let { decode(context, it) }
    }

    private fun peoplePhoto(
        context: Context,
        tag: String,
        number: String?,
    ): Bitmap? {
        val container = (context.applicationContext as? HasContainer<*>)?.container as? MessagesContainer ?: return null
        val resolver = container.avatarResolver
        val jid = NotificationSpec.waChat(tag)
        val avatar =
            (number?.let { resolver.resolve(null, it) } as? PersonAvatar.WhatsApp)
                ?: (jid?.let { resolver.resolveJid(it) } as? PersonAvatar.WhatsApp)
        return avatar?.let { decode(context, it.uri) }
    }

    private fun decode(
        context: Context,
        uri: String,
    ): Bitmap? =
        try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use(BitmapFactory::decodeStream)
        } catch (_: java.io.IOException) {
            null
        }

    /** A centre-cropped circle of at most [SIDE] px. */
    internal fun round(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height, SIDE).coerceAtLeast(1)
        val scale = side.toFloat() / minOf(source.width, source.height)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(
            Matrix().apply {
                setScale(scale, scale)
                postTranslate((side - source.width * scale) / 2f, (side - source.height * scale) / 2f)
            },
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        Canvas(out).drawCircle(side / 2f, side / 2f, side / 2f, paint)
        return out
    }

    private const val SIDE = 192
}
