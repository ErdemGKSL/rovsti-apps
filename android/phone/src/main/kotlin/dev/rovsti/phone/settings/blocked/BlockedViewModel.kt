package dev.rovsti.phone.settings.blocked

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.phone.BlockCallLineRequest
import dev.rovsti.core.model.phone.BlockCheckRequest
import dev.rovsti.core.model.phone.BlockManyRequest
import dev.rovsti.core.model.phone.BlockReason
import dev.rovsti.core.model.phone.BlockRefusal
import dev.rovsti.core.model.phone.BlockedHistoryRequest
import dev.rovsti.core.model.phone.BlockedNumber
import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.EntryRef
import dev.rovsti.core.model.phone.HistoryLine
import dev.rovsti.core.model.phone.HistoryTextRequest
import dev.rovsti.core.model.phone.LocaleTime
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.phone.data.BlockList
import dev.rovsti.phone.data.BlockRepo
import dev.rovsti.phone.data.BlockResult
import dev.rovsti.phone.data.BlockUndo
import dev.rovsti.phone.data.PrefixResult
import dev.rovsti.platform.calllog.BlockPrefix
import dev.rovsti.platform.calllog.CallLogRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One number or prefix range of the list. [numbers] are the raw numbers an Unblock removes. */
@Immutable
data class BlockedRowUi(
    val key: String,
    val title: String,
    /** The contact's number under a name; null for a bare number (the title already is the number). */
    val subtitle: String?,
    val numbers: ImmutableList<String>,
    val range: Boolean = false,
)

/** One blocked call under "Blocked calls". [reason] and [whenText] are composed into the line by the screen. */
@Immutable
data class BlockedCallUi(
    val key: String,
    val title: String,
    val number: String,
    val reason: BlockReason?,
    val whenText: String,
)

/** What pasting would do (the core's `blocklist.parse_many`): the numbers found, sorted. */
@Immutable
data class PastePreview(
    val toBlock: Int = 0,
    val emergency: Int = 0,
    val alreadyBlocked: Int = 0,
) {
    val found: Boolean get() = toBlock + emergency + alreadyBlocked > 0
}

/** Why a typed prefix cannot be added yet, or null when it can. */
enum class PrefixProblem { Invalid, TooShort, Emergency, Already }

/** The prefix field's verdict: [display] is the range as the list will show it. */
@Immutable
data class PrefixPreview(
    val display: String? = null,
    val problem: PrefixProblem? = null,
)

@Immutable
data class BlockedUi(
    val ready: Boolean = false,
    /** False when this app is not the default phone app: the system list cannot be written. */
    val canWrite: Boolean = true,
    val numbers: ImmutableList<BlockedRowUi> = persistentListOf(),
    val ranges: ImmutableList<BlockedRowUi> = persistentListOf(),
    val calls: ImmutableList<BlockedCallUi> = persistentListOf(),
    /** The preview of the text in the paste sheet; null while it is empty. */
    val paste: PastePreview? = null,
    val prefix: PrefixPreview? = null,
) {
    val isEmpty: Boolean get() = numbers.isEmpty() && ranges.isEmpty()
    val count: Int get() = numbers.size + ranges.size
}

sealed interface BlockedAction {
    /** The page came on screen: ask again whether the list can be written. */
    data object Opened : BlockedAction

    data class Unblock(
        val key: String,
    ) : BlockedAction

    data object AskUnblockAll : BlockedAction

    data object ConfirmUnblockAll : BlockedAction

    data class PasteTyped(
        val text: String,
    ) : BlockedAction

    data object PasteConfirm : BlockedAction

    data object PasteClosed : BlockedAction

    data class PrefixTyped(
        val text: String,
    ) : BlockedAction

    data object PrefixConfirm : BlockedAction

    data object PrefixClosed : BlockedAction

    data object Share : BlockedAction

    data object Export : BlockedAction

    /** The text of a picked file (U38). */
    data class Import(
        val text: String,
    ) : BlockedAction

    data class OpenCall(
        val key: String,
    ) : BlockedAction

    data class CopyNumber(
        val key: String,
    ) : BlockedAction

    data class Undo(
        val undo: BlockUndo,
    ) : BlockedAction

    /** The host reports a file result (the picker is the host's). */
    data class FileNotice(
        val notice: BlockedNotice,
    ) : BlockedAction
}

/** The words a pill says besides the Undo pills. */
enum class BlockedNotice {
    Unavailable,
    Failed,
    NoNumbersFound,
    EmergencyOnly,
    NothingNew,
    UndoFailed,
    Exported,
    ExportFailed,
    ImportFailed,
    TooLarge,
    Copied,
}

