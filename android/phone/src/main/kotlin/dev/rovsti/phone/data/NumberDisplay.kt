package dev.rovsti.phone.data

import dev.rovsti.core.Outcome
import dev.rovsti.core.model.shared.NumberForm
import dev.rovsti.core.model.shared.RenderRequest
import dev.rovsti.core.ops.SharedOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * A phone number as people read it (`+905551112233` -> `+90 555 111 22 33`), by the Rust core's `NumberForm.Display`
 * with the home calling code. The call screens build their state without suspending, so a number is [warm]ed (one
 * Rust call, once per number) and then [peek]ed; [version] changes whenever a new text arrives so a state that
 * combines it is built again. A number the core cannot format stays as it is.
 */
class NumberDisplay(
    private val render: suspend (raw: String, home: String?) -> String?,
    private val homeCc: suspend () -> String?,
) {
    constructor(shared: SharedOps, homeCc: suspend () -> String?) : this(
        { raw, home -> (shared.render(RenderRequest(raw, NumberForm.Display, home)) as? Outcome.Ok)?.value?.text },
        homeCc,
    )

    private val cache = ConcurrentHashMap<String, String>()
    private val changes = MutableStateFlow(0)

    /** Counts the texts learned so far. */
    val version: StateFlow<Int> = changes

    /** The text of [raw] if it was [warm]ed, else [raw] itself. */
    fun peek(raw: String): String = if (raw.isBlank()) raw else cache[raw] ?: raw

    /** Learns the text of every number in [raws]. */
    suspend fun warm(raws: Iterable<String>) {
        for (raw in raws) {
            val key = raw.trim()
            if (key.isEmpty() || cache.containsKey(key)) continue
            val text = render(key, homeCc())?.takeIf { it.isNotBlank() } ?: key
            cache[key] = text
            if (text != key) changes.value += 1
        }
    }

    /** The text of [raw], asking the core when it is new. */
    suspend fun text(raw: String): String {
        warm(listOf(raw))
        return peek(raw.trim())
    }

    companion object {
        /** Shows every number as it is (tests that do not look at the number). */
        val AsIs = NumberDisplay({ _, _ -> null }, { null })
    }
}
