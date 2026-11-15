package dev.rosty.messages.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.Outcome
import dev.rosty.core.getOrNull
import dev.rosty.core.model.chat.UnreadDividerRequest
import dev.rosty.core.model.messages.DeliveryState
import dev.rosty.core.model.messages.EventResult
import dev.rosty.core.model.messages.MmsPart
import dev.rosty.core.model.messages.PendingDeleteRequest
import dev.rosty.core.model.messages.SegmentsRequest
import dev.rosty.core.model.messages.SmsKey
import dev.rosty.core.model.messages.SmsMessagesPayload
import dev.rosty.core.model.messages.SmsSend
import dev.rosty.core.model.messages.SmsStatus
import dev.rosty.core.model.messages.TimelineAnswer
import dev.rosty.core.model.messages.TimelineEvent
import dev.rosty.core.model.messages.TimelineItem
import dev.rosty.core.model.messages.TimelineRequest
import dev.rosty.core.model.messages.Waiting
import dev.rosty.core.ops.ChatOps
import dev.rosty.core.ops.MessagesOps
import dev.rosty.design.text.UiText
import dev.rosty.messages.R
import dev.rosty.messages.data.Book
import dev.rosty.messages.data.MessagesStore
import dev.rosty.messages.inbox.RowAvatar
import dev.rosty.platform.Headless
import dev.rosty.platform.sms.SendHandle
import dev.rosty.platform.sms.SmsRepository
import dev.rosty.platform.sms.SmsResult
import dev.rosty.platform.sms.SmsSims
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The words of the SMS page, from `strings.xml`. */
interface SmsWords {
    val placeholder: String
    val sending: String
    val notSent: String
    val now: String
    val readOnly: String

    fun counter(
        left: Int,
        parts: Int,
        unicode: Boolean,
    ): String

    fun people(count: Int): String

    /** "3 unread messages", the divider above the first unread message. */
    fun unread(count: Int): String

    fun part(contentType: String): String
}

/** One-shot effects of a conversation: pills, the undo pills, a menu answer. */
sealed interface ConversationEffect {
    data class Toast(
        val text: UiText,
    ) : ConversationEffect

    data class Undo(
        val text: UiText,
        val undo: () -> Unit,
        val durationMs: Long,
    ) : ConversationEffect

    /** Puts [text] back into the composer (an undone or discarded send). */
    data class Restore(
        val text: String,
    ) : ConversationEffect

    data class Copy(
        val text: String,
    ) : ConversationEffect

    data object NeedsRole : ConversationEffect
}

/** What the SMS page reads and writes; the container wires the real ones. */
class SmsThreadSources(
    val sms: SmsRepository,
    val ops: MessagesOps,
    val chat: ChatOps,
    val store: MessagesStore,
    val book: StateFlow<Book>,
    val roleHeld: StateFlow<Boolean>,
    val now: () -> Long,
    /** The core's `mms_send_req`: a base64 `m-send-req` for a picture and text (blocking; called off the main thread). */
    val mmsPdu: (String) -> String? = { json -> Headless.call("mms_send_req", json) },
)

/**
 * An SMS/MMS conversation (E6.3). The timeline is Rust's (`sms_timeline.build`), which replays the events this model
 * keeps: provider pages, my pending sends, their `sms_status` reports, removals. Sends wait [undoSendMs] (M45, "Sending…
 * Undo") before they go to the radio; deletes wait out a five-second Undo (`inbox.pending_delete`). The draft is kept
 * in `drafts.json` under `sms:<thread>`; the SIM per thread in `messages-prefs.json`.
 */