/** Which change an Undo pill belongs to. */
enum class BlockedUndoKind { Blocked, Unblocked, RangeAdded, RangeRemoved }

sealed interface BlockedEffect {
    data class Say(
        val notice: BlockedNotice,
    ) : BlockedEffect

    data class Undoable(
        val kind: BlockedUndoKind,
        val count: Int,
        val undo: BlockUndo,
    ) : BlockedEffect

    data class ConfirmUnblockAll(
        val count: Int,
    ) : BlockedEffect

    data class Share(
        val text: String,
    ) : BlockedEffect

    data class Export(
        val fileName: String,
        val csv: String,
    ) : BlockedEffect

    data class OpenNumber(
        val number: String,
    ) : BlockedEffect

    data class Copy(
        val text: String,
    ) : BlockedEffect

    /** The paste or prefix sheet did its job: close it. */
    data object CloseSheet : BlockedEffect
}

/**
 * Settings, Blocked numbers (E5.9a): the system blocklist through [BlockRepo] with the core's rules (`blocklist.lines`
 * for the rows, `parse_many` for the paste preview, `parse_file` for an import, `recents.blocked_history` and
 * `blocklist.call_line` for the Blocked calls) plus the prefix ranges of U55. Every change answers an Undo pill.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockedViewModel(
    private val block: BlockRepo,
    private val ops: PhoneOps,
    private val callLog: CallLogRepository,
    private val localeTime: () -> LocaleTime,
    private val clock: () -> Long = System::currentTimeMillis,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val canWrite = MutableStateFlow(true)
    private val pasteText = MutableStateFlow("")
    private val prefixText = MutableStateFlow("")
    private val effects = Channel<BlockedEffect>(Channel.BUFFERED)

    private class Inputs(
        val list: BlockList,
        val ranges: List<String>,
        val calls: List<CallLogEntry>,
        val canWrite: Boolean,
        val paste: String,
        val prefix: String,
    )

    private val calls: Flow<List<CallLogEntry>> =
        callLog
            .blocked()
            .catch { emit(emptyList()) }

    val state: StateFlow<BlockedUi> =
        combine(
            combine(block.list, block.prefixes, calls) { l, r, c -> Triple(l, r, c) },
            canWrite,
            pasteText,
            prefixText,
        ) { (l, r, c), w, p, x -> Inputs(l, r, c, w, p, x) }
            .conflate()
            .mapLatest { build(it) }
            .flowOn(worker)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BlockedUi())

    /** Pills, sheets and the chooser, each delivered once. */
    val effect: Flow<BlockedEffect> = effects.receiveAsFlow()

    init {
        viewModelScope.launch { canWrite.value = block.canBlock() }
    }

    fun onAction(action: BlockedAction) {
        when (action) {
            BlockedAction.Opened -> viewModelScope.launch { canWrite.value = block.canBlock() }
            is BlockedAction.Unblock -> unblock(action.key)
            BlockedAction.AskUnblockAll -> askUnblockAll()
            BlockedAction.ConfirmUnblockAll -> unblockAll()
            is BlockedAction.PasteTyped -> pasteText.value = action.text
            BlockedAction.PasteConfirm -> pasteConfirm()
            BlockedAction.PasteClosed -> pasteText.value = ""
            is BlockedAction.PrefixTyped -> prefixText.value = action.text
            BlockedAction.PrefixConfirm -> prefixConfirm()
            BlockedAction.PrefixClosed -> prefixText.value = ""
            BlockedAction.Share -> share()
            BlockedAction.Export -> export()
            is BlockedAction.Import -> import(action.text)
            is BlockedAction.OpenCall -> openCall(action.key)
            is BlockedAction.CopyNumber -> copyNumber(action.key)
            is BlockedAction.Undo -> undo(action.undo)
            is BlockedAction.FileNotice -> say(action.notice)
        }
    }

    // ---- building the state -------------------------------------------------------------------------------------------

    private suspend fun build(input: Inputs): BlockedUi {
        val numbers =
            input.list.rows.mapIndexed { index, row ->
                val line = input.list.lines.getOrNull(index)
                BlockedRowUi(
                    key = "n-${row.id}",
                    title = line?.title ?: row.numberRaw,
                    // The core says "Blocked number" under a bare number; the row shows no second line then.
                    subtitle = line?.subtitle?.takeIf { it != CORE_GENERIC && it != line.title },
                    numbers = persistentListOf(row.numberRaw),
                )
            }
        val ranges =
            input.ranges.map { range ->
                BlockedRowUi("r-$range", "$range *", null, persistentListOf(range), range = true)
            }
        return BlockedUi(
            ready = input.list.ready,
            canWrite = input.canWrite,
            numbers = numbers.toImmutableList(),
            ranges = ranges.toImmutableList(),
            calls = callsOf(input.list.rows, input.calls),
            paste = if (input.paste.isBlank()) null else pastePreview(input.paste, input.list.rows),
            prefix = if (input.prefix.isBlank()) null else prefixPreview(input.prefix, input.ranges),
        )
    }

    private suspend fun pastePreview(
        text: String,
        rows: List<BlockedNumber>,
    ): PastePreview {
        val answer = (ops.blockParseMany(BlockManyRequest(text = text, blocked = rows)) as? Outcome.Ok)?.value
        val parsed = answer?.parsed ?: return PastePreview()
        return PastePreview(parsed.toBlock.size, parsed.skippedEmergency, parsed.alreadyBlocked)
    }

    private suspend fun prefixPreview(
        text: String,
        ranges: List<String>,
    ): PrefixPreview =
        when (val parsed = BlockPrefix.parse(text)) {
            BlockPrefix.Parsed.Invalid -> {
                PrefixPreview(problem = PrefixProblem.Invalid)
            }

            BlockPrefix.Parsed.TooShort -> {
                PrefixPreview(problem = PrefixProblem.TooShort)
            }

            is BlockPrefix.Parsed.Valid -> {
                val taken = ranges.any { BlockPrefix.key(it) == parsed.key }
                val verdict = (ops.blockCheck(BlockCheckRequest(raw = parsed.key)) as? Outcome.Ok)?.value
                val problem =
                    when {
                        verdict?.refusal == BlockRefusal.Emergency -> PrefixProblem.Emergency
                        taken -> PrefixProblem.Already
                        else -> null
                    }
                PrefixPreview(parsed.display, problem)
            }
        }

    /** The newest blocked calls of the numbers on the list, in the core's order. */
    private suspend fun callsOf(
        rows: List<BlockedNumber>,
        entries: List<CallLogEntry>,
    ): ImmutableList<BlockedCallUi> {
        if (rows.isEmpty() || entries.isEmpty()) return persistentListOf()
        val history =
            (
                ops.blockedHistory(
                    BlockedHistoryRequest(entries, rows.map { it.numberRaw.ifBlank { it.normalized } }, CALLS_LIMIT),
                ) as? Outcome.Ok
            )?.value?.lines ?: return persistentListOf()
        val byId = entries.associateBy { it.id }
        val now = clock()
        val locale = localeTime()
        return history
            .mapNotNull { line -> callUi(line, byId, now, locale) }
            .toImmutableList()
    }

    private suspend fun callUi(
        line: HistoryLine,
        byId: Map<Long, CallLogEntry>,
        now: Long,
        locale: LocaleTime,
    ): BlockedCallUi? {
        val id = (line.entry as? EntryRef.Log)?.id ?: return null
        val entry = byId[id] ?: return null
        val whenText =
            (ops.historyText(HistoryTextRequest(line, now, locale)) as? Outcome.Ok)?.value?.detail.orEmpty()
        val shown = (ops.blockCallLine(BlockCallLineRequest(entry, null, whenText)) as? Outcome.Ok)?.value
        return BlockedCallUi(
            key = "c-$id",
            title = shown?.title ?: entry.numberRaw,
            number = entry.numberRaw.ifBlank { entry.normalized },
            reason = entry.blockReason,
            whenText = whenText,
        )
    }

    // ---- actions --------------------------------------------------------------------------------------------------------

    private fun rowOf(key: String): BlockedRowUi? =
        state.value.let { ui ->
            (ui.numbers + ui.ranges).firstOrNull {
                it.key ==
                    key
            }
        }

    private fun unblock(key: String) {
        val row = rowOf(key) ?: return
        viewModelScope.launch {
            if (row.range) {
                val undo = block.removePrefix(row.numbers.first()) ?: return@launch
                say(BlockedEffect.Undoable(BlockedUndoKind.RangeRemoved, 1, undo))
            } else {
                when (val result = block.unblock(row.numbers)) {
                    is BlockResult.Done -> say(BlockedEffect.Undoable(BlockedUndoKind.Unblocked, 1, result.undo))
                    else -> failed(result)
                }
            }
        }
    }

    private fun askUnblockAll() {
        val count = state.value.count
        if (count > 0) effects.trySend(BlockedEffect.ConfirmUnblockAll(count))
    }

    private fun unblockAll() {
        val rows = state.value.numbers
        val ranges = state.value.ranges
        if (rows.isEmpty() && ranges.isEmpty()) return
        viewModelScope.launch {
            val undoBlock = mutableListOf<String>()
            if (rows.isNotEmpty()) {
                when (val result = block.unblock(rows.flatMap { it.numbers })) {
                    is BlockResult.Done -> undoBlock += result.undo.block
                    else -> if (ranges.isEmpty()) return@launch failed(result)
                }
            }
            val undoRanges = ranges.mapNotNull { block.removePrefix(it.numbers.first()) }.flatMap { it.addPrefixes }
            val count = undoBlock.size + undoRanges.size
            if (count > 0) {
                say(
                    BlockedEffect.Undoable(
                        BlockedUndoKind.Unblocked,
                        count,
                        BlockUndo(block = undoBlock, addPrefixes = undoRanges),
                    ),
                )
            }
        }
    }

    private fun pasteConfirm() {
        val text = pasteText.value
        viewModelScope.launch {
            val answer =
                (
                    ops.blockParseMany(BlockManyRequest(text = text, blocked = block.list.value.rows)) as? Outcome.Ok
                )?.value ?: return@launch say(BlockedNotice.Failed)
            val parsed = answer.parsed
            if (parsed.toBlock.isEmpty()) {
                say(
                    when {
                        parsed.skippedEmergency > 0 -> BlockedNotice.EmergencyOnly
                        parsed.alreadyBlocked > 0 -> BlockedNotice.NothingNew
                        else -> BlockedNotice.NoNumbersFound
                    },
                )
                return@launch
            }
            when (val result = block.blockAll(parsed.toBlock)) {
                is BlockResult.Done -> {
                    pasteText.value = ""
                    effects.trySend(BlockedEffect.CloseSheet)
                    say(BlockedEffect.Undoable(BlockedUndoKind.Blocked, result.undo.unblock.size, result.undo))
                }

                else -> {
                    failed(result)
                }
            }
        }
    }

    private fun prefixConfirm() {
        val text = prefixText.value
        viewModelScope.launch {
            when (val result = block.addPrefix(text)) {
                is PrefixResult.Added -> {
                    prefixText.value = ""
                    effects.trySend(BlockedEffect.CloseSheet)
                    say(BlockedEffect.Undoable(BlockedUndoKind.RangeAdded, 1, result.undo))
                }

                // The sheet's preview already says why; a tap on a disabled button never gets here.
                else -> {
                    say(BlockedNotice.Failed)
                }
            }
        }
    }

    private fun share() {
        val text = block.list.value.shareText
        if (text.isNotEmpty()) effects.trySend(BlockedEffect.Share(text))
    }

    private fun export() {
        val list = block.list.value
        if (list.csv.isNotEmpty() && list.rows.isNotEmpty()) {
            effects.trySend(BlockedEffect.Export(list.csvFileName, list.csv))
        }
    }

    private fun import(text: String) {
        viewModelScope.launch {
            val answer =
                (
                    ops.blockParseFile(BlockManyRequest(text = text, blocked = block.list.value.rows)) as? Outcome.Ok
                )?.value ?: return@launch say(BlockedNotice.ImportFailed)
            val bulk = answer.result.bulk
            if (bulk.toBlock.isEmpty()) {
                say(
                    when {
                        bulk.alreadyBlocked > 0 -> BlockedNotice.NothingNew
                        bulk.skippedEmergency > 0 -> BlockedNotice.EmergencyOnly
                        else -> BlockedNotice.NothingNew
                    },
                )
                return@launch
            }
            when (val result = block.blockAll(bulk.toBlock)) {
                is BlockResult.Done -> {
                    say(BlockedEffect.Undoable(BlockedUndoKind.Blocked, result.undo.unblock.size, result.undo))
                }

                else -> {
                    failed(result)
                }
            }
        }
    }

    private fun openCall(key: String) {
        val call = state.value.calls.firstOrNull { it.key == key } ?: return
        effects.trySend(BlockedEffect.OpenNumber(call.number))
    }

    private fun copyNumber(key: String) {
        val row = rowOf(key) ?: return
        effects.trySend(BlockedEffect.Copy(row.numbers.first()))
    }

    private fun undo(undo: BlockUndo) {
        viewModelScope.launch { if (!block.undo(undo)) say(BlockedNotice.UndoFailed) }
    }

    private fun failed(result: BlockResult) {
        say(if (result is BlockResult.Unavailable) BlockedNotice.Unavailable else BlockedNotice.Failed)
    }

    private fun say(notice: BlockedNotice) = say(BlockedEffect.Say(notice))

    private fun say(effect: BlockedEffect) {
        effects.trySend(effect)
    }

    private companion object {
        /** `blocklist.lines` says this under a number that has no contact. */
        const val CORE_GENERIC = "Blocked number"
        const val CALLS_LIMIT = 50
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
