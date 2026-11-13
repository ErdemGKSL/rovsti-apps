package dev.rovsti.phone.notes

import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.CallNote
import dev.rovsti.core.model.phone.DateOrder
import dev.rovsti.core.model.phone.LocaleTime
import dev.rovsti.core.model.phone.Notes
import dev.rovsti.core.model.phone.TimeFormat
import dev.rovsti.core.ops.NativeSharedOps
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.data.NumberDisplay
import dev.rovsti.phone.data.T0
import dev.rovsti.phone.recents.RecentsRig
import dev.rovsti.phone.stats.statsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

private const val MIN = 60_000L
private const val DAY = 1_440L * MIN

const val AYSE = "+905551112233"
const val MERT = "+905559998877"
const val STRANGER = "+4930123456"
const val TURK = "+905557771122"

/** Three notes on calls of the stats log: Ayse an hour ago, Mert yesterday, a stranger two days ago (T0 = Tue 22:13 UTC). */
internal fun sampleNotes(): Notes =
    Notes(
        schema = 1,
        notes =
            listOf(
                CallNote(AYSE, T0 - 60 * MIN, "Fatura icin geri ara", T0),
                CallNote(MERT, T0 - DAY - 60 * MIN, "Bring the invoice\nand the keys", T0),
                CallNote(STRANGER, T0 - 2 * DAY - 60 * MIN, "Meeting moved to Friday", T0),
            ),
    )

internal val NamesOfSample: Map<String, String> = mapOf(AYSE to "Ayse Yilmaz", MERT to "Mert Kaya")

/** The Notes model over the REAL Rust core and the Recents rig's fakes, at [T0] in UTC. */
internal class NotesRig(
    host: HostRust,
    scope: CoroutineScope,
    log: List<CallLogEntry> = statsLog(),
    private val notes: Notes = sampleNotes(),
) {
    val base = RecentsRig(host, scope, log)
    var locale = LocaleTime(0, TimeFormat(hour12 = false, dateOrder = DateOrder.DayMonthYear))
    val model =
        NotesViewModel(
            store = base.store,
            recents = base.repo,
            ops = base.ops,
            nameOf = { NamesOfSample[it] },
            display = NumberDisplay(NativeSharedOps(host.core)) { "90" },
            locale = { locale },
            scope = scope,
            clock = { T0 },
            tzOffsetS = { 0 },
            compute = Dispatchers.Default,
        )

    suspend fun ready() {
        base.ready()
        base.store.updateNotes { notes }
    }
}

internal suspend fun NotesViewModel.loaded(check: (NotesUi) -> Boolean = { it.ready && it.total > 0 }): NotesUi =
    withTimeout(10_000) { state.first(check) }

internal suspend inline fun <reified E : NotesEffect> NotesViewModel.nextEffect(): E =
    withTimeout(10_000) { effect.first { it is E } as E }
