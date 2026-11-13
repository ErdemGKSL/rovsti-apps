package dev.rovsti.platform.identity

import dev.rovsti.core.model.search.WireContact

/**
 * Who a number is, as every Phone surface shows it: one shape whatever the source. A device contact fills [contactId],
 * [lookupKey], [name] and [photoUri]; a WhatsApp source (the Contacts app's provider, or the gateway sync held in memory)
 * adds [waPushName] and [waPhotoUri] (X.1). [name] is null when no contact owns the number.
 */
data class CallerIdentity(
    val contactId: Long? = null,
    /** `LOOKUP_KEY`: survives raw-contact merges, unlike [contactId]. */
    val lookupKey: String? = null,
    /** The contact's name in the user's name order. */
    val name: String? = null,
    /** The contact's photo (thumbnail URI). */
    val photoUri: String? = null,
    val starred: Boolean = false,
    /** The name the person gave themselves on WhatsApp. */
    val waPushName: String? = null,
    /** The WhatsApp profile picture (a file or content URI), for a number no contact photo covers. */
    val waPhotoUri: String? = null,
) {
    /** True when a device contact owns the number. */
    val isContact: Boolean get() = contactId != null

    /** The title for the number: the contact, else the WhatsApp name, else null (show the formatted number). */
    val title: String? get() = name?.takeIf { it.isNotBlank() } ?: waPushName?.takeIf { it.isNotBlank() }

    /** [other]'s fields where this one has none: the first source's contact wins, later ones add what it lacks. */
    fun orElse(other: CallerIdentity): CallerIdentity =
        CallerIdentity(
            contactId = contactId ?: other.contactId,
            lookupKey = if (contactId != null) lookupKey else other.lookupKey,
            name = if (contactId != null) name else other.name,
            photoUri = if (contactId != null) photoUri else other.photoUri,
            starred = if (contactId != null) starred else other.starred,
            waPushName = waPushName ?: other.waPushName,
            waPhotoUri = waPhotoUri ?: other.waPhotoUri,
        )

    companion object {
        /** The identity of a device contact named [name]. */
        fun of(
            contact: WireContact,
            name: String = contact.displayName,
        ): CallerIdentity =
            CallerIdentity(
                contactId = contact.id,
                lookupKey = contact.lookupKey,
                name = name.takeIf { it.isNotBlank() } ?: contact.displayName,
                photoUri = contact.photoUri,
                starred = contact.starred,
            )
    }
}
