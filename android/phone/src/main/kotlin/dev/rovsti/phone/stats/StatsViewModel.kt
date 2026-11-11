package dev.rovsti.phone.stats

import androidx.compose.runtime.Immutable
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.phone.BucketLine
import dev.rovsti.core.model.phone.BucketRequest
import dev.rovsti.core.model.phone.DayBar
import dev.rovsti.core.model.phone.LocaleTime
import dev.rovsti.core.model.phone.OverviewAnswer
import dev.rovsti.core.model.phone.OverviewRequest
import dev.rovsti.core.model.phone.StatDir
import dev.rovsti.core.model.phone.StatRange
import dev.rovsti.core.model.shared.NumberForm
import dev.rovsti.core.model.shared.RenderRequest
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.core.ops.SharedOps
import dev.rovsti.phone.data.RecentsRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/** The three ranges of the segmented control, in its order. */
internal val StatRanges = listOf(StatRange.Week, StatRange.Days7, StatRange.Weeks8)

/** One bar of the chart: the core's label and counts, the fraction of the tallest bar, and the words a screen reader says. */
@Immutable
data class StatsBarUi(
    val index: Int,
    /** The core's label: "Tue" for a day, "2 Nov" for a week. */
    val label: String,
    /** The weekday written out for a screen reader ("Tuesday"); null for a week bar. */
    val name: String?,
    val calls: Int,
    val talkS: Long,
    /** 0..1 of the tallest bar of the range (0 for no calls). */
    val fraction: Float,
)

/** The count cells: the numbers the core summed and its talk-time text. */
@Immutable
data class StatsCellsUi(
    val calls: Int,
    val incoming: Int,
    val outgoing: Int,
    val missed: Int,
    val talk: String,
)

/** One of the most-called people. */
@Immutable
data class StatsPersonUi(
    val key: String,
    /** The saved name, else the number as the core writes it. */
    val title: String,
    val name: String?,
    val number: String,
    val calls: Int,
    /** The core's "4 calls . 1 h 05 min" over this person. */
    val summary: String,
    val missed: Int,
)

/** U52: a record with the core's words. */
@Immutable
data class StatsRecordUi(
    val title: String,
    val detail: String,
)

@Immutable
data class StatsRecordsUi(
    /** The day (or week) with the most calls: its title and "4 calls . 12 min". */
    val busiest: StatsRecordUi?,
    /** The longest call of the range: who, and "16:13 . Outgoing . 14:40". */
    val longest: StatsRecordUi?,
)

/** What the Statistics page draws. */
@Immutable
data class StatsUi(
    val ready: Boolean = false,
    val range: StatRange = StatRange.Week,
    val summary: String = "",
    val cells: StatsCellsUi? = null,
    val bars: ImmutableList<StatsBarUi> = persistentListOf(),
    val people: ImmutableList<StatsPersonUi> = persistentListOf(),
    val records: StatsRecordsUi = StatsRecordsUi(null, null),
    /** The bar whose bucket sheet is open. */
    val selected: Int? = null,
) {
    val empty: Boolean get() = ready && (cells?.calls ?: 0) == 0
    val rangeIndex: Int get() = StatRanges.indexOf(range).coerceAtLeast(0)
    val weekly: Boolean get() = range == StatRange.Weeks8
}

/** One call of a bucket sheet, as the core wrote its two lines. */
@Immutable
data class BucketLineUi(
    val key: String,
    val title: String,
    val detail: String,
    val name: String?,
    val number: String,
    val dir: StatDir,
    val whatsapp: Boolean,
)

/** The bucket sheet of a tapped bar (U46). */
@Immutable
data class BucketUi(
    val title: String,
    val summary: String,
    val lines: ImmutableList<BucketLineUi>,
)

sealed interface StatsAction {
    data class SelectRange(
        val index: Int,
    ) : StatsAction

    data class TapBar(
        val index: Int,
    ) : StatsAction

    /** The bucket sheet went away. */
    data object BucketClosed : StatsAction

    data class OpenPerson(
        val key: String,
    ) : StatsAction

    data object ExportCsv : StatsAction

    data object Share : StatsAction
}

sealed interface StatsEffect {
    data class OpenBucket(
        val bucket: BucketUi,
    ) : StatsEffect

    /** A person row: the number sheet opens (named when the log knew the name). */
    data class OpenNumber(
        val name: String?,
        val number: String,
    ) : StatsEffect

