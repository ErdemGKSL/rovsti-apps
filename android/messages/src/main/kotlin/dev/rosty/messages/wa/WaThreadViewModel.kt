package dev.rosty.messages.wa

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.getOrNull
import dev.rosty.core.model.chat.SearchJumpRequest
import dev.rosty.core.model.chat.SearchJumpStep
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.TypingState
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncChat
import dev.rosty.core.model.wa.WaSyncCursor
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.design.text.UiText
import dev.rosty.messages.R
import dev.rosty.messages.conversation.BubbleMedia
import dev.rosty.messages.conversation.BubbleQuote
import dev.rosty.messages.conversation.BubbleState
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.conversation.ComposerMode
import dev.rosty.messages.conversation.ConversationEffect
import dev.rosty.messages.conversation.ConversationUi
import dev.rosty.messages.conversation.DayLabels
import dev.rosty.messages.conversation.withDays
import dev.rosty.messages.inbox.InboxWords
import dev.rosty.messages.inbox.RowAvatar
import dev.rosty.messages.inbox.numberOfJid
import dev.rosty.messages.inbox.waPreview
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

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
}

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
    /** The address-book name of a number, or null. */
    val nameOf: suspend (number: String) -> String?,
    /** Emits when the address book changed: names are looked up again. */
    val bookChanges: Flow<*>,
    /** Rust's `unread_jump.divider`: the index (oldest first) the unread run begins at. */
    val divider: suspend (incoming: List<Boolean>, unread: Int) -> Int? = { _, _ -> null },
    /** Rust's `global_search.jump`: the next step towards a search hit that is not loaded; null: it cannot say. */
    val searchJump: suspend (SearchJumpRequest) -> SearchJumpStep? = { null },
    val now: () -> Long,
)

/**
 * A WhatsApp conversation (E6.4a) read from the offline store only (E6.4s): `wa_sync.thread` pages, read again when
 * the sync or the outbox changed the chat. Sending, retrying, editing, deleting, reacting and read marks go into the
 * outbox, so they show at once and work without a connection. Scrolling past the oldest stored message asks the
 * gateway for the page before it; without a connection what is stored stays readable.
 */
