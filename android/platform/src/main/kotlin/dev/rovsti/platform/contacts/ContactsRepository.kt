package dev.rovsti.platform.contacts

import android.content.Context
import android.provider.ContactsContract
import dev.rovsti.core.model.contacts.Account
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.ContactOptions
import dev.rovsti.core.model.contacts.Group
import dev.rovsti.core.model.contacts.GroupMembersResult
import dev.rovsti.core.model.contacts.ImportResult
import dev.rovsti.core.model.contacts.PhotoPlan
import dev.rovsti.core.model.contacts.PhotoSize
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.platform.ProviderChanges
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * The contacts the screens read and write. Reads are cold [Flow]s that emit once on collect and again after every
 * change of the contacts provider; writes are suspend and answer a [ContactResult]. A contact is a [ContactDraft]
 * (`full`, `detail`, `save`); a list row is a [WireContact].
 */
interface ContactsRepository {
    /** Every visible contact, with or without a number. */
    fun all(): Flow<List<WireContact>>

    /** Contacts with a number. */
    fun withNumbers(): Flow<List<WireContact>>

    /** Starred contacts with a number. */
    fun favorites(): Flow<List<WireContact>>

    /** Every contact as a draft: the export. */
    fun full(): Flow<List<ContactDraft>>

    /** One contact, null when it is gone. */
    fun detail(id: Long): Flow<ContactDraft?>

    /** Creates (draft without id) or updates a contact; the answer is its id. */
    suspend fun save(draft: ContactDraft): ContactResult<Long>

    suspend fun delete(id: Long): ContactResult<Unit>

    suspend fun setStarred(
        id: Long,
        on: Boolean,
    ): ContactResult<Unit>

    fun groups(): Flow<List<Group>>

    /** Creates a group in [account] (`type|name`, empty: the phone) when [id] is 0, else renames it; answers its id. */
    suspend fun saveGroup(
        id: Long,
        title: String,
        account: String,
    ): ContactResult<Long>

    suspend fun deleteGroup(id: Long): ContactResult<Unit>

    suspend fun setMembers(
        groupId: Long,
        add: List<Long>,
        remove: List<Long>,
    ): ContactResult<GroupMembersResult>

    suspend fun accounts(): ContactResult<List<Account>>

    /** The pixel size of the picture at [path]; 0 x 0 when unreadable. */
    suspend fun photoSize(path: String): PhotoSize

    /** Crops the picture at [path] by [plan] and makes it the contact's photo; deletes the file. */
    suspend fun setPhoto(
        id: Long,
        path: String,
        plan: PhotoPlan,
    ): ContactResult<Unit>

    suspend fun removePhoto(id: Long): ContactResult<Unit>

    suspend fun options(id: Long): ContactResult<ContactOptions>

    /** [uri] null: the default ringtone; "": silent. */
    suspend fun setRingtone(
        id: Long,
        uri: String?,
    ): ContactResult<Unit>

    suspend fun setVoicemail(
        id: Long,
        on: Boolean,
    ): ContactResult<Unit>

    /** Adds phone-only contacts; the ids are aligned with [drafts]. */
    suspend fun import(drafts: List<ContactDraft>): ContactResult<ImportResult>
}

/**
 * [ContactsRepository] on the contacts provider. Reads re-query on a `ContactsContract` change: the change flow is
 * conflated and debounced by [CHANGE_DEBOUNCE_MS] (a burst of notifications, which one provider write sends several
 * of, is one re-query), a newer change cancels a query still running (`mapLatest`), and the query runs on [io]. A read the app may not make (no `READ_CONTACTS`) is an empty list.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class AndroidContactsRepository(
    private val context: Context,
    private val io: CoroutineDispatcher,
) : ContactsRepository {
    companion object {
        /** Quiet time after the last change notification before the reads query again. */
        const val CHANGE_DEBOUNCE_MS = 50L
    }

    private fun <T> live(
        empty: T,
        query: () -> T,
    ): Flow<T> =
        ProviderChanges
            .observe(context.contentResolver, ContactsContract.AUTHORITY_URI)
            .debounce(CHANGE_DEBOUNCE_MS)
            .onStart { emit(Unit) }
            .conflate()
            .mapLatest {
                try {
                    query()
                } catch (_: SecurityException) {
                    empty
                }
            }.flowOn(io)

    private suspend fun <T> write(block: () -> ContactResult<T>): ContactResult<T> = withContext(io) { guarded(block) }

    override fun all() = live(emptyList()) { ContactsQuery.all(context) }

    override fun withNumbers() = live(emptyList()) { ContactsQuery.withNumbers(context) }

    override fun favorites() = live(emptyList()) { ContactsQuery.favorites(context) }

    override fun full() = live(emptyList()) { ContactsEdit.full(context) }

    override fun detail(id: Long) = live<ContactDraft?>(null) { ContactsEdit.detail(context, id) }

    override fun groups() = live(emptyList()) { ContactsEdit.groups(context) }

    override suspend fun save(draft: ContactDraft) = write { ContactsEdit.save(context, draft) }

    override suspend fun delete(id: Long) = write { ContactsEdit.delete(context, id) }

    override suspend fun setStarred(
        id: Long,
        on: Boolean,
    ) = write {
        if (ContactsQuery.setStarred(
                context,
                id,
                on,
            )
        ) {
            ContactResult.Done
        } else {
            ContactResult.failed(ContactResult.Reason.Refused)
        }
    }

    override suspend fun saveGroup(
        id: Long,
        title: String,
        account: String,
    ) = write { ContactsEdit.saveGroup(context, id, title, account) }

    override suspend fun deleteGroup(id: Long) = write { ContactsEdit.deleteGroup(context, id) }

    override suspend fun setMembers(
        groupId: Long,
        add: List<Long>,
        remove: List<Long>,
    ) = write { ContactsEdit.setGroupMembers(context, groupId, add, remove) }

    override suspend fun accounts() = write { ContactResult.Ok(ContactsEdit.accounts(context)) }

    override suspend fun photoSize(path: String): PhotoSize = withContext(io) { ContactPhotos.size(path) }

    override suspend fun setPhoto(
        id: Long,
        path: String,
        plan: PhotoPlan,
    ) = withContext(io) { ContactPhotos.set(context, id, path, plan) }

    override suspend fun removePhoto(id: Long) = withContext(io) { ContactPhotos.remove(context, id) }

    override suspend fun options(id: Long) =
        write {
            ContactCallOptions.read(context, id)?.let { ContactResult.Ok(it) }
                ?: ContactResult.failed(ContactResult.Reason.NotFound)
        }

    override suspend fun setRingtone(
        id: Long,
        uri: String?,
    ) = withContext(io) { ContactCallOptions.setRingtone(context, id, uri) }

    override suspend fun setVoicemail(
        id: Long,
        on: Boolean,
    ) = withContext(io) { ContactCallOptions.setVoicemail(context, id, on) }

    override suspend fun import(drafts: List<ContactDraft>) = write { ContactsEdit.import(context, drafts) }
}
