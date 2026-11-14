package dev.rovsti.shell.settings.dev

import dev.rovsti.core.log.LogEntry
import dev.rovsti.core.log.LogLevel
import dev.rovsti.core.log.LogRing
import dev.rovsti.core.log.LogSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelTest {
    private fun entry(
        seq: Long,
        level: LogLevel,
        tag: String,
        text: String,
    ) = LogEntry(seq, 1_760_000_000_000 + seq, level, tag, LogSource.Kotlin, text)

    private val lines =
        listOf(
            entry(1, LogLevel.Debug, "core", "people.upsert changed=2"),
            entry(2, LogLevel.Info, "sync", "batch stage=lookup n=15"),
            entry(3, LogLevel.Warn, "sync", "rate_limited source=whatsapp"),
            entry(4, LogLevel.Error, "wa", "link failed"),
        )

    private fun seqs(
        query: String = "",
        level: LogLevel = LogLevel.Debug,
        tag: String? = null,
    ) = LogsViewModel.matching(lines, LogsFilter(query, level, tag)).map { it.seq }

    @Test fun levelTagAndSearchFilterTogether() {
        assertEquals(listOf(1L, 2L, 3L, 4L), seqs())
        assertEquals(listOf(3L, 4L), seqs(level = LogLevel.Warn))
        assertEquals(listOf(2L, 3L), seqs(tag = "sync"))
        assertEquals(listOf(3L), seqs(query = "RATE"))
        assertEquals(listOf(3L), seqs(query = " rate ", tag = "sync", level = LogLevel.Info))
        assertEquals(listOf(4L), seqs(query = "wa", level = LogLevel.Error))
        assertEquals(emptyList<Long>(), seqs(query = "rate", tag = "wa"))
    }

    @Test fun exportKeepsTheHeaderAndTheNewestLinesUnderTheCap() {
        val all = LogsViewModel.export("HEADER masked", lines)
        assertTrue(all.startsWith("HEADER masked\n"))
        assertEquals(5, all.trimEnd().lines().size)
        val small = LogsViewModel.export("H", lines, capBytes = 2 + 2 * 60)
        val kept = small.trimEnd().lines().drop(1)
        assertTrue(kept.isNotEmpty() && kept.size < lines.size)
        assertTrue("the newest line is kept", kept.last().endsWith("link failed"))
        assertTrue(small.encodeToByteArray().size <= 2 + 2 * 60)
    }

    @Test fun theStateFollowsTheRingAndTheFilters() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val ring = LogRing()
        val model = LogsViewModel(ring, scope, LogsFilter(tag = "sync"), work = dispatcher)
        ring.append(1, LogLevel.Info, "sync", LogSource.Kotlin, "one")
        ring.append(2, LogLevel.Info, "wa", LogSource.Kotlin, "two")
        scope.advanceTimeBy(1_000)
        scope.runCurrent()
        assertEquals(
            listOf("one"),
            model.state.value.lines
                .map { it.text },
        )
        assertEquals(listOf("sync", "wa"), model.state.value.tags)
        model.selectTag("sync")
        scope.advanceTimeBy(1_000)
        scope.runCurrent()
        assertEquals("selecting the selected tag shows every tag", 2, model.state.value.lines.size)
        model.setQuery("TWO")
        scope.advanceTimeBy(1_000)
        scope.runCurrent()
        assertEquals(
            listOf("two"),
            model.state.value.lines
                .map { it.text },
        )
        scope.cancel()
    }
}
