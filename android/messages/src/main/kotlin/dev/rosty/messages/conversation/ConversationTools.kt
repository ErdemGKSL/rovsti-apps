package dev.rosty.messages.conversation

import androidx.compose.runtime.Immutable
import dev.rosty.core.Outcome
import dev.rosty.core.model.chat.ChatFindRequest
import dev.rosty.core.model.chat.CursorAction
import dev.rosty.core.model.chat.ExportLine
import dev.rosty.core.model.chat.ExportRequest
import dev.rosty.core.model.chat.FindCursorRequest
import dev.rosty.core.model.chat.GalleryItem
import dev.rosty.core.model.chat.GalleryKind
import dev.rosty.core.model.chat.GalleryRequest
import dev.rosty.core.model.chat.MultiSelectRequest
import dev.rosty.core.ops.ChatOps
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.TimeZone

/** The find bar of a conversation (M5): the query, "2 of 5", and whether the current hit is the oldest one. */
@Immutable
data class FindUi(
    val query: String = "",
    val position: Int? = null,
    val count: Int = 0,
    val oldest: Boolean = false,
)

/** What the tools add to a conversation page: the find bar, the glowing bubble, the jump target. */
@Immutable
data class ToolsUi(
    val find: FindUi? = null,
    /** The bubble that glows for [GLOW_MS] (a find hit, a jump target). */
    val highlight: String? = null,
    /** A bubble the list should scroll to once (find, jump to date); the page clears it after scrolling. */
    val scrollTo: String? = null,
    /** Select mode (M20): the chosen bubbles, oldest first; null when not selecting. */
    val selected: ImmutableList<String>? = null,
)

/** A file the export made: its name and text (Save as, or the share sheet). */
data class ExportedChat(
    val fileName: String,
    val text: String,
)

/**
 * The conversation tools of E6.6 over the bubbles that are loaded, the same for SMS and WhatsApp: find with jump and
 * glow (`chat_find.find`, `find_cursor`), jump to a day, the media/links/docs gallery (`gallery.build`) and the export
 * (`export.text`). Pure decisions are the Rust core's; this class keeps the cursor and the glow timer.
 */
