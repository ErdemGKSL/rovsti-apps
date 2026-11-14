package dev.rovsti.core.log

import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.model.core.LogsDrainAnswer
import dev.rovsti.core.model.core.LogsDrainRequest
import dev.rovsti.core.model.core.RustLogLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RustLogPumpTest {
    /** A fake `core.logs_drain` over [lines]; records every request. */
    private class FakeSource(
        var lines: List<RustLogLine> = emptyList(),
        var dropped: Long = 0,
        var fail: Boolean = false,
    ) : RustLogSource {
        val asked = mutableListOf<LogsDrainRequest>()

        override suspend fun drain(req: LogsDrainRequest): Outcome<LogsDrainAnswer> {
            asked += req
            if (fail) return Outcome.Err(RustError.Panic("x"))
            val after = lines.filter { it.seq > req.afterSeq }.take(req.max)
            val last = after.lastOrNull()?.seq ?: req.afterSeq
            return Outcome.Ok(LogsDrainAnswer(after, dropped.also { dropped = 0 }, last))
        }
    }

    private data class Got(
        val level: LogLevel,
        val tag: String,
        val text: String,
    )

    private fun line(
        seq: Long,
        target: String = "rovsti::sync",
        level: String = "info",
    ) = RustLogLine(seq, 1_000 + seq, level, target, "line $seq")

    @Test
    fun drainsEverythingThenOnlyWhatIsNew() =
        runTest {
            val got = mutableListOf<Got>()
            val source = FakeSource((1L..3L).map { line(it) })
            val pump = RustLogPump(source, this) { _, level, tag, text -> got += Got(level, tag, text) }
            assertTrue(pump.pumpOnce())
            assertEquals(listOf("line 1", "line 2", "line 3"), got.map { it.text })
            assertEquals(3L, pump.afterSeq)
            source.lines = source.lines + line(4, "rovsti_ffi::ops", "warn")
            pump.pumpOnce()
            assertEquals(Got(LogLevel.Warn, "core", "line 4"), got.last())
            assertEquals(4, got.size)
        }

    @Test
    fun lostLinesAreReportedAndAFailureIsRetriedNextTick() =
        runTest {
            val got = mutableListOf<Got>()
            val source = FakeSource(listOf(line(5)), dropped = 4)
            val pump = RustLogPump(source, this) { _, level, tag, text -> got += Got(level, tag, text) }
            pump.pumpOnce()
            assertEquals(Got(LogLevel.Warn, "core", "rust_logs_lost n=4"), got.last())
            source.fail = true
            assertFalse(pump.pumpOnce())
            assertEquals(5L, pump.afterSeq)
        }

    @Test
    fun pollsOnlyWhileStarted() {
        val scope = TestScope(StandardTestDispatcher())
        val source = FakeSource()
        val pump = RustLogPump(source, scope, intervalMs = 1_000) { _, _, _, _ -> }
        pump.start()
        scope.runCurrent()
        scope.advanceTimeBy(2_500)
        val whileOn = source.asked.size
        assertEquals(3, whileOn)
        pump.stop()
        scope.advanceTimeBy(10_000)
        assertEquals(whileOn, source.asked.size)
    }

    @Test
    fun tagsComeFromTheTarget() {
        assertEquals("sync", RustLogPump.tagOf("rovsti::sync"))
        assertEquals("sync", RustLogPump.tagOf("rovsti::sync::pace"))
        assertEquals("core", RustLogPump.tagOf("rovsti_app_core::people"))
        assertEquals("core", RustLogPump.tagOf("ureq"))
    }
}
