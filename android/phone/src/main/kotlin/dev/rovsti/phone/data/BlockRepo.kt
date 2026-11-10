package dev.rovsti.phone.data

import dev.rovsti.core.Outcome
import dev.rovsti.core.model.phone.BlockCheckRequest
import dev.rovsti.core.model.phone.BlockLinesRequest
import dev.rovsti.core.model.phone.BlockRefusal
import dev.rovsti.core.model.phone.BlockedLine
import dev.rovsti.core.model.phone.BlockedNumber
import dev.rovsti.core.model.phone.NamesByNumber
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.platform.calllog.Blocklist
import dev.rovsti.platform.calllog.BlocklistRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.TimeZone

/** The system blocklist with the text the core made for it. [lines] line up with [rows]. */
data class BlockList(
    val rows: ImmutableList<BlockedNumber> = persistentListOf(),
    val lines: ImmutableList<BlockedLine> = persistentListOf(),
    val csv: String = "",
    val csvFileName: String = "",
    val shareText: String = "",
    /** False until the first read has finished. */
    val ready: Boolean = false,
)

/** What [BlockRepo.block] and [BlockRepo.unblock] did. */
sealed interface BlockResult {
    /** Done; [undo] puts the list back as it was. */
    data class Done(
        val undo: BlockUndo,
    ) : BlockResult

    /** The core refused the number (an emergency number, none, not a number). */
    data class Refused(
        val reason: BlockRefusal,
    ) : BlockResult

    data object AlreadyBlocked : BlockResult

    /** This app is not the default dialer: the system blocklist cannot be written. */
    data object Unavailable : BlockResult

    /** The provider refused or failed. */
    data object Failed : BlockResult
}

/** The numbers to unblock and to block again to take back one change. */
data class BlockUndo(
    val unblock: List<String> = emptyList(),
    val block: List<String> = emptyList(),
)

/**
 * The blocklist: [BlocklistRepository] (the system provider, shared with the SMS app) plus the core's rules.
 * `blocklist.check` decides whether a number may be blocked (emergency numbers never; a second block of the same number
 * is "already blocked"), `blocklist.lines` makes the rows' text, the CSV and the share text. Every change answers an
 * [BlockUndo] snapshot, so the Undo toast is one call to [undo].
 */
class BlockRepo(
    private val repository: BlocklistRepository,
    private val ops: PhoneOps,
    scope: CoroutineScope,
    /** Contact names for the list rows, by number as stored. */
    private val names: () -> NamesByNumber = { emptyMap() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val state = MutableStateFlow(BlockList())
    private var current: List<BlockedNumber> = emptyList()

    val list: StateFlow<BlockList> = state.asStateFlow()

    init {
        scope.launch {
            repository.numbers().collect { rows ->
                current = rows
                state.value = build(rows)
            }
        }
    }

    /** Whether the system lets this app write the blocklist. */
    suspend fun canBlock(): Boolean = repository.canBlock()

    /** Whether the number may be blocked, by the core's rules; null when it may, else the reason. */
    suspend fun check(raw: String): BlockResult? {
        val out = ops.blockCheck(BlockCheckRequest(raw = raw, blocked = current))
        val verdict = (out as? Outcome.Ok)?.value ?: return BlockResult.Failed
        return when {
            verdict.refusal != null -> BlockResult.Refused(verdict.refusal!!)
            verdict.alreadyBlocked -> BlockResult.AlreadyBlocked
            else -> null
        }
    }

    suspend fun block(raw: String): BlockResult {
        check(raw)?.let { return it }
        return when (repository.block(raw)) {
            Blocklist.Result.OK -> BlockResult.Done(BlockUndo(unblock = listOf(raw)))
            Blocklist.Result.UNAVAILABLE -> BlockResult.Unavailable
            Blocklist.Result.REFUSED -> BlockResult.Refused(BlockRefusal.NotANumber)
            else -> BlockResult.Failed
        }
    }

    /** Unblocks every row with a number in [numbers]; the snapshot holds the rows as they were. */
    suspend fun unblock(numbers: List<String>): BlockResult {
        val held = current.filter { row -> numbers.any { it == row.numberRaw || it == row.normalized } }
        var failure: BlockResult? = null
        val done = mutableListOf<String>()
        for (row in held) {
            when (repository.unblock(row.numberRaw)) {
                Blocklist.Result.OK -> done += row.numberRaw
                Blocklist.Result.UNAVAILABLE -> failure = failure ?: BlockResult.Unavailable
                else -> failure = failure ?: BlockResult.Failed
            }
        }
        if (done.isEmpty() && failure != null) return failure
        return BlockResult.Done(BlockUndo(block = done))
    }

    /** Takes one change back; true when everything went through. */
    suspend fun undo(undo: BlockUndo): Boolean {
        var ok = true
        for (n in undo.unblock) ok = repository.unblock(n) == Blocklist.Result.OK && ok
        for (n in undo.block) ok = repository.block(n) == Blocklist.Result.OK && ok
        return ok
    }

    private suspend fun build(rows: List<BlockedNumber>): BlockList {
        val now = clock()
        val out =
            ops.blockLines(BlockLinesRequest(rows, names(), TimeZone.getDefault().getOffset(now) / MS_PER_S, now))
        val lines = (out as? Outcome.Ok)?.value
        return BlockList(
            rows = rows.toImmutableList(),
            lines = lines?.lines.orEmpty().toImmutableList(),
            csv = lines?.csv.orEmpty(),
            csvFileName = lines?.csvFileName.orEmpty(),
            shareText = lines?.shareText.orEmpty(),
            ready = true,
        )
    }

    private companion object {
        const val MS_PER_S = 1000
    }
}
