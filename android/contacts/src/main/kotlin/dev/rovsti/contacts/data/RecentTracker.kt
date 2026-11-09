package dev.rovsti.contacts.data

import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.RecentPullRequest
import dev.rovsti.core.ops.ContactsOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * "Recently added" (C15) needs a creation time Android does not give. On every pull of the book this folds the contact
 * ids into the stored marks with `contacts.recent_note_pull`: the first pull only seeds the highest id seen, later pulls
 * mark the ids above it with the time they were first seen. The result is written to the preferences (`known_max_id`,
 * `added`), where the list row and the Recents page read it.
 */
class RecentTracker(
    private val book: BookRepository,
    private val store: ContactsStore,
    private val ops: ContactsOps,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var job: Job? = null

    /** Starts following the book; calling it again does nothing. */
    fun start() {
        if (job != null) return
        job =
            scope.launch {
                book.book.collectLatest { next -> if (next is Book.Ready) pull(next) }
            }
    }

    /** One pull. An empty first pull marks nothing and keeps the seed unset (the op would store 0). */
    internal suspend fun pull(ready: Book.Ready) {
        val loaded = store.load()
        val prefs = (loaded as? Outcome.Ok)?.value ?: return
        if (prefs.knownMaxId == null && ready.contacts.isEmpty()) return
        val request = RecentPullRequest(prefs.knownMaxId, prefs.added, ready.contacts.map { it.id }, clock())
        val answer = (ops.recentNotePull(request) as? Outcome.Ok)?.value ?: return
        store.update { it.copy(knownMaxId = answer.knownMaxId, added = answer.marks) }
    }
}
