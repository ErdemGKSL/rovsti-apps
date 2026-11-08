package dev.rovsti.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable

/**
 * Counts how often counted slots (re)compose (ORIGINAL, SKILL performance 10.2: "<= 2 recompositions per slot during a
 * drag"). A slot is a composable the test puts into a design component's slot or beside it; its count is the number of
 * times its [CountRecompositions] call ran its side effect, i.e. the first composition plus every recomposition.
 */
@Stable
class RecompositionCounter {
    private val counts = mutableMapOf<String, Int>()

    fun increment(tag: String) {
        counts[tag] = (counts[tag] ?: 0) + 1
    }

    operator fun get(tag: String): Int = counts[tag] ?: 0

    val tags: Set<String> get() = counts.keys.toSet()

    /** Forget everything, normally right after the first composition so only the gesture is measured. */
    fun reset() = counts.clear()

    /** Recompositions of [tag] since the last [reset]. Alias of [get]; reads better after `reset()`. */
    fun since(tag: String): Int = get(tag)

    override fun toString() = counts.entries.joinToString { "${it.key}=${it.value}" }
}

/** Put this inside the slot you want counted. Composes nothing; one increment per composition of the enclosing scope. */
@Composable
fun CountRecompositions(
    tag: String,
    counter: RecompositionCounter,
) {
    SideEffect { counter.increment(tag) }
}

/**
 * A stable, skippable sibling for tests: it recomposes only when [tag] or [counter] change, so a count above one while its
 * parent recomposes means skipping broke somewhere above it.
 */
@Composable
fun CountedStatic(
    tag: String,
    counter: RecompositionCounter,
) {
    CountRecompositions(tag, counter)
}
