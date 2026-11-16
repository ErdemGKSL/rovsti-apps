package dev.rosty.messages.inbox

import android.content.res.Resources
import dev.rosty.core.model.wa.MediaKind
import dev.rosty.messages.R

/**
 * The words of the inbox rows, from `strings.xml` (English and Turkish). An interface so the pure [InboxBuilder] is
 * tested on the JVM with [EnglishInboxWords].
 */
interface InboxWords {
    fun you(body: String): String

    fun sender(
        name: String,
        body: String,
    ): String

    fun draft(text: String): String

    fun deleted(mine: Boolean): String

    fun location(
        live: Boolean,
        place: String?,
    ): String

    fun contact(name: String): String

    /** A voice message, with its length when known. */
    fun voice(seconds: Int?): String

    fun media(
        kind: MediaKind,
        caption: String?,
    ): String

    fun unsupported(): String

    fun edited(snippet: String): String

    /** What the other side of a WhatsApp chat does right now; [name] in a group. */
    fun chatState(
        name: String?,
        recording: Boolean,
    ): String =
        when {
            name == null -> if (recording) "recording audio…" else "typing…"
            recording -> "$name is recording audio…"
            else -> "$name is typing…"
        }

    /** An SMS group without a name: its addresses. */
    fun joined(addresses: List<String>): String = addresses.joinToString(", ")
}

/** The words of [InboxWords] in the app's resources. */
class ResourceInboxWords(
    private val res: Resources,
) : InboxWords {
    override fun you(body: String) = res.getString(R.string.inbox_you, body)

    override fun sender(
        name: String,
        body: String,
    ) = res.getString(R.string.inbox_sender, name, body)

    override fun draft(text: String) = res.getString(R.string.inbox_draft, text)

    override fun deleted(mine: Boolean) = res.getString(if (mine) R.string.wa_you_deleted else R.string.wa_deleted)

    override fun location(
        live: Boolean,
        place: String?,
    ) = res.getString(
        if (live) R.string.wa_live_location else R.string.wa_location,
        place?.takeIf { it.isNotBlank() } ?: res.getString(R.string.wa_location_default),
    )

    override fun contact(name: String) = res.getString(R.string.wa_contact, name)

    override fun voice(seconds: Int?) =
        seconds?.let { res.getString(R.string.wa_voice_length, duration(it)) } ?: res.getString(R.string.wa_voice)

    override fun media(
        kind: MediaKind,
        caption: String?,
    ): String {
        val label =
            res.getString(
                when (kind) {
                    MediaKind.Image -> R.string.wa_photo
                    MediaKind.Video -> R.string.wa_video
                    MediaKind.Audio -> R.string.wa_audio
                    MediaKind.Document -> R.string.wa_document
                    MediaKind.Sticker -> R.string.wa_sticker
                },
            )
        return caption?.takeIf { it.isNotBlank() }?.let { "$label · $it" } ?: label
    }

    override fun unsupported() = res.getString(R.string.wa_unsupported)

    override fun edited(snippet: String) = res.getString(R.string.wa_edited_snippet, snippet)

    override fun chatState(
        name: String?,
        recording: Boolean,
    ): String =
        when {
            name == null -> res.getString(if (recording) R.string.wa_thread_recording else R.string.wa_thread_typing)
            recording -> res.getString(R.string.wa_thread_recording_by, name)
            else -> res.getString(R.string.wa_thread_typing_by, name)
        }
}

/** "0:42", "12:05": minutes and seconds of a voice note. */
internal fun duration(seconds: Int): String =
    "%d:%02d".format(
        seconds / SECONDS_PER_MINUTE,
        seconds % SECONDS_PER_MINUTE,
    )

private const val SECONDS_PER_MINUTE = 60
