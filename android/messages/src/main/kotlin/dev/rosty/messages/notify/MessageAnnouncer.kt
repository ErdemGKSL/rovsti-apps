package dev.rosty.messages.notify

import android.content.Context
import dev.rosty.core.Outcome
import dev.rosty.core.model.messages.Incoming
import dev.rosty.core.model.messages.MessagesPrefs
import dev.rosty.core.model.messages.NotifyRequest
import dev.rosty.core.model.messages.OtpRequest
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.MessageMeta
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.WaIds
import dev.rosty.core.ops.MessagesOps
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.names.WaNames
import dev.rosty.messages.wa.WaRekeys
import dev.rosty.messages.wa.isConversation
import dev.rosty.platform.PlatformEvents
import dev.rosty.platform.notify.NotificationSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** The recent incoming messages of each notified conversation, newest last (GPUI `notifications::Digests`). */
class Digests(
    private val maxLines: Int = MAX_LINES,
) {
    private val byTag = HashMap<String, ArrayList<Incoming>>()

    /** Adds [incoming] and answers the conversation's lines, oldest first. */
    @Synchronized
    fun push(incoming: Incoming): List<Incoming> {
        val lines = byTag.getOrPut(incoming.thread) { ArrayList() }
        lines += incoming
        while (lines.size > maxLines) lines.removeAt(0)
        return lines.toList()
    }

    /**
     * The conversation [from] is [into] now: its lines join those of [into], in the order they came, under the tag
     * [into]. Nothing happens when [from] has none.
     */
    @Synchronized
    fun rekey(
        from: String,
        into: String,
    ) {
        val moved = byTag.remove(from) ?: return
        if (from == into) {
            byTag[from] = moved
            return
        }
        val lines = byTag.getOrPut(into) { ArrayList() }
        lines += moved.map { it.copy(thread = into) }
        lines.sortBy { it.atMs }
        while (lines.size > maxLines) lines.removeAt(0)
    }

    /** The lines kept for [tag], oldest first. */
    @Synchronized
    fun lines(tag: String): List<Incoming> = byTag[tag].orEmpty().toList()

    /** Forgets a conversation (its notification was taken away). */
    @Synchronized
    fun clear(tag: String) {
        byTag.remove(tag)
    }

    /** Forgets every conversation whose tag starts with [prefix]. */
    @Synchronized
    fun clearAll(prefix: String) {
        byTag.keys.removeAll { it.startsWith(prefix) }
    }

    companion object {
        /** `rosty_core::notify::MAX_LINES`. */
        const val MAX_LINES = 6
    }
}

/** What the announcer needs from the app; the container wires it, tests give fakes. */
class AnnouncerSources(
    val ops: MessagesOps,
    val prefs: StateFlow<MessagesPrefs?>,
    /** Reads the preferences file once (it needs the native library). */
    val loadPrefs: suspend () -> Unit,
    /** The gateway events of the linked WhatsApp instance as they come. */
    val live: Flow<GatewayEvent>,
    /** The stored chat of a JID (title, group, mute), or null. */
    val chatOf: suspend (jid: String) -> RichChat?,
    /** The name-order rule: who a chat or a group sender is. */
    val names: PersonNames,
    /** The one-line text of a message body ("Photo", a caption, the text). */
    val preview: (MessageBody) -> String,
    /** True when a push service posts the WhatsApp digest while no UI is attached (the firebase flavor, AD-MSG5). */
    val pushWake: Boolean,
    val now: () -> Long,
    /** E6-WA R4: the linked account's own ids (number and LID) as last heard; empty when unknown. */
    val ownIds: () -> List<String> = { emptyList() },
)

/**
 * The notifications the app makes itself while its process runs (GPUI `notifications.rs` and `notify_whatsapp`).
 *
 * SMS and MMS are posted by their receivers, attached or not; this class keeps [NotifyPolicy] in step with the
 * preferences so they honour the switches and the mutes. WhatsApp has no receiver: a message that arrives on the event
 * stream is put into its conversation's digest, Rust builds the spec (`notify.build`, with the passcode of `otp.find`)
 * and [post] renders it. With no UI attached that is left to the push service when there is one ([pushWake]), which
 * asks Rust for the digest itself; without one (the plain flavor) the stream is the only source, so it notifies too.
 * When Rust cannot build the spec the conversation still gets a plain notification with its lines and a reply.
 */
