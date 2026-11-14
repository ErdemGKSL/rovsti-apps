package dev.rosty.messages.inbox

import dev.rosty.core.model.wa.MediaKind

/** The English words, for JVM tests (the same text as `values/strings.xml`). */
object EnglishInboxWords : InboxWords {
    override fun you(body: String) = "You: $body"

    override fun sender(
        name: String,
        body: String,
    ) = "$name: $body"

    override fun draft(text: String) = "Draft: $text"

    override fun deleted(mine: Boolean) = if (mine) "You deleted this message" else "This message was deleted"

    override fun location(
        live: Boolean,
        place: String?,
    ) = (if (live) "Live location · " else "Location · ") + (place?.takeIf { it.isNotBlank() } ?: "Location")

    override fun contact(name: String) = "Contact · $name"

    override fun voice(seconds: Int?) = seconds?.let { "Voice message (${duration(it)})" } ?: "Voice message"

    override fun media(
        kind: MediaKind,
        caption: String?,
    ): String {
        val label =
            when (kind) {
                MediaKind.Image -> "Photo"
                MediaKind.Video -> "Video"
                MediaKind.Audio -> "Audio"
                MediaKind.Document -> "Document"
                MediaKind.Sticker -> "Sticker"
            }
        return caption?.takeIf { it.isNotBlank() }?.let { "$label · $it" } ?: label
    }

    override fun unsupported() = "Unsupported message"

    override fun edited(snippet: String) = "$snippet · Edited"
}
