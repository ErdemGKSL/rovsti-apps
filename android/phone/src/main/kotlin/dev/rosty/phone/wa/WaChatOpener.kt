package dev.rosty.phone.wa

import dev.rosty.core.model.wa.WaIds
import dev.rosty.wa.WaLinkState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "WhatsApp message" on a person in Phone (WA-R2): opens that person's WhatsApp chat in Rosty Messages. Phone shows
 * no messages itself (owner decision); it hands the chat over with `dev.rosty.action.WA_CHAT`, the hand-off Contacts
 * already uses, to an app signed with the same key.
 *
 * The chat is named by the phone JID of the number. A caller that arrived under a LID has a number here because
 * Phone's identity table resolved it before the row was built; Messages resolves the JID against its own store again.
 *
 * [installed] says whether a same-signed app takes the hand-off; while none does, [available] is false and the menus
 * hide the item. [start] starts it and answers whether an app took it.
 */
class WaChatOpener(
    private val link: StateFlow<WaLinkState>,
    private val e164: suspend (String) -> String?,
    private val installed: () -> Boolean,
    private val start: (jid: String, name: String) -> Boolean,
    private val notices: WaNotices,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val present = MutableStateFlow(false)

    /** Rosty Messages is installed to take a chat: the menus show their WhatsApp message item. */
    val available: StateFlow<Boolean> = present.asStateFlow()

    /** Looks again whether Messages is installed (it may have been installed or removed since). */
    fun refresh() {
        scope.launch { present.value = withContext(io) { installed() } }
    }

    /** Opens the WhatsApp chat of [number] in Messages; [name] is what Phone calls the person, for a chat not stored yet. */
    fun open(
        number: String,
        name: String? = null,
    ) {
        scope.launch { run(number, name) }
    }

    private suspend fun run(
        number: String,
        name: String?,
    ) {
        val own =
            (link.value as? WaLinkState.Connected)
                ?.status
                ?.ownJid
                ?.let(WaIds::pnUser)
        val full = e164(number)?.takeIf { it.startsWith('+') } ?: WaCallLauncher.withOwnCountry(number, own)
        // A national number whose country is unknown: its digits are not a WhatsApp user, so no chat is opened.
        val jid = full?.let(WaCallLauncher::jidOf)
        if (jid == null) {
            notices.say(WaNotice.Refused(WaRefusal.NeedsCountryCode))
            return
        }
        val started = withContext(io) { start(jid, name.orEmpty()) }
        if (!started) {
            // Messages went away since the menu was built.
            present.value = false
            notices.say(WaNotice.Failed)
        }
    }
}
