package dev.rosty.messages.notify

import android.content.Context
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.names.PersonName
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.names.WaNames
import dev.rosty.shell.HasContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The name-order rule on notifications. A notification of a number nobody names is posted with the number at once;
 * WhatsApp is asked in the background and, when a name arrives, the notification is posted again with it, silently
 * (no second sound, the same lines).
 */
object NotificationNames {
    /** How long a posted notification waits for the name WhatsApp was asked for. */
    const val RENAME_WAIT_MS = 30_000L

    /** How long a receiver waits for the names already on the device before it posts the number. */
    private const val LOOKUP_MS = 1_500L
    private const val MIN_DIGITS = 7
    private const val NUMBER_PUNCTUATION = "+ -()."
    private const val GROUP_SUFFIX = "@g.us"

    /**
     * Waits for [who] to be named ([PersonNames.changes] moves when an asked name lands or a contact is saved) and
     * renames the notification of [tag]: [shown] is what it shows now, [post] puts the renamed one in its place.
     */
    fun renameLater(
        scope: CoroutineScope,
        names: PersonNames,
        tag: String,
        who: suspend () -> PersonName,
        shown: (tag: String) -> Digest?,
        post: (Digest) -> Boolean,
    ) {
        scope.launch {
            val name =
                withTimeoutOrNull(RENAME_WAIT_MS) {
                    names.changes
                        .map { who() }
                        .first { it.named }
                } ?: return@launch
            val current = shown(tag) ?: return@launch
            if (current.spec.title == name.text) return@launch
            post(current.copy(spec = current.spec.copy(title = name.text, silent = true)))
        }
    }

    /**
     * Posts the SMS/MMS notification [specJson] the receivers built. They looked the sender up in the address book;
     * when the title is still a bare number, the rule goes on here: the WhatsApp name (stored, or asked for now),
     * else the number as people read it. Runs on a receiver worker thread.
     */
    fun showIncoming(
        context: Context,
        specJson: String,
    ): Boolean {
        val digest = Digest.parse(specJson) ?: return false
        val number = digest.spec.title.takeIf(::isNumber)
        val container = (context.applicationContext as? HasContainer<*>)?.container as? MessagesContainer
        if (number == null || container == null) return MessagesNotifier.show(context, digest)
        val names = container.names
        val who = runBlocking { withTimeoutOrNull(LOOKUP_MS) { names.unsaved(number) } }
        val titled = digest.copy(spec = digest.spec.copy(title = who?.text ?: number))
        val posted = MessagesNotifier.show(context, titled)
        if (posted && who?.named != true) {
            renameLater(
                container.scope,
                names,
                digest.spec.tag,
                who = { names.unsaved(number) },
                shown = { tag -> MessagesNotifier.shown(context, tag) },
                post = { next -> MessagesNotifier.show(context, next) },
            )
        }
        return posted
    }

    /**
     * Posts the WhatsApp notification [specJson] the core built for [chat] while the app was closed (the push path).
     * The core names a person by the push name; the rule goes on here for a one-to-one chat: a saved contact first,
     * that push name next, the readable number last, renamed later when WhatsApp answers. A group keeps its title.
     */
    fun showWaPush(
        context: Context,
        chat: String,
        specJson: String,
    ): Boolean {
        val digest = Digest.parse(specJson) ?: return false
        val container = (context.applicationContext as? HasContainer<*>)?.container as? MessagesContainer
        if (container == null || chat.endsWith(GROUP_SUFFIX)) return MessagesNotifier.show(context, digest)
        val names = container.names
        val who = suspend { names.ofJid(chat, waNamesOfTitle(digest.spec.title)) }
        val name = runBlocking { withTimeoutOrNull(LOOKUP_MS) { who() } }
        val posted = MessagesNotifier.show(context, retitled(digest, name?.text))
        if (posted && name?.named != true) {
            renameLater(
                container.scope,
                names,
                digest.spec.tag,
                who = who,
                shown = { tag -> MessagesNotifier.shown(context, tag) },
                post = { next -> MessagesNotifier.show(context, next) },
            )
        }
        return posted
    }

    /** Pure: what the core's push title says about the person: a name is their push name, a number says nothing. */
    fun waNamesOfTitle(title: String): WaNames = if (isNumber(title)) WaNames.None else WaNames(push = title)

    /** Pure: [digest] under [title]; unchanged when there is none. */
    fun retitled(
        digest: Digest,
        title: String?,
    ): Digest = if (title.isNullOrBlank()) digest else digest.copy(spec = digest.spec.copy(title = title))

    /** Pure: a title that is a phone number (not a contact name, not a sender id like `GARANTI`). */
    fun isNumber(title: String): Boolean =
        title.count(Char::isDigit) >= MIN_DIGITS && title.all { it.isDigit() || it in NUMBER_PUNCTUATION }
}
