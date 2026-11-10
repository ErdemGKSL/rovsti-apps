package dev.rovsti.contacts.groups

import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.contacts.R
import dev.rovsti.contacts.data.Book
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.GroupLabelsRequest
import dev.rovsti.core.model.contacts.GroupTitleRequest
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.design.text.UiText
import dev.rovsti.platform.contacts.ContactResult
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.contacts.saveAccount
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
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

/**
 * The Groups tab (E4.7). The groups come from [ContactsRepository.groups]; the subtitle of a row ("3 contacts", with the
 * account when the phone has several) is `contacts.groups_labels` over the members that are in the address book, a new
 * name is checked by `contacts.groups_title` (empty, too long, taken) before the group is saved, in the account new
 * contacts go to ([saveAccount]: the Save To preference, else the account with the most contacts, else the phone).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupsViewModel(
    private val book: BookRepository,
    private val contacts: ContactsRepository,
    private val ops: ContactsOps,
    private val store: ContactsStore,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val outbox = Channel<GroupsEffect>(Channel.UNLIMITED)

    val state: StateFlow<GroupsUiState> =
        combine(book.book, contacts.groups()) { b, groups -> b to groups }
            .conflate()
            .mapLatest { (b, groups) -> build(b, groups) }
            .flowOn(worker)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GroupsUiState())

    val effects: Flow<GroupsEffect> = outbox.receiveAsFlow()

    fun onAction(action: GroupsAction) {
        when (action) {
            is GroupsAction.Open -> outbox.trySend(GroupsEffect.OpenGroup(action.id))
            is GroupsAction.Create -> viewModelScope.launch { create(action.name) }
        }
    }

    /** "+" was tapped: the screen shows the name alert. */
    fun askNewName() {
        outbox.trySend(GroupsEffect.AskName(initial = "", error = null))
    }

    private suspend fun build(
        b: Book,
        groups: List<dev.rovsti.core.model.contacts.Group>,
    ): GroupsUiState {
        val ready =
            when (b) {
                Book.Loading -> return GroupsUiState(GroupsPhase.Loading)
                Book.NoPermission -> return GroupsUiState(GroupsPhase.NoPermission)
                is Book.Ready -> b
            }
        val known = ready.contacts.mapTo(HashSet()) { it.id }
        val several = (contacts.accounts() as? ContactResult.Ok)?.value.orEmpty().size > 1
        val rows =
            groups.map { group ->
                val count = group.members.distinct().count { it in known }
                val labels =
                    (
                        ops.groupsLabels(
                            GroupLabelsRequest(count, group.account.takeIf { several }),
                        ) as? Outcome.Ok
                    )?.value
                GroupItem(
                    group.id,
                    group.title,
                    labels?.subtitle?.let { UiText.Literal(it) } ?: UiText.Plural(R.plurals.contacts_n_contacts, count),
                )
            }
        return GroupsUiState(GroupsPhase.Ready, rows.toImmutableList())
    }

    private suspend fun create(name: String) {
        val groups = contacts.groups().first()
        val checked = (ops.groupsTitle(GroupTitleRequest(name, groups)) as? Outcome.Ok)?.value ?: return
        val title = checked.title
        if (title == null) {
            outbox.trySend(GroupsEffect.AskName(name, checked.error))
            return
        }
        store.load()
        val account = contacts.saveAccount(store.prefs.value.saveTo)
        when (val saved = contacts.saveGroup(0, title, account)) {
            is ContactResult.Ok -> {
                outbox.trySend(GroupsEffect.Toast(UiText.Res(R.string.contacts_group_created)))
                outbox.trySend(GroupsEffect.OpenGroup(saved.value))
            }

            is ContactResult.Failed -> {
                outbox.trySend(GroupsEffect.Toast(groupFailure(saved.reason, GROUP_CANT_SAVE)))
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

internal val GROUP_CANT_SAVE: UiText = UiText.Res(R.string.contacts_cant_save_group)
internal val GROUP_CANT_CHANGE: UiText = UiText.Res(R.string.contacts_cant_change_group)
internal val GROUP_NO_PERMISSION: UiText = UiText.Res(R.string.contacts_cant_change_groups_permission)

/** The notice of a failed group write: the missing WRITE_CONTACTS by name, any other reason as [otherwise]. */
internal fun groupFailure(
    reason: ContactResult.Reason,
    otherwise: UiText,
): UiText = if (reason == ContactResult.Reason.Permission) GROUP_NO_PERMISSION else otherwise