class SmsThreadViewModel(
    private val sources: SmsThreadSources,
    threadId: Long,
    recipients: List<String>,
    private val words: SmsWords,
    private val days: DayLabels,
    private val default: CoroutineDispatcher = Dispatchers.Default,
    private val undoSendMs: Long = UNDO_SEND_MS,
) : ViewModel() {
    private val ui = MutableStateFlow(ConversationUi(composer = ComposerMode.Ready(words.placeholder)))
    val state: StateFlow<ConversationUi> = ui.asStateFlow()

    private val effectChannel = Channel<ConversationEffect>(Channel.BUFFERED)
    val effects: Flow<ConversationEffect> = effectChannel.receiveAsFlow()

    private val lock = Mutex()
    private var thread = threadId
    private var to = recipients
    private val events = ArrayList<TimelineEvent>()
    private var items: List<TimelineItem> = emptyList()
    private var oldestMs: Long? = null
    private var pendingDeletes: List<Waiting> = emptyList()
    private var deleteTicker: Job? = null
    private var sims: SmsSims = SmsSims(emptyList(), null)
    private var subId: Int? = null
    private var nextLocal = sources.now()
    private val holds = HashMap<Long, Job>()

    /** The picture staged for the next send (MMS) and the ones of my sends still on their way, by local id. */
    private var staged: File? = null
    private val sentPictures = HashMap<Long, File>()

    /** Unread incoming messages when the thread opened (before it was marked read); the divider sits above them. */
    private var unreadAtOpen: Int? = null

    /** The tag of drafts and notifications. */
    val tag: String get() = "sms:$thread"

    init {
        viewModelScope.launch {
            sources.store.load()
            sims = sources.sms.sims()
            subId = sources.store.prefs.value
                ?.simChoices
                ?.get(thread)
                ?: sims.defaultSubId
            if (thread > 0) loadNewest() else rebuild()
            if (thread > 0 && sources.roleHeld.value) sources.sms.markRead(thread)
        }
        // A new message in this thread (or the first one of a draft thread): reload the newest page.
        viewModelScope.launch {
            sources.sms
                .threads()
                .map { rows -> rows.firstOrNull { it.threadId == thread || (thread <= 0 && it.addresses == to) } }
                .distinctUntilChanged()
                .collect { row ->
                    if (row == null) return@collect
                    if (thread <= 0) {
                        lock.withLock {
                            thread = row.threadId
                            events += TimelineEvent.adoptThread(row.threadId)
                        }
                    }
                    if (row.addresses.isNotEmpty()) to = row.addresses
                    loadNewest()
                }
        }
        viewModelScope.launch { sources.roleHeld.collect { rebuild() } }
        viewModelScope.launch { sources.book.collect { rebuild() } }
    }

    private suspend fun loadNewest() {
        if (thread <= 0) return
        val page = sources.sms.messages(thread, null)
        if (unreadAtOpen == null) {
            unreadAtOpen =
                page.sms.count { !it.read && it.box == INBOX_BOX } + page.mms.count { !it.read && !it.outgoing }
        }
        lock.withLock { events += TimelineEvent.page(page) }
        rebuild()
    }

    fun loadOlder() {
        val before = oldestMs ?: return
        if (ui.value.loadingOlder) return
        ui.value = ui.value.copy(loadingOlder = true)
        viewModelScope.launch {
            val page: SmsMessagesPayload = sources.sms.messages(thread, before)
            lock.withLock { events += TimelineEvent.page(page) }
            rebuild()
            ui.value = ui.value.copy(loadingOlder = false)
        }
    }

    /** Replays the events in Rust and turns the items into bubbles. */
    private suspend fun rebuild() {
        val answer =
            lock.withLock {
                if (thread <= 0 && events.none { it.kind == "pending" }) {
                    null
                } else {
                    replay()
                }
            }
        if (answer != null) {
            items = answer.items
            oldestMs = answer.oldestMs
        }
        val hidden = pendingDeletes.map { it.key }.toSet()
        val now = sources.now()
        val bubbles =
            items
                .filter { providerKey(it) !in hidden }
                .map { bubbleOf(it, now) }
        val title = titleOf()
        ui.value =
            ui.value.copy(
                title = title,
                subtitle = subtitleOf(title),
                avatar = avatarOf(title),
                items = withDays(bubbles, days, now, dividerOf(bubbles)).toImmutableList(),
                loaded = true,
                hasOlder = answer?.hasOlder ?: ui.value.hasOlder,
                composer = composerMode(),
            )
    }

    /** Rust's `unread_jump.divider` over the bubbles (oldest first): the key it sits above and its words. */
    private suspend fun dividerOf(bubbles: List<BubbleUi>): Pair<String, String>? {
        val unread = unreadAtOpen ?: return null
        if (unread == 0) return null
        val answer =
            sources.chat
                .unreadDivider(UnreadDividerRequest(incoming = bubbles.map { !it.outgoing }, unread = unread))
                .getOrNull() ?: return null
        val at = answer.index ?: return null
        return bubbles.getOrNull(at)?.let { it.key to words.unread(unread) }
    }

    private fun titleOf(): String {
        val book = sources.book.value
        val contacts = book.contacts.values
        if (to.size > 1) return to.joinToString(", ")
        val number = to.firstOrNull().orEmpty()
        val digits = number.filter(Char::isDigit).takeLast(MATCH_DIGITS)
        val contact =
            contacts.firstOrNull { c ->
                digits.length >= MIN_DIGITS &&
                    c.numbers.any { it.raw.filter(Char::isDigit).endsWith(digits) }
            }
        return contact?.displayName?.takeIf { it.isNotBlank() } ?: number
    }

    private fun subtitleOf(title: String): String? =
        when {
            to.size > 1 -> words.people(to.size)
            to.singleOrNull() != null && to.single() != title -> to.single()
            else -> null
        }

    private fun avatarOf(title: String): RowAvatar =
        RowAvatar(
            photoUri = null,
            numbers = to.toImmutableList(),
            group = to.size > 1,
            colorKey = to.firstOrNull() ?: title,
        )

    private fun composerMode(): ComposerMode {
        if (!sources.roleHeld.value) return ComposerMode.NeedsRole
        val sender = to.singleOrNull()
        if (sender != null && sender.none(Char::isDigit)) return ComposerMode.ReadOnly(words.readOnly)
        val simLabel =
            if (sims.sims.size >
                1
            ) {
                sims.sims.firstOrNull { it.subId == subId }?.label ?: sims.sims.first().label
            } else {
                null
            }
        val current = ui.value.composer as? ComposerMode.Ready
        return ComposerMode.Ready(words.placeholder, simLabel, current?.counter)
    }

    private fun bubbleOf(
        item: TimelineItem,
        now: Long,
    ): BubbleUi {
        val state =
            when (item.state) {
                DeliveryState.Pending -> BubbleState.Sending
                DeliveryState.Sent -> BubbleState.Sent
                DeliveryState.Delivered -> BubbleState.Delivered
                DeliveryState.Failed -> BubbleState.Failed
                DeliveryState.None -> BubbleState.None
            }
        val meta =
            when {
                state == BubbleState.Sending -> words.sending
                state == BubbleState.Failed -> words.notSent
                now - item.atMs < NOW_MS -> words.now
                else -> days.clock(item.atMs)
            }
        return BubbleUi(
            key = keyOf(item.key),
            outgoing = item.outgoing,
            text = item.body,
            meta = meta,
            state = if (item.outgoing) state else BubbleState.None,
            atMs = item.atMs,
            media =
                (
                    item.parts.filter(::isAttachment).map(::mediaOf) +
                        listOfNotNull(
                            item.key.let { (it as? SmsKey.Local)?.id }?.let(sentPictures::get)?.let {
                                BubbleMedia(it, words.part("image/*"), isImage = true)
                            },
                        )
                ).toImmutableList(),
        )
    }

    private fun mediaOf(part: MmsPart): BubbleMedia {
        val image = part.contentType.startsWith("image/")
        return BubbleMedia(
            model = part.path?.let(::File),
            label = words.part(part.contentType),
            isImage = image && part.path != null,
            mime = part.contentType,
        )
    }

    // ---- composer

    fun onDraft(text: String) {
        viewModelScope.launch {
            sources.store.putDraft(tag, text)
            val info = if (text.isBlank()) null else (sources.ops.segments(SegmentsRequest(text)) as? Outcome.Ok)?.value
            val counter =
                info
                    ?.takeIf {
                        it.parts > 1 || it.leftInPart <= COUNTER_WITHIN
                    }?.let { words.counter(it.leftInPart, it.parts, it.unicode) }
            val mode = ui.value.composer as? ComposerMode.Ready ?: return@launch
            ui.value = ui.value.copy(composer = mode.copy(counter = counter))
        }
    }

    /** The draft kept for this thread, or the body an `sms:` link brought. */
    suspend fun initialDraft(fromLink: String?): String {
        sources.store.load()
        return fromLink ?: sources.store.drafts.value[tag]
            .orEmpty()
    }

    /** Stages [file] (a picture copied into the app) for the next send; too big ones are refused with a pill. */
    fun attach(file: File) {
        if (file.length() > MAX_ATTACHMENT_BYTES) {
            file.delete()
            effectChannel.trySend(ConversationEffect.Toast(UiText.Res(R.string.sms_attachment_too_big)))
            return
        }
        staged = file
        ui.value =
            ui.value.copy(attachment = AttachmentUi(words.part("image/*"), sizeLabel(file.length()), model = file))
    }

    fun clearAttachment() {
        staged = null
        ui.value = ui.value.copy(attachment = null)
    }

    /** Puts the message on the page at once, then hands it to the radio after the undo window (M45). */
    fun send(text: String) {
        val body = text.trim()
        val picture = staged
        if (body.isEmpty() && picture == null) return
        if (!sources.roleHeld.value) {
            effectChannel.trySend(ConversationEffect.NeedsRole)
            return
        }
        val local = ++nextLocal
        if (picture != null) {
            sentPictures[local] = picture
            clearAttachment()
        }
        viewModelScope.launch {
            sources.store.putDraft(tag, "")
            lock.withLock { events += TimelineEvent.pending(local, body, sources.now(), subId) }
            rebuild()
            holds[local] =
                viewModelScope.launch {
                    delay(undoSendMs)
                    holds.remove(local)
                    val request = SmsSend(local, to, body, subId)
                    val pic = sentPictures[local]
                    if (pic != null) mms(request, pic) else radio(request)
                }
            effectChannel.trySend(
                ConversationEffect.Undo(UiText.Res(R.string.sms_sending_undo), { undoSend(local) }, undoSendMs),
            )
        }
    }

    private fun undoSend(local: Long) {
        val hold = holds.remove(local) ?: return
        hold.cancel()
        viewModelScope.launch {
            lock.withLock { events += TimelineEvent.discard(local) }
            rebuild()
            val text = lastResult { it.text }
            effectChannel.trySend(ConversationEffect.Restore(text.orEmpty()))
        }
    }

    private suspend fun <T> lastResult(pick: (EventResult) -> T?): T? =
        lock
            .withLock { replay() }
            ?.results
            ?.lastOrNull()
            ?.let(pick)

    /** The caller holds [lock]. */
    private suspend fun replay(): TimelineAnswer? =
        (sources.ops.timeline(TimelineRequest(thread.coerceAtLeast(0), to, events.toList())) as? Outcome.Ok)?.value

    /** An MMS: the core builds the PDU from the picture's path, the platform sends it. */
    private suspend fun mms(
        request: SmsSend,
        picture: File,
    ) {
        val json =
            JSONObject()
                .put("local_id", request.localId)
                .put("to", JSONArray(request.to))
                .put("text", request.body)
                .put("image_path", picture.absolutePath)
                .apply { request.subId?.let { put("sub_id", it) } }
                .toString()
        val pdu =
            withContext(Dispatchers.IO) { sources.mmsPdu(json) }
                ?.let { runCatching { JSONObject(it).optString("pdu_b64") }.getOrNull() }
                .orEmpty()
        if (pdu.isEmpty()) {
            status(SmsStatus(request.localId, state = DeliveryState.Failed))
            return
        }
        follow(request.localId, sources.sms.sendMms(request.localId, pdu, request.subId))
    }

    private suspend fun radio(request: SmsSend) {
        follow(request.localId, sources.sms.send(request))
    }

    private suspend fun follow(
        localId: Long,
        handle: SendHandle,
    ) {
        if (!handle.accepted) {
            status(SmsStatus(localId, state = DeliveryState.Failed))
            return
        }
        viewModelScope.launch {
            handle.status
                .transformWhile {
                    emit(it)
                    !it.isFinal()
                }.collect(::status)
        }
    }

    private suspend fun status(s: SmsStatus) {
        lock.withLock { events += TimelineEvent.status(s) }
        rebuild()
    }

    /** A tap on a failed message of mine: retry it. */
    fun retry(key: String) {
        val local = localOf(key) ?: return
        viewModelScope.launch {
            lock.withLock { events += TimelineEvent.retry(local) }
            val send = lastResult { it.send }
            rebuild()
            send?.let { radio(it) }
        }
    }

    /** Drops a failed message of mine ("Delete" in its menu). */
    fun discard(key: String) {
        val local = localOf(key) ?: return
        viewModelScope.launch {
            lock.withLock { events += TimelineEvent.discard(local) }
            rebuild()
            effectChannel.trySend(ConversationEffect.Toast(UiText.Res(R.string.sms_message_deleted)))
        }
    }

    fun copy(key: String) {
        items.firstOrNull { keyOf(it.key) == key }?.let { effectChannel.trySend(ConversationEffect.Copy(it.body)) }
    }

    /** Deletes one message after a five-second Undo (M15); needs the role. */
    fun delete(key: String) {
        val item = items.firstOrNull { keyOf(it.key) == key } ?: return
        val provider = providerKey(item) ?: return discard(key)
        if (!sources.roleHeld.value) {
            effectChannel.trySend(ConversationEffect.NeedsRole)
            return
        }
        viewModelScope.launch {
            val answer =
                pending("add", provider)
                    ?: return@launch
            pendingDeletes = answer.pending
            rebuild()
            effectChannel.trySend(
                ConversationEffect.Undo(
                    UiText.Res(R.string.sms_message_deleted),
                    { undoDelete(provider) },
                    UNDO_DELETE_MS,
                ),
            )
            tickDeletes()
        }
    }

    /** Deletes the messages of [keys] (select mode, M20) behind one Undo; needs the role. */
    fun deleteAll(keys: List<String>) {
        val providers = keys.mapNotNull { k -> items.firstOrNull { keyOf(it.key) == k }?.let(::providerKey) }
        if (providers.isEmpty()) return
        if (!sources.roleHeld.value) {
            effectChannel.trySend(ConversationEffect.NeedsRole)
            return
        }
        viewModelScope.launch {
            for (p in providers) pending("add", p)?.let { pendingDeletes = it.pending }
            rebuild()
            effectChannel.trySend(
                ConversationEffect.Undo(
                    UiText.Res(R.string.sms_messages_deleted),
                    {
                        viewModelScope.launch {
                            for (p in providers) pending("undo", p)?.let { pendingDeletes = it.pending }
                            rebuild()
                        }
                    },
                    UNDO_DELETE_MS,
                ),
            )
            tickDeletes()
        }
    }

    private suspend fun pending(
        action: String,
        key: String = "",
    ) = (
        sources.ops.pendingDelete(
            PendingDeleteRequest(pendingDeletes, sources.now(), action, key),
        ) as? Outcome.Ok
    )?.value

    private fun undoDelete(provider: String) {
        viewModelScope.launch {
            val answer =
                pending("undo", provider)
                    ?: return@launch
            pendingDeletes = answer.pending
            rebuild()
        }
    }

    private fun tickDeletes() {
        if (deleteTicker?.isActive == true) return
        deleteTicker =
            viewModelScope.launch {
                while (pendingDeletes.isNotEmpty()) {
                    delay(TICK_MS)
                    commitDeletes("due")
                }
            }
    }

    private suspend fun commitDeletes(action: String) {
        val answer =
            pending(action) ?: return
        pendingDeletes = answer.pending
        for (key in answer.keys) {
            val parsed = smsKeyOf(key) ?: continue
            if (sources.sms.deleteMessage(parsed) is SmsResult.Ok) {
                lock.withLock { events += TimelineEvent.remove(parsed) }
            }
        }
        if (answer.keys.isNotEmpty()) rebuild()
    }

    /** Next SIM in slot order; remembered for this thread. */
    fun nextSim() {
        val list = sims.sims.sortedBy { it.slotIndex ?: Int.MAX_VALUE }
        if (list.size < 2) return
        val at = list.indexOfFirst { it.subId == subId }
        val next = list[(at + 1).mod(list.size)]
        subId = next.subId
        viewModelScope.launch {
            if (thread > 0) sources.store.updatePrefs { it.copy(simChoices = it.simChoices + (thread to next.subId)) }
            rebuild()
        }
    }

    fun refreshRole() {
        viewModelScope.launch { rebuild() }
    }

    override fun onCleared() {
        // Leaving the page commits the deletes still waiting out their Undo and sends what is still held.
        val held = holds.keys.toList()
        sources.store.let { store ->
            kotlinx.coroutines.CoroutineScope(default).launch {
                commitDeletes("drain")
                store.flush()
            }
        }
        if (held.isNotEmpty()) {
            for (local in held) holds.remove(local)?.cancel()
        }
        super.onCleared()
    }

    companion object {
        const val UNDO_SEND_MS = 4_000L
        const val UNDO_DELETE_MS = 5_000L

        /** The core reads no bigger attachment (`headless::MAX_ATTACHMENT_BYTES`). */
        const val MAX_ATTACHMENT_BYTES = 4L * 1024 * 1024
        private const val TICK_MS = 1_000L

        /** `Telephony.Sms.MESSAGE_TYPE_INBOX`: a received SMS. */
        private const val INBOX_BOX = 1
        private const val NOW_MS = 60_000L
        private const val COUNTER_WITHIN = 30
        private const val MATCH_DIGITS = 9
        private const val MIN_DIGITS = 7

        fun keyOf(key: SmsKey): String =
            when (key) {
                is SmsKey.Sms -> "sms:${key.id}"
                is SmsKey.Mms -> "mms:${key.id}"
                is SmsKey.Local -> "local:${key.id}"
            }

        fun smsKeyOf(key: String): SmsKey? {
            val id = key.substringAfter(':').toLongOrNull() ?: return null
            return when (key.substringBefore(':')) {
                "sms" -> SmsKey.Sms(id)
                "mms" -> SmsKey.Mms(id)
                "local" -> SmsKey.Local(id)
                else -> null
            }
        }

        private fun localOf(key: String): Long? = (smsKeyOf(key) as? SmsKey.Local)?.id

        private fun providerKey(item: TimelineItem): String? = item.providerId
    }
}

/** Delivered or failed: nothing more comes for this send. */
private fun SmsStatus.isFinal(): Boolean = state == DeliveryState.Delivered || state == DeliveryState.Failed

/** An MMS part shown as an attachment (not the text body or the SMIL layout). */
private fun isAttachment(part: MmsPart): Boolean =
    part.contentType != "text/plain" && part.contentType != "application/smil"

/** "340 KB", "1.2 MB": the size under a staged picture. */
internal fun sizeLabel(bytes: Long): String =
    if (bytes <
        MIB
    ) {
        "${(bytes + KIB - 1) / KIB} KB"
    } else {
        String.format(java.util.Locale.ROOT, "%.1f MB", bytes / MIB.toDouble())
    }

private const val KIB = 1024L
private const val MIB = 1024L * 1024L
