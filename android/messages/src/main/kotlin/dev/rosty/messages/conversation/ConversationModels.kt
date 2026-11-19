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
    /** The bars of a voice note (0..1 each, the waveform WhatsApp carries); empty: the plain bar. */
    val waveform: ImmutableList<Float> = persistentListOf(),
    /**
     * A video sent as a GIF (WhatsApp `gif_playback`) whose file is on the phone: the bubble loops it, muted, over its
     * thumbnail. Null for everything else.
     */
    val loop: java.io.File? = null,
    /** A view-once message (E6-WA R3): never saved, shared or forwarded. */
    val viewOnce: Boolean = false,
    /**
     * An animated sticker whose file is on the phone (E6-WA R3): the bubble animates it with the platform decoder
     * ([dev.rosty.messages.viewer.AnimatedSticker]). Null for everything else.
     */
    val animated: java.io.File? = null,
    /** The pixel size the message names (a sticker is drawn in its aspect); null when unknown. */
    val width: Int? = null,
    val height: Int? = null,
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

    /**
     * A sticker (E6-WA R3): drawn at sticker size with no bubble around it, the time in a small pill over its corner; a
     * tap does nothing.
     */
    Sticker,

    /**
     * What this device cannot show (E6-WA R3): a view-once message someone sent (only the phone opens it), or one I
     * sent as view once. The glyph and [BubbleMedia.label]; no download, no viewer, no Save, Share or Forward.
     */
    Unavailable,
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

/** Where the voice note being recorded in the composer is (E6-WA R3). */
enum class RecordPhase {
    /** The finger holds the microphone: releasing sends, sliding left cancels, sliding up locks. */
    Holding,

    /** Recording hands-free after the slide up: Stop, Delete and Send. */
    Locked,

    /** Stopped: the take can be played before it is sent or deleted. */
    Review,
}

/** The voice note being recorded; null while the composer shows the microphone. */
@Immutable
data class RecordUi(
    val phase: RecordPhase,
    /** "0:07": the length of the take (while recording: so far). */
    val elapsed: String,
    /** The input level now, 0..1 (while recording). */
    val level: Float = 0f,
    /** The bars of the take, 0..1 each ([RecordPhase.Review]). */
    val bars: ImmutableList<Float> = persistentListOf(),
    /** The preview plays ([RecordPhase.Review]). */
    val playing: Boolean = false,
    /** Fraction of the preview played, 0..1. */
    val progress: Float = 0f,
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
    /** E6-WA R4: "Forwarded many times" instead of "Forwarded". */
    val forwardedMany: Boolean = false,
    /** E6-WA R4: ranges of [text] drawn as names (`@Anna`), UTF-16. */
    val marks: ImmutableList<TextMark> = persistentListOf(),
    /** E6-WA R4: the card of the link in the text. */
    val link: LinkCardUi? = null,
    /** E6-WA R4: a line of the conversation ("Disappearing messages turned on"): drawn centred, no bubble, no menu. */
    val notice: Boolean = false,
    /** E6-WA R5: a poll, a place or contact cards, drawn by the page's [ConversationActions.richCard]. */
    val rich: RichCardUi? = null,
)

/** What a rich bubble shows (E6-WA R5); every string is ready to draw. */
@Immutable
sealed interface RichCardUi {
    /** A poll: tap an option to vote ([single]: one answer, a tap moves the vote; else a tap toggles it). */
    @Immutable
    data class Poll(
        val messageId: String,
        val question: String,
        /** "Select one" or "Select one or more". */
        val hint: String,
        val single: Boolean,
        val options: ImmutableList<PollOptionUi>,
        /** "3 votes", or "No votes yet". */
        val total: String,
        /** Votes can be sent (a stored poll, not one of mine still in the outbox). */
        val canVote: Boolean,
    ) : RichCardUi

    /** A place: its map is painted from tiles by the page ([latitude], [longitude]). */
    @Immutable
    data class Place(
        val title: String,
        val subtitle: String?,
        /** "Live location · last update shown", or null for a place. */
        val live: String?,
        val latitude: Double,
        val longitude: Double,
        val geoUri: String,
    ) : RichCardUi

    /** One or several contact cards. */
    @Immutable
    data class Contacts(
        val cards: ImmutableList<ContactCardUi>,
    ) : RichCardUi
}

/** One option of a poll bubble: its share of the voters as a bar, its count and who picked it. */
@Immutable
data class PollOptionUi(
    val name: String,
    val count: Int,
    /** 0 to 1. */
    val share: Float,
    val mine: Boolean,
    val leading: Boolean,
    /** "Ali, You", short; empty with no votes. */
    val voters: String,
)

/** One contact card: the name, its numbers as lines, the chat a "Message" opens and the vCard to add. */
@Immutable
data class ContactCardUi(
    val name: String,
    val numbers: ImmutableList<String>,
    /** The WhatsApp chat of its first number; null: no "Message". */
    val messageJid: String?,
    val vcard: String,
)

/** A range of a bubble's text drawn as a name (a mention). */
@Immutable
data class TextMark(
    val start: Int,
    val end: Int,
)

/** The card of a link: its picture (a thumbnail model; null: none), title, description and host. */
@Immutable
data class LinkCardUi(
    val url: String,
    val title: String?,
    val description: String?,
    val host: String?,
    val picture: Any? = null,
)

/** The message the composer edits (E6-WA R4): Send saves the new text. */
@Immutable
data class EditingUi(
    val key: String,
    val text: String,
)

/** The link card that goes out with the next Send (E6-WA R4); [loading] while the page is read. */
@Immutable
data class LinkChipUi(
    val url: String,
    val title: String?,
    val host: String?,
    val picture: Any? = null,
    val loading: Boolean = false,
)

/** A group member the `@` in the composer offers (E6-WA R4). */
@Immutable
data class MentionOptionUi(
    val jid: String,
    val name: String,
    /** A readable number, when the member has one that is not the name. */
    val detail: String? = null,
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
    /** E6-WA R4: the sent message the composer edits; null: a new message. */
    val editing: EditingUi? = null,
    /** E6-WA R4: the link card of the next Send. */
    val linkChip: LinkChipUi? = null,
    /** E6-WA R4: the members the `@` being typed may name (empty: no list). */
    val mentionOptions: ImmutableList<MentionOptionUi> = persistentListOf(),
    /** The label of the voice speed pill ("1×", "1.5×", "2×"). */
    val voiceSpeed: String = "",
    /**
     * How many openings of this thread have their unread count in [items] (the view model's `opened()`): a page
     * opens at the unread divider only once the divider of its own opening is there.
     */
    val opens: Int = 0,
)
