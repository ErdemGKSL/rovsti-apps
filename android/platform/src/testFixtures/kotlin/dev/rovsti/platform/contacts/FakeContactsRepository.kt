package dev.rovsti.platform.contacts

import dev.rovsti.core.model.contacts.Account
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.ContactOptions
import dev.rovsti.core.model.contacts.Group
import dev.rovsti.core.model.contacts.GroupMembersResult
import dev.rovsti.core.model.contacts.ImportResult
import dev.rovsti.core.model.contacts.PhotoPlan
import dev.rovsti.core.model.contacts.PhotoSize
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.model.search.WireNumber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * An in-memory [ContactsRepository] for screen tests: the drafts, groups and options live in state flows, so a write
 * re-emits the reads like the provider does. Set [failWith] to make every write answer that failure.
 */
class FakeContactsRepository(
    drafts: List<ContactDraft> = emptyList(),
    groups: List<Group> = emptyList(),
    accounts: List<Account> = listOf(Account()),
) : ContactsRepository {
    private val contacts = MutableStateFlow(drafts)
    private val labels = MutableStateFlow(groups)
    private val accountList = MutableStateFlow(accounts)
    private val optionsById = MutableStateFlow<Map<Long, ContactOptions>>(emptyMap())
    private var nextId = (drafts.mapNotNull { it.id }.maxOrNull() ?: 0L) + 1
    private var nextGroupId = (groups.maxOfOrNull { it.id } ?: 0L) + 1

    /** When set, every write answers this failure and changes nothing. */
    var failWith: ContactResult.Reason? = null

    /** Photos set so far: contact id to the plan used. */
    val photos = LinkedHashMap<Long, PhotoPlan>()

    /** The path [photoSize] answers for; anything else is 0 x 0. */
    var photoSizes: Map<String, PhotoSize> = emptyMap()

    private fun toWire(d: ContactDraft) =
        WireContact(
            id = d.id ?: 0,
            displayName = listOf(d.given, d.family).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { d.org },
            numbers = d.numbers.map { WireNumber(raw = it.value, label = it.label, default = it.primary) },
            starred = d.starred,
            email = d.emails.firstOrNull()?.value,
        )

    override fun all(): Flow<List<WireContact>> = contacts.map { list -> list.map(::toWire) }

    override fun withNumbers(): Flow<List<WireContact>> = all().map { list -> list.filter { it.numbers.isNotEmpty() } }

    override fun favorites(): Flow<List<WireContact>> = withNumbers().map { list -> list.filter { it.starred } }

    override fun full(): Flow<List<ContactDraft>> = contacts

    override fun detail(id: Long): Flow<ContactDraft?> = contacts.map { list -> list.firstOrNull { it.id == id } }

    private inline fun <T> write(block: () -> ContactResult<T>): ContactResult<T> =
        failWith?.let { ContactResult.failed(it) } ?: block()

    override suspend fun save(draft: ContactDraft): ContactResult<Long> =
        write {
            val id = draft.id?.takeIf { it > 0 }
            if (id != null &&
                contacts.value.none { it.id == id }
            ) {
                return@write ContactResult.failed(ContactResult.Reason.NotFound)
            }
            val saved = draft.copy(id = id ?: nextId++)
            contacts.update { list -> if (id == null) list + saved else list.map { if (it.id == id) saved else it } }
            ContactResult.Ok(saved.id!!)
        }

    override suspend fun delete(id: Long): ContactResult<Unit> =
        write {
            if (contacts.value.none { it.id == id }) return@write ContactResult.failed(ContactResult.Reason.NotFound)
            contacts.update { list -> list.filterNot { it.id == id } }
            ContactResult.Done
        }

    override suspend fun setStarred(
        id: Long,
        on: Boolean,
    ): ContactResult<Unit> =
        write {
            if (contacts.value.none { it.id == id }) return@write ContactResult.failed(ContactResult.Reason.NotFound)
            contacts.update { list -> list.map { if (it.id == id) it.copy(starred = on) else it } }
            ContactResult.Done
        }

    override fun groups(): Flow<List<Group>> = labels

    override suspend fun saveGroup(
        id: Long,
        title: String,
        account: String,
    ): ContactResult<Long> =
        write {
            if (id > 0) {
                if (labels.value.none { it.id == id }) return@write ContactResult.failed(ContactResult.Reason.NotFound)
                labels.update { list -> list.map { if (it.id == id) it.copy(title = title) else it } }
                ContactResult.Ok(id)
            } else {
                val made = Group(id = nextGroupId++, title = title, account = account)
                labels.update { it + made }
                ContactResult.Ok(made.id)
            }
        }

    override suspend fun deleteGroup(id: Long): ContactResult<Unit> =
        write {
            if (labels.value.none { it.id == id }) return@write ContactResult.failed(ContactResult.Reason.NotFound)
            labels.update { list -> list.filterNot { it.id == id } }
            ContactResult.Done
        }

    override suspend fun setMembers(
        groupId: Long,
        add: List<Long>,
        remove: List<Long>,
    ): ContactResult<GroupMembersResult> =
        write {
            val group =
                labels.value.firstOrNull { it.id == groupId }
                    ?: return@write ContactResult.failed(ContactResult.Reason.NotFound)
            val known = contacts.value.mapNotNull { it.id }.toSet()
            val fresh = add.filter { it in known && it !in group.members }.distinct()
            val gone = remove.filter { it in group.members }
            val skipped = add.count { it !in known }
            labels.update { list ->
                list.map { if (it.id == groupId) it.copy(members = (it.members - gone.toSet()) + fresh) else it }
            }
            ContactResult.Ok(GroupMembersResult(added = fresh.size, removed = remove.size, skipped = skipped))
        }

    override suspend fun accounts(): ContactResult<List<Account>> = write { ContactResult.Ok(accountList.value) }

    override suspend fun photoSize(path: String): PhotoSize = photoSizes[path] ?: PhotoSize(0, 0)

    override suspend fun setPhoto(
        id: Long,
        path: String,
        plan: PhotoPlan,
    ): ContactResult<Unit> =
        write {
            if (contacts.value.none { it.id == id }) return@write ContactResult.failed(ContactResult.Reason.NotFound)
            photos[id] = plan
            ContactResult.Done
        }

    override suspend fun removePhoto(id: Long): ContactResult<Unit> =
        write {
            photos.remove(id)
            ContactResult.Done
        }

    override suspend fun options(id: Long): ContactResult<ContactOptions> =
        write {
            if (contacts.value.none { it.id == id }) return@write ContactResult.failed(ContactResult.Reason.NotFound)
            ContactResult.Ok(
                optionsById.value[id] ?: ContactOptions(ringtoneUri = null, ringtoneTitle = null, toVoicemail = false),
            )
        }

    /** Sets the call options a contact starts with (a ringtone with a real title, voicemail on). */
    fun seedOptions(
        id: Long,
        options: ContactOptions,
    ) {
        optionsById.update { it + (id to options) }
    }

    private fun change(
        id: Long,
        edit: (ContactOptions) -> ContactOptions,
    ): ContactResult<Unit> =
        write {
            if (contacts.value.none { it.id == id }) return@write ContactResult.failed(ContactResult.Reason.NotFound)
            val now =
                optionsById.value[id] ?: ContactOptions(ringtoneUri = null, ringtoneTitle = null, toVoicemail = false)
            optionsById.update { it + (id to edit(now)) }
            ContactResult.Done
        }

    override suspend fun setRingtone(
        id: Long,
        uri: String?,
    ): ContactResult<Unit> =
        change(id) {
            it.copy(
                ringtoneUri = uri,
                ringtoneTitle =
                    uri?.takeIf { u ->
                        u.isNotEmpty()
                    },
            )
        }

    override suspend fun setVoicemail(
        id: Long,
        on: Boolean,
    ): ContactResult<Unit> = change(id) { it.copy(toVoicemail = on) }

    override suspend fun import(drafts: List<ContactDraft>): ContactResult<ImportResult> =
        write {
            val saved = drafts.map { it.copy(id = nextId++) }
            contacts.update { it + saved }
            ContactResult.Ok(ImportResult(saved.size, saved.map { it.id!! }))
        }
}
