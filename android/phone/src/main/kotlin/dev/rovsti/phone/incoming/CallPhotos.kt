// Rovsti Project (C) 2026, GPL-3.0-or-later. Original code (not ported).
package dev.rovsti.phone.incoming

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import dev.rovsti.people.AvatarResolver
import dev.rovsti.people.PersonAvatar
import dev.rovsti.people.ui.LocalAvatarResolver
import dev.rovsti.phone.people.ContactPhotoBook
import dev.rovsti.phone.people.LocalContactPhotos
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
 * The picture of a caller that is known without waiting (X.1d): the contact's own photo from [book], else the WhatsApp
 * picture the people cache holds for the caller's [number] or [jid]. Memory only: it never suspends, never reads the
 * disk or Rust, so the incoming screen draws it on its first frame. Null when nothing is known yet.
 */
fun knownCallPhoto(
    resolver: AvatarResolver?,
    book: ContactPhotoBook,
    contactId: Long?,
    number: String?,
    jid: String?,
): String? {
    book.ofContact(contactId)?.let { return it }
    book.ofNumber(number)?.let { return it }
    val numbers = listOfNotNull(number?.takeIf { it.isNotBlank() })
    val byNumber = resolver?.resolve(null, numbers) as? PersonAvatar.WhatsApp
    val found = byNumber ?: jid?.let { resolver?.resolveJid(it) as? PersonAvatar.WhatsApp }
    return found?.uri
}

/**
 * The photo model of a caller, null until it is known and for a caller without one. In order: what memory knows at once
 * ([knownCallPhoto]: the contact's photo, else the WhatsApp picture of the number), then the contacts photo of
 * [contactId] as it loads, then (a WhatsApp call) the profile picture of [jid] as the avatar cache stores it.
 */
@Composable
fun rememberCallPhoto(
    photos: CallPhotos,
    contactId: Long?,
    jid: String? = null,
    number: String? = null,
): Any? {
    val resolver = LocalAvatarResolver.current
    val book = LocalContactPhotos.current
    val version = resolver?.version?.collectAsState()?.value ?: 0L
    val known =
        remember(
            resolver,
            version,
            book,
            contactId,
            number,
            jid,
        ) { knownCallPhoto(resolver, book, contactId, number, jid) }
    val own by produceState<String?>(null, photos, contactId) {
        value = null
        contactId?.let { photos.photoOf(it) }?.collect { value = it?.takeIf(String::isNotBlank) }
    }
    val wa by produceState<Any?>(null, photos, jid) {
        value = null
        jid?.let { photos.photoOfJid(it) }?.collect { value = it }
    }
    return own ?: known ?: wa
}
