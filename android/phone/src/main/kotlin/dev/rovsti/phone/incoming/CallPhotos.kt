// Rovsti Project (C) 2026, GPL-3.0-or-later. Original code (not ported).
package dev.rovsti.phone.incoming

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart

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
 * else (a WhatsApp call, also one from a contact without a photo) the profile picture of [jid].
 */
@Composable
fun rememberCallPhoto(
    photos: CallPhotos,
    contactId: Long?,
    jid: String? = null,
): Any? {
    val photo by produceState<Any?>(null, photos, contactId, jid) {
        value = null
        val own = contactId?.let { photos.photoOf(it) }
        val wa = jid?.let { photos.photoOfJid(it) }
        when {
            // The contact's own photo first; without one, the WhatsApp picture of a WhatsApp call (F39, X.1).
            own != null && wa != null -> {
                combine(own, wa.onStart { emit(null) }) { mine, theirs -> mine?.takeIf(String::isNotBlank) ?: theirs }
                    .collect { value = it }
            }

            own != null -> {
                own.collect { value = it?.takeIf(String::isNotBlank) }
            }

            wa != null -> {
                wa.collect { value = it }
            }
        }
    }
    return photo
}
