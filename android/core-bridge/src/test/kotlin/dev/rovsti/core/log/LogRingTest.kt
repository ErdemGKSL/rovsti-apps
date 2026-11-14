package dev.rovsti.core.log

import org.junit.Assert.assertEquals
import org.junit.Test

class LogRingTest {
    private fun LogRing.add(text: String) = append(0, LogLevel.Info, "t", LogSource.Kotlin, text)

    @Test
    fun keepsTheNewestCapacityLinesWithGrowingSeq() {
        val ring = LogRing(capacity = 3)
        for (i in 1..5) ring.add("line $i")
        assertEquals(listOf(3L, 4L, 5L), ring.snapshot().map { it.seq })
        assertEquals(listOf("line 3", "line 4", "line 5"), ring.snapshot().map { it.text })
        assertEquals(5L, ring.lastSeq.value)
    }

    @Test
    fun longLinesAreCutAndClearKeepsCounting() {
        val ring = LogRing()
        ring.add("x".repeat(LogRing.MAX_LINE + 50))
        assertEquals(
            LogRing.MAX_LINE,
            ring
                .snapshot()
                .single()
                .text.length,
        )
        ring.clear()
        assertEquals(emptyList<LogEntry>(), ring.snapshot())
        assertEquals(2L, ring.add("next").seq)
    }

    @Test
    fun appendsFromManyThreadsLoseNothing() {
        val ring = LogRing(capacity = 10_000)
        val threads = (0 until 8).map { t -> Thread { repeat(500) { ring.add("$t-$it") } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        val seqs = ring.snapshot().map { it.seq }
        assertEquals(4_000, seqs.size)
        assertEquals((1L..4_000L).toList(), seqs.sorted())
    }
}
