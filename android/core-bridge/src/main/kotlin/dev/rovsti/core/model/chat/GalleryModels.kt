@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.chat

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Models of `chat_gallery.*`, `chat_export.*` and `multi_select.plan` (E2.11b), mirroring
// `crates/rovsti_ffi/fixtures/{chat_gallery,chat_export,multi_select}.*`. Stability: `compose_stability.conf`.

@Serializable
enum class GalleryKind {
    @SerialName("image")
    Image,

    @SerialName("video")
    Video,

    @SerialName("audio")
    Audio,

    @SerialName("doc")
    Doc,

    @SerialName("other")
    Other,
}

/** One message as the gallery sees it; no [kind] is a plain text message. */
@Serializable
data class GalleryItem(
    val id: String,
    @SerialName("at_ms") val atMs: Long,
    @EncodeDefault(NEVER) val kind: GalleryKind? = null,
    @EncodeDefault(NEVER) val text: String? = null,
    @SerialName("file_name") @EncodeDefault(NEVER) val fileName: String? = null,
    @EncodeDefault(NEVER) val size: Long? = null,
)

@Serializable
data class GalleryRequest(
    val items: List<GalleryItem>,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
    /** Longest link text in a row (40 when absent). */
    @SerialName("short_max") @EncodeDefault(NEVER) val shortMax: Int? = null,
)

@Serializable
data class GalleryMedia(
    val id: String,
    @SerialName("at_ms") val atMs: Long,
)

@Serializable
data class GalleryLink(
    val id: String,
    @SerialName("at_ms") val atMs: Long,
    val url: String,
    val host: String,
    /** The link without scheme and `www.`, cut with an ellipsis. */
    val short: String,
)

@Serializable
data class GalleryDoc(
    val id: String,
    @SerialName("at_ms") val atMs: Long,
    val name: String,
    /** `PDF · 1.2 MB`, `Audio · 340 KB`. */
    val detail: String,
)

/** Consecutive items of one month ("November 2026"): items `start until end` of the tab's list. */
@Serializable
data class GallerySection(
    val label: String,
    val start: Int,
    val end: Int,
)

@Serializable
data class GalleryAnswer(
    /** Pictures and videos, newest first. */
    val media: List<GalleryMedia>,
    val links: List<GalleryLink>,
    val docs: List<GalleryDoc>,
    @SerialName("media_sections") val mediaSections: List<GallerySection>,
    @SerialName("link_sections") val linkSections: List<GallerySection>,
    @SerialName("doc_sections") val docSections: List<GallerySection>,
)

@Serializable
data class GalleryTabsRequest(
    /** Only part of the chat is loaded: the empty texts say so. */
    @EncodeDefault(NEVER) val partial: Boolean = false,
)

@Serializable
data class GalleryTab(
    /** `media`, `links` or `docs`. */
    val key: String,
    val label: String,
    @SerialName("empty_text") val emptyText: String,
)

@Serializable
data class GalleryTabsAnswer(
    val tabs: List<GalleryTab>,
)

/** One message of an export or a copy, oldest first. */
@Serializable
data class ExportLine(
    @SerialName("at_ms") val atMs: Long,
    val author: String,
    val text: String,
)

@Serializable
data class ExportRequest(
    val title: String,
    val lines: List<ExportLine>,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
)

@Serializable
data class ExportAnswer(
    /** "Chat with Ada", a blank line, then the day-grouped block. */
    val text: String,
    /** `chat-ada-2026-09-21.txt`. */
    @SerialName("file_name") val fileName: String,
)

@Serializable
data class ExportBlockRequest(
    val lines: List<ExportLine>,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
)

@Serializable
data class ExportNoticeRequest(
    val ok: Boolean,
    /** Older messages were not loaded. */
    @EncodeDefault(NEVER) val partial: Boolean = false,
)

@Serializable
data class MultiSelectRequest(
    /** How many messages are selected. */
    val count: Int,
    /** The selected messages, oldest first. */
    val lines: List<ExportLine>,
    /** Whether each selected message is already starred. */
    @EncodeDefault(NEVER) val starred: List<Boolean> = emptyList(),
    @SerialName("tz_offset_s") val tzOffsetS: Int,
)

@Serializable
data class MultiSelectPlan(
    /** "Select messages" or "3 selected". */
    val title: String,
    /** The clipboard text: a lone message is its text, several are a chat block. */
    @SerialName("copy_text") val copyText: String,
    /** The star button stars (true) or unstars (false). */
    val star: Boolean,
    @SerialName("copied_notice") val copiedNotice: String,
    @SerialName("starred_notice") val starredNotice: String,
    @SerialName("deleted_notice") val deletedNotice: String,
    /** Shown when a tap would pass the cap. */
    @SerialName("cap_notice") val capNotice: String,
    @SerialName("max_selected") val maxSelected: Int,
)
