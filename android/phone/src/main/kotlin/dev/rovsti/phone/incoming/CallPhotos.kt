// Rovsti Project (C) 2026, GPL-3.0-or-later. Original code (not ported).
package dev.rovsti.phone.incoming

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Where the call screens get the photo of a caller: the contacts provider's photo URI for a contact id (WhatsApp
 * profile pictures join through `WaAvatars` with the WhatsApp call story). A contact without a photo emits `null`.
 */
fun interface CallPhotos {
    fun photoOf(contactId: Long): Flow<String?>

    companion object {
        val None = CallPhotos { emptyFlow() }
    }
}

/** The photo model of [contactId], null until it is known and for a caller without one. */
@Composable
fun rememberCallPhoto(
    photos: CallPhotos,
    contactId: Long?,
): String? {
    val photo by produceState<String?>(null, photos, contactId) {
        value = null
        val id = contactId ?: return@produceState
        photos.photoOf(id).collect { value = it?.takeIf(String::isNotBlank) }
    }
    return photo
}