    data class SaveCsv(
        val fileName: String,
        val csv: String,
    ) : StatsEffect

    data class ShareText(
        val text: String,
    ) : StatsEffect
}

/**
 * The brain of the Statistics page (E5.10). Every number and sentence is the core's (`call_stats.overview` for the totals,
 * the bars and the top people, `call_stats.bucket` for the calls of a bar, the share text and the CSV); this class orders
 * the calls, scales the bars against the tallest one, finds the two records and runs the effects. It rebuilds on every new
 * Recents model, so a call that ends while the page is open shows up.
 *
 * [scope] belongs to the page: the owner calls [close] when the page is gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    private val recents: RecentsRepository,
    private val ops: PhoneOps,
    private val shared: SharedOps,
    private val homeCc: suspend () -> String?,
    private val locale: () -> LocaleTime,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tzOffsetS: () -> Int = {
        java.util.TimeZone
            .getDefault()
            .getOffset(clock()) / MS_PER_S
    },
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val language: () -> Locale = Locale::getDefault,
) {
    private val effects = Channel<StatsEffect>(Channel.BUFFERED)
    val effect = effects.receiveAsFlow()

    private val range = MutableStateFlow(StatRange.Week)
    private val selected = MutableStateFlow<Int?>(null)

    @Volatile private var coreBars: List<DayBar> = emptyList()

    @Volatile private var people: Map<String, StatsPersonUi> = emptyMap()

    @Volatile private var csv: Pair<String, String> = "" to ""

    @Volatile private var shareText: String = ""

    private val built: StateFlow<StatsUi> =
        combine(recents.model, range) { _, r -> r }
            .mapLatest { build(it) }
            .flowOn(compute)
            .stateIn(scope, SharingStarted.Eagerly, StatsUi())

    val state: StateFlow<StatsUi> =
        combine(built, selected) { ui, at -> ui.copy(selected = at) }
            .stateIn(scope, SharingStarted.Eagerly, StatsUi())

    /** Stops everything the page started. */
    fun close() {
        scope.cancel()
    }

    private fun hour12(): Boolean = locale().format.hour12

    private suspend fun render(
        raw: String,
        home: String?,
    ): String = (shared.render(RenderRequest(raw, NumberForm.Display, home)) as? Outcome.Ok)?.value?.text ?: raw

    private suspend fun bucket(
        bar: DayBar,
        range: StatRange,
        entries: List<dev.rovsti.core.model.phone.CallLogEntry>,
    ) = (
        ops.statsBucket(
            BucketRequest(entries, recents.extraCalls, bar, range, tzOffsetS(), hour12()),
        ) as? Outcome.Ok
    )?.value

    private suspend fun build(r: StatRange): StatsUi {
        val entries = recents.callEntries
        val extras = recents.extraCalls
        val tz = tzOffsetS()
        val answer =
            (
                ops.statsOverview(
                    OverviewRequest(entries, extras, r, clock(), tz, TOP_PEOPLE),
                ) as? Outcome.Ok
            )?.value ?: return StatsUi(ready = true, range = r)
        coreBars = answer.bars
        csv = answer.csvFileName to answer.csv
        shareText = answer.shareText
        val home = homeCc()
        // The core words each person's summary in its share text: the header line, the totals line, then one line a person.
        val personLines =
            answer.shareText
                .lines()
                .drop(2)
                .map { it.substringAfter(": ", "") }
        val top =
            answer.people.mapIndexed { i, stat ->
                val title = stat.name?.takeIf { it.isNotBlank() } ?: render(stat.number, home)
                StatsPersonUi(
                    key = stat.key,
                    title = title,
                    name = stat.name?.takeIf { it.isNotBlank() },
                    number = stat.number,
                    calls = stat.totals.calls,
                    summary = personLines.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "${stat.totals.calls}",
                    missed = stat.totals.missed,
                )
            }
        people = top.associateBy { it.key }
        return StatsUi(
            ready = true,
            range = r,
            summary = answer.summary,
            cells =
                StatsCellsUi(
                    calls = answer.totals.calls,
                    incoming = answer.totals.incoming,
                    outgoing = answer.totals.outgoing,
                    missed = answer.totals.missed,
                    talk = answer.talk,
                ),
            bars = bars(answer, tz),
            people = top.toImmutableList(),
            records = if (answer.totals.calls == 0) StatsRecordsUi(null, null) else records(answer, r, entries),
        )
    }

    private fun bars(
        answer: OverviewAnswer,
        tz: Int,
    ): ImmutableList<StatsBarUi> {
        val tallest = answer.bars.maxOfOrNull { it.calls } ?: 0
        val weekly = answer.bars.size > WEEK_BARS
        return answer.bars
            .mapIndexed { i, bar ->
                StatsBarUi(
                    index = i,
                    label = bar.label,
                    name = if (weekly) null else weekday(bar.dayStartMs, tz),
                    calls = bar.calls,
                    talkS = bar.talkS,
                    fraction = if (tallest == 0) 0f else bar.calls.toFloat() / tallest,
                )
            }.toImmutableList()
    }

    private fun weekday(
        startMs: Long,
        tz: Int,
    ): String =
        Instant
            .ofEpochMilli(startMs)
            .atOffset(ZoneOffset.ofTotalSeconds(tz))
            .dayOfWeek
            .getDisplayName(TextStyle.FULL, language())

    /**
     * U52. The busiest bar is the one with the most calls (the earliest on a tie); its title and summary are the core's
     * `call_stats.bucket`. The longest call is searched only in the bars whose talk time reaches the range's longest call
     * (no other bar can hold it); the core's line of that call words it.
     */
    private suspend fun records(
        answer: OverviewAnswer,
        r: StatRange,
        entries: List<dev.rovsti.core.model.phone.CallLogEntry>,
    ): StatsRecordsUi {
        val busiestBar =
            answer.bars
                .withIndex()
                .maxByOrNull { (_, bar) -> bar.calls }
                ?.value
        val busiest =
            busiestBar?.takeIf { it.calls > 0 }?.let { bar ->
                bucket(bar, r, entries)?.let { StatsRecordUi(it.title, it.summary) }
            }
        val longestS = answer.totals.longestS
        val longest =
            if (longestS <= 0) {
                null
            } else {
                answer.bars
                    .filter { it.talkS >= longestS }
                    .firstNotNullOfOrNull { bar ->
                        bucket(bar, r, entries)
                            ?.lines
                            ?.firstOrNull { it.call.dir != StatDir.Missed && it.call.durationS == longestS }
                    }?.let { line -> StatsRecordUi(line.title, line.detail) }
            }
        return StatsRecordsUi(busiest, longest)
    }

    fun onAction(action: StatsAction) {
        when (action) {
            is StatsAction.SelectRange -> {
                StatRanges.getOrNull(action.index)?.let { range.value = it }
            }

            is StatsAction.TapBar -> {
                tapBar(action.index)
            }

            StatsAction.BucketClosed -> {
                selected.value = null
            }

            is StatsAction.OpenPerson -> {
                people[action.key]?.let { say(StatsEffect.OpenNumber(it.name, it.number)) }
            }

            StatsAction.ExportCsv -> {
                csv.takeIf { it.second.isNotEmpty() }?.let { say(StatsEffect.SaveCsv(it.first, it.second)) }
            }

            StatsAction.Share -> {
                shareText.takeIf { it.isNotEmpty() }?.let { say(StatsEffect.ShareText(it)) }
            }
        }
    }

    private fun tapBar(index: Int) {
        val bar = coreBars.getOrNull(index) ?: return
        val r = range.value
        selected.value = index
        scope.launch(compute) {
            val answer = bucket(bar, r, recents.callEntries)
            if (answer == null) {
                selected.value = null
                return@launch
            }
            say(StatsEffect.OpenBucket(bucketUi(answer.title, answer.summary, answer.lines)))
        }
    }

    private fun bucketUi(
        title: String,
        summary: String,
        lines: List<BucketLine>,
    ) = BucketUi(
        title = title,
        summary = summary,
        lines =
            lines
                .mapIndexed { i, line -> line.toUi(i) }
                .toImmutableList(),
    )

    private fun BucketLine.toUi(i: Int) =
        BucketLineUi(
            key = "${call.dateMs}-$i",
            title = title,
            detail = detail,
            name = call.name?.takeIf { it.isNotBlank() },
            number = call.number,
            dir = call.dir,
            whatsapp = call.whatsapp,
        )

    private fun say(effect: StatsEffect) {
        effects.trySend(effect)
    }

    private companion object {
        const val TOP_PEOPLE = 5
        const val WEEK_BARS = 7
        const val MS_PER_S = 1000
    }
}
