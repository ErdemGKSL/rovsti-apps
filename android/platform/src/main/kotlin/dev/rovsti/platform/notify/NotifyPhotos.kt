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
import dev.rovsti.platform.contacts.ContactsQuery

/** The person a call notification is about: the contact's name and a round photo for the large icon, when known. */
data class NotifyPerson(
    val name: String?,
    val photo: Icon?,
)

/**
 * Looks a number up for a notification (`PhoneLookup`, then the contact's thumbnail). Every read can fail on a missing
 * grant or a missing contact; the answer is then just less decorated, never an error.
 */
object NotifyPhotos {
    private const val SIDE = 192

    fun person(
        context: Context,
        number: String,
    ): NotifyPerson {
        val match = ContactsQuery.lookup(context, number) ?: return NotifyPerson(null, null)
        return NotifyPerson(match.displayName.takeIf { it.isNotBlank() }, thumbnail(context, match.contactId))
    }

    private fun thumbnail(
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