class ConversationTools(
    private val chat: ChatOps,
    private val scope: CoroutineScope,
    private val bubbles: () -> List<BubbleUi>,
    private val now: () -> Long,
) {
    private val ui = MutableStateFlow(ToolsUi())
    val state: StateFlow<ToolsUi> = ui.asStateFlow()

    private var hits: List<Int> = emptyList()
    private var glow: Job? = null

    /** Oldest first, the order of the core's indices. */
    private fun ordered(): List<BubbleUi> = bubbles().sortedBy { it.atMs }

    fun openFind() {
        ui.value = ui.value.copy(find = FindUi())
    }

    fun closeFind() {
        hits = emptyList()
        ui.value = ui.value.copy(find = null, highlight = null)
    }

    fun setQuery(query: String) {
        scope.launch {
            val list = ordered()
            val found =
                if (query.isBlank()) {
                    emptyList()
                } else {
                    (chat.find(ChatFindRequest(list.map { it.text }, query)) as? Outcome.Ok)?.value?.hits.orEmpty()
                }
            hits = found
            cursor(CursorAction.Start, null, query, list)
        }
    }

    fun older() = step(CursorAction.Older)

    fun newer() = step(CursorAction.Newer)

    private fun step(action: CursorAction) {
        val find = ui.value.find ?: return
        scope.launch { cursor(action, find.position, find.query, ordered()) }
    }

    private suspend fun cursor(
        action: CursorAction,
        at: Int?,
        query: String,
        list: List<BubbleUi>,
    ) {
        val answer = (chat.findCursor(FindCursorRequest(hits, at, action)) as? Outcome.Ok)?.value
        val current = answer?.current?.let(list::getOrNull)
        ui.value =
            ui.value.copy(
                find = FindUi(query, answer?.at, hits.size, answer?.oldest == true),
                scrollTo = current?.key,
            )
        current?.let { glowOn(it.key) }
    }

    /** Scrolls to the first loaded bubble on or after [dayStartMs]; false when none is loaded that late. */
    fun jumpTo(dayStartMs: Long): Boolean {
        val target = ordered().firstOrNull { it.atMs >= dayStartMs } ?: return false
        ui.value = ui.value.copy(scrollTo = target.key)
        glowOn(target.key)
        return true
    }

    /** Scrolls to [key] and makes it glow (a gallery row, a search hit from the inbox). */
    fun reveal(key: String) {
        ui.value = ui.value.copy(scrollTo = key)
        glowOn(key)
    }

    /** The page scrolled to [ToolsUi.scrollTo]. */
    fun scrolled() {
        ui.value = ui.value.copy(scrollTo = null)
    }

    private fun glowOn(key: String) {
        glow?.cancel()
        ui.value = ui.value.copy(highlight = key)
        glow =
            scope.launch {
                delay(GLOW_MS)
                if (ui.value.highlight == key) ui.value = ui.value.copy(highlight = null)
            }
    }

    /** The gallery of what is loaded: pictures, links and documents with month sections. */
    suspend fun gallery() =
        (
            chat.gallery(
                GalleryRequest(
                    ordered().map { b ->
                        val media = b.media.firstOrNull()
                        GalleryItem(
                            id = b.key,
                            atMs = b.atMs,
                            kind = media?.let { if (it.isImage) GalleryKind.Image else GalleryKind.Doc },
                            text = b.text.takeIf { it.isNotBlank() },
                            fileName = media?.label,
                        )
                    },
                    tzOffsetS(),
                ),
            ) as? Outcome.Ok
        )?.value

    /** Select mode (M20) with [key] chosen. */
    fun enterSelect(key: String) {
        ui.value = ui.value.copy(selected = persistentListOf(key), find = null)
    }

    /** Adds or removes [key]; the last one removed ends select mode. */
    fun toggleSelect(key: String) {
        val now = ui.value.selected ?: return
        val next = if (key in now) now - key else now + key
        ui.value = ui.value.copy(selected = next.takeIf { it.isNotEmpty() }?.toImmutableList())
    }

    fun exitSelect() {
        ui.value = ui.value.copy(selected = null)
    }

    /** The selected bubbles as one clipboard text (`multi_select.plan`: a lone message is its text, more a block). */
    suspend fun selectionText(
        title: String,
        me: String,
    ): String? {
        val chosen = ui.value.selected ?: return null
        val lines =
            ordered().filter { it.key in chosen && !it.deleted }.map { b ->
                ExportLine(
                    b.atMs,
                    if (b.outgoing) me else b.sender ?: title,
                    b.text.ifBlank {
                        b.media
                            .firstOrNull()
                            ?.label
                            .orEmpty()
                    },
                )
            }
        if (lines.isEmpty()) return null
        return (chat.multiSelect(MultiSelectRequest(lines.size, lines, tzOffsetS = tzOffsetS())) as? Outcome.Ok)
            ?.value
            ?.copyText
    }

    /** The files of the loaded messages that are on the phone (M48 zip), oldest first. */
    fun mediaFiles(): List<java.io.File> =
        ordered().flatMap { b -> b.media.mapNotNull { it.model as? java.io.File } }.distinct()

    /** The picture of each loaded media message by bubble key, for the gallery grid. */
    fun mediaModels(): Map<String, Any?> =
        bubbles()
            .mapNotNull { b -> b.media.firstOrNull { it.isImage }?.let { b.key to it.model } }
            .toMap()

    /** The loaded messages as text: "Chat with …", day blocks, "[14:05] Ada: hi" (mine as "Me"). */
    suspend fun export(
        title: String,
        me: String,
    ): ExportedChat? {
        val lines =
            ordered()
                .filter { !it.deleted }
                .map {
                    ExportLine(
                        it.atMs,
                        if (it.outgoing) me else it.sender ?: title,
                        it.text.ifBlank {
                            it.media
                                .firstOrNull()
                                ?.label
                                .orEmpty()
                        },
                    )
                }
        val answer =
            (chat.exportText(ExportRequest(title, lines, now(), tzOffsetS())) as? Outcome.Ok)?.value ?: return null
        return ExportedChat(answer.fileName, answer.text)
    }

    private fun tzOffsetS(): Int = TimeZone.getDefault().getOffset(now()) / MS_PER_S

    companion object {
        const val GLOW_MS = 1_600L
        private const val MS_PER_S = 1000
    }
}
