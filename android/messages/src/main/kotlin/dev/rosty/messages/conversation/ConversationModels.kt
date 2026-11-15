package dev.rosty.messages.conversation

import androidx.compose.runtime.Immutable
import dev.rosty.messages.inbox.RowAvatar
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** How far one of my messages got (SMS delivery reports, WhatsApp ticks, the offline outbox). */
enum class BubbleState { None, Waiting, Sending, Sent, Delivered, Read, Played, Failed }

/** A picture or file of a bubble: a local file (MMS part, cached WhatsApp media) or a thumbnail model. */
@Immutable
data class BubbleMedia(
    val model: Any?,
    val label: String,
    val isImage: Boolean,
    /** Width over height when known, so the bubble keeps its size before the picture loads. */
    val aspect: Float? = null,
)

/** The quoted message a reply shows above its text. */
@Immutable
data class BubbleQuote(
    val author: String,
    val text: String,
    val targetKey: String?,
)

/**
 * One bubble, the same for SMS/MMS and WhatsApp so both threads share [ConversationScreen]. Built by the view models;
 * the screen never maps or formats.
 */
@Immutable
data class BubbleUi(
    val key: String,
    val outgoing: Boolean,
    val text: String,
    /** "14:05", or the state when it replaces the time ("Sending…", "Not sent · Tap to retry"). */
    val meta: String,
    val state: BubbleState = BubbleState.None,
    val atMs: Long,
    val media: ImmutableList<BubbleMedia> = persistentListOf(),
    /** A group chat: who wrote it (not on my own bubbles), and whether it starts a run of that sender. */
    val sender: String? = null,
    val quote: BubbleQuote? = null,
    val forwarded: Boolean = false,
    val edited: Boolean = false,
    /** Deleted for everyone or by me: the bubble says so; the content is kept for "Show content". */
    val deleted: Boolean = false,
    val reactions: String? = null,
    /** True for the first bubble of a run from the same side (more space above it, the tail). */
    val firstOfRun: Boolean = true,
)

/** A row of the conversation list: a day separator, the unread divider or a bubble. Newest first (reverse layout). */
@Immutable
sealed interface ConversationItem {
    val key: String

    @Immutable
    data class Day(
        override val key: String,
        val label: String,
    ) : ConversationItem

    @Immutable
    data class Divider(
        override val key: String,
        val label: String,
    ) : ConversationItem

    @Immutable
    data class Bubble(
        val bubble: BubbleUi,
    ) : ConversationItem {
        override val key: String get() = bubble.key
    }
}

/** What the composer can do right now. */
@Immutable
sealed interface ComposerMode {
    /** The normal field and Send. [simLabel] is the SIM chip of a dual-SIM phone (null on one SIM or WhatsApp). */
    @Immutable
    data class Ready(
        val placeholder: String,
        val simLabel: String? = null,
        val counter: String? = null,
    ) : ComposerMode

    /** Sending needs the default-SMS role: the composer is the "Make default" row instead. */
    data object NeedsRole : ComposerMode

    /** A thread that cannot take a reply (a sender like "BANK", a broadcast). */
    data class ReadOnly(
        val reason: String,
    ) : ComposerMode
}

/** The whole page state of one conversation. */
@Immutable
data class ConversationUi(
    val title: String = "",
    val subtitle: String? = null,
    val avatar: RowAvatar = RowAvatar(),
    val items: ImmutableList<ConversationItem> = persistentListOf(),
    val loaded: Boolean = false,
    val hasOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    val composer: ComposerMode = ComposerMode.Ready(""),
    /** A line under the header: "Waiting for connection", "This conversation is in Spam." */
    val notice: String? = null,
    val error: String? = null,
)
