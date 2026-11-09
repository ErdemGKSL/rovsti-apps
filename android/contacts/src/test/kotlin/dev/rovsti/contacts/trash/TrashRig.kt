package dev.rovsti.contacts.trash

import dev.rovsti.contacts.HostRust
import dev.rovsti.contacts.card.SampleCards
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.platform.contacts.FakeContactsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * The Trash on the REAL Rust core (files in a temp data directory) with the in-memory provider. [now] is the clock of
 * the store, so a test can push an entry "40 days ago" and read the Trash "today".
 */
internal class TrashRig(
    drafts: List<ContactDraft> = SampleCards.all,
    var now: Long = SampleCards.NOW,
) {
    val host = HostRust("rovsti-trash")
    val scope = CoroutineScope(Dispatchers.Default)
    val repo = FakeContactsRepository(drafts, SampleCards.groups)
    val store = ContactsStore(host.ops, host.storeDir()) { now }
    val trash = ContactTrash(repo, store)

    fun model(): TrashViewModel = TrashViewModel(trash, store, scope)

    fun contacts(): List<ContactDraft> = runBlocking { repo.full().first() }

    fun <T> wait(block: suspend () -> T): T = runBlocking { withTimeout(15_000) { block() } }

    fun stop() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
