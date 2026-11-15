package dev.rosty.stops.notice

import androidx.compose.runtime.Immutable
import dev.rosty.core.Outcome
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.WaNamesAnswer
import dev.rosty.core.model.wa.WaNamesRequest
import dev.rosty.core.model.wa.WaProfile

/** A chat the arriving-soon message can go to, as the picker lists it. */
@Immutable
data class NoticeChat(
    val jid: String,
    /** The saved contact name, else WhatsApp's name, else the number; a group's subject. */
    val name: String,
    /** Under the name: the number of a person whose name is shown; null for a group and for a bare number. */
    val number: String?,
    val group: Boolean,
)

/**
 * The chats as the picker shows them, in the gateway's order (newest first). People are named by the owner rule,
 * through the same core rule Phone and Messages use (`wa.names`): the saved contact name first, then the name
 * WhatsApp has (verified business name, push name, the chat's own name), the number last. Stops reads no phone
 * contacts (it has no contacts permission), so "saved contact" is the contact name WhatsApp itself reports for the
 * person ([WaProfile.contactName]). A group is its subject; without one, what the core makes of its id.
 */
suspend fun namedChats(
    chats: List<RichChat>,
    profile: (String) -> WaProfile?,
    names: suspend (WaNamesRequest) -> Outcome<WaNamesAnswer>,
): List<NoticeChat> =
    chats.map { chat ->
        val known = profile(chat.jid)
        val waName = listOf(known?.verifiedName, known?.pushName, chat.name).firstNotNullOfOrNull(::words)
        val request =
            if (chat.isGroup) {
                WaNamesRequest(waName = words(chat.name), jid = chat.jid)
            } else {
                WaNamesRequest(contact = words(known?.contactName), waName = waName, jid = chat.jid)
            }
        val answer = (names(request) as? Outcome.Ok)?.value
        val name = answer?.name ?: request.contact ?: request.waName ?: chat.jid
        NoticeChat(
            jid = chat.jid,
            name = name,
            number = answer?.number?.takeIf { !chat.isGroup && it != name },
            group = chat.isGroup,
        )
    }

private fun words(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }

/** The chats whose name or number holds every word of [query] (any case); all of them for a blank query. */
fun filterChats(
    chats: List<NoticeChat>,
    query: String,
): List<NoticeChat> {
    val wanted = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (wanted.isEmpty()) return chats
    return chats.filter { chat ->
        wanted.all { word ->
            chat.name.contains(word, ignoreCase = true) || chat.number?.contains(word, ignoreCase = true) == true
        }
    }
}
