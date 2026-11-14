package dev.rovsti.platform.notify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Icon
import android.net.Uri
import android.provider.ContactsContract
import dev.rovsti.platform.identity.CallerLookup

/** The person a call notification is about: the contact's name and a round photo for the large icon, when known. */
data class NotifyPerson(
    val name: String?,
    val photo: Icon?,
)

/**
 * Looks a number up for a notification (the app's caller-ID, [CallerLookup]: the same E.164 matching as Recents, F39),
 * then the contact's thumbnail. Every read can fail on a missing grant or a missing contact; the answer is then just
 * less decorated, never an error. Blocking: off the main thread.
 */
object NotifyPhotos {
    private const val SIDE = 192

    fun person(
        context: Context,
        number: String,
    ): NotifyPerson {
        val who =
            try {
                CallerLookup.of(context).identify(number)
            } catch (_: RuntimeException) {
                null
            } ?: return NotifyPerson(null, null)
        return NotifyPerson(who.title, photoOf(context, who))
    }

    /**
     * The round photo for [who]: the contact's own photo first, else (a stranger on WhatsApp, or a contact without a
     * photo) their WhatsApp picture from the people cache, else null (X.1d). Blocking: off the main thread.
     */
    fun photoOf(
        context: Context,
        who: dev.rovsti.platform.identity.CallerIdentity,
        contactPhoto: (Long) -> Icon? = { icon(context, it) },
    ): Icon? = who.contactId?.let(contactPhoto) ?: who.waPhotoUri?.let { icon(context, Uri.parse(it)) }

    /** The round picture behind [uri] (a `file:` or `content:` URI); null when it cannot be read or decoded. */
    fun icon(
        context: Context,
        uri: Uri,
    ): Icon? =
        try {
            context.contentResolver
                .openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it) }
                ?.let { Icon.createWithBitmap(circle(it)) }
        } catch (_: java.io.IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    /** The round photo of contact [contactId] for a notification; null without one. */
    fun icon(
        context: Context,
        contactId: Long,
    ): Icon? =
        try {
            val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId.toString())
            ContactsContract.Contacts
                .openContactPhotoInputStream(context.contentResolver, uri, false)
                ?.use { BitmapFactory.decodeStream(it) }
                ?.let { Icon.createWithBitmap(circle(it)) }
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    /** A square centre crop of [source] drawn into a circle of [SIDE] pixels. */
    internal fun circle(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        val square =
            Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, SIDE, SIDE, true)
        val out = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
        Canvas(out).drawCircle(SIDE / 2f, SIDE / 2f, SIDE / 2f, paint)
        return out
    }
}
