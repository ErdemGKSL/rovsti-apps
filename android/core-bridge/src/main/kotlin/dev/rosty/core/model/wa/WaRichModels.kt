package dev.rosty.core.model.wa

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

// Rich content and presence (E6-WA R5): polls, places and contact cards on the wire (`rosty_wa_proto::rich`) and the
// pure ops of `rosty_core::wa_rich`.

/** How the gateway names my own vote, from any of my devices (`rosty_core::wa_rich::ME`). */
const val MY_VOTE: String = "me"

/** One option of a poll and who picked it ([MY_VOTE] is me). */
@Serializable
data class PollOption(
    val name: String,
    @EncodeDefault(NEVER) val voters: List<String> = emptyList(),
)

/** A poll ([MessageMeta.poll]): [selectable] 1 is one answer, more is "multiple answers"; [myVotes] my choice. */
@Serializable
data class Poll(
    val question: String,
    val options: List<PollOption>,
    val selectable: Int,
    @SerialName("my_votes") @EncodeDefault(NEVER) val myVotes: List<String> = emptyList(),
) {
    val multiple: Boolean get() = selectable != 1
}

/** What `wa.send_rich` sends. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface RichContent {
    @Serializable
    @SerialName("poll")
    data class PollDraft(
        val question: String,
        val options: List<String>,
        @EncodeDefault(NEVER) val multiple: Boolean = false,
    ) : RichContent

    /** A one-shot place of mine (never a live location). */
    @Serializable
    @SerialName("location")
    data class Place(
        val latitude: Double,
        val longitude: Double,
        @EncodeDefault(NEVER) val name: String? = null,
        @EncodeDefault(NEVER) val address: String? = null,
    ) : RichContent

    /** One card goes as a contact message, several as one contacts array. */
    @Serializable
    @SerialName("contacts")
    data class Contacts(
        val contacts: List<SharedContact>,
    ) : RichContent
}

/** `wa.send_rich`. */
@Serializable
data class SendRich(
    val chat: String,
    @SerialName("quoted_id") @EncodeDefault(NEVER) val quotedId: String? = null,
    val content: RichContent,
)

/** `wa.poll_vote`: my choice in the poll [messageId] (empty: take my vote back). */
@Serializable
data class PollVote(
    val chat: String,
    @SerialName("message_id") val messageId: String,
    val options: List<String>,
)

/** `wa.poll_check`: a poll as the create sheet holds it. */
@Serializable
data class PollCheckRequest(
    val question: String,
    val options: List<String>,
    @EncodeDefault(NEVER) val multiple: Boolean = false,
)

/** What keeps a draft from being sent ([index] is an option of the cleaned draft). */
@Serializable
data class PollProblem(
    val kind: String,
    @EncodeDefault(NEVER) val index: Int? = null,
) {
    companion object {
        const val NO_QUESTION = "no_question"
        const val QUESTION_TOO_LONG = "question_too_long"
        const val TOO_FEW_OPTIONS = "too_few_options"
        const val TOO_MANY_OPTIONS = "too_many_options"
        const val OPTION_TOO_LONG = "option_too_long"
        const val DUPLICATE = "duplicate"
    }
}

@Serializable
data class PollCheckAnswer(
    /** The draft as it is sent; null while it cannot be sent. */
    @EncodeDefault(NEVER) val draft: PollCheckRequest? = null,
    @EncodeDefault(NEVER) val problems: List<PollProblem> = emptyList(),
    @SerialName("min_options") val minOptions: Int,
    @SerialName("max_options") val maxOptions: Int,
)

/** `wa.poll_view`: [own] are my ids (number and LID). */
@Serializable
data class PollViewRequest(
    val poll: Poll,
    @EncodeDefault(NEVER) val own: List<String> = emptyList(),
)

@Serializable
data class PollOptionView(
    val name: String,
    val count: Int,
    /** This option's voters out of everyone who voted, 0 to 100. */
    val percent: Int,
    val mine: Boolean,
    val leading: Boolean,
    val voters: List<String>,
)

@Serializable
data class PollView(
    val question: String,
    val multiple: Boolean,
    /** People who voted. */
    val voters: Int,
    val options: List<PollOptionView>,
    val voted: Boolean,
)

/** `wa.vcard_parse`. */
@Serializable
data class VcardParseRequest(
    val vcard: String,
)

