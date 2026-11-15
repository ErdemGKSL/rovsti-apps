package dev.rosty.stops.notice

import androidx.compose.runtime.Immutable
import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.NoticeChatInput
import dev.rosty.core.model.stops.NoticeChatsAnswer
import dev.rosty.core.model.stops.NoticeChatsRequest
import dev.rosty.core.model.wa.RichChat
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
 * The chats as the picker shows them, in the gateway's order (newest first), named by the owner rule: the saved
 * contact name first, then the name WhatsApp has (the verified business name, the push name, the chat's own name),
 * the number last. The rule is not written here: `stops.notice_chats` applies the one every app names a WhatsApp
 * person with (`rosty_core::wa_identity::display_name`); this only hands it what is known. Stops reads no phone
 * contacts (it has no contacts permission), so "saved contact" is the contact name WhatsApp itself reports for the
 * person ([WaProfile.contactName]). A group is its subject. If the core cannot be asked, a chat keeps the gateway's
 * name for it, else its id.
 */
suspend fun namedChats(
    chats: List<RichChat>,
    profile: (String) -> WaProfile?,
    names: suspend (NoticeChatsRequest) -> Outcome<NoticeChatsAnswer>,
): List<NoticeChat> {
    if (chats.isEmpty()) return emptyList()
    val request =
        NoticeChatsRequest(
            chats.map { chat ->
                val known = profile(chat.jid).takeUnless { chat.isGroup }
                NoticeChatInput(
                    jid = chat.jid,
                    group = chat.isGroup,
                    contact = known?.contactName,
                    verified = known?.verifiedName,
                    push = known?.pushName,
                    chatName = chat.name,
                )
            },
        )
    val named = (names(request) as? Outcome.Ok)?.value?.chats
    return named?.map { NoticeChat(it.jid, it.name, it.number, it.group) }
        ?: chats.map { NoticeChat(it.jid, it.name?.trim()?.takeIf(String::isNotEmpty) ?: it.jid, null, it.isGroup) }
}

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