class MessageAnnouncer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sources: AnnouncerSources,
    private val post: (Context, Digest) -> Boolean = MessagesNotifier::show,
    /** What the notification of a tag shows now (to rename it in place). */
    private val shown: (Context, String) -> Digest? = MessagesNotifier::shown,
) {
    private val digests = Digests()
    private val started = AtomicBoolean(false)
    private val seen = LinkedHashSet<String>()

    /** Messages of WhatsApp-muted chats, until their details say whether they address me (E6-WA R4). */
    private val held = LinkedHashMap<String, GatewayEvent.Message>()

    /** Follows the preferences and the event stream from now on. Idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        MessagesNotifier.onDismissed = digests::clear
        scope.launch {
            sources.loadPrefs()
            sources.prefs.filterNotNull().collect { NotifyPolicy.current = NotifyPolicy.of(it) }
        }
        scope.launch {
            sources.live.collect { event ->
                when (event) {
                    is GatewayEvent.Message -> onWhatsApp(event)
                    is GatewayEvent.MessageMetaChanged -> onMeta(event)
                    else -> Unit
                }
            }
        }
    }

    /**
     * The chat [from] merged into [into] (`wa:` tags): the lines collected for [from] go on under [into], so the
     * next message of the person continues one notification.
     */
    fun rekey(
        from: String,
        into: String,
    ) = digests.rekey(from, into)

    /** The WhatsApp account is another one, or the link is gone: the lines collected for its chats are dropped. */
    fun forgetWhatsApp() = digests.clearAll(WaRekeys.tagOf(""))

    /** One live WhatsApp message: notify unless it is mine, old, muted, or its chat is on screen. */
    internal suspend fun onWhatsApp(message: GatewayEvent.Message): Boolean {
        // The stream names the chat by the store's key ([WaOffline.live]).
        val jid = message.chat
        val tag = WaRekeys.tagOf(jid)
        if (message.fromMe || !isChat(jid)) return false
        if (!PlatformEvents.uiAttached && sources.pushWake) return false
        // A reconnect replays what was missed: history is not news, and nothing is announced twice.
        if (sources.now() - message.timestampMs > RECENT_MS || !firstTime(message.id)) return false
        if (OpenConversation.isShowing(tag)) return false
        val chat = sources.chatOf(jid)
        if (chat != null && !chat.isConversation()) return false
        // Muted in WhatsApp (here or on another device): stay quiet, unless its details (they follow the message)
        // say it mentions me or answers me (E6-WA R4).
        val mutedUntil = chat?.meta?.mutedUntilMs
        if (mutedUntil != null && (mutedUntil < 0 || mutedUntil > sources.now())) {
            hold(message)
            return false
        }
        return announce(message, chat)
    }

    /**
     * E6-WA R4: the details of a message held back by a WhatsApp mute arrived. WhatsApp lets a message that mentions
     * me, or quotes one of mine, through a muted chat; so does Rosty.
     */
    internal suspend fun onMeta(event: GatewayEvent.MessageMetaChanged): Boolean {
        val message = synchronized(held) { held.remove(event.id) } ?: return false
        if (message.chat != event.chat || !addressesMe(event.meta)) return false
        if (OpenConversation.isShowing(WaRekeys.tagOf(message.chat))) return false
        return announce(message, sources.chatOf(message.chat))
    }

    /** `rosty_core::wa_actions::addresses_me`: one of my ids is mentioned, or the quoted message is mine. */
    private fun addressesMe(meta: MessageMeta): Boolean {
        val own = sources.ownIds()
        if (own.isEmpty()) return false
        val mine = { jid: String? -> own.any { WaIds.same(it, jid) } }
        return meta.mentions.any(mine) || mine(meta.quoted?.sender)
    }

    private fun hold(message: GatewayEvent.Message) =
        synchronized(held) {
            held[message.id] = message
            if (held.size > HELD_MAX) held.remove(held.keys.first())
        }

    private suspend fun announce(
        message: GatewayEvent.Message,
        chat: RichChat?,
    ): Boolean {
        val jid = message.chat
        val tag = WaRekeys.tagOf(jid)
        val group = chat?.isGroup ?: WaIds.isGroup(jid)
        val body = sources.preview(message.body)
        val incoming =
            Incoming(
                thread = tag,
                senderTitle = if (group) sources.names.sender(message.sender, null) else "",
                body = body,
                atMs = message.timestampMs,
                verdictSpam = false,
            )
        val recent = digests.push(incoming)
        val who = if (group) null else sources.names.ofJid(jid, WaNames.of(chat))
        val title = who?.text ?: sources.names.chatTitle(jid, chat)
        val spec = plan(title, group, recent, body) ?: return false
        val posted = post(context, Digest(spec))
        // Nobody names the number yet: WhatsApp was asked, and the notification is renamed when the answer lands.
        if (posted && who != null && !who.named) {
            NotificationNames.renameLater(
                scope,
                sources.names,
                spec.tag,
                who = { sources.names.ofJid(jid, WaNames.of(chat)) },
                shown = { shown(context, it) },
                post = { post(context, it) },
            )
        }
        return posted
    }

    /** Rust's digest for the conversation; a plain one when Rust gives none; null when only spam is left. */
    internal suspend fun plan(
        title: String,
        group: Boolean,
        recent: List<Incoming>,
        newest: String,
    ): NotificationSpec? {
        val code = (sources.ops.otp(OtpRequest(newest)) as? Outcome.Ok)?.value?.otp?.code
        return when (val answer = sources.ops.notify(NotifyRequest(title, group, recent, code))) {
            is Outcome.Ok -> {
                answer.value.spec?.let { NotificationSpec(it.tag, it.title, it.lines, it.otp, it.reply, it.silent) }
            }

            is Outcome.Err -> {
                val lines =
                    recent.map {
                        if (group &&
                            it.senderTitle.isNotEmpty()
                        ) {
                            "${it.senderTitle}: ${it.body}"
                        } else {
                            it.body
                        }
                    }
                NotificationSpec(recent.last().thread, title, lines, otp = null, reply = true, silent = false)
            }
        }
    }

    private fun isChat(jid: String): Boolean = WaIds.isConversation(jid)

    private fun firstTime(id: String): Boolean =
        synchronized(seen) {
            if (!seen.add(id)) return false
            if (seen.size > SEEN_MAX) {
                val oldest = seen.iterator()
                oldest.next()
                oldest.remove()
            }
            true
        }

    companion object {
        /** A message older than this when it arrives was missed, not new. */
        const val RECENT_MS = 10 * 60_000L
        private const val SEEN_MAX = 500
        private const val HELD_MAX = 64
    }
}
