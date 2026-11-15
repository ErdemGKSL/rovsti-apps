package dev.rosty.messages.wa.status

import dev.rosty.core.model.wa.StatusAuthor
import dev.rosty.core.model.wa.StatusList
import dev.rosty.core.model.wa.WaIds

// The viewer rules of `rosty_wa_proto::status` (`StatusPlayer`, `StatusList::mark_viewed`, `sort_authors`), which have
// no bridge op: the same pure decisions, kept in step with the Rust tests by `StatusPlayerTest`.

/** What a viewer step did. */
enum class StatusStep {
    /** Still on the same update. */
    Stay,

    /** Another update of the same author. */
    Item,

    /** The first update of another author. */
    Author,

    /** Past the last update: the viewer closes. */
    Close,
}

/**
 * The full-screen viewer's position: which update of which author shows and how far its timer got. Authors are in the
 * list's order; `counts[a]` is how many updates author `a` has. Pure: the caller feeds it elapsed time and taps.
 */
class StatusPlayer private constructor(
    private val counts: List<Int>,
    author: Int,
    item: Int,
    durationMs: Long,
) {
    var author: Int = author
        private set
    var item: Int = item
        private set
    var elapsedMs: Long = 0
        private set
    var durationMs: Long = durationMs.coerceAtLeast(1)
        set(value) {
            field = value.coerceAtLeast(1)
        }

    /** Holding the screen stops the timer. */
    var paused: Boolean = false

    /** Updates the current author has (the progress bars). */
    val count: Int get() = counts.getOrElse(author) { 0 }

    /** The fill of progress bar [index] of the current author, 0 to 1. */
    fun fill(index: Int): Float =
        when {
            index < item -> 1f
            index > item -> 0f
            else -> (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        }

    /** Lets [dtMs] pass; moves on when the update's time is up. */
    fun advance(dtMs: Long): StatusStep {
        if (paused) return StatusStep.Stay
        elapsedMs += dtMs.coerceAtLeast(0)
        return if (elapsedMs >= durationMs) forward() else StatusStep.Stay
    }

    /** A tap on the right: the next update, the next author's first, or close. */
    fun forward(): StatusStep {
        elapsedMs = 0
        if (item + 1 < count) {
            item++
            return StatusStep.Item
        }
        val next = (author + 1 until counts.size).firstOrNull { counts[it] > 0 } ?: return StatusStep.Close
        author = next
        item = 0
        return StatusStep.Author
    }

    /** A tap on the left: the previous update, the previous author's last, or the very first one from its start. */
    fun back(): StatusStep {
        elapsedMs = 0
        if (item > 0) {
            item--
            return StatusStep.Item
        }
        val previous = (author - 1 downTo 0).firstOrNull { counts[it] > 0 } ?: return StatusStep.Stay
        author = previous
        item = counts[previous] - 1
        return StatusStep.Author
    }

    companion object {
        /** How long a text or photo update shows. */
        const val SHOW_MS = 5_000L

        /** Starts at update [item] of [author]; null when there is nothing to show there. */
        fun of(
            counts: List<Int>,
            author: Int,
            item: Int,
            durationMs: Long = SHOW_MS,
        ): StatusPlayer? {
            val count = counts.getOrNull(author) ?: return null
            return if (item in 0 until count) StatusPlayer(counts, author, item, durationMs) else null
        }
    }
}

/** Updates of this author not yet seen. */
fun StatusAuthor.unviewed(): Int = items.count { !it.viewed }

/** Updates of others not yet seen. */
fun StatusList.unviewed(): Int = authors.sumOf { it.unviewed() }

fun StatusList.isEmpty(): Boolean = mine.isEmpty() && authors.isEmpty()

/**
 * The list with [ids] seen. A person whose updates are all seen now moves down among the seen ones: people with unseen
 * updates first, then by their newest update, newest first (ties by JID).
 */
fun StatusList.markViewed(ids: Collection<String>): StatusList {
    if (ids.isEmpty() || authors.none { a -> a.items.any { !it.viewed && it.id in ids } }) return this
    val marked =
        authors.map { a ->
            a.copy(
                items =
                    a.items.map {
                        if (!it.viewed &&
                            it.id in ids
                        ) {
                            it.copy(viewed = true)
                        } else {
                            it
                        }
                    },
            )
        }
    return copy(
        authors =
            marked.sortedWith(
                compareBy<StatusAuthor> { it.unviewed() == 0 }
                    .thenByDescending { a -> a.items.maxOfOrNull { it.timestampMs } ?: 0 }
                    .thenBy { it.author },
            ),
    )
}

/** The chat to write to about an update: only other people's phone JIDs can be messaged. */
fun statusMessageTarget(
    author: String,
    fromMe: Boolean,
): String? {
    if (fromMe) return null
    return WaIds.bare(author).takeIf { WaIds.isPn(author) }
}
