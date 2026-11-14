package dev.rovsti.people

/**
 * Own photo first, then the WhatsApp picture of the same person, then initials. The WhatsApp picture is a fallback
 * only: a contact that has a photo of its own never shows another one, so adding a photo in the address book wins
 * immediately.
 *
 * A person is found by number (any spelling Android can normalize) or by JID; a number that is no contact at all
 * still gets the WhatsApp picture when the cache knows it (an unknown caller in Recents, a chat with a stranger).
 * A stale picture (the id changed, the new file is not in yet) still shows: the old picture stays until the new one is
 * in, like the avatar loader does.
 */
class AvatarResolver(
    private val index: PeopleIndex,
) {
    /** Counts up when the people cache changed; a screen reads it so its avatars recompose with the new picture. */
    val version: kotlinx.coroutines.flow.StateFlow<Long> get() = index.version

    /** The avatar of a contact or caller with [ownPhoto] (null: none) and [numbers] (any order, any spelling). */
    fun resolve(
        ownPhoto: String?,
        numbers: List<String>,
    ): PersonAvatar {
        if (!ownPhoto.isNullOrBlank()) return PersonAvatar.Own(ownPhoto)
        for (number in numbers) {
            whatsApp(index.byNumber(number))?.let { return it }
        }
        return PersonAvatar.Initials
    }

    fun resolve(
        ownPhoto: String?,
        number: String?,
    ): PersonAvatar = resolve(ownPhoto, listOfNotNull(number))

    /** A chat or participant known by JID only (Messages). */
    fun resolveJid(
        jid: String,
        ownPhoto: String? = null,
    ): PersonAvatar {
        if (!ownPhoto.isNullOrBlank()) return PersonAvatar.Own(ownPhoto)
        return whatsApp(index.byJid(jid)) ?: PersonAvatar.Initials
    }

    private fun whatsApp(person: IndexedPerson?): PersonAvatar? {
        val id = person?.row?.pictureId ?: return null
        val uri = person.pictureUri ?: return null
        return PersonAvatar.WhatsApp(uri, id)
    }
}
