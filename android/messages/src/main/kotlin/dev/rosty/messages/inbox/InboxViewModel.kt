package dev.rosty.messages.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.Outcome
import dev.rosty.core.model.chat.MarksAction
import dev.rosty.core.model.chat.MarksRequest
import dev.rosty.core.model.messages.FilterRequest
import dev.rosty.core.model.messages.InboxFilter
import dev.rosty.core.model.messages.InboxRow
import dev.rosty.core.model.messages.MergeRequest
import dev.rosty.core.model.messages.MessageDirection
import dev.rosty.core.model.messages.MessagesPrefs
import dev.rosty.core.model.messages.OtpRequest
import dev.rosty.core.model.messages.PrefsEditRequest
import dev.rosty.core.model.messages.RowFacts
import dev.rosty.core.model.messages.SmsMarks
import dev.rosty.core.model.messages.SmsThreadRow
import dev.rosty.core.model.phone.BlockedNumber
import dev.rosty.core.model.wa.ChatActionKind
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.ops.ChatOps
import dev.rosty.core.ops.MessagesOps
import dev.rosty.design.text.UiText
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.R
import dev.rosty.messages.data.Book
import dev.rosty.messages.data.MessagesStore
import dev.rosty.messages.spam.BlockOutcome
import dev.rosty.messages.spam.CodeCleanup
import dev.rosty.messages.spam.MarkOutcome
import dev.rosty.messages.spam.SpamActions
import dev.rosty.messages.spam.SpamFacts
import dev.rosty.messages.spam.SpamVerdicts
import dev.rosty.messages.wa.WaChats
import dev.rosty.messages.wa.toInboxThread
import dev.rosty.platform.sms.SmsRepository
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Normalizer
import dev.rosty.core.model.chat.SmsMarks as ChatMarks

/** The sources the inbox reads; the container wires the real ones, tests pass fakes. */
class InboxSources(
    val sms: SmsRepository,
    val smsReadable: StateFlow<Boolean>,
    val roleHeld: StateFlow<Boolean>,
    val wa: WaChats,
    val book: StateFlow<Book>,
    val store: MessagesStore,
    val ops: MessagesOps,
    val chat: ChatOps,
    val now: () -> Long,
    /** Spam rules, allow list and blocklist changes (E6.11); null keeps the menus without them. */
    val spam: SpamActions? = null,
    /** The system blocklist, for the spam verdicts and Block / Unblock. */
    val blocked: Flow<List<BlockedNumber>> = flowOf(emptyList()),
    /** M47, told when a passcode is copied. */
    val cleanup: CodeCleanup? = null,
)

