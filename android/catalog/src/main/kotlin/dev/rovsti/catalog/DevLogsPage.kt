package dev.rovsti.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.rovsti.core.log.LogEntry
import dev.rovsti.core.log.LogLevel
import dev.rovsti.core.log.LogMask
import dev.rovsti.core.log.LogSource
import dev.rovsti.shell.settings.dev.LogsFilter
import dev.rovsti.shell.settings.dev.LogsScreen
import dev.rovsti.shell.settings.dev.LogsUi
import dev.rovsti.shell.settings.dev.LogsViewModel
import kotlinx.collections.immutable.toImmutableList

/** The lines of the demo, as the sync and the Rust core write them (already masked: the numbers are fakes anyway). */
internal val DemoLogLines: List<LogEntry> =
    listOf(
        Triple(LogLevel.Info, "sync", "run_begin mode=v1 wa_state=Connected"),
        Triple(LogLevel.Info, "sync", "targets numbers=412 known=180 to_lookup=232 deferred=0"),
        Triple(LogLevel.Info, "sync", "batch stage=lookup n=15 found=6 ms=820"),
        Triple(LogLevel.Debug, "core", "people.upsert changed=6 jid=905321230001@s.whatsapp.net"),
        Triple(
            LogLevel.Warn,
            "sync",
            "rate_limited stage=profiles source=whatsapp retry_after_ms=60000 attempt=0 n=15",
        ),
        Triple(LogLevel.Info, "wa", "link state=Connected key=rwk_demo"),
        Triple(LogLevel.Error, "sync", "error stage=pictures kind=Network message=connection reset"),
        Triple(LogLevel.Info, "sync", "run_end outcome=partial people=186 downloaded=12 ms=421337"),
    ).mapIndexed { i, (level, tag, text) ->
        val source = if (tag == "core") LogSource.Rust else LogSource.Kotlin
        LogEntry(i + 1L, DEMO_LOG_NOW + i * 1_000L, level, tag, source, LogMask.mask(text))
    }

/** People sync v2 S3: the Logs page of developer mode on a fixed set of lines, filters live. */
@Composable
internal fun DevLogsPage() {
    var filter by remember { mutableStateOf(LogsFilter()) }
    val ui =
        LogsUi(
            lines = LogsViewModel.matching(DemoLogLines, filter).toImmutableList(),
            tags =
                DemoLogLines
                    .map { it.tag }
                    .distinct()
                    .sorted()
                    .toImmutableList(),
            filter = filter,
            total = DemoLogLines.size,
        )
    LogsScreen(
        state = ui,
        onQuery = { filter = filter.copy(query = it) },
        onLevel = { filter = filter.copy(minLevel = it) },
        onTag = { filter = filter.copy(tag = it) },
        onCommand = {},
    )
}

/** 2025-10-09T08:53:20Z: a fixed time, so the page and its shots never change. */
private const val DEMO_LOG_NOW = 1_760_000_000_000L
