package dev.rosty.messages.wa

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.getOrNull
import dev.rosty.core.model.chat.SearchJumpRequest
import dev.rosty.core.model.chat.SearchJumpStep
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.MediaKind
import dev.rosty.core.model.wa.MediaMeta
import dev.rosty.core.model.wa.MediaWaveform
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.TypingState
import dev.rosty.core.model.wa.Unavailable
import dev.rosty.core.model.wa.WaIds
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncChat
import dev.rosty.core.model.wa.WaSyncCursor
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMediaState
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.design.text.UiText
import dev.rosty.messages.R
import dev.rosty.messages.conversation.AttachmentUi
import dev.rosty.messages.conversation.BubbleMedia
import dev.rosty.messages.conversation.BubbleMediaKind
import dev.rosty.messages.conversation.BubbleQuote
import dev.rosty.messages.conversation.BubbleState
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.conversation.ComposerMode
import dev.rosty.messages.conversation.ConversationEffect
import dev.rosty.messages.conversation.ConversationUi
import dev.rosty.messages.conversation.DayLabels
import dev.rosty.messages.conversation.MediaAction
import dev.rosty.messages.conversation.RecordUi
import dev.rosty.messages.conversation.ReplyUi
import dev.rosty.messages.conversation.VoiceUi
import dev.rosty.messages.conversation.withDays
import dev.rosty.messages.inbox.InboxWords
import dev.rosty.messages.inbox.RowAvatar
import dev.rosty.messages.inbox.duration
import dev.rosty.messages.inbox.waPreview
import dev.rosty.messages.names.PersonNames
import dev.rosty.platform.audio.VoiceRecorder
import dev.rosty.platform.media.MediaProbe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** The words of a WhatsApp thread, from `strings.xml` (English and Turkish). */
interface WaWords {
    val placeholder: String
    val sending: String

    /** A message written offline: "Waiting for connection". */
    val waiting: String
    val notSent: String
    val now: String

    /** The notice while the gateway cannot be reached. */
    val offline: String
    val syncing: String
    val you: String
    val deleted: String
    val youDeleted: String

    /** "typing…" under the title of a one-to-one chat; with a name in a group. */
    fun typing(
        name: String?,
        recording: Boolean,
    ): String

    /** "Edited 14:05". */
    fun edited(clock: String): String

    fun unread(count: Int): String

    /** "1.2 MB": the size of a file as people read it. */
    fun size(bytes: Long): String

    /** What a bubble says about a file the gateway no longer has. */
    val gone: String get() = ""

    /** A view-once message someone sent: only their phone and mine can open it (E6-WA R3). */
    val viewOnceOpenPhone: String get() = "View once message. Open it on your phone."

    /** A view-once message I sent from my phone; and the label of one whose kind is not known. */
    val viewOnce: String get() = "View once message"

    /** A view-once photo, video or voice note I sent (I cannot open it again either). */
    fun viewOnceOf(kind: MediaKind): String =
        when (kind) {
            MediaKind.Image -> "View once photo"
            MediaKind.Video -> "View once video"
            MediaKind.Audio -> "View once voice message"
            else -> viewOnce
        }

    /** A bot's or a hosted business's message, which WhatsApp keeps from linked devices. */
    val unavailableOpenPhone: String get() = "This message is not available on this device. Open it on your phone."
}

/** What a tap on a bubble's media asks the page to do (the page owns the viewer, the pickers and the intents). */
sealed interface WaMediaEffect {
    /**
     * Open the viewer on the photo or video [row] (the bubble [key]): at once, with its thumbnail; the viewer downloads
     * what is not on the phone and pages through the chat's other photos and videos.
     */
    data class ShowViewer(
        val key: String,
        val row: WaSyncMessage,
    ) : WaMediaEffect

    /** Hand a downloaded video or document to an app that shows it. */
    data class Open(
        val file: File,
        val mime: String,
        val name: String,
    ) : WaMediaEffect

    /** Save a copy to the phone's gallery or downloads (MediaStore), recorded on the message [chat] / [id]. */
    data class Save(
        val file: File,
        val mime: String,
        val name: String,
        val picture: Boolean,
        val chat: String = "",
        val id: String = "",
    ) : WaMediaEffect

    /** "Forward to": the chooser, then the chat picked with [file] attached and [caption] in its composer. */
    data class Forward(
        val file: File,
        val mime: String,
        val name: String,
        val caption: String,
    ) : WaMediaEffect

    data class Share(
        val file: File,
        val mime: String,
        val name: String,
    ) : WaMediaEffect
}

/** Who wrote a group message, for the mini card (M50). */
data class SenderCardUi(
    val jid: String,
    val name: String,
    /** E.164, when the JID carries a phone number. */
    val number: String?,
    /** [number] as people read it. */
    val numberLabel: String?,
    val avatar: RowAvatar,
    /** In the address book: "Open contact"; else "Add contact". */
    val saved: Boolean,
)

/** The drafts of the threads (`drafts.json` under `wa:<jid>`). */
interface WaDrafts {
    suspend fun load(tag: String): String

    suspend fun put(
        tag: String,
        text: String,
    )
}

/** What a WhatsApp thread reads and writes; the page wires the real ones, tests pass fakes. */
class WaThreadSources(
    val offline: WaOffline,
    val remote: WaRemote,
    val drafts: WaDrafts,
    /** The name-order rule: the chat's title, the group senders, the quotes, the typing line. */
    val names: PersonNames,
    /** Rust's `unread_jump.divider`: the index (oldest first) the unread run begins at. */
    val divider: suspend (incoming: List<Boolean>, unread: Int) -> Int? = { _, _ -> null },
    /** Rust's `global_search.jump`: the next step towards a search hit that is not loaded; null: it cannot say. */
    val searchJump: suspend (SearchJumpRequest) -> SearchJumpStep? = { null },
    val now: () -> Long,
    /** Thumbnails, downloads and the files to send (E6.4b); null: media rows show what they are, nothing loads. */
    val media: WaMedia? = null,
    /** The voice note player (M17). */
    val voice: WaVoiceNotes? = null,
    /** The emoji used last for reactions (M49). */
    val emoji: WaRecentEmoji = WaRecentEmoji(null),
    /** Copies a picked or pasted document (a content URI) into app storage. */
    val stage: suspend (uri: String) -> WaStageResult = { WaStageResult.Failed },
    /** A number as people read it (`+90 555 000 00 01`). */
    val readable: suspend (number: String) -> String = { it },
    /** The key changes of the store, after what is kept per chat was moved ([WaRekeys.rekeys]). */
    val rekeys: Flow<Rekey> = emptyFlow(),
    /** The microphone of the composer's voice notes (E6-WA R3); null: the composer has no microphone. */
    val recorder: VoiceRecorder? = null,
    /** Plays a recorded take before it is sent. */
    val preview: VoiceEngine? = null,
    /** Where a take is recorded; null: next to the files to send ([WaMedia.outgoing]). */
    val voiceFile: (() -> File?)? = null,
    /** A monotonic clock in milliseconds, for the length of a take. */
    val recordClock: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    /** Measures a photo or video to send (size, length, preview); null: they go out without them. */
    val probe: MediaProbe? = null,
)

