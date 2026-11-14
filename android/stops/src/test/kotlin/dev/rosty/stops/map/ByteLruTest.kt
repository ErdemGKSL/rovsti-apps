package dev.rosty.stops.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** E7.2b AC2: the bitmap cache's eviction rules, on the JVM with a byte budget and plain strings as values. */
class ByteLruTest {
    private val evicted = ArrayList<Long>()

    private fun lru(budget: Long) = ByteLru<String>(budget) { key, _ -> evicted += key }

    @Test fun holdsWhatFitsAndEvictsTheLeastRecentlyUsed() {
        val c = lru(300)
        c.put(1, "a", 100)
        c.put(2, "b", 100)
        c.put(3, "c", 100)
        assertEquals(300, c.bytes)
        assertTrue(evicted.isEmpty())
        c.put(4, "d", 100)
        assertEquals(listOf(1L), evicted)
        assertNull(c.get(1))
        assertEquals(3, c.count)
        assertEquals(300, c.bytes)
        assertEquals(1, c.evictions)
    }

    @Test fun getMarksAsUsedAndPeekDoesNot() {
        val c = lru(300)
        c.put(1, "a", 100)
        c.put(2, "b", 100)
        c.put(3, "c", 100)
        assertEquals("a", c.get(1))
        assertEquals("b", c.peek(2))
        assertTrue(2L in c)
        c.put(4, "d", 100)
        // 2 was only peeked at, so it is the oldest; 1 was used.
        assertEquals(listOf(2L), evicted)
        assertEquals("a", c.peek(1))
    }

    @Test fun oneBigEntryEvictsSeveral() {
        val c = lru(300)
        c.put(1, "a", 100)
        c.put(2, "b", 100)
        c.put(3, "c", 100)
        c.put(4, "big", 250)
        assertEquals(listOf(1L, 2L, 3L), evicted)
        assertEquals(250, c.bytes)
    }

    @Test fun anEntryLargerThanTheBudgetIsKeptAlone() {
        val c = lru(100)
        c.put(1, "a", 60)
        c.put(2, "huge", 500)
        assertEquals(listOf(1L), evicted)
        assertEquals("huge", c.get(2))
        c.put(3, "c", 10)
        assertEquals(listOf(1L, 2L), evicted)
        assertEquals(10, c.bytes)
    }

    @Test fun replacingAKeyReleasesTheOldValue() {
        val c = lru(300)
        c.put(1, "a", 100)
        c.put(1, "a2", 150)
        assertEquals(listOf(1L), evicted)
        assertEquals(150, c.bytes)
        assertEquals(1, c.count)
        assertEquals("a2", c.get(1))
        // A replacement is not an eviction for room.
        assertEquals(0, c.evictions)
    }

    @Test fun theFloorKeepsAScreenOfTilesWhateverTheBudget() {
        val c = lru(200)
        c.setFloor(400)
        assertEquals(400, c.limitBytes)
        for (k in 1L..4L) c.put(k, "t$k", 100)
        assertTrue(evicted.isEmpty())
        c.put(5, "t5", 100)
        assertEquals(listOf(1L), evicted)
        // The screen got smaller: the budget applies again and the oldest go.
        c.setFloor(0)
        assertEquals(200, c.limitBytes)
        assertEquals(200, c.bytes)
        assertEquals(listOf(1L, 2L, 3L), evicted)
    }

    @Test fun shrinkingTheBudgetTrims() {
        val c = lru(1000)
        for (k in 1L..10L) c.put(k, "t", 100)
        c.get(1)
        c.setBudget(250)
        assertEquals(200, c.bytes)
        assertTrue(1L in c && 10L in c)
        assertEquals(8, evicted.size)
    }

    @Test fun removeAndClear() {
        val c = lru(1000)
        c.put(1, "a", 100)
        c.put(2, "b", 100)
        assertTrue(c.remove(1))
        assertFalse(c.remove(1))
        assertEquals(100, c.bytes)
        c.clear()
        assertEquals(0, c.count)
        assertEquals(0, c.bytes)
        assertEquals(listOf(1L, 2L), evicted)
        c.put(3, "c", 10)
        assertEquals("c", c.get(3))
    }

    @Test fun survivesManyInsertionsAndRemovals() {
        // Churn far beyond the table size: removed slots are reused and lookups still end.
        val c = lru(16 * 100)
        for (k in 0L until 20_000L) {
            c.put(TileKey.of(14, (k % 5000).toInt(), (k / 7).toInt()), "v", 100)
            if (k % 3 == 0L) c.remove(TileKey.of(14, ((k - 1) % 5000).toInt(), ((k - 1) / 7).toInt()))
            assertTrue(c.bytes <= 1600)
        }
        var seen = 0
        c.forEachKey { seen++ }
        assertEquals(c.count, seen)
        assertEquals(c.count * 100L, c.bytes)
        assertNull(c.get(TileKey.of(3, 1, 1)))
    }

    @Test fun growsPastItsFirstTable() {
        val c = lru(Long.MAX_VALUE)
        for (k in 0L until 1000L) c.put(k, "v$k", 1)
        assertEquals(1000, c.count)
        for (k in 0L until 1000L) assertEquals("v$k", c.get(k))
    }

    @Test fun theDeviceBudgetIsAnEighthOfTheMemoryClass() {
        assertEquals(32L * 1024 * 1024, ByteLru.bitmapBudgetBytes(256))
        assertEquals(64L * 1024 * 1024, ByteLru.bitmapBudgetBytes(512))
        // A small heap still gets a screen's worth.
        assertEquals(ByteLru.MIN_BITMAP_BUDGET, ByteLru.bitmapBudgetBytes(64))
        assertEquals(ByteLru.MIN_BITMAP_BUDGET, ByteLru.bitmapBudgetBytes(-5))
    }
}
