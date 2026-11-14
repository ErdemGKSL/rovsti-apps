package dev.rosty.stops.map

/**
 * A least-recently-used cache with a byte budget, keyed by a packed tile id ([TileKey]). Pure (no Android types), so
 * the eviction rules are tested on the JVM; the map keeps its tile bitmaps and decoded buffers in two of these.
 *
 * Built for the draw loop: [get] and [contains] neither box the key nor allocate (open addressing over a `LongArray`),
 * and "used" is a stamp on the entry, so 16 lookups per frame cost nothing. Eviction scans for the oldest stamp, which
 * is fine for the few dozen entries a screen of tiles needs.
 *
 * Not thread-safe: the map uses it on the main thread only.
 */
class ByteLru<V : Any>(
    budgetBytes: Long,
    /** Called for every value that leaves the cache (evicted, replaced, removed or cleared). */
    private val onEvicted: (key: Long, value: V) -> Unit = { _, _ -> },
) {
    private var keys = LongArray(INITIAL_CAPACITY) { EMPTY }
    private var values = arrayOfNulls<Any>(INITIAL_CAPACITY)
    private var sizes = LongArray(INITIAL_CAPACITY)
    private var stamps = LongArray(INITIAL_CAPACITY)
    private var clock = 0L

    /** Slots that are not empty: live entries and the marks left by removed ones. */
    private var occupied = 0

    var count = 0
        private set

    /** Bytes held now. */
    var bytes = 0L
        private set

    /** The configured budget. */
    var budgetBytes: Long = budgetBytes.coerceAtLeast(0)
        private set

    /**
     * A floor under the budget: what one screen needs right now. A budget below it would evict tiles that are on
     * screen, which would be requested again at once and never settle.
     */
    var floorBytes = 0L
        private set

    /** What the cache may hold: the budget, or the floor when that is larger. */
    val limitBytes: Long get() = maxOf(budgetBytes, floorBytes)

    /** Values evicted for room since the cache was made (tests and the write-up's numbers). */
    var evictions = 0L
        private set

    fun setBudget(bytes: Long) {
        budgetBytes = bytes.coerceAtLeast(0)
        trim(NO_KEY)
    }

    fun setFloor(bytes: Long) {
        floorBytes = bytes.coerceAtLeast(0)
        trim(NO_KEY)
    }

    /** The value and a "used now" mark, or null. */
    fun get(key: Long): V? {
        val i = find(key)
        if (i < 0) return null
        stamps[i] = ++clock
        @Suppress("UNCHECKED_CAST")
        return values[i] as V
    }

    /** The value without marking it used. */
    fun peek(key: Long): V? {
        val i = find(key)
        @Suppress("UNCHECKED_CAST")
        return if (i < 0) null else values[i] as V
    }

    operator fun contains(key: Long): Boolean = find(key) >= 0

    /**
     * Stores [value] as the most recently used entry and evicts the least recently used ones until the cache fits.
     * The new entry itself is never evicted by its own insertion, even when it alone is over the limit.
     */
    fun put(
        key: Long,
        value: V,
        sizeBytes: Long,
    ) {
        require(key != EMPTY && key != TOMBSTONE) { "reserved key" }
        remove(key)
        if ((occupied + 1) * 2 > keys.size) grow()
        val i = slotFor(key)
        if (keys[i] == EMPTY) occupied++
        keys[i] = key
        values[i] = value
        sizes[i] = sizeBytes.coerceAtLeast(0)
        stamps[i] = ++clock
        count++
        bytes += sizes[i]
        trim(key)
    }

    fun remove(key: Long): Boolean {
        val i = find(key)
        if (i < 0) return false
        drop(i)
        return true
    }

    fun clear() {
        for (i in keys.indices) {
            if (keys[i] != EMPTY && keys[i] != TOMBSTONE) drop(i)
        }
        keys.fill(EMPTY)
        occupied = 0
    }

    /** Calls [action] for every key, in no particular order. */
    fun forEachKey(action: (Long) -> Unit) {
        for (k in keys) if (k != EMPTY && k != TOMBSTONE) action(k)
    }

    private fun trim(keep: Long) {
        while (bytes > limitBytes && count > 0) {
            var oldest = -1
            for (i in keys.indices) {
                val k = keys[i]
                if (k == EMPTY || k == TOMBSTONE || k == keep) continue
                if (oldest < 0 || stamps[i] < stamps[oldest]) oldest = i
            }
            if (oldest < 0) return
            evictions++
            drop(oldest)
        }
    }

    private fun drop(i: Int) {
        val key = keys[i]

        @Suppress("UNCHECKED_CAST")
        val value = values[i] as V
        keys[i] = TOMBSTONE
        values[i] = null
        bytes -= sizes[i]
        sizes[i] = 0
        count--
        onEvicted(key, value)
    }

    private fun find(key: Long): Int {
        val mask = keys.size - 1
        var i = hash(key) and mask
        while (true) {
            val k = keys[i]
            if (k == key) return i
            if (k == EMPTY) return -1
            i = (i + 1) and mask
        }
    }

    private fun slotFor(key: Long): Int {
        val mask = keys.size - 1
        var i = hash(key) and mask
        while (keys[i] != EMPTY && keys[i] != TOMBSTONE) i = (i + 1) and mask
        return i
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        val oldSizes = sizes
        val oldStamps = stamps
        // Same size when the table is mostly tombstones, double when it is really full.
        val capacity = if ((count + 1) * 4 > oldKeys.size) oldKeys.size * 2 else oldKeys.size
        keys = LongArray(capacity) { EMPTY }
        values = arrayOfNulls(capacity)
        sizes = LongArray(capacity)
        stamps = LongArray(capacity)
        occupied = count
        for (j in oldKeys.indices) {
            val k = oldKeys[j]
            if (k == EMPTY || k == TOMBSTONE) continue
            val i = slotFor(k)
            keys[i] = k
            values[i] = oldValues[j]
            sizes[i] = oldSizes[j]
            stamps[i] = oldStamps[j]
        }
    }

    private fun hash(key: Long): Int {
        val h = key * -0x61c8864680b583ebL
        return (h xor (h ushr 32)).toInt()
    }

    companion object {
        private const val INITIAL_CAPACITY = 64
        private const val EMPTY = Long.MIN_VALUE
        private const val TOMBSTONE = Long.MIN_VALUE + 1
        private const val NO_KEY = Long.MIN_VALUE + 2

        /**
         * The bitmap budget for a device: about one eighth of the app's memory class (ARCH 8.4 step 3), never less
         * than [MIN_BITMAP_BUDGET]. [memoryClassMb] is `ActivityManager.memoryClass`.
         */
        fun bitmapBudgetBytes(memoryClassMb: Int): Long =
            maxOf(MIN_BITMAP_BUDGET, memoryClassMb.coerceAtLeast(0).toLong() * 1024 * 1024 / 8)

        const val MIN_BITMAP_BUDGET = 16L * 1024 * 1024
    }
}
