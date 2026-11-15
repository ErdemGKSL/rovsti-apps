package dev.rosty.messages.names

import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.WaSyncChat
import dev.rosty.messages.inbox.numberOfJid
import dev.rosty.people.LiveWaNames
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.scan

/** Where a shown name came from, in the order the rule tries them. */
enum class NameTier {
    /** A saved device contact (the address book). */
    Contact,

    /** The verified business name WhatsApp carries. */
    Verified,

    /** The name the person gave themselves on WhatsApp (stored, or asked for just now). */
    Push,

    /** The name the stored WhatsApp chat carries, when it is all there is. */
    Chat,

    /** Nobody names the number: the number itself, formatted for reading. */
    Number,
}

/** The name a surface shows for a person and the tier that gave it. */
data class PersonName(
    val text: String,
    val tier: NameTier,
) {
    /** False when the text is only the number (or the JID's user part). */
    val named: Boolean get() = tier != NameTier.Number

    /** True when a device contact owns the number. */
    val saved: Boolean get() = tier == NameTier.Contact
}

/**
 * What WhatsApp is known to call a person, as stored on the device: the [verified] business name, the [push] name
 * (the one the person chose; a group message carries its sender's) and the stored [chat] name of a one-to-one chat
 * (what the gateway's history sync called the conversation: it may be a name WhatsApp's own address book synced, so
 * it comes after the push name).
 */
data class WaNames(
    val verified: String? = null,
    val push: String? = null,
    val chat: String? = null,
) {
    /** These names, with what they lack taken from [other]. */
    fun orElse(other: WaNames?): WaNames =
        if (other == null) {
            this
        } else {
            WaNames(clean(verified) ?: other.verified, clean(push) ?: other.push, clean(chat) ?: other.chat)
        }

    companion object {
        val None = WaNames()

        /** The names a one-to-one chat of the gateway's list carries; none for a group (its name is its subject). */
        fun of(chat: RichChat?): WaNames =
            if (chat == null || chat.isGroup) None else WaNames(push = chat.meta.pushName, chat = chat.name)

        /** The names a stored one-to-one chat carries; none for a group. */
        fun of(chat: WaSyncChat?): WaNames =
            if (chat == null || chat.isGroup) None else WaNames(push = chat.pushName, chat = chat.name)

        internal fun clean(name: String?): String? = name?.trim()?.takeIf(String::isNotEmpty)
    }
}

/** WhatsApp names asked for on demand (`dev.rosty.people.LiveWaNames`); a seam, so tests need no gateway. */
interface LiveNames {
    /** Moves whenever an asked name arrived. */
    val revision: StateFlow<Long>

    /** The name already known for [number] (verified business name, else push name), or null. Never asks. */
    suspend fun nameOf(number: String): String?

    /** Starts a background query for [number] unless one runs or a fresh answer is known. */
    fun ask(number: String)

    /** No live names: nothing is known, nothing is asked. */
    object None : LiveNames {
        override val revision: StateFlow<Long> = MutableStateFlow(0L)

        override suspend fun nameOf(number: String): String? = null

        override fun ask(number: String) = Unit
    }

    companion object {
        /** The app's source: the shared [LiveWaNames] of `:people`, its questions paced by [asks]. */
        fun of(
            live: LiveWaNames,
            asks: PacedAsks,
        ): LiveNames =
            object : LiveNames {
                override val revision: StateFlow<Long> get() = live.revision

                override suspend fun nameOf(number: String): String? =
                    WaNames.clean(live.lookup(listOf(number))[number]?.waPushName)

                override fun ask(number: String) = asks.ask(number)
            }
    }
}

/**
 * The one name-order rule of Messages. Wherever a person is named (inbox rows, thread headers, notifications, search
 * results, the forward chooser, New Conversation, Scheduled, Starred, the muted list, group senders and quotes) the
 * order is:
 *
 * 1. the saved device contact's name;
 * 2. the WhatsApp name: verified business name, else push name (stored on the device, else asked from WhatsApp);
 * 3. the stored WhatsApp chat name, when that is all there is;
 * 4. the number, formatted for reading.
 *
 * A number nobody names is shown at once and asked from WhatsApp in the background ([LiveNames.ask], deduplicated and
 * cached there); when the answer lands [changes] moves and the surface asks again, so the name swaps in without a
 * restart. A contact saved later moves [changes] too and wins. Group chats keep their subject as title ([chatTitle]);
 * the rule names the people inside them ([sender]).
 */
