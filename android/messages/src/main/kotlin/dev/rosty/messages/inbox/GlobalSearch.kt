package dev.rosty.messages.inbox

import androidx.compose.runtime.Immutable
import dev.rosty.core.getOrNull
import dev.rosty.core.model.chat.ChatRecency
import dev.rosty.core.model.chat.GlobalSearchRequest
import dev.rosty.core.model.chat.SearchCandidate
import dev.rosty.core.model.chat.SearchPrepareRequest
import dev.rosty.core.model.messages.SmsMessagesPayload
import dev.rosty.core.ops.ChatOps
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.wa.WaChats
import dev.rosty.platform.sms.SmsRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** One message that matches the inbox search; a tap opens its conversation on it. */
@Immutable
data class SearchHitUi(
    val key: String,
    val title: String,
    val avatar: RowAvatar,
    val time: String,
    val snippet: String,
    /** The match inside [snippet], UTF-16 offsets. */
    val highlightStart: Int,
    val highlightEnd: Int,
    val route: MessagesRoute,
)

/** The Messages section under the matching chats (M7 global search). */
@Immutable
data class MessagesSectionUi(
    val hits: ImmutableList<SearchHitUi>,
    /** At least this many matched (Rust stops at 200). */
    val count: Int,
    /** Only the recent WhatsApp chats were read. */
    val partial: Boolean,
    /** SMS could not be read (no permission): the section says so. */
    val smsMissing: Boolean,
)

/**
 * The message search of the inbox (E6.2, M7): SMS bodies from the provider (`LIKE` pattern from
 * `global_search.prepare`), WhatsApp messages of the recent chats from [WaChats.searchCandidates], ranked and cut by
 * `global_search.search`. All words come from `strings.xml`; Rust's English summary is not shown.
 */
class GlobalSearch(
    private val chat: ChatOps,
    private val sms: SmsRepository,
    private val wa: WaChats,
    private val time: RowTime,
    private val now: () -> Long,
) {
    /** The section for [query] over the inbox [rows], or null while the query is too short to search. */
    suspend fun run(
        query: String,
        rows: List<InboxRowUi>,
        smsReadable: Boolean,
    ): MessagesSectionUi? {
        val q = query.trim()
        val chats = rows.mapNotNull { r -> r.waChat?.let { ChatRecency("wa:$it", r.atMs) } }
        val prepared = chat.searchPrepare(SearchPrepareRequest(q, chats)).getOrNull() ?: return null
        if (!prepared.searchable) return null
        val bySms = HashMap<Long, InboxRowUi>()
        val byWa = HashMap<String, InboxRowUi>()
        for (r in rows) {
            r.smsThread?.let { bySms[it] = r }
            r.waChat?.let { byWa[it] = r }
        }
        val candidates = ArrayList<SearchCandidate>()
        if (smsReadable) candidates += smsCandidates(sms.search(prepared.like), bySms)
        val waRecent = prepared.recent.filter { it.startsWith("wa:") }.map { it.removePrefix("wa:") }
        val waFound = wa.searchCandidates(waRecent)
        if (waFound != null) candidates += waFound
        val partial = waFound != null && chats.size > waRecent.size
        val answer = chat.globalSearch(GlobalSearchRequest(q, partial, candidates)).getOrNull() ?: return null
        val at = now()
        val hits =
            answer.hits.map { hit ->
                val row =
                    if (hit.chatKey.startsWith("wa:")) {
                        byWa[hit.chatKey.removePrefix("wa:")]
                    } else {
                        hit.chatKey
                            .removePrefix("sms:")
                            .toLongOrNull()
                            ?.let(bySms::get)
                    }
                SearchHitUi(
                    key = "${hit.chatKey}/${hit.messageId}",
                    title = hit.chatTitle,
                    avatar = row?.avatar ?: RowAvatar(colorKey = hit.chatKey),
                    time = time.label(hit.atMs, at),
                    snippet = hit.snippet,
                    highlightStart = hit.highlight.start,
                    highlightEnd = hit.highlight.end,
                    route = routeOf(hit.chatKey, hit.messageId, hit.atMs, row),
                )
            }
        return MessagesSectionUi(hits.toImmutableList(), hits.size, partial, smsMissing = !smsReadable)
    }

    private fun smsCandidates(
        page: SmsMessagesPayload,
        bySms: Map<Long, InboxRowUi>,
    ): List<SearchCandidate> =
        page.sms.map { m ->
            SearchCandidate(
                chatKey = "sms:${m.threadId}",
                chatTitle = bySms[m.threadId]?.title ?: m.address,
                messageId = "sms:${m.id}",
                text = m.body,
                atMs = m.dateMs,
            )
        }

    companion object {
        /** The conversation of a hit, told to reveal [messageId] (sent at [atMs]) once it is loaded. */
        fun routeOf(
            chatKey: String,
            messageId: String,
            atMs: Long,
            row: InboxRowUi?,
        ): MessagesRoute =
            if (chatKey.startsWith("wa:")) {
                MessagesRoute.WaThread(chatKey.removePrefix("wa:"), reveal = messageId, revealAtMs = atMs)
            } else {
                MessagesRoute.SmsThread(
                    chatKey.removePrefix("sms:").toLong(),
                    listOfNotNull(row?.smsSender),
                    reveal = messageId,
                    revealAtMs = atMs,
                )
            }
    }
}
