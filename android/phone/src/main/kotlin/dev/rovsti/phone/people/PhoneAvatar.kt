package dev.rovsti.phone.people

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.design.theme.Sizes
import dev.rovsti.people.PersonAvatar
import dev.rovsti.people.ui.AvatarFace
import dev.rovsti.people.ui.ContactAvatar
import dev.rovsti.people.ui.LocalAvatarResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The contacts' own photos, by contact id and by number (as [normalize] spells it), for the Phone surfaces that know a
 * person only by one of the two (Recents, the keypad, statistics, notes). The resolver of `:people` prefers the own
 * photo, so a surface passes what this knows as `own` and the WhatsApp picture never covers a photo from the address
 * book.
 */
@Immutable
class ContactPhotoBook(
    private val byId: Map<Long, String> = emptyMap(),
    private val byNumber: Map<String, String> = emptyMap(),
    private val normalize: (String) -> String? = { null },
) {
    fun ofContact(id: Long?): String? = id?.let(byId::get)

    fun ofNumber(raw: String?): String? {
        if (raw.isNullOrBlank() || byNumber.isEmpty()) return null
        return byNumber[key(raw)]
    }

    private fun key(raw: String): String = normalize(raw) ?: digitsKey(raw)

    companion object {
        val Empty = ContactPhotoBook()

        fun of(
            contacts: List<WireContact>,
            normalize: (String) -> String?,
        ): ContactPhotoBook {
            val byId = HashMap<Long, String>()
            val byNumber = HashMap<String, String>()
            for (c in contacts) {
                val photo = c.photoUri?.takeIf { it.isNotBlank() } ?: continue
                byId[c.id] = photo
                for (n in c.numbers) byNumber[normalize(n.raw) ?: n.normalized ?: digitsKey(n.raw)] = photo
            }
            return ContactPhotoBook(byId, byNumber, normalize)
        }

        private fun digitsKey(raw: String): String = "+" + raw.filter(Char::isDigit)
    }
}

val LocalContactPhotos = staticCompositionLocalOf { ContactPhotoBook.Empty }

/** The book for [people], built off the main thread and rebuilt when the contacts change. */
@Composable
fun rememberContactPhotos(
    contacts: List<WireContact>?,
    normalize: (String) -> String?,
): ContactPhotoBook {
    val book by produceState(ContactPhotoBook.Empty, contacts) {
        value =
            if (contacts == null) {
                ContactPhotoBook.Empty
            } else {
                withContext(Dispatchers.Default) { ContactPhotoBook.of(contacts, normalize) }
            }
    }
    return book
}

/**
 * The avatar of a person on a Phone surface: the contact's own photo (given as [own], else found by [contactId] or
 * [number] in [LocalContactPhotos]), else their WhatsApp picture from the people cache, else initials (X.1d). A person
 * who is not a contact at all still gets the WhatsApp picture of their [number]. [name] empty draws the person glyph
 * of an unnamed number.
 */
@Composable
fun PhoneAvatar(
    name: String,
    number: String?,
    modifier: Modifier = Modifier,
    size: Dp = Sizes.avatar,
    colorKey: String = name,
    own: String? = null,
    contactId: Long? = null,
    onAura: Boolean = false,
    contentDescription: String? = null,
) {
    val photos = LocalContactPhotos.current
    val photo = own ?: photos.ofContact(contactId) ?: photos.ofNumber(number)
    ContactAvatar(
        name = name,
        face = AvatarFace(own = photo, numbers = listOfNotNull(number?.takeIf { it.isNotBlank() })),
        modifier = modifier,
        size = size,
        colorKey = colorKey,
        contentDescription = contentDescription,
        onAura = onAura,
    )
}

/** True while the people cache holds a WhatsApp picture for [number] (and no own photo is asked about here). */
@Composable
fun rememberWhatsAppPicture(number: String): Boolean {
    val resolver = LocalAvatarResolver.current ?: return false
    val version = resolver.version.collectAsState().value
    return remember(resolver, version, number) { resolver.resolve(null, number) is PersonAvatar.WhatsApp }
}