/**
 * The unified inbox (E6.2, AD-MSG3): SMS threads and the WhatsApp chats merged into one row per person by the Rust
 * core (`inbox.merge`), shaped by [InboxBuilder], filtered by the chips (`inbox.filter`) and the search text. The
 * actions apply to every thread of a row at once: SMS marks and mutes live in `messages-prefs.json`, WhatsApp ones go to
 * the chat (the gateway or, with E6.4s, the offline outbox).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InboxViewModel(
    private val sources: InboxSources,
    private val builder: InboxBuilder,
    private val words: InboxWords,
    private val search: GlobalSearch? = null,
    private val default: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val stateFlow = MutableStateFlow(InboxUiState())
    val state: StateFlow<InboxUiState> = stateFlow.asStateFlow()

    private val effectChannel = Channel<InboxEffect>(Channel.BUFFERED)
    val effects: Flow<InboxEffect> = effectChannel.receiveAsFlow()

    @Volatile private var view: InboxView = InboxView.Empty
    private val verdicts = SpamVerdicts(sources.ops)
    private val waByJid = MutableStateFlow<Map<String, RichChat>>(emptyMap())

    init {
        viewModelScope.launch { sources.store.load() }
        // The Messages section of the search (M7): a short pause after typing, the newest query wins.
        if (search != null) {
            viewModelScope.launch {
                stateFlow
                    .map { it.query.trim() }
                    .distinctUntilChanged()
                    .collectLatest { query ->
                        if (query.isEmpty()) {
                            stateFlow.update { it.copy(messages = null) }
                            return@collectLatest
                        }
                        delay(SEARCH_PAUSE_MS)
                        val section = search.run(query, view.rows + view.archived, sources.smsReadable.value)
                        stateFlow.update { if (it.query.trim() == query) it.copy(messages = section) else it }
                    }
            }
        }
        val smsRows: Flow<List<SmsThreadRow>?> =
            sources.smsReadable.flatMapLatest { readable ->
                if (readable) {
                    sources.sms.threads().map<List<SmsThreadRow>, List<SmsThreadRow>?> { it }
                } else {
                    flowOf(
                        null,
                    )
                }
            }
        val waRows = sources.wa.chats.onStart { emit(emptyList()) }
        val prefsAndBlocked =
            combine(sources.store.prefs, sources.blocked.onStart { emit(emptyList()) }) { prefs, blocked ->
                prefs to blocked
            }
        viewModelScope.launch {
            combine(
                smsRows,
                waRows,
                sources.book,
                prefsAndBlocked,
                sources.store.drafts,
            ) { sms, wa, book, (prefs, blocked), drafts ->
                Snapshot(sms, wa, book, prefs, drafts, blocked)
            }.conflate()
                .map { build(it) }
                .flowOn(default)
                .collect { (built, access) ->
                    view = built
                    stateFlow.update { reshape(it.copy(loaded = true, access = access)) }
                }
        }
        viewModelScope.launch { sources.roleHeld.collect { held -> stateFlow.update { it.copy(roleHeld = held) } } }
    }

    private data class Snapshot(
        val sms: List<SmsThreadRow>?,
        val wa: List<RichChat>,
        val book: Book,
        val prefs: MessagesPrefs?,
        val drafts: Map<String, String>,
        val blocked: List<BlockedNumber> = emptyList(),
    )

    private suspend fun build(s: Snapshot): Pair<InboxView, SmsAccess> {
        val access = if (s.sms == null) SmsAccess.Denied else SmsAccess.Ready
        val index = s.book.index ?: return InboxView.Empty to SmsAccess.Loading
        val sms = s.sms.orEmpty()
        val wa = s.wa.mapNotNull { it.toInboxThread(words) }
        waByJid.value = s.wa.associateBy { it.jid }
        val merged = (sources.ops.merge(MergeRequest(index.id, sms, wa)) as? Outcome.Ok)?.value?.rows.orEmpty()
        val prefs = s.prefs
        val smsById = sms.associateBy { it.threadId }
        val facts = prefs?.let { verdicts.judge(merged, smsById, it.spam, s.blocked) } ?: SpamFacts.None
        val inputs =
            InboxInputs(
                sms = smsById,
                wa = waByJid.value,
                contacts = s.book.contacts,
                marks = prefs?.smsMarks ?: SmsMarks(),
                muted = prefs?.muted.orEmpty(),
                muteUntil = prefs?.muteUntil.orEmpty(),
                drafts = s.drafts,
                otps = otps(merged, prefs?.otpCopy != false),
                spamThreads = facts.spam,
                blockedThreads = facts.blocked,
                nowMs = sources.now(),
            )
        return builder.build(merged, inputs) to access
    }

    /** Passcodes of rows whose newest message is an unread incoming SMS (GPUI shows the chip only while unread). */
    private suspend fun otps(
        rows: List<InboxRow>,
        on: Boolean,
    ): Map<String, String> {
        if (!on) return emptyMap()
        val out = HashMap<String, String>()
        for (row in rows) {
            val last = row.last
            if (row.unread == 0 || last.direction != MessageDirection.Incoming) continue
            if (last.thread !is dev.rosty.core.model.messages.ThreadId.Sms) continue
            val code = (sources.ops.otp(OtpRequest(last.body)) as? Outcome.Ok)?.value?.otp?.code ?: continue
            out[InboxBuilder.keyOf(row.key)] = code
        }
        return out
    }

    /** The visible rows of the current folder, chip and query, and the chips themselves. */
    private suspend fun reshape(base: InboxUiState): InboxUiState {
        val v = view
        val folderRows =
            when (base.folder) {
                InboxFolder.Inbox -> v.rows
                InboxFolder.Archived -> v.archived
                InboxFolder.Spam -> v.spam
            }
        val facts = folderRows.map { RowFacts(it.isUnread, it.waChat != null, it.smsThread != null, it.group) }
        val answer = (sources.ops.filter(FilterRequest(facts, base.filter)) as? Outcome.Ok)?.value
        val filter =
            if (answer != null &&
                answer.chips.none { it.filter == base.filter }
            ) {
                InboxFilter.All
            } else {
                base.filter
            }
        val matches = answer?.matches ?: folderRows.map { true }
        val needle = fold(base.query.trim())
        val shown =
            folderRows
                .filterIndexed { i, row -> filter == InboxFilter.All || matches.getOrElse(i) { true } }
                .filter { needle.isEmpty() || matchesQuery(it, needle) }
        val chips = answer?.chips?.map { FilterChipUi(it.filter, it.count) }.orEmpty()
        val select =
            base.select?.let { sel ->
                val keys = sel.selected.filter { k -> folderRows.any { it.key == k } }.toImmutableSet()
                sel.copy(
                    selected = keys,
                    actions = bulkActions(folderRows.filter { it.key in keys }, base.folder).toImmutableList(),
                    allSelected = keys.size == shown.size && shown.isNotEmpty(),
                )
            }
        return base.copy(
            rows = shown.toImmutableList(),
            chips = (if (base.folder == InboxFolder.Inbox) chips else emptyList()).toImmutableList(),
            filter = filter,
            archivedCount = v.archived.size,
            archivedUnread = v.archived.count { it.isUnread },
            spamCount = v.spam.size,
            select = select,
            anyRows = folderRows.isNotEmpty(),
        )
    }

    private fun matchesQuery(
        row: InboxRowUi,
        needle: String,
    ): Boolean {
        if (fold(row.title).contains(needle) || fold(row.snippet).contains(needle)) return true
        val digits = needle.filter(Char::isDigit)
        return digits.length >= MIN_QUERY_DIGITS && row.avatar.numbers.any { it.filter(Char::isDigit).contains(digits) }
    }

    fun onAction(action: InboxAction) {
        when (action) {
            is InboxAction.Open -> {
                open(action.key)
            }

            is InboxAction.OpenRoute -> {
                effectChannel.trySend(InboxEffect.Open(action.route))
            }

            is InboxAction.SetFilter -> {
                restate { it.copy(filter = action.filter) }
            }

            is InboxAction.SetQuery -> {
                restate { it.copy(query = action.text) }
            }

            is InboxAction.ShowFolder -> {
                restate { it.copy(folder = action.folder, filter = InboxFilter.All, select = null) }
            }

            is InboxAction.Pin -> {
                withRow(action.key) { pin(listOf(it), action.pinned) }
            }

            is InboxAction.MarkRead -> {
                withRow(action.key) { markRead(listOf(it), action.read) }
            }

            is InboxAction.Mute -> {
                withRow(action.key) { mute(listOf(it), action.duration, undoable = true) }
            }

            is InboxAction.Unmute -> {
                withRow(action.key) { unmute(listOf(it), undoable = true) }
            }

            is InboxAction.Archive -> {
                withRow(action.key) { archive(listOf(it), action.archived, undoable = true) }
            }

            is InboxAction.Delete -> {
                withRow(action.key) { askDelete(listOf(it)) }
            }

            is InboxAction.NotSpam -> {
                withRow(action.key) { notSpam(listOf(it)) }
            }

            is InboxAction.MoveToSpam -> {
                withRow(action.key) { moveToSpam(it) }
            }

            is InboxAction.Block -> {
                withRow(action.key) { block(it, action.blocked) }
            }

            InboxAction.MarkAllRead -> {
                launch { markAllRead() }
            }

            is InboxAction.EnterSelect -> {
                restate {
                    it.copy(
                        select = SelectUi(setOfNotNull(action.key).toImmutableSet(), persistentListOf(), false),
                    )
                }
            }

            is InboxAction.Toggle -> {
                restate { s ->
                    val sel = s.select ?: return@restate s
                    val next = if (action.key in sel.selected) sel.selected - action.key else sel.selected + action.key
                    s.copy(select = sel.copy(selected = next.toImmutableSet()))
                }
            }

            InboxAction.ToggleAll -> {
                restate { s ->
                    val sel = s.select ?: return@restate s
                    val keys = if (sel.allSelected) emptySet() else s.rows.map { it.key }.toSet()
                    s.copy(select = sel.copy(selected = keys.toImmutableSet()))
                }
            }

            InboxAction.ExitSelect -> {
                restate { it.copy(select = null) }
            }

            is InboxAction.Bulk -> {
                launch { bulk(action.kind, action.mute) }
            }
        }
    }

    private fun open(key: String) {
        val row = rowOf(key) ?: return
        val route =
            when {
                row.latest == dev.rosty.core.model.messages.Channel.WhatsApp && row.waChat != null -> {
                    MessagesRoute.WaThread(row.waChat)
                }

                row.smsThread != null -> {
                    MessagesRoute.SmsThread(row.smsThread, listOfNotNull(row.smsSender))
                }

                row.waChat != null -> {
                    MessagesRoute.WaThread(row.waChat)
                }

                else -> {
                    return
                }
            }
        effectChannel.trySend(InboxEffect.Open(route))
    }

    private fun rowOf(key: String): InboxRowUi? =
        view.rows.firstOrNull { it.key == key } ?: view.archived.firstOrNull { it.key == key }
            ?: view.spam.firstOrNull { it.key == key }

    private fun withRow(
        key: String,
        block: suspend (InboxRowUi) -> Unit,
    ) {
        val row = rowOf(key) ?: return
        launch { block(row) }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun restate(change: (InboxUiState) -> InboxUiState) {
        viewModelScope.launch(default) {
            val next = change(stateFlow.value)
            stateFlow.value = reshape(next)
        }
    }

    private fun toast(
        id: Int,
        vararg args: Any,
    ) {
        effectChannel.trySend(InboxEffect.Toast(UiText.Res(id, args.toList())))
    }

    // ---- actions on rows

    private suspend fun marks(
        action: MarksAction,
        thread: Long,
    ): Boolean {
        val prefs = sources.store.prefs.value ?: return false
        val mine = prefs.smsMarks
        val request = MarksRequest(ChatMarks(mine.pinned, mine.archived, mine.unread), action, thread)
        val answer = (sources.chat.marks(request) as? Outcome.Ok)?.value ?: return false
        if (answer.refused != null) {
            toast(R.string.inbox_pin_cap_sms)
            return false
        }
        if (answer.changed) {
            val next = answer.marks
            sources.store.updatePrefs { it.copy(smsMarks = SmsMarks(next.pinned, next.archived, next.unread)) }
        }
        return true
    }

    private suspend fun pin(
        rows: List<InboxRowUi>,
        pinned: Boolean,
    ) {
        for (row in rows) {
            row.smsThread?.let { marks(if (pinned) MarksAction.Pin else MarksAction.Unpin, it) }
            row.waChat?.let { sources.wa.act(it, ChatActionKind.Pin(pinned)) }
        }
    }

    private suspend fun markRead(
        rows: List<InboxRowUi>,
        read: Boolean,
    ) {
        for (row in rows) {
            row.smsThread?.let { thread ->
                marks(if (read) MarksAction.MarkRead else MarksAction.MarkUnread, thread)
                if (read && row.unread > 0) sources.sms.markRead(thread)
            }
            row.waChat?.let { sources.wa.act(it, ChatActionKind.MarkRead(read)) }
        }
        if (!read && rows.size == 1) toast(R.string.inbox_marked_unread)
    }

    private suspend fun mute(
        rows: List<InboxRowUi>,
        duration: MuteFor,
        undoable: Boolean,
    ) {
        val until = duration.ms?.let { sources.now() + it }
        for (row in rows) {
            row.waChat?.let { sources.wa.act(it, ChatActionKind.Mute(until)) }
            for (tag in row.threads.map(InboxBuilder::tagOf)) editPrefs("mute", tag, until)
        }
        val text = UiText.Res(R.string.inbox_muted)
        if (undoable) {
            effectChannel.trySend(InboxEffect.Undo(text) { launch { unmute(rows, undoable = false) } })
        } else {
            effectChannel.trySend(InboxEffect.Toast(text))
        }
    }

    private suspend fun unmute(
        rows: List<InboxRowUi>,
        undoable: Boolean,
    ) {
        for (row in rows) {
            row.waChat?.let { sources.wa.act(it, ChatActionKind.Unmute) }
            for (tag in row.threads.map(InboxBuilder::tagOf)) editPrefs("unmute", tag, null)
        }
        val text = UiText.Res(R.string.inbox_unmuted)
        if (undoable) {
            effectChannel.trySend(InboxEffect.Undo(text) { launch { mute(rows, MuteFor.Always, undoable = false) } })
        }
    }

    private suspend fun editPrefs(
        edit: String,
        tag: String,
        until: Long?,
    ) {
        val prefs = sources.store.prefs.value ?: return
        val answer =
            (sources.ops.prefsEdit(PrefsEditRequest(prefs, edit, tag = tag, untilMs = until)) as? Outcome.Ok)?.value
                ?: return
        if (answer.changed) sources.store.updatePrefs { answer.prefs }
    }

    private suspend fun archive(
        rows: List<InboxRowUi>,
        archived: Boolean,
        undoable: Boolean,
    ) {
        for (row in rows) {
            row.smsThread?.let { marks(if (archived) MarksAction.Archive else MarksAction.Unarchive, it) }
            row.waChat?.let { sources.wa.act(it, ChatActionKind.Archive(archived)) }
        }
        val text =
            if (rows.size == 1) {
                UiText.Res(if (archived) R.string.inbox_archived else R.string.inbox_unarchived)
            } else {
                UiText.Plural(R.plurals.inbox_archived_n, rows.size)
            }
        if (undoable) {
            effectChannel.trySend(InboxEffect.Undo(text) { launch { archive(rows, !archived, undoable = false) } })
        } else {
            effectChannel.trySend(InboxEffect.Toast(text))
        }
    }

    private fun askDelete(rows: List<InboxRowUi>) {
        val deletable = rows.filter { it.smsThread != null && it.waChat == null }
        if (deletable.isEmpty()) {
            toast(R.string.inbox_nothing_to_delete)
            return
        }
        if (!sources.roleHeld.value) {
            effectChannel.trySend(InboxEffect.NeedsRole)
            return
        }
        effectChannel.trySend(
            InboxEffect.ConfirmDelete(deletable.size, deletable.singleOrNull()?.title) {
                launch {
                    var done = 0
                    for (row in deletable) {
                        val thread = row.smsThread ?: continue
                        if (sources.sms.deleteThread(thread).isOk()) {
                            done++
                            marks(MarksAction.Forget, thread)
                            sources.store.putDraft(InboxBuilder.tagOf(row.threads.first()), "")
                        }
                    }
                    effectChannel.trySend(InboxEffect.Toast(UiText.Plural(R.plurals.inbox_deleted_n, done)))
                    restate { it.copy(select = null) }
                }
            },
        )
    }

    private suspend fun markAllRead() {
        val targets = view.rows.filter { it.isUnread }
        if (targets.isEmpty()) {
            toast(R.string.inbox_nothing_to_mark)
            return
        }
        markRead(targets, read = true)
        effectChannel.trySend(InboxEffect.Toast(UiText.Plural(R.plurals.inbox_marked_read_n, targets.size)))
    }

    private suspend fun bulk(
        kind: BulkKind,
        duration: MuteFor,
    ) {
        val sel = stateFlow.value.select ?: return
        val rows = stateFlow.value.rows.filter { it.key in sel.selected }
        if (rows.isEmpty()) return
        when (kind) {
            BulkKind.MarkRead -> {
                markRead(rows, true)
            }

            BulkKind.MarkUnread -> {
                markRead(rows, false)
            }

            BulkKind.Mute -> {
                mute(rows, duration, undoable = false)
            }

            BulkKind.Unmute -> {
                unmute(rows, undoable = false)
            }

            BulkKind.Pin -> {
                pin(rows, true)
            }

            BulkKind.Unpin -> {
                pin(rows, false)
            }

            BulkKind.Archive -> {
                archive(rows, true, undoable = true)
            }

            BulkKind.Unarchive -> {
                archive(rows, false, undoable = true)
            }

            BulkKind.Delete -> {
                askDelete(rows)
                return
            }

            BulkKind.NotSpam -> {
                notSpam(rows)
            }
        }
        if (kind == BulkKind.MarkRead) {
            effectChannel.trySend(InboxEffect.Toast(UiText.Plural(R.plurals.inbox_marked_read_n, rows.size)))
        }
        restate { it.copy(select = null) }
    }

    // ---- spam (E6.11)

    /** Not spam: every sender joins the allow list (Rust), with Undo. */
    private suspend fun notSpam(rows: List<InboxRowUi>) {
        val spam = sources.spam ?: return
        val senders = rows.mapNotNull { it.smsSender }.filter { spam.allow(it) }
        if (senders.isEmpty()) {
            toast(R.string.spam_allow_exists)
            return
        }
        val text =
            senders.singleOrNull()?.let { UiText.Res(R.string.inbox_not_spam_done, listOf(it)) }
                ?: UiText.Plural(R.plurals.inbox_not_spam_n, senders.size)
        effectChannel.trySend(
            InboxEffect.Undo(text) {
                launch {
                    senders.forEach { spam.unallow(it) }
                    toast(R.string.inbox_back_in_spam)
                }
            },
        )
        restate { it.copy(select = null) }
    }

    /** Move to Spam: a sender rule and off the allow list (Rust `mark_spam`), with Undo. */
    private suspend fun moveToSpam(row: InboxRowUi) {
        val spam = sources.spam ?: return
        val sender = row.smsSender ?: return
        when (val outcome = spam.markSpam(sender)) {
            is MarkOutcome.Marked -> {
                effectChannel.trySend(
                    InboxEffect.Undo(UiText.Res(R.string.inbox_moved_to_spam, listOf(row.title))) {
                        launch {
                            spam.unmarkSpam(sender, outcome.mark)
                            toast(R.string.inbox_back_in_inbox)
                        }
                    },
                )
            }

            MarkOutcome.Full -> {
                toast(R.string.spam_err_full)
            }

            MarkOutcome.Failed -> {
                toast(R.string.inbox_spam_failed)
            }
        }
    }

    /** Block or Unblock the row's sender on the system blocklist (shared with Phone), with Undo. */
    private suspend fun block(
        row: InboxRowUi,
        blocked: Boolean,
    ) {
        val spam = sources.spam ?: return
        val sender = row.smsSender ?: return
        val outcome = if (blocked) spam.block(sender) else spam.unblock(sender)
        when (outcome) {
            BlockOutcome.Done -> {
                val text = UiText.Res(if (blocked) R.string.spam_blocked_done else R.string.spam_unblocked_done, listOf(sender))
                effectChannel.trySend(
                    InboxEffect.Undo(text) {
                        launch { if (blocked) spam.unblock(sender) else spam.block(sender) }
                    },
                )
            }

            BlockOutcome.Refused -> {
                toast(R.string.spam_block_refused)
            }

            BlockOutcome.Unavailable -> {
                toast(R.string.spam_block_failed)
            }
        }
    }

    /**
     * A passcode was copied from the row showing [code]: M47 books the message's deletion when it is on, and the pill
     * says so.
     */
    fun codeCopied(code: String) {
        val row = (view.rows + view.archived + view.spam).firstOrNull { it.otp == code }
        launch {
            val thread = row?.smsThread
            val booked = thread != null && sources.cleanup?.copied(thread, code) == true
            toast(if (booked) R.string.inbox_code_copied_delete else R.string.inbox_code_copied, code)
        }
    }

    companion object {
        /** The pause after the last keystroke before the message search runs. */
        private const val SEARCH_PAUSE_MS = 250L
        private const val MIN_QUERY_DIGITS = 3

        /** Case- and accent-folded, for the search box (İ/ı fold to i like the Rust index does). */
        fun fold(text: String): String =
            Normalizer
                .normalize(text.replace('İ', 'i').replace('ı', 'i').lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")

        /** GPUI `inbox_bulk::actions` over the selected rows. */
        fun bulkActions(
            rows: List<InboxRowUi>,
            folder: InboxFolder,
        ): List<BulkKind> {
            if (rows.isEmpty()) return emptyList()
            if (folder == InboxFolder.Spam) return spamBulkActions(rows)
            val out = ArrayList<BulkKind>()
            out += if (rows.any { it.isUnread }) BulkKind.MarkRead else BulkKind.MarkUnread
            out += if (rows.all { it.muted }) BulkKind.Unmute else BulkKind.Mute
            if (folder != InboxFolder.Archived) out += if (rows.all { it.pinned }) BulkKind.Unpin else BulkKind.Pin
            out += if (folder == InboxFolder.Archived) BulkKind.Unarchive else BulkKind.Archive
            if (rows.all { it.smsThread != null && it.waChat == null }) out += BulkKind.Delete
            return out
        }

        /** In the Spam folder: read, mute, Not spam, Delete (no pin, no archive, as the row menu there). */
        private fun spamBulkActions(rows: List<InboxRowUi>): List<BulkKind> {
            val out = ArrayList<BulkKind>()
            out += if (rows.any { it.isUnread }) BulkKind.MarkRead else BulkKind.MarkUnread
            out += if (rows.all { it.muted }) BulkKind.Unmute else BulkKind.Mute
            out += BulkKind.NotSpam
            if (rows.all { it.smsThread != null && it.waChat == null }) out += BulkKind.Delete
            return out
        }
    }
}

private fun <T> dev.rosty.platform.sms.SmsResult<T>.isOk(): Boolean = this is dev.rosty.platform.sms.SmsResult.Ok
