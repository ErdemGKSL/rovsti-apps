package dev.rosty.core.model.wa

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The pure ops of the message actions (E6-WA R4, `rosty_core::wa_actions`). Offsets are UTF-16, as a Kotlin String.

/** `wa.mention_query`: the `@` the caret at [caret] is typing, if any. */
@Serializable
data class MentionQueryRequest(
    val text: String,
    val caret: Int,
)

@Serializable
data class MentionQuery(
    /** Where the `@` is. */
    val start: Int,
    /** What follows it up to the caret. */
    val query: String,
)

@Serializable
data class MentionQueryAnswer(
    @EncodeDefault(NEVER) val query: MentionQuery? = null,
)

/** A member picked in the composer: the range of the `@Name` it inserted and who it names. */
@Serializable
data class MentionPick(
    val start: Int,
    val end: Int,
    val jid: String,
)

/** `wa.mention_encode`: the composer's `@Name`s become `@<digits>` tokens. */
@Serializable
data class MentionEncodeRequest(
    val text: String,
    val picks: List<MentionPick> = emptyList(),
)

@Serializable
data class MentionEncoded(
    val text: String,
    val mentions: List<String> = emptyList(),
)

/** `wa.link_find`: the first link of a text. */
@Serializable
data class LinkFindRequest(
    val text: String,
)

@Serializable
data class FoundUrl(
    /** What to fetch (`https://` put in front of a bare `www.` link). */
    val url: String,
    val start: Int,
    val end: Int,
)

@Serializable
data class LinkFindAnswer(
    @EncodeDefault(NEVER) val found: FoundUrl? = null,
)

/** `wa.link_parse`: what the head of a page says about itself. [url] is the page after redirects. */
@Serializable
data class LinkParseRequest(
    val html: String,
    val url: String,
)

@Serializable
data class LinkMeta(
    @EncodeDefault(NEVER) val title: String? = null,
    @EncodeDefault(NEVER) val description: String? = null,
    /** The preview image, absolute, http(s). */
    @EncodeDefault(NEVER) val image: String? = null,
    /** The page's own URL (`og:url`). */
    @EncodeDefault(NEVER) val url: String? = null,
    @EncodeDefault(NEVER) val host: String? = null,
    /** How much of a page to read. */
    @SerialName("max_bytes") val maxBytes: Int = 512 * 1024,
) {
    /** Worth a card: a title or a description. */
    val isEmpty: Boolean get() = title == null && description == null
}

/** `wa.receipt_sections`. */
@Serializable
data class ReceiptSectionsRequest(
    val members: List<ReceiptMember>,
    val voice: Boolean = false,
)

@Serializable
enum class ReceiptSection {
    @SerialName("played")
    Played,

    @SerialName("read")
    Read,

    @SerialName("delivered")
    Delivered,

    @SerialName("waiting")
    Waiting,
}

@Serializable
data class ReceiptRow(
    val jid: String,
    /** When they got this far; null or 0: unknown. */
    @SerialName("at_ms") @EncodeDefault(NEVER) val atMs: Long? = null,
    @EncodeDefault(NEVER) val left: Boolean = false,
)

@Serializable
data class SectionRows(
    val section: ReceiptSection,
    val rows: List<ReceiptRow>,
)

@Serializable
data class ReceiptSectionsAnswer(
    val sections: List<SectionRows> = emptyList(),
)
