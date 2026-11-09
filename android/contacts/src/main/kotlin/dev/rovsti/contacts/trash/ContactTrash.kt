package dev.rovsti.contacts.trash

import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.Account
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.TrashListAnswer
import dev.rovsti.platform.contacts.ContactResult
import dev.rovsti.platform.contacts.ContactsRepository
import kotlinx.coroutines.flow.first

/** The words under a contact that has no name: the Trash and the undo pill never print an empty line. */
fun ContactDraft.trashName(): String =
    listOf(given, family)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifEmpty { org.ifBlank { nickname.ifBlank { "Contact" } } }

/** What moving a contact to the Trash came to. */
sealed interface MoveResult {
    /** The contact is in the Trash under [key] and gone from the address book. */
    data class Done(
        val key: Long,
        val name: String,
    ) : MoveResult

    /** The contact is not in the address book (any more). */
    data object Gone : MoveResult

    /** No copy could be kept, so the contact was NOT deleted. */
    data object NoCopy : MoveResult

    /** The provider refused the delete; the copy was taken back out of the Trash. */
    data object Refused : MoveResult
}

/** What taking contacts out of the Trash came to. */
sealed interface RestoreResult {
    data class Done(
        val ids: List<Long>,
        val notice: String,
    ) : RestoreResult

    /** The entries are not in the Trash (they expired or were deleted for good). */
    data object Missing : RestoreResult

    /** The provider refused the insert; the entries are back in the Trash. */
    data object Refused : RestoreResult
}

/**
 * Delete and restore with the Trash (E4.5). A delete copies the contact to the Trash first (`contacts.trash_push`) and
 * only then removes it from the provider, so nothing is ever lost between the two steps: when the provider refuses,
 * the copy is taken back out. A restore takes the entries out (`contacts.trash_restore`, which drops groups of other
 * accounts and moves a contact whose account is gone to the default one) and inserts them as new contacts; when the
 * provider refuses, they go back in the Trash. The decisions are the Rust core's; this class only orders the effects.
 */
class ContactTrash(
    private val repo: ContactsRepository,
    private val store: ContactsStore,
) {
    /** The Trash, newest first, expired entries dropped. */
    suspend fun list(): TrashListAnswer? = (store.trash() as? Outcome.Ok)?.value

    suspend fun delete(id: Long): MoveResult {
        val draft = repo.detail(id).first() ?: return MoveResult.Gone
        val pushed = (store.trashPush(listOf(draft)) as? Outcome.Ok)?.value
        val key = pushed?.keys?.firstOrNull() ?: return MoveResult.NoCopy
        return when (repo.delete(id)) {
            is ContactResult.Ok -> {
                MoveResult.Done(key, draft.trashName())
            }

            is ContactResult.Failed -> {
                store.trashDelete(listOf(key))
                MoveResult.Refused
            }
        }
    }

    suspend fun restore(keys: List<Long>): RestoreResult {
        if (keys.isEmpty()) return RestoreResult.Missing
        val groups = repo.groups().first()
        val accounts = (repo.accounts() as? ContactResult.Ok)?.value.orEmpty().ifEmpty { listOf(Account()) }
        val answer =
            (store.trashRestore(keys, groups, accounts, store.prefs.value.saveTo) as? Outcome.Ok)?.value
                ?: return RestoreResult.Missing
        if (answer.drafts.isEmpty()) return RestoreResult.Missing
        val ids = ArrayList<Long>(answer.drafts.size)
        val failed = ArrayList<ContactDraft>()
        for (draft in answer.drafts) {
            when (val saved = repo.save(draft)) {
                is ContactResult.Ok -> ids += saved.value
                is ContactResult.Failed -> failed += draft
            }
        }
        if (failed.isNotEmpty()) store.trashPush(failed)
        return if (ids.isEmpty()) RestoreResult.Refused else RestoreResult.Done(ids, answer.notice)
    }

    /** Deletes [keys] for good. */
    suspend fun deleteForever(keys: List<Long>): Int = (store.trashDelete(keys) as? Outcome.Ok)?.value?.removed ?: 0

    /** Empties the Trash. */
    suspend fun empty(): Int = (store.trashEmpty() as? Outcome.Ok)?.value?.removed ?: 0
}
