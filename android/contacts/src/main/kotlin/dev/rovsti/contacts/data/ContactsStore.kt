package dev.rovsti.contacts.data

import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.Account
import dev.rovsti.core.model.contacts.Confirm
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.ContactsPrefs
import dev.rovsti.core.model.contacts.DirRequest
import dev.rovsti.core.model.contacts.Group
import dev.rovsti.core.model.contacts.PrefsSaveRequest
import dev.rovsti.core.model.contacts.TrashConfirmRequest
import dev.rovsti.core.model.contacts.TrashDeleteAnswer
import dev.rovsti.core.model.contacts.TrashDeleteRequest
import dev.rovsti.core.model.contacts.TrashListAnswer
import dev.rovsti.core.model.contacts.TrashListRequest
import dev.rovsti.core.model.contacts.TrashPushAnswer
import dev.rovsti.core.model.contacts.TrashPushRequest
import dev.rovsti.core.model.contacts.TrashRestoreAnswer
import dev.rovsti.core.model.contacts.TrashRestoreRequest
import dev.rovsti.core.model.phone.SortBy
import dev.rovsti.core.model.search.NameFormat
import dev.rovsti.core.ops.ContactsOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Contacts preferences (`contacts-prefs.json`) and the Trash (`contacts-trash.json`), files in the data directory
 * the Rust core owns (`contacts.prefs_*`, `contacts.trash_*`). The preferences are held as state flows; [update]
 * reads the stored file the first time, applies the change and writes it back, one at a time under a [Mutex], so two
 * screens editing at once never lose a write. A failed write leaves the flows as they were.
 *
 * [dir] names another data directory (tests); null is the one `core.init` was given. [clock] is the wall clock in
 * ms (the Trash expires entries by it).
 */
class ContactsStore(
    private val ops: ContactsOps,
    private val dir: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var loaded = false
    private val state = MutableStateFlow(DEFAULT_PREFS)
    private val order = MutableStateFlow<List<String>>(emptyList())
    private val sos = MutableStateFlow<List<String>>(emptyList())

    /** The preferences; [DEFAULT_PREFS] until the first [load] or [update]. */
    val prefs: StateFlow<ContactsPrefs> = state.asStateFlow()

    /** Lookup keys of the favorites in the user's order, without blanks or repeats. */
    val favoritesOrder: StateFlow<List<String>> = order.asStateFlow()

    /** Lookup keys of the emergency contacts. */
    val emergency: StateFlow<List<String>> = sos.asStateFlow()

    /** Lookup keys of the contacts opened last, newest first. */
    val viewed: List<String> get() = state.value.viewed

    /** Lookup key of the user's own card. */
    val myCard: String? get() = state.value.myCard

    /** Reads the stored preferences (once); later calls answer the held value. */
    suspend fun load(): Outcome<ContactsPrefs> = mutex.withLock { ensureLoaded() }

    /** Applies [change] to the stored preferences and writes the result; answers what is held afterwards. */
    suspend fun update(change: (ContactsPrefs) -> ContactsPrefs): Outcome<ContactsPrefs> =
        mutex.withLock {
            val current =
                when (val read = ensureLoaded()) {
                    is Outcome.Err -> return@withLock read
                    is Outcome.Ok -> read.value
                }
            val next = change(current)
            if (next == current) return@withLock Outcome.Ok(current)
            when (val saved = ops.prefsSave(PrefsSaveRequest(dir, next))) {
                is Outcome.Err -> saved
                is Outcome.Ok -> publish(next)
            }
        }

    /** Reads the stored file once. The caller holds the lock. */
    private suspend fun ensureLoaded(): Outcome<ContactsPrefs> {
        if (loaded) return Outcome.Ok(state.value)
        return when (val read = ops.prefsLoad(DirRequest(dir))) {
            is Outcome.Err -> {
                read
            }

            is Outcome.Ok -> {
                loaded = true
                order.value = read.value.favoritesOrder
                sos.value = read.value.emergency
                state.value = read.value.prefs
                Outcome.Ok(read.value.prefs)
            }
        }
    }

    /** Takes the saved value as the held one; the derived lists come from the core, which also removes blanks. */
    private suspend fun publish(saved: ContactsPrefs): Outcome<ContactsPrefs> {
        state.value = saved
        when (val read = ops.prefsLoad(DirRequest(dir))) {
            is Outcome.Err -> {
                Unit
            }

            is Outcome.Ok -> {
                order.value = read.value.favoritesOrder
                sos.value = read.value.emergency
            }
        }
        return Outcome.Ok(saved)
    }

    // ---- the Trash

    /** The Trash, newest first, expired entries dropped. */
    suspend fun trash(): Outcome<TrashListAnswer> = ops.trashList(TrashListRequest(dir, clock()))

    /** Moves deleted contacts to the Trash. */
    suspend fun trashPush(drafts: List<ContactDraft>): Outcome<TrashPushAnswer> =
        ops.trashPush(TrashPushRequest(dir, drafts, clock()))

    /** Takes [keys] out of the Trash as new drafts, ready to insert into [accounts] (or [fallbackAccount]). */
    suspend fun trashRestore(
        keys: List<Long>,
        groups: List<Group>,
        accounts: List<Account>,
        fallbackAccount: String,
    ): Outcome<TrashRestoreAnswer> =
        ops.trashRestore(TrashRestoreRequest(dir, keys, groups, accounts, fallbackAccount, clock()))

    /** Deletes [keys] for good. */
    suspend fun trashDelete(keys: List<Long>): Outcome<TrashDeleteAnswer> =
        ops.trashDelete(TrashDeleteRequest(dir = dir, keys = keys))

    /** Empties the Trash. */
    suspend fun trashEmpty(): Outcome<TrashDeleteAnswer> = ops.trashDelete(TrashDeleteRequest(dir = dir, all = true))

    /** The dialog for deleting [count] Trash entries for good ([forever]) or for emptying the Trash. */
    suspend fun trashConfirm(
        count: Int,
        forever: Boolean,
    ): Outcome<Confirm> = ops.trashConfirm(TrashConfirmRequest(if (forever) "forever" else "empty", count))

    companion object {
        /** What `ContactsPrefs::default()` holds (`rovsti_core::contacts_prefs`), for the first frame before the load. */
        val DEFAULT_PREFS =
            ContactsPrefs(
                schemaVersion = 1,
                nameFormat = NameFormat.FirstLast,
                sortBy = SortBy.FirstName,
                saveTo = "",
                birthdayReminders = false,
                reminderHour = 9,
                favoritesOrder = emptyList(),
                myCard = null,
                emergency = emptyList(),
                knownMaxId = null,
                added = emptyList(),
                viewed = emptyList(),
            )
    }
}
