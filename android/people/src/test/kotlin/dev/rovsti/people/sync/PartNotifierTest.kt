package dev.rovsti.people.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

/** D9: the first part is told at once, then at most one per window, and the last one is never lost. */
@OptIn(ExperimentalCoroutinesApi::class)
class PartNotifierTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val fired = mutableListOf<Long>()
    private val notifier = PartNotifier(scope, 3_000) { fired += scope.testScheduler.currentTime }

    @Test fun leadingThenCoalescedThenTrailing() {
        notifier.part()
        scope.runCurrent()
        assertEquals("leading: at once", listOf(0L), fired)
        repeat(10) {
            scope.advanceTimeBy(200)
            notifier.part()
        }
        scope.runCurrent()
        assertEquals("ten parts in the window: nothing more yet", 1, fired.size)
        scope.advanceTimeBy(1_000)
        scope.runCurrent()
        assertEquals("the trailing one, at the end of the window", listOf(0L, 3_000L), fired)
        scope.advanceTimeBy(10_000)
        scope.runCurrent()
        assertEquals("nothing pending, nothing fired", 2, fired.size)
    }

    @Test fun aQuietWindowEndsAndTheNextPartIsLeadingAgain() {
        notifier.part()
        scope.runCurrent()
        scope.advanceTimeBy(3_001)
        scope.runCurrent()
        notifier.part()
        scope.runCurrent()
        assertEquals(listOf(0L, 3_001L), fired)
    }

    @Test fun aPartDuringTheTrailingWindowIsStillTold() {
        notifier.part()
        scope.runCurrent()
        scope.advanceTimeBy(1_000)
        notifier.part()
        scope.advanceTimeBy(2_000)
        scope.runCurrent()
        assertEquals(listOf(0L, 3_000L), fired)
        scope.advanceTimeBy(1_000)
        notifier.part()
        scope.advanceTimeBy(5_000)
        scope.runCurrent()
        assertEquals(listOf(0L, 3_000L, 6_000L), fired)
    }
}
