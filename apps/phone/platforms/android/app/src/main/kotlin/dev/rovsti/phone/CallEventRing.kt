package dev.rovsti.phone

/**
 * A fixed ring of the last [CAPACITY] registry events for the Diagnostics report (AD-36).
 * Stores only an event name (a constant), a call id, a state name (a constant), a caps mask and
 * counts: never a number or a name. Parallel primitive arrays, so recording allocates nothing.
 * Main thread only.
 */
class CallEventRing(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val at = LongArray(CAPACITY)
    private val event = arrayOfNulls<String>(CAPACITY)
    private val callId = IntArray(CAPACITY)
    private val state = arrayOfNulls<String>(CAPACITY)
    private val caps = IntArray(CAPACITY)
    private val calls = IntArray(CAPACITY)
    private val endpoints = IntArray(CAPACITY)
    private var next = 0
    private var total = 0
    private val origin = clock()

    val size: Int get() = minOf(total, CAPACITY)

    /** [name] and [stateName] must be constants, never text from a call. */
    fun record(name: String, id: Int, stateName: String, capsMask: Int, callCount: Int, endpointCount: Int) {
        val i = next
        at[i] = clock() - origin
        event[i] = name
        callId[i] = id
        state[i] = stateName
        caps[i] = capsMask
        calls[i] = callCount
        endpoints[i] = endpointCount
        next = (i + 1) % CAPACITY
        total++
    }

    /** Oldest first, one line per event: `elapsed event id state caps calls endpoints`. */
    fun dump(): List<String> {
        val out = ArrayList<String>(size)
        val start = if (total < CAPACITY) 0 else next
        for (k in 0 until size) {
            val i = (start + k) % CAPACITY
            out.add("${at[i]} ${event[i]} id=${callId[i]} ${state[i]} caps=${caps[i]} calls=${calls[i]} ep=${endpoints[i]}")
        }
        return out
    }

    companion object {
        const val CAPACITY = 50
    }
}