class PersonNames(
    /** The address-book name of a number, or null. */
    private val contactName: suspend (number: String) -> String?,
    /** Emits when the address book changed. */
    contactChanges: Flow<*>,
    private val live: LiveNames = LiveNames.None,
    /** The names the offline WhatsApp store has for a number (its one-to-one chat), or null. */
    private val stored: suspend (number: String) -> WaNames? = { null },
    /** A number as people read it (`+90 555 000 00 01`). */
    private val readable: suspend (number: String) -> String = { it },
) {
    /** Moves when a name may read differently: the address book changed or an asked WhatsApp name arrived. */
    val changes: Flow<Long> =
        combine(contactChanges.scan(0L) { n, _ -> n + 1 }, live.revision) { book, asked -> book + asked }
            .distinctUntilChanged()

    /**
     * The name of [number]; [wa] is what the caller already holds from the WhatsApp store for it. [ask] false only
     * looks: a caller that has not read its stored names yet must not ask WhatsApp for a name it may already hold.
     */
    suspend fun of(
        number: String,
        wa: WaNames = WaNames.None,
        ask: Boolean = true,
    ): PersonName = resolve(number, wa, contactName(number), ask)

    /** [of] for a number the caller already knows no contact owns (the inbox merge looked): no address-book lookup. */
    suspend fun unsaved(
        number: String,
        wa: WaNames = WaNames.None,
    ): PersonName = resolve(number, wa, null, ask = true)

    /**
     * The name of the WhatsApp user [jid]. A JID that carries no phone number (a LID) can only be named by [wa]; with
     * nothing at all its user part stands in, so a row is never blank.
     */
    suspend fun ofJid(
        jid: String?,
        wa: WaNames = WaNames.None,
        ask: Boolean = true,
    ): PersonName {
        val number = jid?.let(::numberOfJid)
        if (number != null) return of(number, wa, ask)
        return pick(null, wa, null) ?: PersonName(jid?.substringBefore('@').orEmpty(), NameTier.Number)
    }

    /** Who wrote a group message, or whom a quote or a typing line names: [carried] is the push name it came with. */
    suspend fun sender(
        jid: String?,
        carried: String?,
    ): String = ofJid(jid, WaNames(push = carried)).text

    /** The title of a WhatsApp chat: a group keeps its subject; a person is named by the rule. */
    suspend fun chatTitle(
        jid: String,
        chat: RichChat?,
    ): String = if (isGroup(jid, chat?.isGroup)) groupTitle(jid, chat?.name) else ofJid(jid, WaNames.of(chat)).text

    /** [chatTitle] for a chat read from the offline store; [ask] false while the store has not been read yet. */
    suspend fun chatTitle(
        jid: String,
        chat: WaSyncChat?,
        ask: Boolean = true,
    ): String = if (isGroup(jid, chat?.isGroup)) groupTitle(jid, chat?.name) else ofJid(jid, WaNames.of(chat), ask).text

    private suspend fun resolve(
        number: String,
        wa: WaNames,
        contact: String?,
        ask: Boolean,
    ): PersonName {
        pick(contact, WaNames.None, null)?.let { return it }
        val known = wa.orElse(stored(number))
        val named = pick(null, known, live.nameOf(number))
        if (named != null) return named
        if (ask) live.ask(number)
        return PersonName(readable(number), NameTier.Number)
    }

    companion object {
        private const val GROUP_SUFFIX = "@g.us"

        /**
         * Pure: the rule over what is known. [contact] first, then [wa]'s verified and push names, then the name
         * asked from WhatsApp ([live], already verified-then-push), then the stored chat name. Blank names are
         * skipped; null when nobody names the person.
         */
        fun pick(
            contact: String?,
            wa: WaNames,
            live: String?,
        ): PersonName? {
            WaNames.clean(contact)?.let { return PersonName(it, NameTier.Contact) }
            WaNames.clean(wa.verified)?.let { return PersonName(it, NameTier.Verified) }
            WaNames.clean(wa.push)?.let { return PersonName(it, NameTier.Push) }
            WaNames.clean(live)?.let { return PersonName(it, NameTier.Push) }
            WaNames.clean(wa.chat)?.let { return PersonName(it, NameTier.Chat) }
            return null
        }

        /** Pure: the WhatsApp-side title of a chat with no contact applied: a group's subject, else [pick]. */
        fun storedTitle(chat: RichChat): String? =
            if (chat.isGroup) WaNames.clean(chat.name) else pick(null, WaNames.of(chat), null)?.text

        private fun isGroup(
            jid: String,
            flag: Boolean?,
        ): Boolean = flag ?: jid.endsWith(GROUP_SUFFIX)

        private fun groupTitle(
            jid: String,
            subject: String?,
        ): String = WaNames.clean(subject) ?: jid.substringBefore('@')
    }
}
