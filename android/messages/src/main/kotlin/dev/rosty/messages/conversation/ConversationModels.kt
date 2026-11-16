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
    /** The part's content type, when known (a vCard opens the contact card sheet, M26). */
    val mime: String? = null,
    /** What it is drawn as; null keeps the E6.3 rule ([isImage] with a [model]: a picture, else a label row). */
    val kind: BubbleMediaKind? = null,
    /** The second line of a file row ("PDF · 1.2 MB"), the length of a voice note ("0:42"). */
    val detail: String? = null,
    /** Being downloaded or uploaded right now. */
    val busy: Boolean = false,
    /** The full file is on the phone (a picture shows it, a file can be saved or shared at once). */
    val local: Boolean = false,
    /**
     * A video sent as a GIF (WhatsApp `gif_playback`) whose file is on the phone: the bubble loops it, muted, over its
     * thumbnail. Null for everything else.
     */
    val loop: java.io.File? = null,
    /** A view-once message (E6-WA R3): never saved, shared or forwarded. */
    val viewOnce: Boolean = false,
)

/**
 * Where the pictures of the bubbles on screen are, in window pixels, by bubble key: the viewer flies out of the
 * tapped one and back into it (the shared-element flight of an overlay, which a `SharedTransitionScope` cannot carry).
 * A bubble that scrolled away is not in it; the viewer then closes with its fade.
 */
class MediaAnchors {
    private val rects = HashMap<String, androidx.compose.ui.geometry.Rect>()

    operator fun get(key: String): androidx.compose.ui.geometry.Rect? = rects[key]

    fun put(
        key: String,
        rect: androidx.compose.ui.geometry.Rect,
    ) {
        if (rect.width > 0f && rect.height > 0f) rects[key] = rect else rects.remove(key)
    }

    fun remove(key: String) {
        rects.remove(key)
    }
}

/** How a [BubbleMedia] is drawn (E6.4b). */
enum class BubbleMediaKind {
    /** A picture or sticker: the thumbnail, then the picture; a tap opens the viewer. */
    Picture,

    /** A video: its thumbnail under a play mark; a tap downloads and opens it. */
    Video,

    /** A voice note or audio file: play/pause, the scrub bar, the speed pill. */
    Voice,

    /** Any other file: name, size, Save and Share. */
    File,
}

/** What the buttons of a file row do. */
enum class MediaAction { Save, Share, Forward }

/** The one voice note that is loaded in the player (M17); every other note shows its idle row. */
@Immutable
data class VoiceUi(
    /** The bubble key of the note. */
    val key: String,
    val playing: Boolean,
    /** Fraction played, 0..1. */
    val progress: Float,
    /** "0:12 / 0:42". */
    val timeLabel: String,
)

/** The message being answered, shown above the composer (M31). */
@Immutable
data class ReplyUi(
    val key: String,
    val author: String,
    val text: String,
)

/** A picked or pasted file waiting above the composer for Send. */
@Immutable
data class AttachmentUi(
    val name: String,
    /** "1.2 MB". */
    val detail: String,
    /** The picture itself when it is one. */
    val model: Any? = null,
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
    /** Who wrote a group message, for the sender card (M50): the WhatsApp JID. */
    val senderId: String? = null,
    /** A swipe to the right answers this bubble (M31). */
    val canReply: Boolean = false,
    /** E6.13: starred (M10 WhatsApp, M23 SMS): a small star beside the time. */
    val starred: Boolean = false,
    /** E6.13: the clock of the reminder armed on it ("18:05", M33): a bell and the clock beside the time. */
    val reminder: String? = null,
    /** E6.13: the id stars and reminders know the message by when it is not [key] (a WhatsApp message I sent). */
    val markId: String? = null,
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
    /** The quoted composer bar (M31); null: a plain message. */
    val reply: ReplyUi? = null,
    /** The file that goes out with the next Send. */
    val attachment: AttachmentUi? = null,
    /** The label of the voice speed pill ("1×", "1.5×", "2×"). */
    val voiceSpeed: String = "",
)