class WaThreadViewModel(
    private val sources: WaThreadSources,
    val jid: String,
    private val words: WaWords,
    private val preview: InboxWords,
    private val days: DayLabels,
) : ViewModel() {
    private val ui = MutableStateFlow(ConversationUi(composer = ComposerMode.Ready(words.placeholder)))
    val state: StateFlow<ConversationUi> = ui.asStateFlow()

    private val effectChannel = Channel<ConversationEffect>(Channel.BUFFERED)
    val effects: Flow<ConversationEffect> = effectChannel.receiveAsFlow()

    private val revealChannel = Channel<String>(Channel.BUFFERED)

    /** Bubble keys the page scrolls to and lets glow (a search hit opened from the inbox). */
    val reveals: Flow<String> = revealChannel.receiveAsFlow()

    private val reloads =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Stored messages to show; grows by a page when older ones are asked for. */
    private var wanted = PAGE
    private var rows: List<WaSyncMessage> = emptyList()
    private var chat: WaSyncChat? = null
    private var hasStoredOlder = false
    private var reachedStart = false
    private var syncing = false
    private val names = HashMap<String, String?>()
    private val marked = HashSet<String>()
    private var unreadAtOpen: Int? = null
    private var typing: String? = null
    private var typingClear: Job? = null
    private var lastTypingSentMs = 0L
    private var visible = false

    /** The tag of drafts and notifications. */
    val tag: String get() = "wa:$jid"

    init {
        viewModelScope.launch {
            sources.offline.session.collectLatest { session ->
                if (session == null) {
                    rows = emptyList()
                    chat = null
                    publish()
                    return@collectLatest
                }
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
        viewModelScope.launch { sources.offline.online.collect { publish() } }
        viewModelScope.launch {
            sources.bookChanges.collect {
                names.clear()
                publish()
            }
        }
        viewModelScope.launch { sources.offline.live.collect(::onLive) }
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
        rows = loaded
        chat =
            session.store
                .chats(includeArchived = true)
                .getOrNull()
                ?.firstOrNull { it.jid == jid }
        if (unreadAtOpen == null) unreadAtOpen = chat?.unread ?: 0
        publish()
        markRead()
    }

    // ---- state

    private suspend fun publish() {
        val now = sources.now()
        val group = chat?.isGroup ?: jid.endsWith(GROUP_SUFFIX)
        // Oldest first, the order `withDays` and the divider want.
        val bubbles = rows.asReversed().map { bubbleOf(it, group, now) }
        val title = titleOf(group)
        val number = numberOfJid(jid)
        val online = sources.offline.online.value
        ui.value =
            ui.value.copy(
                title = title,
                subtitle = typing ?: number?.takeIf { !group && it != title },
                avatar =
                    RowAvatar(
                        numbers = listOfNotNull(number).toImmutableList(),
                        waJid = jid,
                        pictureId = chat?.pictureId,
                        group = group,
                        colorKey = number ?: jid,
                    ),
                items = withDays(bubbles, days, now, dividerOf(bubbles)).toImmutableList(),
                loaded = true,
                hasOlder = hasStoredOlder || (!reachedStart && rows.isNotEmpty()),
                composer = ComposerMode.Ready(words.placeholder),
                notice =
                    when {
                        !online -> words.offline
                        syncing -> words.syncing
                        else -> null
                    },
            )
    }

    private suspend fun titleOf(group: Boolean): String {
        val stored = chat?.name?.takeIf { it.isNotBlank() }
        if (group) return stored ?: jid.substringBefore('@')
        val number = numberOfJid(jid)
        return number?.let { nameOf(it) }
            ?: stored
            ?: chat?.pushName?.takeIf { it.isNotBlank() }
            ?: number
            ?: jid.substringBefore('@')
    }

    /** The address-book name of [number], looked up once per book. */
    private suspend fun nameOf(number: String): String? =
        if (number in names) {
            names[number]
        } else {
            sources.nameOf(number)?.takeIf { it.isNotBlank() }.also { names[number] = it }
        }

    /** Who wrote a group message: the address-book name, the name WhatsApp carries, the number. */
    private suspend fun senderOf(
        sender: String?,
        carried: String?,
    ): String {
        val number = sender?.let(::numberOfJid)
        return number?.let { nameOf(it) }
            ?: carried?.takeIf { it.isNotBlank() }
            // A quote or a typing line names no one: the name a loaded message of theirs carried.
            ?: rows.firstOrNull { it.sender == sender && !it.senderName.isNullOrBlank() }?.senderName
            ?: number
            ?: sender?.substringBefore('@').orEmpty()
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
        val text =
            when {
                row.deleted -> if (row.deletedByMe()) words.youDeleted else words.deleted
                row.kind == WaSyncKind.Text || isMedia -> row.text.orEmpty()
                else -> waPreview(row.toBody(), row.toMeta(), preview)
            }
        return BubbleUi(
            key = keyOf(row),
            outgoing = row.fromMe,
            text = text,
            meta = meta,
            state = if (row.fromMe) state else BubbleState.None,
            atMs = row.timestampMs,
            media =
                if (isMedia && !row.deleted) {
                    // E6.4a shows what the attachment is; the pictures and the player are E6.4b.
                    persistentListOf(BubbleMedia(null, mediaLabel(row), isImage = false))
                } else {
                    persistentListOf()
                },
            sender = if (group && !row.fromMe) senderOf(row.sender, row.senderName) else null,
            quote =
                row.quoted?.takeIf { !row.deleted }?.let { quoted ->
                    val mine = quoted.sender == null
                    BubbleQuote(
                        author = if (mine) words.you else senderOf(quoted.sender, null),
                        text = quoted.preview.orEmpty(),
                        targetKey = quoted.id,
                    )
                },
            forwarded = row.forwarded && !row.deleted,
            edited = row.editedMs != null,
            deleted = row.deleted,
            reactions = reactionsOf(row),
        )
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
        for ((sender, list) in fresh.groupBy { if (group) it.sender else null }) {
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

    suspend fun initialDraft(fromLink: String?): String = fromLink ?: sources.drafts.load(tag)

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

    /** Queues the message: it is on the page at once, with a clock until the gateway has it. */
    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        viewModelScope.launch {
            sources.drafts.put(tag, "")
            lastTypingSentMs = 0
            if (!sources.offline.enqueue(jid, WaSyncAction.SendText(body))) {
                effectChannel.trySend(ConversationEffect.Toast(UiText.Res(R.string.wa_thread_not_linked)))
                effectChannel.trySend(ConversationEffect.Restore(body))
            }
        }
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
            if (sources.offline.cancel(jid, row.clientId ?: row.id) && restore) {
                effectChannel.trySend(ConversationEffect.Restore(row.text.orEmpty()))
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
        viewModelScope.launch { sources.offline.enqueue(jid, WaSyncAction.Revoke(row.id, row.sender)) }
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
            sources.offline.enqueue(jid, WaSyncAction.React(row.id, row.fromMe, row.sender, emoji))
        }
    }

    // ---- typing

    private suspend fun onLive(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.Typing -> {
                if (event.chat != jid) return
                typingClear?.cancel()
                if (event.state == TypingState.Paused) {
                    typing = null
                } else {
                    val group = chat?.isGroup ?: jid.endsWith(GROUP_SUFFIX)
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
        private const val GROUP_SUFFIX = "@g.us"
        private val OUTBOX_STATES = setOf(WaSyncRowState.Waiting, WaSyncRowState.Sending, WaSyncRowState.Failed)
        private val MEDIA_KINDS =
            setOf(WaSyncKind.Image, WaSyncKind.Video, WaSyncKind.Audio, WaSyncKind.Document, WaSyncKind.Sticker)

        /** Stable from the moment a message is written: the outbox client id it keeps after it went out. */
        fun keyOf(row: WaSyncMessage): String = row.clientId ?: row.id
    }
}
