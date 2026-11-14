package dev.rovsti.shell.settings.dev

import androidx.compose.runtime.Immutable
import dev.rovsti.core.log.LogEntry
import dev.rovsti.core.log.LogFile
import dev.rovsti.core.log.LogLevel
import dev.rovsti.core.log.LogRing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the Logs page filters by. [minLevel] Debug means "All"; [tag] null means every tag. */
@Immutable
data class LogsFilter(
    val query: String = "",
    val minLevel: LogLevel = LogLevel.Debug,
    val tag: String? = null,
)

/** What the Logs page shows: the matching [lines] (oldest first), the [tags] present, and [total] lines kept. */
@Immutable
data class LogsUi(
    val lines: ImmutableList<LogEntry> = persistentListOf(),
    val tags: ImmutableList<String> = persistentListOf(),
    val filter: LogsFilter = LogsFilter(),
    val total: Int = 0,
)

/**
 * The Logs page's state over the memory [ring]. Filtering runs on [work] on a snapshot, never on the main thread; new
 * lines refresh the page at most [REFRESH_MS] apart (the last one always lands), the search after [SEARCH_DEBOUNCE_MS].
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LogsViewModel(
    private val ring: LogRing,
    scope: CoroutineScope,
    initial: LogsFilter = LogsFilter(),
    work: CoroutineDispatcher = Dispatchers.Default,
) {
    private val query = MutableStateFlow(initial.query)
    private val level = MutableStateFlow(initial.minLevel)
    private val tag = MutableStateFlow(initial.tag)

    // A StateFlow is conflated already: a slow collector gets the newest seq after its pause.
    private val ticks = ring.lastSeq.onEach { delay(REFRESH_MS) }

    val state: StateFlow<LogsUi> =
        combine(ticks, query.debounce(SEARCH_DEBOUNCE_MS), level, tag) { _, q, l, t -> LogsFilter(q, l, t) }
            .mapLatest { wanted ->
                val all = ring.snapshot()
                LogsUi(
                    lines = matching(all, wanted).toImmutableList(),
                    tags = all.mapTo(sortedSetOf()) { it.tag }.toImmutableList(),
                    filter = wanted,
                    total = all.size,
                )
            }.flowOn(work)
            .stateIn(scope, SharingStarted.Eagerly, LogsUi(filter = initial))

    fun setQuery(text: String) {
        query.value = text
    }

    fun setLevel(min: LogLevel) {
        level.value = min
    }

    /** Selects [name], or every tag again when it is the selected one (or null). */
    fun selectTag(name: String?) {
        tag.value = if (name == tag.value) null else name
    }

    companion object {
        const val REFRESH_MS = 250L
        const val SEARCH_DEBOUNCE_MS = 150L

        /** Copy and Share export at most this many bytes (the newest lines win). */
        const val EXPORT_CAP_BYTES = 256 * 1024

        /** The lines that pass [filter]: at least its level, its tag, and the query anywhere (any case). */
        fun matching(
            entries: List<LogEntry>,
            filter: LogsFilter,
        ): List<LogEntry> {
            val query = filter.query.trim()
            return entries.filter { e ->
                e.level >= filter.minLevel &&
                    (filter.tag == null || e.tag == filter.tag) &&
                    (query.isEmpty() || e.text.contains(query, ignoreCase = true) || e.tag.contains(query, true))
            }
        }

        private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

        /** `09:58:34.123`, local time. */
        fun time(ms: Long): String = clockFormat.format(Instant.ofEpochMilli(ms))

        /** One line as the page and the export show it: time, level letter, tag, text. */
        fun line(entry: LogEntry): String = "${time(entry.timeMs)} ${entry.level.letter} ${entry.tag} ${entry.text}"

        /**
         * [header] and then [entries] as text, newest kept when it is over [capBytes] (UTF-8): the export of Copy and
         * Share.
         */
        fun export(
            header: String,
            entries: List<LogEntry>,
            capBytes: Int = EXPORT_CAP_BYTES,
        ): String {
            var budget = capBytes - header.encodeToByteArray().size - 1
            val kept = ArrayDeque<String>()
            for (entry in entries.asReversed()) {
                val text = LogFile.format(entry)
                val size = text.encodeToByteArray().size
                if (size > budget) break
                budget -= size
                kept.addFirst(text)
            }
            return buildString {
                append(header).append('\n')
                kept.forEach(::append)
            }
        }
    }
}
