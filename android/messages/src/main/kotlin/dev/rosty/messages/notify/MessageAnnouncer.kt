package dev.rosty.messages.notify

import android.content.Context
import dev.rosty.core.Outcome
import dev.rosty.core.model.messages.Incoming
import dev.rosty.core.model.messages.MessagesPrefs
import dev.rosty.core.model.messages.NotifyRequest
import dev.rosty.core.model.messages.OtpRequest
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.ops.MessagesOps
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.names.WaNames
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

    /** Forgets a conversation (its notification was taken away). */
    @Synchronized
    fun clear(tag: String) {
        byTag.remove(tag)
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

    /** Follows the preferences and the event stream from now on. Idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        MessagesNotifier.onDismissed = digests::clear
        scope.launch {
            sources.loadPrefs()
            sources.prefs.filterNotNull().collect { NotifyPolicy.current = NotifyPolicy.of(it) }
        }
        scope.launch {
            sources.live.collect { event -> if (event is GatewayEvent.Message) onWhatsApp(event) }
        }
    }

    /** One live WhatsApp message: notify unless it is mine, old, muted, or its chat is on screen. */
    internal suspend fun onWhatsApp(message: GatewayEvent.Message): Boolean {
        val jid = message.chat
        val tag = "wa:$jid"
        if (message.fromMe || !isChat(jid)) return false
        if (!PlatformEvents.uiAttached && sources.pushWake) return false
        // A reconnect replays what was missed: history is not news, and nothing is announced twice.
        if (sources.now() - message.timestampMs > RECENT_MS || !firstTime(message.id)) return false
        if (OpenConversation.isShowing(tag)) return false
        val chat = sources.chatOf(jid)
        if (chat != null && !chat.isConversation()) return false
        // Muted in WhatsApp (here or on another device): stay quiet.
        val mutedUntil = chat?.meta?.mutedUntilMs
        if (mutedUntil != null && (mutedUntil < 0 || mutedUntil > sources.now())) return false
        val group = chat?.isGroup ?: jid.endsWith(GROUP_SUFFIX)
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

    private fun isChat(jid: String): Boolean =
        jid != STATUS_JID && !jid.endsWith("@newsletter") && !jid.endsWith("@broadcast")

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
        private const val GROUP_SUFFIX = "@g.us"
        private const val STATUS_JID = "status@broadcast"
    }
}
