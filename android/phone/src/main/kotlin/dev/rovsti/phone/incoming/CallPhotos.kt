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

    /** The profile picture of a WhatsApp peer ([jid]), as the Coil model `WaAvatar(jid)`; absent until it is stored. */
    fun photoOfJid(jid: String): Flow<Any?> = emptyFlow()

    companion object {
        val None = CallPhotos { emptyFlow() }
    }
}

/**
 * The photo model of a caller, null until it is known and for a caller without one: the contacts photo of [contactId],
 * else (a WhatsApp call) the profile picture of [jid].
 */
@Composable
fun rememberCallPhoto(
    photos: CallPhotos,
    contactId: Long?,
    jid: String? = null,
): Any? {
    val photo by produceState<Any?>(null, photos, contactId, jid) {
        value = null
        if (contactId != null) {
            photos.photoOf(contactId).collect { value = it?.takeIf(String::isNotBlank) }
        } else if (jid != null) {
            photos.photoOfJid(jid).collect { value = it }
        }
    }
    return photo
}
