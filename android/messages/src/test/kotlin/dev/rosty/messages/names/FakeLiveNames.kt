package dev.rosty.messages.names

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** WhatsApp names "asked for" in memory: [ask] is recorded, [land] is the answer arriving. */
class FakeLiveNames : LiveNames {
    private val moves = MutableStateFlow(0L)
    private val known = HashMap<String, String>()

    /** Every number asked for, in order (the real source deduplicates; this one counts). */
    val asked = ArrayList<String>()

    override val revision: StateFlow<Long> get() = moves

    override suspend fun nameOf(number: String): String? = known[number]

    override fun ask(number: String) {
        asked += number
    }

    /** WhatsApp answered: [number] is called [name]. */
    fun land(
        number: String,
        name: String,
    ) {
        known[number] = name
        moves.value += 1
    }
}
