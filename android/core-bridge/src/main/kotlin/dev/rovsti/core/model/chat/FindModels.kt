@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.chat

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Models of `chat_find.*` and `global_search.*` (E2.11b), mirroring `crates/rovsti_ffi/fixtures/{chat_find,global_search}.*`.
// Offsets into a snippet are UTF-16 units. Stability: `compose_stability.conf`.

/** A `{text}` answer: the export block, the export pill, the search footer. */
@Serializable
data class TextAnswer(
    val text: String,
)

/** A `[start, end)` range in UTF-16 units, the unit of a Kotlin `String`. */
@Serializable
data class Utf16Span(
    val start: Int,
    val end: Int,
)

@Serializable
data class ChatFindRequest(
    val texts: List<String>,
    val query: String,
)

@Serializable
data class ChatFindAnswer(
    /** Indices into the request's `texts` that contain the folded query, oldest first. */
    val hits: List<Int>,
)

@Serializable
enum class CursorAction {
    /** Just describe the cursor at `at` (the newest hit when absent). */
    @SerialName("start")
    Start,

    /** The previous (older) hit, wrapping to the newest. */
    @SerialName("older")
    Older,

    /** The next (newer) hit, wrapping to the oldest. */
    @SerialName("newer")
    Newer,

    /** Fresh `newHits` after older pages loaded: stay on `keep`, one hit older when `stepOlder`. */
    @SerialName("resume")
    Resume,

    /** Replace the hits, keeping the current item when it is still a hit. */
    @SerialName("retarget")
    Retarget,
}

@Serializable
data class FindCursorRequest(
    val hits: List<Int>,
    /** Position in [hits] of the current hit; the newest when absent. */
    @EncodeDefault(NEVER) val at: Int? = null,
    val action: CursorAction,
    @SerialName("new_hits") @EncodeDefault(NEVER) val newHits: List<Int> = emptyList(),
    @EncodeDefault(NEVER) val keep: Int? = null,
    @SerialName("step_older") @EncodeDefault(NEVER) val stepOlder: Boolean = false,
)

@Serializable
data class FindCursorAnswer(
    val hits: List<Int>,
    /** Position in [hits] of the current hit. */
    val at: Int?,
    /** Item index of the current hit. */
    val current: Int?,
    /** "2 of 5" or "No matches". */
    val label: String,
    val empty: Boolean,
    /** The current hit is the oldest one: older pages may hold more. */
    val oldest: Boolean,
)

/** One message offered to the global search. */
@Serializable
data class SearchCandidate(
    @SerialName("chat_key") val chatKey: String,
    @SerialName("chat_title") val chatTitle: String,
    @SerialName("message_id") val messageId: String,
    @EncodeDefault(NEVER) val author: String? = null,
    val text: String,
    @SerialName("at_ms") val atMs: Long,
)

@Serializable
data class GlobalSearchRequest(
    val query: String,
    /** Only recent chats were read: the summary says so. */
    @EncodeDefault(NEVER) val partial: Boolean = false,
    val items: List<SearchCandidate>,
)

@Serializable
data class SearchHit(
    @SerialName("chat_key") val chatKey: String,
    @SerialName("chat_title") val chatTitle: String,
    @SerialName("message_id") val messageId: String,
    @SerialName("at_ms") val atMs: Long,
    val snippet: String,
    /** The match inside [snippet]. */
    val highlight: Utf16Span,
)

@Serializable
data class GlobalSearchAnswer(
    /** Newest first, at most 200. */
    val hits: List<SearchHit>,
    /** "3 results", "No results", "200+ results", plus "in recent chats" when partial. */
    val summary: String,
)

@Serializable
data class ChatRecency(
    val key: String,
    @SerialName("last_ms") val lastMs: Long,
)

@Serializable
data class SearchPrepareRequest(
    val query: String,
    @EncodeDefault(NEVER) val chats: List<ChatRecency> = emptyList(),
    /** How many recent WhatsApp chats to read (15 when absent). */
    @EncodeDefault(NEVER) val limit: Int? = null,
)

@Serializable
data class SearchPrepareAnswer(
    /** The query is long enough to search. */
    val searchable: Boolean,
    @SerialName("min_query_chars") val minQueryChars: Int,
    /** `%query%` for `LIKE ... ESCAPE '\'` on the SMS provider. */
    val like: String,
    /** The newest chat keys, newest first. */
    val recent: List<String>,
    /** Older pages a jump to a hit loads before giving up. */
    @SerialName("max_jump_pages") val maxJumpPages: Int,
)

/** Whether one source took part in a search. */
@Serializable
enum class SearchSource {
    @SerialName("searched")
    Searched,

    /** The SMS permission is missing. */
    @SerialName("no_access")
    NoAccess,

    /** WhatsApp is not linked. */
    @SerialName("not_linked")
    NotLinked,

    @SerialName("failed")
    Failed,
}

@Serializable
data class SearchFooterRequest(
    val hits: Int,
    @EncodeDefault(NEVER) val partial: Boolean = false,
    val sms: SearchSource,
    val wa: SearchSource,
)

@Serializable
data class SearchJumpRequest(
    val loaded: Boolean,
    @SerialName("has_older") val hasOlder: Boolean,
    @SerialName("pages_loaded") val pagesLoaded: Int,
    /** The oldest loaded message. */
    @SerialName("oldest_ms") @EncodeDefault(NEVER) val oldestMs: Long? = null,
    @SerialName("target_ms") val targetMs: Long,
)

/** What a page does next while it works towards a search hit. */
@Serializable
enum class SearchJumpStep {
    /** The message is loaded: show it and let it glow. */
    @SerialName("reveal")
    Reveal,

    /** Load one more older page. */
    @SerialName("load_older")
    LoadOlder,

    /** Ten pages were not enough: tell the user to scroll up. */
    @SerialName("further_back")
    FurtherBack,

    /** History reaches past the message and it is not there. */
    @SerialName("gone")
    Gone,
}

@Serializable
data class SearchJumpAnswer(
    val step: SearchJumpStep,
    /** The pill for a jump that stopped short. */
    val notice: String?,
)