private const val NANOS_PER_MS = 1_000_000L

/**
 * A WhatsApp conversation (E6.4a) read from the offline store only (E6.4s): `wa_sync.thread` pages, read again when
 * the sync or the outbox changed the chat. Sending, retrying, editing, deleting, reacting and read marks go into the
 * outbox, so they show at once and work without a connection. Scrolling past the oldest stored message asks the
 * gateway for the page before it; without a connection what is stored stays readable.
 *
 * The thread follows its person, not an id. The JID it was opened with ([routeJid]: a route argument, a
 * notification tag, a hand-off) is resolved to the key the store keeps the chat under before anything is read, and
 * when the store later learns that this chat is another one's (`rekeys`: a LID that turned out to be a number, a
 * number that changed) the thread swaps its key and goes on: the page stays, what it shows stays until the merged
 * rows are read, and the draft, the reply bar and the attachment are kept.
 */
class WaThreadViewModel(
    private val sources: WaThreadSources,
    routeJid: String,
    private val words: WaWords,
    private val preview: InboxWords,
    private val days: DayLabels,
) : ViewModel() {
    private val ui = MutableStateFlow(ConversationUi(composer = ComposerMode.Ready(words.placeholder)))
    val state: StateFlow<ConversationUi> = ui.asStateFlow()

    private val effectChannel = Channel<ConversationEffect>(Channel.BUFFERED)
    val effects: Flow<ConversationEffect> = effectChannel.receiveAsFlow()

    private val mediaChannel = Channel<WaMediaEffect>(Channel.BUFFERED)

    /** What the page does for a tapped picture or file. */
    val mediaEffects: Flow<WaMediaEffect> = mediaChannel.receiveAsFlow()

    /** The emoji of the reaction bar, most recently used first (M49); the message menu reads it. */
    val recentEmoji: StateFlow<List<String>> get() = sources.emoji.recent

    private val noVoice = MutableStateFlow<VoiceUi?>(null)

    /** The voice note in the player. */
    val voice: StateFlow<VoiceUi?> get() = sources.voice?.state ?: noVoice

    /**
     * The voice note recorder of the composer (E6-WA R3); null without a microphone. A take goes out through the
     * outbox as a voice note, with its length and waveform, answering the quoted message like a text would.
     */
    val recording: VoiceRecording? =
        sources.recorder?.let { recorder ->
            VoiceRecording(
                scope = viewModelScope,
                recorder = recorder,
                preview = sources.preview ?: NoVoiceEngine,
                newFile = sources.voiceFile ?: { sources.media?.outgoing(UUID.randomUUID().toString(), VOICE_FILE) },
                discard = ::discardTake,
                clock = sources.recordClock,
                chatState = { state -> if (sources.offline.online.value) sources.remote.typing(jid, state) },
                hint = { text -> effectChannel.trySend(ConversationEffect.Toast(text)) },
                onTake = ::sendVoice,
            )
        }

    private val noRecord = MutableStateFlow<RecordUi?>(null)

    /** The voice note being recorded in the composer. */
    val record: StateFlow<RecordUi?> get() = recording?.state ?: noRecord

    /** Received voice notes whose played receipt was asked for in this visit. */
    private val playedAsked = HashSet<String>()

    private val revealChannel = Channel<String>(Channel.BUFFERED)

    /** Bubble keys the page scrolls to and lets glow (a search hit opened from the inbox). */
    val reveals: Flow<String> = revealChannel.receiveAsFlow()

    private val reloads =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Stored messages to show; grows by a page when older ones are asked for. */
    private var wanted = PAGE
    private var rows: List<WaSyncMessage> = emptyList()
    private var chat: WaSyncChat? = null
    private var chatRead = false
    private var hasStoredOlder = false
    private var reachedStart = false
    private var syncing = false
    private val senders = HashMap<Pair<String?, String?>, String>()
    private val marked = HashSet<String>()
    private var unreadAtOpen: Int? = null
    private var typing: String? = null
    private var typingClear: Job? = null
    private var lastTypingSentMs = 0L
    private var visible = false

    private val batchState = MutableStateFlow<WaSendBatch?>(null)

    /**
     * The send preview of picked files (E6-WA R3): null while nothing is picked. Each file goes out as its own message,
     * in this order, with its caption; a photo or video can go as view once.
     */
    val batch: StateFlow<WaSendBatch?> = batchState.asStateFlow()

    /** The message the composer answers (its id), and the file that goes out with the next Send. */
    private var replyTo: String? = null
    private var attachment: WaStagedFile? = null
    private var mediaJob: Job? = null
    private val thumbsTried = HashSet<String>()
    private val filesTried = HashSet<String>()
    private var lastTapKey: String? = null
    private var lastTapMs = 0L

    private val key = MutableStateFlow(WaIds.bare(routeJid) ?: routeJid)

    /** The key the chat is stored under: the id the thread was opened with until it is resolved, then the store's. */
    val chatJid: StateFlow<String> = key.asStateFlow()

    /** [chatJid] now. */
    val jid: String get() = key.value

    /** The tag of drafts and notifications. */
    val tag: String get() = WaRekeys.tagOf(jid)

    /** The numbers of the person of a one-to-one chat, the current one first; read with the stored rows. */
    private var numbers: List<String> = listOfNotNull(WaIds.number(jid))

    init {
        viewModelScope.launch {
            sources.offline.session.collectLatest { session ->
                if (session == null) {
                    rows = emptyList()
                    chat = null
                    publish()
                    return@collectLatest
                }
                adopt(session)
                merge(
                    sources.offline.changes
                        .filter { it == null || jid in it }
                        .conflate(),
                    reloads,
                ).onStart { emit(Unit) }
                    .conflate()
                    .collect { reload(session) }
            }
        }
        viewModelScope.launch {
            sources.offline.online.collect { online ->
                publish()
                if (online) {
                    // What could not be loaded without a connection is tried again.
                    thumbsTried.clear()
                    filesTried.clear()
                    prefetch()
                }
            }
        }
        sources.media?.let { media ->
            viewModelScope.launch { media.busy.collect { publish() } }
        }
        sources.voice?.let { voice ->
            viewModelScope.launch {
                voice.prepare()
                voice.speedLabel.collect { publish() }
            }
        }
        viewModelScope.launch { sources.emoji.load() }
        viewModelScope.launch {
            // A contact was saved, or a WhatsApp name that was asked for arrived: everyone is named again.
            sources.names.changes.collect {
                senders.clear()
                publish()
            }
        }
        viewModelScope.launch { sources.offline.live.collect(::onLive) }
        viewModelScope.launch {
            sources.rekeys.collect { step ->
                // Two ids became one person: whoever was named by one of them is named again.
                senders.clear()
                // This chat is another one's now: the same page under the new key, read again.
                if (step.from == jid) swap(step.into) else reloads.tryEmit(Unit)
            }
        }
    }

    /** Resolves the id the thread holds to the key its chat is stored under. */
    private suspend fun adopt(session: WaSession) {
        val found =
            session.store
                .resolve(jid)
                .getOrNull()
                ?.key ?: return
        if (found != jid) swap(found)
    }

    /** The thread goes on under [into]: nothing on the page is cleared, the rows of the merged chat are read. */
    private fun swap(into: String) {
        if (into == jid) return
        key.value = into
        senders.clear()
        reloads.tryEmit(Unit)
    }

    private suspend fun reload(session: WaSession) {
        val loaded = ArrayList<WaSyncMessage>()
        var cursor: WaSyncCursor? = null
        var stored = 0
        while (true) {
            val limit = (wanted - stored).coerceIn(1, MAX_STORE_PAGE)
            val page = session.store.thread(jid, limit, cursor).getOrNull() ?: break
            loaded += page.items
            stored += page.items.count { it.state !in OUTBOX_STATES }
            cursor = page.next
            hasStoredOlder = page.next != null
            reachedStart = page.reachedStart
            syncing = page.syncing
            if (cursor == null || stored >= wanted) break
        }
        // The store answers the rows of a merged chat under the key they live in now: the thread takes that key.
        loaded
            .firstOrNull()
            ?.chat
            ?.takeIf { it != jid && it.isNotEmpty() }
            ?.let { key.value = it }
        rows = loaded
        chat =
            session.store
                .chats(includeArchived = true)
                .getOrNull()
                ?.firstOrNull { it.jid == jid }
        numbers = sources.names.numbersOf(jid)
        chatRead = true
        if (unreadAtOpen == null) unreadAtOpen = chat?.unread ?: 0
        publish()
        markRead()
        prefetch()
    }

    /**
     * Loads what the loaded messages still miss: the thumbnails of pictures and videos one after the other, then
     * (connected) the files the store's auto-download rules allow on this network (photos, voice notes, on Wi-Fi
     * stickers). A file the cap took, a video and a document wait for a tap. Each message is offered once per
     * connection.
     */
    private fun prefetch() {
        val media = sources.media ?: return
        if (mediaJob?.isActive == true) return
        val stored = rows.filter { !it.deleted && it.state !in OUTBOX_STATES }
        val thumbs =
            stored.filter {
                it.kind in VISUAL_KINDS && media.thumb(
                    it,
                ) == null && it.mediaPath == null && placeholderOf(it) == null &&
                    (it.hasThumbnail || it.media?.thumbnail == true) && it.id !in thumbsTried
            }
        val files =
            if (sources.offline.online.value) {
                stored.filter {
                    it.kind in MEDIA_KINDS && it.mediaPath == null && it.id !in filesTried &&
                        (it.mediaState == null || it.mediaState == WaSyncMediaState.None)
                }
            } else {
                emptyList()
            }
        if (thumbs.isEmpty() && files.isEmpty()) return
        mediaJob =
            viewModelScope.launch {
                var fresh = false
                for (row in thumbs) {
                    thumbsTried += row.id
                    if (media.ensureThumb(row)) fresh = true
                }
                if (fresh) publish()
                filesTried += files.map { it.id }
                // A finished download tells the store, and the store's change reads the page again.
                media.want(files)
            }
    }

    // ---- state

    private suspend fun publish() {
        val now = sources.now()
        val group = chat?.isGroup ?: WaIds.isGroup(jid)
        // Oldest first, the order `withDays` and the divider want.
        val bubbles = rows.asReversed().map { bubbleOf(it, group, now) }
        // Until the stored chat was read its names are unknown: the number shows, WhatsApp is not asked yet.
        val title = sources.names.chatTitle(jid, chat, ask = chatRead)
        // A LID whose number the store knows shows that number; a hidden one shows none.
        val number = numbers.firstOrNull()?.takeIf { !group }
        val online = sources.offline.online.value
        ui.value =
            ui.value.copy(
                title = title,
                // The number as people read it (`+90 555 000 00 01`), as on the SMS header.
                subtitle = typing ?: number?.let { sources.readable(it) }?.takeIf { !group && it != title },
                avatar =
                    RowAvatar(
                        numbers = (if (group) emptyList() else numbers).toImmutableList(),
                        waJid = jid,
                        pictureId = chat?.pictureId,
                        group = group,
                        colorKey = number ?: jid,
                    ),
                items = withDays(bubbles, days, now, dividerOf(bubbles)).toImmutableList(),
                loaded = true,
                hasOlder = hasStoredOlder || (!reachedStart && rows.isNotEmpty()),
                composer = ComposerMode.Ready(words.placeholder),
                reply = replyOf(),
                attachment =
                    attachment?.let {
                        AttachmentUi(it.name, words.size(it.size), it.file.takeIf { _ -> it.mime.startsWith("image/") })
                    },
                voiceSpeed =
                    sources.voice
                        ?.speedLabel
                        ?.value
                        .orEmpty(),
                notice =
                    when {
                        !online -> words.offline
                        syncing -> words.syncing
                        else -> null
                    },
            )
    }

    /**
     * Who wrote a group message, by the name-order rule: the address-book name, the name WhatsApp carries, the
     * number. Looked up once per sender until the names change.
     */
    private suspend fun senderOf(
        sender: String?,
        carried: String?,
    ): String {
        val push =
            carried?.takeIf { it.isNotBlank() }
                // A quote or a typing line names no one: the name a loaded message of theirs carried.
                ?: rows.firstOrNull { it.sender == sender && !it.senderName.isNullOrBlank() }?.senderName
        val key = sender to push
        return senders[key] ?: sources.names.sender(sender, push).also { senders[key] = it }
    }

    private suspend fun bubbleOf(
        row: WaSyncMessage,
        group: Boolean,
        now: Long,
    ): BubbleUi {
        val state =
            when (row.state) {
                WaSyncRowState.Received -> BubbleState.None
                WaSyncRowState.Sent -> BubbleState.Sent
                WaSyncRowState.Delivered -> BubbleState.Delivered
                WaSyncRowState.Read -> BubbleState.Read
                WaSyncRowState.Played -> BubbleState.Played
                WaSyncRowState.Waiting -> BubbleState.Waiting
                WaSyncRowState.Sending -> BubbleState.Sending
                WaSyncRowState.Failed -> BubbleState.Failed
            }
        val clock = if (now - row.timestampMs in 0 until NOW_MS) words.now else days.clock(row.timestampMs)
        val meta =
            when {
                state == BubbleState.Waiting -> words.waiting
                state == BubbleState.Sending -> words.sending
                state == BubbleState.Failed -> words.notSent
                row.editedMs != null && !row.deleted -> words.edited(clock)
                else -> clock
            }
        val isMedia = row.kind in MEDIA_KINDS
        val placeholder = if (row.deleted) null else placeholderOf(row)
        val text =
            when {
                row.deleted -> if (row.deletedByMe()) words.youDeleted else words.deleted

                // A placeholder says what it is in its row; a view-once send of mine keeps its caption.
                placeholder != null -> if (isMedia) row.text.orEmpty() else ""

                row.kind == WaSyncKind.Text || isMedia -> row.text.orEmpty()

                else -> waPreview(row.toBody(), row.toMeta(), preview)
            }
        val media =
            when {
                placeholder != null -> persistentListOf(placeholder)
                isMedia && !row.deleted -> persistentListOf(mediaOf(row, state))
                else -> persistentListOf()
            }
        return BubbleUi(
            key = keyOf(row),
            outgoing = row.fromMe,
            text = text,
            meta = meta,
            state = if (row.fromMe) state else BubbleState.None,
            atMs = row.timestampMs,
            media = media,
            sender = if (group && !row.fromMe) senderOf(row.sender, row.senderName) else null,
            quote = if (row.deleted) null else quoteOf(row),
            forwarded = row.forwarded && !row.deleted,
            edited = row.editedMs != null,
            deleted = row.deleted,
            reactions = reactionsOf(row),
            senderId = if (group && !row.fromMe) row.sender else null,
            canReply = !row.deleted && row.state !in OUTBOX_STATES,
            starred = row.starred && !row.deleted,
            markId = row.id.takeIf { it != keyOf(row) },
        )
    }

    /**
     * The quote above a reply. A reply still in the outbox carries only the id of what it answers, so author and
     * preview come from the loaded message.
     */
    private suspend fun quoteOf(row: WaSyncMessage): BubbleQuote? {
        val quoted = row.quoted ?: return null
        val target = rows.firstOrNull { it.id == quoted.id }
        val queued = row.state in OUTBOX_STATES && target != null
        val mine = if (queued) target?.fromMe == true else quoted.sender == null
        val author =
            when {
                mine -> words.you
                queued -> senderOf(target?.sender, target?.senderName)
                else -> senderOf(quoted.sender, null)
            }
        return BubbleQuote(
            author = author,
            text = quoted.preview ?: target?.let(::snippetOf).orEmpty(),
            targetKey = quoted.id,
        )
    }

    /** One line of a message for a quote: its text, else what its attachment is. */
    private fun snippetOf(row: WaSyncMessage): String = row.text?.takeIf { it.isNotBlank() } ?: mediaLabel(row)

    private suspend fun replyOf(): ReplyUi? {
        val row = replyTo?.let { id -> rows.firstOrNull { it.id == id } } ?: return null
        val author = if (row.fromMe) words.you else senderOf(row.sender, row.senderName)
        return ReplyUi(keyOf(row), author, snippetOf(row))
    }

    /**
     * What this device cannot show (E6-WA R3): a message that reached it as a placeholder only (a view-once message
     * someone sent, a bot's message), or a view-once photo, video or voice note I sent (I cannot open it again either).
     * Null for everything else.
     */
    private fun placeholderOf(row: WaSyncMessage): BubbleMedia? {
        val reason = row.unavailable
        val label =
            when {
                reason == Unavailable.ViewOnce -> if (row.fromMe) words.viewOnce else words.viewOnceOpenPhone
                reason != null -> words.unavailableOpenPhone
                row.fromMe && row.media?.viewOnce == true && row.kind in MEDIA_KINDS -> words.viewOnceOf(mediaKind(row))
                else -> return null
            }
        return BubbleMedia(
            model = null,
            label = label,
            isImage = false,
            kind = BubbleMediaKind.Unavailable,
            viewOnce = reason == null || reason == Unavailable.ViewOnce,
        )
    }

    private fun mediaKind(row: WaSyncMessage): MediaKind = (row.toBody() as? MessageBody.Media)?.kind ?: MediaKind.Image

    /** How the attachment of [row] is drawn: the picture, the player, the file row. */
    private fun mediaOf(
        row: WaSyncMessage,
        state: BubbleState,
    ): BubbleMedia {
        val media = sources.media
        val queued = row.state in OUTBOX_STATES
        val local = row.mediaPath?.let(::File)?.takeIf { it.isFile }
        val busy = state == BubbleState.Sending || media?.busy?.value?.contains(row.id) == true
        val meta = row.media
        val label = mediaLabel(row)
        val length = meta?.seconds?.let(::duration)
        val size = row.size?.takeIf { it > 0 }?.let(words::size)
        // The gateway no longer has the file: the bubble says so where it would say the size or the length.
        val gone = words.gone.takeIf { local == null && row.mediaState == WaSyncMediaState.Gone && it.isNotEmpty() }
        return when (row.kind) {
            WaSyncKind.Sticker -> {
                BubbleMedia(
                    model = local ?: media?.thumb(row),
                    label = label,
                    isImage = true,
                    kind = BubbleMediaKind.Sticker,
                    busy = busy,
                    local = local != null,
                    animated = local?.takeIf { meta?.animated == true },
                    width = meta?.width,
                    height = meta?.height,
                )
            }

            WaSyncKind.Image -> {
                BubbleMedia(
                    model = local ?: media?.thumb(row),
                    label = label,
                    isImage = true,
                    aspect = aspectOf(meta),
                    kind = BubbleMediaKind.Picture,
                    detail = gone,
                    busy = busy,
                    local = local != null,
                    viewOnce = meta?.viewOnce == true,
                )
            }

            WaSyncKind.Video -> {
                BubbleMedia(
                    model = media?.thumb(row),
                    label = label,
                    isImage = false,
                    aspect = aspectOf(meta),
                    kind = BubbleMediaKind.Video,
                    detail = gone ?: listOfNotNull(length, size).joinToString(" · ").ifEmpty { null },
                    busy = busy,
                    local = local != null,
                    loop = local?.takeIf { meta?.animated == true },
                    viewOnce = meta?.viewOnce == true,
                )
            }

            WaSyncKind.Audio -> {
                BubbleMedia(
                    model = null,
                    label = label,
                    isImage = false,
                    kind = BubbleMediaKind.Voice,
                    detail = gone ?: length ?: size,
                    busy = busy,
                    local = local != null,
                    // The 64 bars WhatsApp carries for a voice note; without them the plain bar.
                    waveform =
                        meta
                            ?.waveform()
                            ?.let(VoiceWaveform::bars)
                            ?.toImmutableList() ?: persistentListOf(),
                    viewOnce = meta?.viewOnce == true,
                )
            }

            else -> {
                // An outbox row carries no file name: the staged file is kept under the name it was picked with.
                val name = meta?.fileName?.takeIf { it.isNotBlank() } ?: local?.takeIf { queued }?.name
                val type = (name ?: local?.name)?.substringAfterLast('.', "")?.takeIf { it.length in 1..MAX_TYPE }
                BubbleMedia(
                    model = null,
                    label = name ?: label,
                    isImage = false,
                    kind = BubbleMediaKind.File,
                    detail = gone ?: listOfNotNull(type?.uppercase(), size).joinToString(" · ").ifEmpty { null },
                    busy = busy,
                    local = local != null,
                )
            }
        }
    }

    private fun aspectOf(meta: MediaMeta?): Float? {
        val width = meta?.width?.takeIf { it > 0 } ?: return null
        val height = meta.height?.takeIf { it > 0 } ?: return null
        return (width.toFloat() / height).coerceIn(MIN_ASPECT, MAX_ASPECT)
    }

    /** "Photo", "Voice message (0:12)": what the attachment is, without its caption. */
    private fun mediaLabel(row: WaSyncMessage): String {
        val body = row.toBody()
        return waPreview(if (body is MessageBody.Media) body.copy(caption = null) else body, row.toMeta(), preview)
    }

    private fun reactionsOf(row: WaSyncMessage): String? {
        if (row.deleted || row.reactions.isEmpty()) return null
        return row.reactions
            .groupingBy { it.emoji }
            .eachCount()
            .entries
            .joinToString(" ") { (emoji, count) -> if (count > 1) "$emoji $count" else emoji }
    }

    private suspend fun dividerOf(bubbles: List<BubbleUi>): Pair<String, String>? {
        val unread = unreadAtOpen?.takeIf { it > 0 } ?: return null
        val at = sources.divider(bubbles.map { !it.outgoing }, unread) ?: return null
        return bubbles.getOrNull(at)?.let { it.key to words.unread(unread) }
    }

    // ---- reading

    /** The page is on screen (or not): only then are incoming messages marked read. */
    fun setVisible(now: Boolean) {
        visible = now
        if (now) viewModelScope.launch { markRead() }
    }

    /** Queues read receipts for the unread incoming messages on the page (by sender in a group). */
    private suspend fun markRead() {
        if (!visible) return
        val unread = chat?.unread ?: return
        if (unread <= 0) return
        val fresh =
            rows
                .asSequence()
                .filter { !it.fromMe && it.state == WaSyncRowState.Received && !it.deleted }
                .take(unread)
                .filter { marked.add(it.id) }
                .toList()
        if (fresh.isEmpty()) return
        val group = chat?.isGroup == true
        // A receipt names the author by the id the message arrived under (a LID in a LID-addressed group).
        for ((sender, list) in fresh.groupBy { if (group) it.wireSender ?: it.sender else null }) {
            sources.offline.enqueue(jid, WaSyncAction.MarkRead(sender, list.map { it.id }))
        }
    }

    // ---- paging

    /**
     * Shows a page more: from the store when it has older messages, else from the gateway (the page before the oldest
     * stored one). Offline, what is stored stays and a pill says why nothing came.
     */
    fun loadOlder() {
        if (ui.value.loadingOlder || !ui.value.hasOlder) return
        viewModelScope.launch { olderNow(quiet = false) }
    }

    private suspend fun olderNow(quiet: Boolean) {
        ui.value = ui.value.copy(loadingOlder = true)
        if (hasStoredOlder) {
            wanted += PAGE
        } else {
            when (sources.offline.older(jid)) {
                OlderResult.Stored -> {
                    wanted += PAGE
                }

                OlderResult.ReachedStart -> {
                    reachedStart = true
                }

                OlderResult.Offline -> {
                    if (!quiet) {
                        effectChannel.trySend(ConversationEffect.Toast(UiText.Res(R.string.wa_thread_older_offline)))
                    }
                }
            }
        }
        val session = sources.offline.session.value
        if (session != null) reload(session) else publish()
        ui.value = ui.value.copy(loadingOlder = false)
    }

    /**
     * Works towards the message [messageId] of [atMs] (a search hit): reveals it when it is loaded, else loads older
     * pages as Rust's `search_jump` says, and says so when it is further back than that.
     */
    fun reveal(
        messageId: String,
        atMs: Long?,
    ) {
        viewModelScope.launch {
            ui.first { it.loaded && (rows.isNotEmpty() || sources.offline.settled.value) }
            var pages = 1
            while (pages <= MAX_JUMP_PAGES) {
                val found = rows.firstOrNull { it.id == messageId || it.clientId == messageId }
                if (found != null) {
                    revealChannel.trySend(keyOf(found))
                    return@launch
                }
                val target = atMs ?: break
                val oldest = rows.lastOrNull()?.timestampMs
                val request = SearchJumpRequest(true, ui.value.hasOlder, pages, oldest, target)
                if (sources.searchJump(request) != SearchJumpStep.LoadOlder) break
                val before = rows.size
                olderNow(quiet = true)
                if (rows.size == before) break
                pages++
            }
            effectChannel.trySend(ConversationEffect.Toast(UiText.Res(R.string.tools_further_back)))
        }
    }

    // ---- composer

    suspend fun initialDraft(fromLink: String?): String {
        if (fromLink != null) return fromLink
        // The draft is kept under the chat's key: an id from before a merge is resolved first.
        sources.offline.session.value
            ?.let { adopt(it) }
        return sources.drafts.load(tag)
    }

    fun onDraft(text: String) {
        viewModelScope.launch {
            sources.drafts.put(tag, text)
            // "typing…" for the other side, at most once every few seconds and only while connected.
            val now = sources.now()
            if (text.isNotBlank() && sources.offline.online.value && now - lastTypingSentMs >= TYPING_EVERY_MS) {
                lastTypingSentMs = now
                sources.remote.typing(jid, TypingState.Typing)
            }
        }
    }

    /**
     * Queues the message: it is on the page at once, with a clock until the gateway has it. With a file above the
     * composer the file goes out and [text] is its caption; with a quoted bar it is a reply.
     */
    fun send(text: String) {
        val body = text.trim()
        val file = attachment
        if (body.isEmpty() && file == null) return
        val quoted = replyTo
        attachment = null
        replyTo = null
        viewModelScope.launch {
            sources.drafts.put(tag, "")
            lastTypingSentMs = 0
            val action =
                if (file != null) {
                    WaSyncAction.SendMedia(
                        file = file.file.absolutePath,
                        media = mediaKindOf(file.mime),
                        mime = file.mime,
                        size = file.size,
                        caption = body.ifEmpty { null },
                        quotedId = quoted,
                        fileName = file.name,
                    )
                } else {
                    WaSyncAction.SendText(body, quoted)
                }
            if (!sources.offline.enqueue(jid, action)) {
                attachment = file
                replyTo = quoted
                effectChannel.trySend(ConversationEffect.Toast(UiText.Res(R.string.wa_thread_not_linked)))
                effectChannel.trySend(ConversationEffect.Restore(body))
            }
            publish()
        }
    }

    // ---- replies (M31)

    /** The composer answers the message [key] from now on (a swipe to the right, "Reply" in the message menu). */
    fun startReply(key: String) {
        val row = rowOf(key)?.takeIf { !it.deleted && it.state !in OUTBOX_STATES } ?: return
        replyTo = row.id
        viewModelScope.launch { publish() }
    }

    /** The × of the quoted bar. */
    fun cancelReply() {
        if (replyTo == null) return
        replyTo = null
        viewModelScope.launch { publish() }
    }

    // ---- attachments

    /**
     * A picked or pasted document ([uri], a content URI): copied into app storage and kept above the composer until
     * Send. A file over WhatsApp's 16 MiB is refused with a pill.
     */
    fun attach(uri: String) {
        viewModelScope.launch {
            when (val result = sources.stage(uri)) {
                is WaStageResult.Staged -> {
                    if (result.file.size > WaMedia.MAX_SEND_BYTES) {
                        sources.media?.discard(result.file.file.absolutePath)
                        tooLarge()
                    } else {
                        attachment?.let { sources.media?.discard(it.file.absolutePath) }
                        attachment = result.file
                        publish()
                    }
                }

                WaStageResult.TooLarge -> {
                    tooLarge()
                }

                WaStageResult.Failed -> {
                    effectChannel.trySend(ConversationEffect.Toast(UiText.Res(R.string.wa_media_cant_attach)))
                }
            }
        }
    }

    /**
     * Picked or shared files ([uris], content URIs), one or many: copied into app storage, measured and put in the send
     * preview after what is there. A file over WhatsApp's 16 MiB is left out with a pill; the others still come.
     */
    fun pick(uris: List<String>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = ArrayList<WaBatchItem>()
            var large = 0
            var failed = 0
            for (uri in uris) {
                when (val result = sources.stage(uri)) {
                    is WaStageResult.Staged -> {
                        if (result.file.size > WaMedia.MAX_SEND_BYTES) {
                            sources.media?.discard(result.file.file.absolutePath)
                            large++
                        } else {
                            added +=
                                WaBatchItem(UUID.randomUUID().toString(), result.file, measure = measure(result.file))
                        }
                    }

                    WaStageResult.TooLarge -> {
                        large++
                    }

                    WaStageResult.Failed -> {
                        failed++
                    }
                }
            }
            if (large > 0) {
                val megabytes = (WaMedia.MAX_SEND_BYTES / BYTES_PER_MB).toInt()
                effectChannel.trySend(
                    ConversationEffect.Toast(UiText.Plural(R.plurals.wa_batch_too_large, large, listOf(megabytes))),
                )
            }
            if (failed > 0) effectChannel.trySend(ConversationEffect.Toast(UiText.Res(R.string.wa_media_cant_attach)))
            if (added.isNotEmpty()) batchState.value = (batchState.value ?: WaSendBatch()).add(added)
        }
    }

    private suspend fun measure(file: WaStagedFile): dev.rosty.platform.media.MediaMeasure? {
        val probe = sources.probe ?: return null
        val kind = mediaKindOf(file.mime)
        if (kind != MediaKind.Image && kind != MediaKind.Video) return null
        return probe.measure(file.file, file.mime)
    }

    /** The preview shows item [index]. */
    fun batchSelect(index: Int) {
        batchState.value = batchState.value?.select(index)
    }

    fun batchCaption(
        id: String,
        text: String,
    ) {
        batchState.value = batchState.value?.caption(id, text)
    }

    fun batchViewOnce(id: String) {
        batchState.value = batchState.value?.toggleViewOnce(id)
    }

    /** Moves item [id] [by] places (negative: earlier). */
    fun batchMove(
        id: String,
        by: Int,
    ) {
        batchState.value = batchState.value?.move(id, by)
    }

    /** Takes item [id] out of the preview (its copy is deleted); the last one closes the preview. */
    fun batchRemove(id: String) {
        val batch = batchState.value ?: return
        val item = batch.items.firstOrNull { it.id == id } ?: return
        val rest = batch.remove(id)
        batchState.value = rest.takeIf { !it.isEmpty }
        viewModelScope.launch { sources.media?.discard(item.file.file.absolutePath) }
    }

    /** Closes the preview without sending: the copies are deleted. */
    fun batchClose() {
        val batch = batchState.value ?: return
        batchState.value = null
        viewModelScope.launch { for (item in batch.items) sources.media?.discard(item.file.file.absolutePath) }
    }

    /**
     * Sends the preview: every file its own message through the outbox, in order, with its caption, its options and
     * (the first one) the reply of the quoted bar. Without a linked account nothing goes and the preview stays.
     */
    fun batchSend() {
        val batch = batchState.value?.takeIf { !it.isEmpty } ?: return
        val quoted = replyTo
        batchState.value = null
        replyTo = null
        viewModelScope.launch {
            val actions = batch.actions(quoted)
            var sent = 0
            for (action in actions) {
                if (!sources.offline.enqueue(jid, action)) break
                sent++
            }
            if (sent < actions.size) {
                // What did not go stays in the preview, in its order.
                batchState.value = WaSendBatch(batch.items.drop(sent))
                if (sent == 0) replyTo = quoted
                effectChannel.trySend(ConversationEffect.Toast(UiText.Res(R.string.wa_thread_not_linked)))
            }
            publish()
        }
    }

    private fun tooLarge() {
        val megabytes = (WaMedia.MAX_SEND_BYTES / BYTES_PER_MB).toInt()
        effectChannel.trySend(ConversationEffect.Toast(UiText.Res(R.string.wa_media_too_large, listOf(megabytes))))
    }

    /** The × of the file above the composer. */
    fun removeAttachment() {
        val file = attachment ?: return
        attachment = null
        viewModelScope.launch {
            sources.media?.discard(file.file.absolutePath)
            publish()
        }
    }

    // ---- media of a bubble

    /** The file of [row] on the phone: the one being sent, the cached one, else downloaded now. */
    private suspend fun fileOf(row: WaSyncMessage): File? {
        val local = row.mediaPath?.let(::File)?.takeIf { it.isFile }
        if (row.state in OUTBOX_STATES) return local
        val file = sources.media?.fetch(row) ?: local
        if (file == null) {
            val gone = sources.media?.gone(row) == true
            val why = if (gone) R.string.wa_media_gone else R.string.wa_media_cant_download
            effectChannel.trySend(ConversationEffect.Toast(UiText.Res(why)))
        }
        return file
    }

    private fun fileName(
        row: WaSyncMessage,
        file: File,
    ): String = row.media?.fileName?.takeIf { it.isNotBlank() } ?: file.name

    /**
     * A tap on a bubble with an attachment: a picture or a video opens in the viewer at once (which downloads it), a
     * voice note plays or pauses, a document is downloaded and handed to an app that shows it. False: the bubble has
     * nothing to open.
     */
    fun openMedia(key: String): Boolean {
        val row = rowOf(key)?.takeIf { !it.deleted && it.kind in MEDIA_KINDS } ?: return false
        // A sticker is all there is to see; a view-once send of mine cannot be opened again.
        if (row.kind == WaSyncKind.Sticker || placeholderOf(row) != null) return false
        if (row.kind == WaSyncKind.Audio) {
            toggleVoice(key)
            return true
        }
        if (row.kind in VISUAL_KINDS) {
            mediaChannel.trySend(WaMediaEffect.ShowViewer(key, row))
            return true
        }
        viewModelScope.launch {
            val file = fileOf(row) ?: return@launch
            mediaChannel.trySend(WaMediaEffect.Open(file, row.mime.orEmpty(), fileName(row, file)))
        }
        return true
    }

    /**
     * Save, Share or Forward of a received file (downloaded first when it is not on the phone). A view-once message
     * offers none of them.
     */
    fun mediaAction(
        key: String,
        action: MediaAction,
    ) {
        val row = rowOf(key)?.takeIf { !it.deleted && it.kind in MEDIA_KINDS && it.media?.viewOnce != true } ?: return
        viewModelScope.launch {
            val file = fileOf(row) ?: return@launch
            val name = fileName(row, file)
            val mime = row.mime.orEmpty()
            mediaChannel.trySend(
                when (action) {
                    MediaAction.Save -> {
                        WaMediaEffect.Save(file, mime, name, row.kind in PICTURE_KINDS, row.chat, row.id)
                    }

                    MediaAction.Share -> {
                        WaMediaEffect.Share(file, mime, name)
                    }

                    MediaAction.Forward -> {
                        WaMediaEffect.Forward(file, mime, name, row.text.orEmpty())
                    }
                },
            )
        }
    }

    // ---- voice notes (M17)

    /**
     * Play or pause of the voice note [key]; another note that was playing stops. The first play of a received voice
     * note queues its played receipt (blue microphone on the other phone), once; my own notes never send one.
     */
    fun toggleVoice(key: String) {
        val voice = sources.voice ?: return
        val row = rowOf(key)?.takeIf { !it.deleted && it.kind == WaSyncKind.Audio } ?: return
        viewModelScope.launch {
            val file = fileOf(row) ?: return@launch
            if (!voice.toggle(key, file.absolutePath)) {
                effectChannel.trySend(ConversationEffect.Toast(UiText.Res(R.string.wa_voice_cant_play)))
                return@launch
            }
            if (playedOwed(row) && playedAsked.add(row.id)) sources.offline.markPlayed(jid, row.id)
        }
    }

    /** A received voice note whose played receipt has not been queued yet. */
    private fun playedOwed(row: WaSyncMessage): Boolean =
        !row.fromMe && !row.played && row.media?.voiceNote == true && row.state !in OUTBOX_STATES

    /** A recorded take goes out as a voice note through the outbox (E6-WA R3). */
    private suspend fun sendVoice(take: VoiceTake) {
        val quoted = replyTo
        replyTo = null
        val action =
            WaSyncAction.SendMedia(
                file = take.file.absolutePath,
                media = MediaKind.Audio,
                mime = VoiceRecording.MIME,
                size = take.file.length(),
                quotedId = quoted,
                seconds = take.seconds,
                voiceNote = true,
                waveformB64 = MediaWaveform.encode(take.waveform),
            )
        if (!sources.offline.enqueue(jid, action)) {
            replyTo = quoted
            discardTake(take.file)
            effectChannel.trySend(ConversationEffect.Toast(UiText.Res(R.string.wa_thread_not_linked)))
        }
        publish()
    }

    private suspend fun discardTake(file: File) {
        val media = sources.media
        if (media != null) media.discard(file.absolutePath) else file.delete()
    }

    /** The thread is gone for good: a take that was not sent is thrown away. */
    override fun onCleared() {
        recording?.dispose()
        sources.preview?.release()
    }

    /** Scrubbing the bar of the note in the player to [fraction] (0..1). */
    fun seekVoice(
        key: String,
        fraction: Float,
    ) {
        viewModelScope.launch { sources.voice?.seek(key, fraction) }
    }

    /** The speed pill: 1×, 1.5×, 2×, remembered. */
    fun nextVoiceSpeed() {
        viewModelScope.launch { sources.voice?.nextSpeed() }
    }

    /** The page is left: nothing keeps playing. */
    fun stopVoice() {
        sources.voice?.stop()
    }

    // ---- the sender card (M50)

    /** Who [senderJid] is, for the card a tap on a sender name of a group opens. */
    suspend fun senderCard(senderJid: String): SenderCardUi {
        val all = sources.names.numbersOf(senderJid)
        val number = all.firstOrNull()
        return SenderCardUi(
            jid = senderJid,
            name = senderOf(senderJid, null),
            number = number,
            numberLabel = number?.let { sources.readable(it) },
            avatar =
                RowAvatar(
                    numbers = all.toImmutableList(),
                    waJid = senderJid,
                    colorKey = number ?: senderJid,
                ),
            // A contact that holds the current number or a former one.
            saved = number != null && sources.names.ofJid(senderJid, ask = false).saved,
        )
    }

    // ---- bubble actions

    private fun rowOf(key: String): WaSyncMessage? = rows.firstOrNull { keyOf(it) == key }

    /** A tap on a failed message of mine: let it go out again. */
    fun retry(key: String) {
        val row = rowOf(key)?.takeIf { it.state == WaSyncRowState.Failed } ?: return
        viewModelScope.launch { sources.offline.retry(jid, row.clientId ?: row.id) }
    }

    /** Takes a message that has not gone out from the queue; [restore] puts its text back into the composer. */
    fun cancel(
        key: String,
        restore: Boolean = false,
    ) {
        val row = rowOf(key)?.takeIf { it.state in OUTBOX_STATES } ?: return
        viewModelScope.launch {
            if (!sources.offline.cancel(jid, row.clientId ?: row.id)) return@launch
            val file = row.mediaPath?.let(::File)?.takeIf { row.kind in MEDIA_KINDS }
            if (restore) {
                effectChannel.trySend(ConversationEffect.Restore(row.text.orEmpty()))
                // A queued file goes back above the composer with its caption.
                if (file != null && file.isFile) {
                    attachment?.let { sources.media?.discard(it.file.absolutePath) }
                    attachment = WaStagedFile(file, file.name, row.mime.orEmpty(), row.size ?: file.length())
                    publish()
                }
            } else if (file != null) {
                sources.media?.discard(file.absolutePath)
            }
        }
    }

    fun copy(key: String) {
        rowOf(key)?.takeIf { !it.deleted }?.text?.takeIf { it.isNotEmpty() }?.let {
            effectChannel.trySend(ConversationEffect.Copy(it))
        }
    }

    /** Deletes a sent message of mine for everyone (queued; the store keeps its content, marked deleted). */
    fun revoke(key: String) {
        val row = rowOf(key)?.takeIf { it.fromMe && it.state !in OUTBOX_STATES && !it.deleted } ?: return
        viewModelScope.launch {
            sources.offline.enqueue(
                jid,
                WaSyncAction.Revoke(row.id, row.wireSender ?: row.sender),
            )
        }
    }

    /** Changes the text of a sent message of mine (queued). */
    fun edit(
        key: String,
        text: String,
    ) {
        val row = rowOf(key)?.takeIf { it.fromMe && it.state !in OUTBOX_STATES && !it.deleted } ?: return
        val body = text.trim().takeIf { it.isNotEmpty() && it != row.text } ?: return
        viewModelScope.launch { sources.offline.enqueue(jid, WaSyncAction.Edit(row.id, body)) }
    }

    /** Reacts with [emoji] (empty: takes my reaction back), queued. */
    fun react(
        key: String,
        emoji: String,
    ) {
        val row = rowOf(key)?.takeIf { it.state !in OUTBOX_STATES && !it.deleted } ?: return
        viewModelScope.launch {
            // The message key names its author by the id the message arrived under.
            val author = row.wireSender ?: row.sender
            if (sources.offline.enqueue(jid, WaSyncAction.React(row.id, row.fromMe, author, emoji))) {
                sources.emoji.note(emoji)
            } else {
                effectChannel.trySend(ConversationEffect.Toast(UiText.Res(R.string.wa_thread_not_linked)))
            }
        }
    }

    /**
     * A tap on the bubble [key]. The second tap within [DOUBLE_TAP_MS] on a text message reacts with the default
     * emoji, or takes that reaction back (M32). True when the tap was that double tap.
     */
    fun tap(key: String): Boolean {
        val now = sources.now()
        val double = lastTapKey == key && now - lastTapMs in 0..DOUBLE_TAP_MS
        lastTapKey = if (double) null else key
        lastTapMs = now
        if (!double) return false
        val row =
            rowOf(key)?.takeIf { it.kind == WaSyncKind.Text && it.state !in OUTBOX_STATES && !it.deleted }
                ?: return false
        val mine = row.reactions.firstOrNull { it.fromMe }?.emoji
        react(key, if (mine == WaRecentEmoji.DEFAULT) "" else WaRecentEmoji.DEFAULT)
        return true
    }

    // ---- typing

    /** The events of [WaOffline.live] name their chat and people by the store's keys, as [jid] is. */
    private suspend fun onLive(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.Typing -> {
                if (event.chat != jid) return
                typingClear?.cancel()
                if (event.state == TypingState.Paused) {
                    typing = null
                } else {
                    val group = chat?.isGroup ?: WaIds.isGroup(jid)
                    val who = if (group) senderOf(event.jid, null) else null
                    typing = words.typing(who, event.state == TypingState.Recording)
                    // WhatsApp sends no "paused" when the other side just stops: the line clears on its own.
                    typingClear =
                        viewModelScope.launch {
                            delay(TYPING_SHOWN_MS)
                            typing = null
                            publish()
                        }
                }
                publish()
            }

            is GatewayEvent.Message -> {
                // Their message arrived: they are not typing any more.
                if (event.chat == jid && !event.fromMe && typing != null) {
                    typingClear?.cancel()
                    typing = null
                    publish()
                }
            }

            else -> {
                Unit
            }
        }
    }

    companion object {
        /** Stored messages per page. */
        const val PAGE = 50
        private const val MAX_JUMP_PAGES = 12
        private const val MAX_STORE_PAGE = 500
        private const val NOW_MS = 60_000L
        private const val TYPING_EVERY_MS = 5_000L
        const val TYPING_SHOWN_MS = 12_000L
        private val OUTBOX_STATES = setOf(WaSyncRowState.Waiting, WaSyncRowState.Sending, WaSyncRowState.Failed)
        private val MEDIA_KINDS =
            setOf(WaSyncKind.Image, WaSyncKind.Video, WaSyncKind.Audio, WaSyncKind.Document, WaSyncKind.Sticker)
        private val PICTURE_KINDS = setOf(WaSyncKind.Image, WaSyncKind.Sticker)
        private val VISUAL_KINDS = setOf(WaSyncKind.Image, WaSyncKind.Sticker, WaSyncKind.Video)
        private const val MIN_ASPECT = 0.6f
        private const val MAX_ASPECT = 1.9f
        private const val MAX_TYPE = 5
        private const val BYTES_PER_MB = 1024L * 1024L

        /** The file name of a recorded voice note (WhatsApp shows none). */
        private const val VOICE_FILE = "voice-note.ogg"

        /** Two taps this close together are a double tap (Android's own timeout). */
        const val DOUBLE_TAP_MS = 300L

        /** What a picked file is sent as. */
        fun mediaKindOf(mime: String): MediaKind =
            when {
                mime.startsWith("image/") -> MediaKind.Image
                mime.startsWith("video/") -> MediaKind.Video
                mime.startsWith("audio/") -> MediaKind.Audio
                else -> MediaKind.Document
            }

        /** Stable from the moment a message is written: the outbox client id it keeps after it went out. */
        fun keyOf(row: WaSyncMessage): String = row.clientId ?: row.id
    }
}
