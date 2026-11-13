package dev.rovsti.people

/**
 * What a surface draws for a person: their own contact photo, else their WhatsApp picture, else initials. A sealed
 * type so a screen cannot forget a case; [AvatarResolver] decides which one.
 */
sealed interface PersonAvatar {
    /** The photo of the address-book contact. [uri] is a content URI the app can open. */
    data class Own(
        val uri: String,
    ) : PersonAvatar

    /**
     * The WhatsApp picture, from the people cache. [uri] is a content or file URI; [pictureId] changes when the
     * picture does, so it is the image cache key (the crossfade of X.1 runs when it changes).
     */
    data class WhatsApp(
        val uri: String,
        val pictureId: String,
    ) : PersonAvatar

    /** Nothing to show: draw the monogram. */
    data object Initials : PersonAvatar
}
