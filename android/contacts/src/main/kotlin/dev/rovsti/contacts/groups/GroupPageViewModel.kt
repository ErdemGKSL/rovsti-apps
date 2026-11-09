package dev.rovsti.contacts.groups

import dev.rovsti.contacts.data.Book
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.contacts.people.PersonItem
import dev.rovsti.contacts.people.toPerson
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.ContactsPrefs
import dev.rovsti.core.model.contacts.Day
import dev.rovsti.core.model.contacts.Group
import dev.rovsti.core.model.contacts.GroupChangeRequest
import dev.rovsti.core.model.contacts.GroupLabelsRequest
import dev.rovsti.core.model.contacts.GroupSummaryRequest
import dev.rovsti.core.model.contacts.GroupTitleRequest
import dev.rovsti.core.model.contacts.LocalYmdRequest
import dev.rovsti.core.model.contacts.RecipientsAnswer
import dev.rovsti.core.model.contacts.RecipientsRequest
import dev.rovsti.core.model.contacts.VcardWriteRequest
import dev.rovsti.core.model.contacts.ViewRequest
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.platform.contacts.ContactResult
import dev.rovsti.platform.contacts.ContactsRepository
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * One group's page (E4.7, C25). Members are the group's people that are in the address book, in name order
 * (`contacts.view`). Add Members hands the ticked set to `contacts.groups_change` (what to add and remove) and the
 * provider; the sentence after a change is `contacts.groups_summary`. Message Group and Email Group read the numbers or
 * addresses through `contacts.groups_recipients` (capped, with the notice about who was left out); Export Group writes
 * the members with `contacts.vcard_write` under the file name of the same op. A group that vanishes closes the page.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupPageViewModel(
    private val groupId: Long,
    private val book: BookRepository,
    private val contacts: ContactsRepository,
    private val ops: ContactsOps,
    private val store: ContactsStore,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = scope
    private val outbox = Channel<GroupPageEffect>(Channel.UNLIMITED)

    val state: StateFlow<GroupPageState> =
        combine(book.book, contacts.groups(), store.prefs) { b, groups, prefs -> Triple(b, groups, prefs) }
            .conflate()
            .mapLatest { (b, groups, prefs) -> build(b, groups, prefs) }
            .flowOn(worker)
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GroupPageState.Loading)

    val effects: Flow<GroupPageEffect> = outbox.receiveAsFlow()

    fun onAction(action: GroupPageAction) {
        when (action) {
            is GroupPageAction.OpenMember -> outbox.trySend(GroupPageEffect.OpenCard(action.id))
            is GroupPageAction.Rename -> scope.launch { rename(action.name) }
            is GroupPageAction.SetMembers -> scope.launch { setMembers(action.chosen) }
            is GroupPageAction.RemoveMember -> scope.launch { change(add = emptyList(), remove = listOf(action.id)) }
            GroupPageAction.MessageGroup -> scope.launch { compose("sms") }
            GroupPageAction.EmailGroup -> scope.launch { compose("email") }
            GroupPageAction.ExportGroup -> scope.launch { export() }
            GroupPageAction.ConfirmDelete -> scope.launch { delete() }
        }
    }

    /** The menu's Rename: the screen shows the name alert with the current title. */
    fun askRename() {
        val ready = state.value as? GroupPageState.Ready ?: return
        outbox.trySend(GroupPageEffect.AskName(ready.page.title, null))
    }

    // ---- building the page

    private suspend fun build(
        b: Book,
        groups: List<Group>,
        prefs: ContactsPrefs,
    ): GroupPageState {
        val group = groups.firstOrNull { it.id == groupId }
        val ready =
            when (b) {
                Book.Loading -> return GroupPageState.Loading
                Book.NoPermission -> return GroupPageState.Gone
                is Book.Ready -> b
            }
        if (group == null) return GroupPageState.Gone
        val ids = group.members.toSet()
        val inGroup = ready.contacts.filter { it.id in ids }
        val several = (contacts.accounts() as? ContactResult.Ok)?.value.orEmpty().size > 1
        val labels =
            (ops.groupsLabels(GroupLabelsRequest(inGroup.size, group.account.takeIf { several })) as? Outcome.Ok)?.value
        val drafts = ready.drafts.filter { it.id in ids }
        return GroupPageState.Ready(
            GroupPageUi(
                id = group.id,
                title = group.title,
                subtitle = labels?.subtitle ?: "${inGroup.size} contacts",
                joinHint = if (several) labels?.joinHint else null,
                members = rows(inGroup, prefs),
                canMessage = drafts.any { it.numbers.isNotEmpty() },
                canEmail = drafts.any { it.emails.isNotEmpty() },
                everyone = rows(ready.contacts, prefs),
            ),
        )
    }

    private suspend fun rows(
        people: List<WireContact>,
        prefs: ContactsPrefs,
    ) = people
        .takeIf { it.isNotEmpty() }
        ?.let { list ->
            val request =
                ViewRequest(contacts = list, nameFormat = prefs.nameFormat, sortBy = prefs.sortBy, widthDp = WIDTH_DP)
            (ops.view(request) as? Outcome.Ok)
                ?.value
                ?.rows
                .orEmpty()
                .map { it.toPerson() }
        }.orEmpty()
        .toImmutableList<PersonItem>()

    // ---- actions

    private suspend fun group(): Group? = contacts.groups().first().firstOrNull { it.id == groupId }

    private suspend fun rename(name: String) {
        val group = group() ?: return
        val groups = contacts.groups().first()
        val checked =
            (ops.groupsTitle(GroupTitleRequest(name, groups, editing = groupId)) as? Outcome.Ok)?.value ?: return
        val title = checked.title
        if (title == null) {
            outbox.trySend(GroupPageEffect.AskName(name, checked.error))
            return
        }
        if (title == group.title) return
        val said =
            if (contacts.saveGroup(groupId, title, group.account) is ContactResult.Ok) {
                "Group renamed"
            } else {
                "Can't save the group right now."
            }
        outbox.trySend(GroupPageEffect.Toast(said))
    }

    private suspend fun setMembers(chosen: Set<Long>) {
        val group = group() ?: return
        val answer =
            (ops.groupsChange(GroupChangeRequest(group.members, chosen.toList())) as? Outcome.Ok)?.value ?: return
        if (answer.empty) return
        change(answer.add, answer.remove)
    }

    private suspend fun change(
        add: List<Long>,
        remove: List<Long>,
    ) {
        val group = group() ?: return
        when (val result = contacts.setMembers(groupId, add, remove)) {
            is ContactResult.Ok -> {
                val r = result.value
                val notice = ops.groupsSummary(GroupSummaryRequest(group.title, r.added, r.removed, r.skipped))
                (notice as? Outcome.Ok)?.value?.text?.takeIf { it.isNotBlank() }?.let {
                    outbox.trySend(GroupPageEffect.Toast(it))
                }
            }

            is ContactResult.Failed -> {
                outbox.trySend(GroupPageEffect.Toast("Can't change this group right now."))
            }
        }
    }

    private suspend fun members(): List<ContactDraft> {
        val ids = group()?.members?.toSet().orEmpty()
        return (book.book.value as? Book.Ready)?.drafts.orEmpty().filter { it.id in ids }
    }

    private suspend fun today(): Day? {
        val now = clock()
        return (
            ops.localYmd(
                LocalYmdRequest(now, TimeZone.getDefault().getOffset(now) / MS_PER_S),
            ) as? Outcome.Ok
        )?.value
    }

    private suspend fun recipients(
        kind: String,
        drafts: List<ContactDraft>,
    ): RecipientsAnswer? {
        val title = group()?.title.orEmpty()
        val day = today() ?: return null
        return (ops.groupsRecipients(RecipientsRequest(drafts, kind, title, day)) as? Outcome.Ok)?.value
    }

    private suspend fun compose(kind: String) {
        val answer = recipients(kind, members()) ?: return
        if (answer.addresses.isEmpty()) {
            val what = if (kind == "sms") "a number" else "an email"
            outbox.trySend(GroupPageEffect.Toast(answer.notice ?: "No one in this group has $what."))
            return
        }
        outbox.trySend(
            if (kind ==
                "sms"
            ) {
                GroupPageEffect.Sms(answer.address)
            } else {
                GroupPageEffect.Email(answer.address)
            },
        )
        answer.notice?.takeIf { it.isNotBlank() }?.let { outbox.trySend(GroupPageEffect.Toast(it)) }
    }

    private suspend fun export() {
        val drafts = members()
        if (drafts.isEmpty()) {
            outbox.trySend(GroupPageEffect.Toast("Nothing to share."))
            return
        }
        val day = today() ?: return
        val name = recipients("sms", drafts)?.fileName ?: return
        val file = (ops.vcardWrite(VcardWriteRequest(drafts, day)) as? Outcome.Ok)?.value ?: return
        outbox.trySend(GroupPageEffect.SaveVcf(name, file.text, file.count))
    }

    private suspend fun delete() {
        val said =
            if (contacts.deleteGroup(groupId) is ContactResult.Ok) {
                "Group deleted"
            } else {
                "Can't delete the group right now."
            }
        outbox.trySend(GroupPageEffect.Toast(said))
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val WIDTH_DP = 411.0
        const val MS_PER_S = 1_000
    }
}