@Serializable
data class CardNumber(
    val number: String,
    @EncodeDefault(NEVER) val label: String? = null,
    /** The number's WhatsApp JID when the card says it is on WhatsApp. */
    @SerialName("wa_jid") @EncodeDefault(NEVER) val waJid: String? = null,
)

@Serializable
data class Card(
    @EncodeDefault(NEVER) val name: String? = null,
    @EncodeDefault(NEVER) val numbers: List<CardNumber> = emptyList(),
    @EncodeDefault(NEVER) val emails: List<String> = emptyList(),
    @EncodeDefault(NEVER) val org: String? = null,
)

@Serializable
data class VcardParseAnswer(
    val cards: List<Card>,
)

/** `wa.vcard_build`: the vCard Rosty sends for one contact. */
@Serializable
data class VcardBuildRequest(
    val name: String,
    @EncodeDefault(NEVER) val numbers: List<CardNumber> = emptyList(),
)

@Serializable
data class VcardBuildAnswer(
    val vcard: String,
)

/** `wa.place_view`. */
@Serializable
data class PlaceViewRequest(
    val latitude: Double,
    val longitude: Double,
    @EncodeDefault(NEVER) val name: String? = null,
    @EncodeDefault(NEVER) val address: String? = null,
    @EncodeDefault(NEVER) val live: Boolean = false,
)

/** A place as the card and the map page show it: no [title] is "Shared location". */
@Serializable
data class PlaceView(
    @EncodeDefault(NEVER) val title: String? = null,
    @EncodeDefault(NEVER) val subtitle: String? = null,
    val coords: String,
    @SerialName("geo_uri") val geoUri: String,
    val live: Boolean,
)

/** `wa.static_map`: the tiles of a [width] x [height] picture of a point, each [tilePx] wide. */
@Serializable
data class StaticMapRequest(
    val latitude: Double,
    val longitude: Double,
    @EncodeDefault(NEVER) val zoom: Int? = null,
    val width: Int,
    val height: Int,
    @SerialName("tile_px") val tilePx: Int,
)

@Serializable
data class MapTile(
    val z: Int,
    val x: Int,
    val y: Int,
    val left: Int,
    val top: Int,
)

@Serializable
data class StaticMap(
    val zoom: Int,
    @SerialName("tile_px") val tilePx: Int,
    val tiles: List<MapTile>,
    @SerialName("pin_x") val pinX: Int,
    @SerialName("pin_y") val pinY: Int,
)

/** The newest presence of the other person of a one-to-one chat. */
@Serializable
data class PresenceSeen(
    val available: Boolean,
    @SerialName("last_seen_ms") @EncodeDefault(NEVER) val lastSeenMs: Long? = null,
)

@Serializable
enum class TypingKind {
    @SerialName("typing")
    Typing,

    @SerialName("recording")
    Recording,
}

/** A typing or recording state as it arrived. */
@Serializable
data class TypingSeen(
    val jid: String,
    val kind: TypingKind,
    @SerialName("at_ms") val atMs: Long,
)

/** `wa.presence_view`. */
@Serializable
data class PresenceViewRequest(
    @EncodeDefault(NEVER) val group: Boolean = false,
    @EncodeDefault(NEVER) val presence: PresenceSeen? = null,
    @EncodeDefault(NEVER) val typing: List<TypingSeen> = emptyList(),
    @SerialName("now_ms") val nowMs: Long,
)

@Serializable
enum class HeaderPresenceKind {
    @SerialName("none")
    None,

    @SerialName("online")
    Online,

    @SerialName("last_seen")
    LastSeen,

    @SerialName("typing")
    Typing,

    @SerialName("recording")
    Recording,

    /** Some type and some record (groups). */
    @SerialName("busy")
    Busy,
}

/** The line under a chat's title: [atMs] for last seen, [who] for typing or recording, both lists for busy. */
@Serializable
data class HeaderPresence(
    val kind: HeaderPresenceKind,
    @SerialName("at_ms") @EncodeDefault(NEVER) val atMs: Long? = null,
    @EncodeDefault(NEVER) val who: List<String> = emptyList(),
    @EncodeDefault(NEVER) val typing: List<String> = emptyList(),
    @EncodeDefault(NEVER) val recording: List<String> = emptyList(),
) {
    companion object {
        val NONE = HeaderPresence(HeaderPresenceKind.None)
    }
}
