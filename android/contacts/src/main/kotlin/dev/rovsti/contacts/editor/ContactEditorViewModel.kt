package dev.rovsti.contacts.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.SavedStateHandle
import dev.rovsti.contacts.DraftSeed
import dev.rovsti.contacts.R
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.Account
import dev.rovsti.core.model.contacts.AccountLabelRequest
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.DateParseRequest
import dev.rovsti.core.model.contacts.DiffRequest
import dev.rovsti.core.model.contacts.DraftEdit
import dev.rovsti.core.model.contacts.DraftEditRequest
import dev.rovsti.core.model.contacts.DraftEvent
import dev.rovsti.core.model.contacts.DraftField
import dev.rovsti.core.model.contacts.DraftIssue
import dev.rovsti.core.model.contacts.DraftRelation
import dev.rovsti.core.model.contacts.DraftRequest
import dev.rovsti.core.model.contacts.EventKind
import dev.rovsti.core.model.contacts.FieldKind
import dev.rovsti.core.model.contacts.FieldList
import dev.rovsti.core.model.contacts.Group
import dev.rovsti.core.model.contacts.RecentCreatedRequest
import dev.rovsti.core.model.contacts.RelationKind
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.design.text.UiText
import dev.rovsti.platform.contacts.ContactResult
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.contacts.SaveAccount
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The contact editor (E4.4 AC1, AC3, AC4). [id] null makes a new contact, optionally from a [seed].
 *
 * The values live in a [ContactDraft] that is also kept in [saved] as JSON, so a process death while the form is open
 * brings the half-typed form back. Every decision is the Rust core's: `contacts.draft_validate` names the first problem
 * as a sentence (shown under its field once the field was left, or under all of them after a Save), `contacts.draft_diff`
 * says whether anything changed (the Save button and the discard question) and carries the draft that undoes a save,
 * `contacts.draft_edit` moves the default of a list and drops the groups of another account, `contacts.date_parse`
 * reads a typed date. A save goes to `prefs.save_to` (or the account the owner chose; AD-CT3) and may have no number
 * (AD-CT2). A new contact's account becomes the next default and the contact is noted as created (`recent_note_created`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactEditorViewModel(
    private val id: Long?,
    private val seed: DraftSeed?,
    private val saved: SavedStateHandle,
    private val repo: ContactsRepository,
    private val ops: ContactsOps,
    private val store: ContactsStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) {
    private data class Edit(
        val phase: EditorPhase = EditorPhase.Loading,
        val initial: ContactDraft = ContactDraft(),
        val draft: ContactDraft = ContactDraft(),
        val keys: EditorKeys = EditorKeys(),
        val external: Int = 0,
        val attempted: Boolean = false,
        val left: Set<String> = emptySet(),
        val saving: Boolean = false,
    )

    private class Derived(
        val issue: DraftIssue? = null,
        val issueRow: String? = null,
        val dirty: Boolean = false,
        val hints: Map<String, String> = emptyMap(),
    )

    private val outbox = Channel<EditorEffect>(Channel.UNLIMITED)

    /** One-shot results. */
    val effects: Flow<EditorEffect> = outbox.receiveAsFlow()

    private val edit = MutableStateFlow(Edit())
    private val derived = MutableStateFlow(Derived())
    private val accounts = MutableStateFlow<List<AccountChoice>>(emptyList())
    private var counter = 0L
    private var lastUndo: ContactDraft? = null

    private val groups: StateFlow<List<Group>> =
        repo
            .groups()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The page. */
    val state: StateFlow<EditorUiState> =
        combine(edit, derived, accounts, groups) { e, d, accountList, allGroups ->
            val shown = LinkedHashMap<String, String>()
            d.issue?.let { issue ->
                val key = d.issueRow ?: EditorIssueKey.NAME
                if (e.attempted || key in e.left) shown[key] = issue.message
            }
            EditorUiState(
                phase = e.phase,
                isNew = id == null,
                draft = e.draft,
                keys = e.keys,
                external = e.external,
                dirty = d.dirty,
                saving = e.saving,
                issues = shown,
                hints = d.hints,
                accounts = accountList,
                accountLabel = accountList.firstOrNull { it.key == e.draft.account }?.label.orEmpty(),
                groups = allGroups.filter { it.account == e.draft.account },
            )
        }.stateIn(scope, SharingStarted.Eagerly, EditorUiState())

    init {
        scope.launch { load() }
        scope.launch {
            edit
                .map { it.draft to it.initial }
                .distinctUntilChanged()
                .mapLatest { (draft, initial) -> derive(draft, initial) }
                .flowOn(worker)
                .collect { derived.value = it }
        }
    }

    // ---- loading

    private suspend fun load() {
        store.load()
        val list = (repo.accounts() as? ContactResult.Ok)?.value.orEmpty().ifEmpty { listOf(Account()) }
        accounts.value = list.map { AccountChoice(it.key, labelOf(it)) }
        val restored = restore()
        if (restored != null) {
            edit.update { restored.copy(phase = EditorPhase.Ready, external = 1) }
            return
        }
        val initial: ContactDraft =
            if (id != null) {
                val found = repo.detail(id).first()
                if (found == null) {
                    edit.update { it.copy(phase = EditorPhase.Gone) }
                    outbox.trySend(EditorEffect.Close)
                    return
                }
                found
            } else {
                newDraft(list)
            }
        val keyed = keysFor(initial)
        edit.update { it.copy(phase = EditorPhase.Ready, initial = initial, draft = initial, keys = keyed) }
        persist()
    }

    private suspend fun labelOf(account: Account): String =
        (ops.accountLabel(AccountLabelRequest(account.key)) as? Outcome.Ok)?.value?.label ?: account.name

    /**
     * A new contact's draft: the seed, in the account [SaveAccount] picks (AD-CT3: `prefs.save_to` when that account
     * still exists; the Groups tab creates groups in the same one).
     */
    private suspend fun newDraft(list: List<Account>): ContactDraft {
        val counts = (repo.rawContactCounts() as? ContactResult.Ok)?.value.orEmpty()
        val account = SaveAccount.choose(store.prefs.value.saveTo, list, counts)
        val name = seed?.name?.trim().orEmpty()
        val given = name.substringBeforeLast(' ', name)
        val family = if (name.contains(' ')) name.substringAfterLast(' ') else ""
        return ContactDraft(
            given = if (name.contains(' ')) given else name,
            family = family,
            numbers =
                seed
                    ?.number
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        listOf(
                            DraftField(it, FieldKind.Mobile),
                        )
                    }.orEmpty(),
            emails =
                seed
                    ?.email
                    ?.takeIf { it.isNotBlank() }
                    ?.let { listOf(DraftField(it, FieldKind.Home)) }
                    .orEmpty(),
            account = account,
        )
    }

    private fun restore(): Edit? {
        val draftJson = saved.get<String>(KEY_DRAFT) ?: return null
        val initialJson = saved.get<String>(KEY_INITIAL) ?: return null
        return runCatching {
            val draft = JSON.decodeFromString(ContactDraft.serializer(), draftJson)
            val initial = JSON.decodeFromString(ContactDraft.serializer(), initialJson)
            Edit(initial = initial, draft = draft, keys = keysFor(draft))
        }.getOrNull()
    }

    private fun persist() {
        val e = edit.value
        saved[KEY_DRAFT] = JSON.encodeToString(ContactDraft.serializer(), e.draft)
        saved[KEY_INITIAL] = JSON.encodeToString(ContactDraft.serializer(), e.initial)
    }

    private fun key(): Long = ++counter

    private fun keysFor(d: ContactDraft) =
        EditorKeys(
            numbers = d.numbers.map { key() },
            emails = d.emails.map { key() },
            addresses = d.addresses.map { key() },
            websites = d.websites.map { key() },
            events = d.events.map { key() },
            relations = d.relations.map { key() },
        )

    // ---- derived: validation, dirty, parsed dates

    private suspend fun derive(
        draft: ContactDraft,
        initial: ContactDraft,
    ): Derived {
        if (edit.value.phase != EditorPhase.Ready) return Derived()
        val validated = (ops.draftValidate(DraftRequest(draft)) as? Outcome.Ok)?.value
        val diff = (ops.draftDiff(DiffRequest(initial, draft)) as? Outcome.Ok)?.value
        val cleanedBefore = (ops.draftValidate(DraftRequest(initial)) as? Outcome.Ok)?.value?.draft
        val dirty =
            diff?.summary != null ||
                (validated != null && cleanedBefore != null && validated.draft != cleanedBefore)
        val hints = LinkedHashMap<String, String>()
        edit.value.keys.events.forEachIndexed { i, rowKey ->
            val text =
                draft.events
                    .getOrNull(i)
                    ?.date
                    ?.trim()
                    .orEmpty()
            if (text.isNotEmpty()) {
                val parsed = (ops.dateParse(DateParseRequest(text)) as? Outcome.Ok)?.value?.strict
                if (parsed != null) hints[EditorIssueKey.row(EditorList.Events, rowKey)] = parsed.text
            }
        }
        val issue = validated?.error
        return Derived(issue, issue?.let { rowOf(draft, validated.draft, it) }, dirty, hints)
    }

    /** The issue key of the row an issue names; the indexes count the cleaned draft, which dropped blank rows. */
    private fun rowOf(
        raw: ContactDraft,
        cleaned: ContactDraft,
        issue: DraftIssue,
    ): String? {
        val list = issue.kind.listOrNull() ?: return null
        val index = issue.index ?: return null
        val wanted =
            when (list) {
                EditorList.Numbers -> cleaned.numbers.getOrNull(index)?.value
                EditorList.Emails -> cleaned.emails.getOrNull(index)?.value
                EditorList.Events -> cleaned.events.getOrNull(index)?.date
                else -> null
            } ?: return null
        val rawValues =
            when (list) {
                EditorList.Numbers -> raw.numbers.map { it.value.trim() }
                EditorList.Emails -> raw.emails.map { it.value.trim() }
                else -> raw.events.map { it.date.trim() }
            }
        val at = rawValues.indexOf(wanted)
        val rowKey =
            edit.value.keys
                .of(list)
                .getOrNull(at) ?: return null
        return EditorIssueKey.row(list, rowKey)
    }

    // ---- actions

    fun onAction(action: EditorAction) {
        when (action) {
            is EditorAction.Text -> {
                change { it.copy(draft = withText(it.draft, action.field, action.value)) }
            }

            is EditorAction.RowValue -> {
                row(
                    action.list,
                    action.key,
                ) { d, i -> withValue(d, action.list, i, action.value) }
            }

            is EditorAction.Label -> {
                row(action.list, action.key) { d, i -> withLabel(d, action.list, i, action.label) }
            }

            is EditorAction.CustomLabel -> {
                row(
                    action.list,
                    action.key,
                ) { d, i -> withCustom(d, action.list, i, action.text) }
            }

            is EditorAction.AddRow -> {
                add(action.list)
            }

            is EditorAction.RemoveRow -> {
                remove(action.list, action.key)
            }

            is EditorAction.ToggleDefault -> {
                scope.launch { toggleDefault(action.list, action.key) }
            }

            is EditorAction.ToggleGroup -> {
                toggleGroup(action.id)
            }

            is EditorAction.SetAccount -> {
                scope.launch { setAccount(action.key) }
            }

            is EditorAction.Left -> {
                edit.update { it.copy(left = it.left + action.issueKey) }
            }

            EditorAction.Save -> {
                scope.launch { save() }
            }

            EditorAction.RequestClose -> {
                requestClose()
            }

            EditorAction.Discard -> {
                outbox.trySend(EditorEffect.Close)
            }
        }
    }

    private fun requestClose() {
        val e = edit.value
        outbox.trySend(if (derived.value.dirty && !e.saving) EditorEffect.ConfirmDiscard else EditorEffect.Close)
    }

    private inline fun change(transform: (Edit) -> Edit) {
        edit.update(transform)
        persist()
    }

    private fun row(
        list: EditorList,
        rowKey: Long,
        transform: (ContactDraft, Int) -> ContactDraft,
    ) {
        change { e ->
            val at = e.keys.of(list).indexOf(rowKey)
            if (at < 0) e else e.copy(draft = transform(e.draft, at))
        }
    }

    private fun add(list: EditorList) {
        change { e ->
            if (e.keys.of(list).size >= MAX_ROWS) return@change e
            val d = e.draft
            val next =
                when (list) {
                    EditorList.Numbers -> d.copy(numbers = d.numbers + DraftField(kind = FieldKind.Mobile))
                    EditorList.Emails -> d.copy(emails = d.emails + DraftField(kind = FieldKind.Home))
                    EditorList.Addresses -> d.copy(addresses = d.addresses + DraftField(kind = FieldKind.Home))
                    EditorList.Websites -> d.copy(websites = d.websites + "")
                    EditorList.Events -> d.copy(events = d.events + DraftEvent(kind = EventKind.Birthday))
                    EditorList.Relations -> d.copy(relations = d.relations + DraftRelation(kind = RelationKind.Spouse))
                }
            e.copy(draft = next, keys = e.keys.with(list, e.keys.of(list) + key()))
        }
    }

    private fun remove(
        list: EditorList,
        rowKey: Long,
    ) {
        change { e ->
            val at = e.keys.of(list).indexOf(rowKey)
            if (at < 0) return@change e
            val d = e.draft
            val next =
                when (list) {
                    EditorList.Numbers -> d.copy(numbers = d.numbers.without(at))
                    EditorList.Emails -> d.copy(emails = d.emails.without(at))
                    EditorList.Addresses -> d.copy(addresses = d.addresses.without(at))
                    EditorList.Websites -> d.copy(websites = d.websites.without(at))
                    EditorList.Events -> d.copy(events = d.events.without(at))
                    EditorList.Relations -> d.copy(relations = d.relations.without(at))
                }
            e.copy(draft = next, keys = e.keys.with(list, e.keys.of(list).without(at)))
        }
    }

    private suspend fun toggleDefault(
        list: EditorList,
        rowKey: Long,
    ) {
        val e = edit.value
        val at = e.keys.of(list).indexOf(rowKey)
        val field =
            when (list) {
                EditorList.Numbers -> FieldList.Numbers
                EditorList.Emails -> FieldList.Emails
                EditorList.Addresses -> FieldList.Addresses
                else -> return
            }
        if (at < 0) return
        val rows =
            when (list) {
                EditorList.Numbers -> e.draft.numbers
                EditorList.Emails -> e.draft.emails
                else -> e.draft.addresses
            }
        val clears = rows.getOrNull(at)?.primary == true
        val answer =
            (
                ops.draftEdit(
                    DraftEditRequest(
                        draft = e.draft,
                        edit = DraftEdit.SetPrimary,
                        list = field,
                        index = if (clears) null else at,
                    ),
                ) as? Outcome.Ok
            )?.value ?: return
        // Only the list the op changed is taken back: the owner may have typed elsewhere while the core answered.
        change { e ->
            val d = e.draft
            e.copy(
                draft =
                    when (list) {
                        EditorList.Numbers -> d.copy(numbers = answer.draft.numbers)
                        EditorList.Emails -> d.copy(emails = answer.draft.emails)
                        else -> d.copy(addresses = answer.draft.addresses)
                    },
            )
        }
    }

    private fun toggleGroup(group: Long) {
        change { e ->
            val chosen = if (group in e.draft.groups) e.draft.groups - group else e.draft.groups + group
            e.copy(draft = e.draft.copy(groups = chosen))
        }
    }

    private suspend fun setAccount(key: String) {
        if (id != null || edit.value.draft.account == key) return
        change { e -> e.copy(draft = e.draft.copy(account = key)) }
        val moved = edit.value.draft
        val answer =
            (
                ops.draftEdit(
                    DraftEditRequest(draft = moved, edit = DraftEdit.RetainAccountGroups, groups = groups.value),
                ) as? Outcome.Ok
            )?.value?.draft ?: return
        // A group of another account cannot join the contact: the ones the core dropped go, a group ticked meanwhile stays.
        val dropped = moved.groups.toSet() - answer.groups.toSet()
        change { e -> e.copy(draft = e.draft.copy(groups = e.draft.groups.filterNot { it in dropped })) }
    }

    // ---- save and undo
    private suspend fun save() {
        val before = edit.value
        if (before.saving || before.phase != EditorPhase.Ready || !derived.value.dirty) return
        edit.update { it.copy(saving = true) }
        val validated = (ops.draftValidate(DraftRequest(before.draft)) as? Outcome.Ok)?.value
        if (validated == null) {
            fail(UiText.Res(R.string.contacts_cant_save_this))
            return
        }
        val issue = validated.error
        if (issue != null) {
            edit.update { it.copy(saving = false, attempted = true) }
            outbox.trySend(EditorEffect.Invalid(derived.value.issueRow ?: EditorIssueKey.NAME))
            return
        }
        val cleaned = validated.draft
        val diff = (ops.draftDiff(DiffRequest(before.initial, cleaned)) as? Outcome.Ok)?.value
        when (val result = repo.save(cleaned)) {
            is ContactResult.Failed -> {
                fail(
                    when (result.reason) {
                        ContactResult.Reason.Permission -> UiText.Res(R.string.contacts_cant_save_permission)
                        ContactResult.Reason.NotFound -> UiText.Res(R.string.contacts_cant_save_gone)
                        else -> UiText.Res(R.string.contacts_cant_save_this)
                    },
                )
            }

            is ContactResult.Ok -> {
                val newId = result.value
                if (id == null) remember(newId, cleaned.account)
                lastUndo = diff?.undo?.copy(id = newId)
                outbox.trySend(
                    EditorEffect.Saved(
                        newId,
                        diff?.notice?.let { UiText.Literal(it) } ?: UiText.Res(R.string.contacts_saved),
                        lastUndo != null,
                    ),
                )
                outbox.trySend(EditorEffect.Close)
            }
        }
    }

    private fun fail(text: UiText) {
        edit.update { it.copy(saving = false) }
        outbox.trySend(EditorEffect.Toast(text))
    }

    /** A new contact's account becomes the default of the next one, and the contact is noted as just created. */
    private suspend fun remember(
        newId: Long,
        account: String,
    ) {
        val marks = store.prefs.value.added
        val noted =
            (ops.recentNoteCreated(RecentCreatedRequest(marks, newId, clock())) as? Outcome.Ok)?.value?.marks ?: marks
        store.update { it.copy(saveTo = account, added = noted) }
    }

    /** Puts the contact back the way it was before the last save (the Undo of the pill). False when nothing can be undone. */
    suspend fun undo(): Boolean {
        val back = lastUndo ?: return false
        lastUndo = null
        return repo.save(back) is ContactResult.Ok
    }

    companion object {
        const val KEY_DRAFT = "editor.draft"
        const val KEY_INITIAL = "editor.initial"

        /** The most rows one list takes (`contact_edit::MAX_FIELDS`). */
        const val MAX_ROWS = 12

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

private fun <T> List<T>.without(index: Int): List<T> = filterIndexed { i, _ -> i != index }

private fun withText(
    d: ContactDraft,
    field: EditorText,
    value: String,
): ContactDraft =
    when (field) {
        EditorText.Given -> d.copy(given = value)
        EditorText.Family -> d.copy(family = value)
        EditorText.PhoneticGiven -> d.copy(phoneticGiven = value)
        EditorText.PhoneticFamily -> d.copy(phoneticFamily = value)
        EditorText.Org -> d.copy(org = value)
        EditorText.Title -> d.copy(title = value)
        EditorText.Nickname -> d.copy(nickname = value)
        EditorText.Note -> d.copy(note = value)
    }

private fun <T> List<T>.set(
    index: Int,
    value: T,
): List<T> = mapIndexed { i, old -> if (i == index) value else old }

private fun withValue(
    d: ContactDraft,
    list: EditorList,
    at: Int,
    value: String,
): ContactDraft =
    when (list) {
        EditorList.Numbers -> d.copy(numbers = d.numbers.set(at, d.numbers[at].copy(value = value)))
        EditorList.Emails -> d.copy(emails = d.emails.set(at, d.emails[at].copy(value = value)))
        EditorList.Addresses -> d.copy(addresses = d.addresses.set(at, d.addresses[at].copy(value = value)))
        EditorList.Websites -> d.copy(websites = d.websites.set(at, value))
        EditorList.Events -> d.copy(events = d.events.set(at, d.events[at].copy(date = value)))
        EditorList.Relations -> d.copy(relations = d.relations.set(at, d.relations[at].copy(name = value)))
    }

private fun withLabel(
    d: ContactDraft,
    list: EditorList,
    at: Int,
    label: RowLabel,
): ContactDraft =
    when {
        label is RowLabel.Field && list == EditorList.Numbers -> {
            d.copy(
                numbers =
                    d.numbers.set(
                        at,
                        d.numbers[at].copy(kind = label.kind, label = keepCustom(label.kind, d.numbers[at])),
                    ),
            )
        }

        label is RowLabel.Field && list == EditorList.Emails -> {
            d.copy(
                emails =
                    d.emails.set(
                        at,
                        d.emails[at].copy(kind = label.kind, label = keepCustom(label.kind, d.emails[at])),
                    ),
            )
        }

        label is RowLabel.Field && list == EditorList.Addresses -> {
            d.copy(
                addresses =
                    d.addresses.set(
                        at,
                        d.addresses[at].copy(kind = label.kind, label = keepCustom(label.kind, d.addresses[at])),
                    ),
            )
        }

        label is RowLabel.Event && list == EditorList.Events -> {
            d.copy(
                events =
                    d.events.set(
                        at,
                        d.events[at].copy(
                            kind = label.kind,
                            label =
                                d.events[at].label.takeIf {
                                    label.kind ==
                                        EventKind.Custom
                                },
                        ),
                    ),
            )
        }

        label is RowLabel.Relation && list == EditorList.Relations -> {
            d.copy(
                relations =
                    d.relations.set(
                        at,
                        d.relations[at].copy(
                            kind = label.kind,
                            label =
                                d.relations[at]
                                    .label
                                    .takeIf { label.kind == RelationKind.Custom }
                                    .orEmpty(),
                        ),
                    ),
            )
        }

        else -> {
            d
        }
    }

private fun keepCustom(
    kind: FieldKind,
    old: DraftField,
): String? = old.label.takeIf { kind == FieldKind.Custom }

private fun withCustom(
    d: ContactDraft,
    list: EditorList,
    at: Int,
    text: String,
): ContactDraft =
    when (list) {
        EditorList.Numbers -> {
            d.copy(
                numbers = d.numbers.set(at, d.numbers[at].copy(kind = FieldKind.Custom, label = text)),
            )
        }

        EditorList.Emails -> {
            d.copy(emails = d.emails.set(at, d.emails[at].copy(kind = FieldKind.Custom, label = text)))
        }

        EditorList.Addresses -> {
            d.copy(
                addresses = d.addresses.set(at, d.addresses[at].copy(kind = FieldKind.Custom, label = text)),
            )
        }

        EditorList.Events -> {
            d.copy(events = d.events.set(at, d.events[at].copy(kind = EventKind.Custom, label = text)))
        }

        EditorList.Relations -> {
            d.copy(
                relations = d.relations.set(at, d.relations[at].copy(kind = RelationKind.Custom, label = text)),
            )
        }

        EditorList.Websites -> {
            d
        }
    }
