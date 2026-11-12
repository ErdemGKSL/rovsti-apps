package dev.rovsti.phone.details.photo

import android.content.ContentUris
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Rect
import dev.rovsti.design.media.PhotoSource

/**
 * The picture behind the avatar of the Person and Call details sheets (E5.13): the contact it belongs to, the small
 * [thumb] the avatar already draws (null for a contact without a photo) and where it [source]s from. Only a
 * [PhotoSource.Contact] photo can be edited; the WhatsApp fallback of X.1 is shown read-only through the same viewer.
 */
@Immutable
data class PersonPhoto(
    val contactId: Long,
    val thumb: String?,
    val source: PhotoSource = PhotoSource.Contact,
) {
    val hasPhoto: Boolean get() = thumb != null

    /** The full-size picture of the contact (`display_photo`); the avatar shows the thumbnail of the same photo. */
    val full: Uri
        get() =
            Uri.withAppendedPath(
                ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
                ContactsContract.Contacts.Photo.DISPLAY_PHOTO,
            )
}

/**
 * What a tap on a sheet's avatar does. [anchor] reads the avatar's rectangle in window px at the moment it is asked
 * (the viewer flies out of it and back into it). With no photo the opener offers Choose / Take instead.
 */
fun interface PersonPhotoOpener {
    fun open(
        photo: PersonPhoto,
        name: String,
        anchor: () -> Rect?,
    )
}

/** The opener the sheets use; null in a harness without one (the avatar is then not tappable). */
val LocalPersonPhotoOpener = compositionLocalOf<PersonPhotoOpener?> { null }
