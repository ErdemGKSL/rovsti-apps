package dev.rovsti.contacts.list

import dev.rovsti.contacts.data.Book
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.trash.ContactTrash
import dev.rovsti.contacts.trash.MoveResult
import dev.rovsti.contacts.trash.RestoreResult
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.Day
import dev.rovsti.core.model.contacts.GroupChangeRequest
import dev.rovsti.core.model.contacts.GroupSummaryRequest
import dev.rovsti.core.model.contacts.GroupTargetsRequest
import dev.rovsti.core.model.contacts.LocalYmdRequest
import dev.rovsti.core.model.contacts.RecipientsRequest
import dev.rovsti.core.model.contacts.SelectBarAnswer
import dev.rovsti.core.model.contacts.SelectBarRequest
import dev.rovsti.core.model.contacts.SelectExportRequest
import dev.rovsti.core.model.contacts.SelectedContact
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.platform.contacts.ContactResult
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.files.ClipboardRepository
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TimeZone

/**
 * Select mode of the Contacts tab (E4.8). It owns the selection (`null` = not selecting) and the ids that are hidden
 * while a delete finishes (`contacts.view` `hidden`), and runs the bulk actions. Every sentence, cap and decision comes
 * from the Rust core (`contacts.select_bar`, `select_export`, `groups_targets`, `groups_change`, `groups_summary`,
 * `groups_recipients`); this class only orders the effects, as [ContactTrash] does for one contact.
 */
internal class SelectController(
    private val book: BookRepository,
    private val contacts: ContactsRepository,
    private val ops: ContactsOps,
    private val trash: ContactTrash,
    private val clipboard: ClipboardRepository,
    private val clock: () -> Long,
    private val send: (ContactsListEffect) -> Unit,
) {
    /** Who is selected; null while the list is not in select mode. */
    val selection = MutableStateFlow<Set<Long>?>(null)

    /** Contacts the list leaves out while their delete is being finished. */
    val hidden = MutableStateFlow<Set<Long>>(emptySet())

    @Volatile private var cap = FALLBACK_CAP

    private val ready: Book.Ready? get() = book.book.value as? Book.Ready

    fun enter(first: Long?) {
        selection.value = setOfNotNull(first)
    }

    fun exit() {
        selection.value = null
    }

    fun toggle(id: Long) {
        val now = selection.value ?: return
        if (id in now) {
            selection.value = now - id
        } else if (now.size >= cap) {
            send(ContactsListEffect.Toast(capNotice))
        } else {
            selection.value = now + id
        }
    }

    private var capNotice = "You can select up to $FALLBACK_CAP contacts"

    /** Selects [listed] (up to the cap), or clears the selection when all of [listed] are selected already. */
    fun toggleAll(listed: List<Long>) {
        val now = selection.value ?: return
        if (listed.isNotEmpty() && now.containsAll(listed)) {
            selection.value = now - listed.toSet()
            return
        }
        val next = LinkedHashSet(now)
        for (id in listed) {
            if (next.size >= cap) {
                send(ContactsListEffect.Toast(capNotice))
                break
            }
            next += id
        }
        selection.value = next
    }

    /** The bar for the current selection, null while not selecting. */
    suspend fun bar(
        selected: Set<Long>?,
        listed: List<Long>,
        hasGroups: Boolean,
    ): SelectMode? {
        selected ?: return null
        val answer = barAnswer(selected.size, hasGroups)
        if (answer != null) {
            cap = answer.cap
            capNotice = answer.capNotice
        }
        return SelectMode(
            selected = selected.toImmutableSet(),
            title = answer?.title ?: "${selected.size} selected",
            actions = answer?.actions.orEmpty().toImmutableList(),
            allSelected = listed.isNotEmpty() && selected.containsAll(listed),
        )
    }

    private suspend fun barAnswer(
        count: Int,
        hasGroups: Boolean,
    ): SelectBarAnswer? = (ops.selectBar(SelectBarRequest(count, hasGroups)) as? Outcome.Ok)?.value

    // ---- share, export, copy

    suspend fun share() {
        val export = export("text") ?: return
        send(ContactsListEffect.Share(export.text))
    }

    suspend fun exportVcf() {
        val export = export("vcf") ?: return
        send(ContactsListEffect.SaveVcf(export.fileName, export.text, export.count))
    }

    private suspend fun export(format: String): dev.rovsti.core.model.contacts.SelectExportAnswer? {
        val chosen = selection.value.orEmpty()
        val drafts = ready?.drafts.orEmpty()
        val day = today() ?: return null
        val answer =
            (
                ops.selectExport(
                    SelectExportRequest(drafts, chosen.toList(), hidden.value.toList(), day, format),
                ) as? Outcome.Ok
            )?.value
        if (answer == null || answer.count == 0) {
            send(ContactsListEffect.Toast("Nothing to share."))
            return null
        }
        return answer
    }

    suspend fun copyAddresses(kind: String) {
        val picked = picked()
        val day = today() ?: return
        val answer =
            (ops.groupsRecipients(RecipientsRequest(picked, kind, "Selected", day)) as? Outcome.Ok)?.value ?: return
        if (answer.addresses.isEmpty()) {
            val what = if (kind == "sms") "a number" else "an email"
            send(ContactsListEffect.Toast(answer.notice ?: "None of them has $what."))
            return
        }
        val done = clipboard.copy(answer.addresses.joinToString("\n"))
        val noun = if (kind == "sms") "number" else "email"
        val count = answer.addresses.size
        send(
            ContactsListEffect.Toast(
                if (done) "Copied $count ${if (count == 1) noun else noun + "s"}" else "Can't copy right now.",
            ),
        )
    }

    private fun picked(): List<ContactDraft> {
        val chosen = selection.value.orEmpty()
        return ready?.drafts.orEmpty().filter { it.id in chosen && it.id !in hidden.value }
    }

    private suspend fun today(): Day? {
        val now = clock()
        return (
            ops.localYmd(LocalYmdRequest(now, TimeZone.getDefault().getOffset(now) / MS_PER_S)) as? Outcome.Ok
        )?.value
    }

    // ---- star

    suspend fun star() {
        val chosen = selection.value.orEmpty()
        // Read from the provider, not the cached book: the book may not have caught up with the last change yet.
        val listed = contacts.full().first().filter { it.id in chosen }
        if (listed.isEmpty()) return
        // Stars all of them, unless all are starred already: then it unstars them (`multi_select::star_all`).
        val on = !listed.all { it.starred }
        var failed = 0
        for (contact in listed) {
            if (contacts.setStarred(contact.id ?: continue, on) is ContactResult.Failed) failed++
        }
        val answer = barAnswer(listed.size - failed, hasGroups = false)
        val notice = if (on) answer?.starredNotice else answer?.unstarredNotice
        send(
            ContactsListEffect.Toast(
                if (failed == listed.size) "Can't change these contacts right now." else notice.orEmpty(),
            ),
        )
        exit()
    }

    // ---- add to a group

    suspend fun askGroup() {
        val chosen = selection.value.orEmpty()
        val drafts = ready?.drafts.orEmpty().filter { it.id in chosen }
        val groups = contacts.groups().first()
        val selected = drafts.mapNotNull { d -> d.id?.let { SelectedContact(it, d.account) } }
        val targets =
            (ops.groupsTargets(GroupTargetsRequest(groups, selected)) as? Outcome.Ok)?.value?.targets.orEmpty()
        if (targets.isEmpty()) {
            send(ContactsListEffect.Toast("No group fits the selected contacts."))
            return
        }
        send(
            ContactsListEffect.ChooseGroup(
                targets.map { GroupChoice(it.groupId, it.title, it.skipped) }.toImmutableList(),
            ),
        )
    }

    suspend fun addToGroup(groupId: Long) {
        val chosen = selection.value.orEmpty()
        val drafts = ready?.drafts.orEmpty().filter { it.id in chosen }
        val groups = contacts.groups().first()
        val group = groups.firstOrNull { it.id == groupId } ?: return
        val selected = drafts.mapNotNull { d -> d.id?.let { SelectedContact(it, d.account) } }
        val target =
            (ops.groupsTargets(GroupTargetsRequest(groups, selected)) as? Outcome.Ok)
                ?.value
                ?.targets
                .orEmpty()
                .firstOrNull { it.groupId == groupId } ?: return
        val change =
            (ops.groupsChange(GroupChangeRequest(group.members, target.eligible, addOnly = true)) as? Outcome.Ok)?.value
                ?: return
        val added =
            if (change.add.isEmpty()) {
                0
            } else {
                when (val result = contacts.setMembers(groupId, change.add, emptyList())) {
                    is ContactResult.Ok -> {
                        result.value.added
                    }

                    is ContactResult.Failed -> {
                        send(ContactsListEffect.Toast("Can't change this group right now."))
                        return
                    }
                }
            }
        val text =
            (ops.groupsSummary(GroupSummaryRequest(group.title, added, 0, target.skipped)) as? Outcome.Ok)?.value?.text
        send(ContactsListEffect.Toast(text?.takeIf { it.isNotBlank() } ?: target.notice))
        exit()
    }

    // ---- delete

    suspend fun askDelete() {
        val count = selection.value.orEmpty().size
        if (count == 0) return
        val confirm = barAnswer(count, hasGroups = false)?.deleteConfirm ?: return
        send(ContactsListEffect.ConfirmDeleteSelected(confirm))
    }

    /** Hides the rows at once, moves each contact to the Trash and deletes it, then offers ONE undo for all. */
    suspend fun delete() {
        val ids = selection.value.orEmpty().toList()
        if (ids.isEmpty()) return
        hidden.value = hidden.value + ids
        exit()
        val keys = ArrayList<Long>()
        val failed = ArrayList<Long>()
        for (id in ids) {
            when (val moved = trash.delete(id)) {
                is MoveResult.Done -> keys += moved.key
                MoveResult.Gone -> Unit
                MoveResult.NoCopy, MoveResult.Refused -> failed += id
            }
        }
        // The rows come back for the contacts that were not deleted; the others stay hidden until the book is rid of them.
        hidden.value = hidden.value - failed.toSet()
        val gone = ids - failed.toSet()
        withTimeoutOrNull(BOOK_WAIT_MS) {
            book.book.first { b -> b !is Book.Ready || b.contacts.none { it.id in gone } }
        }
        hidden.value = hidden.value - ids.toSet()
        if (keys.isNotEmpty()) {
            val notice = barAnswer(keys.size, hasGroups = false)?.deletedNotice ?: "${keys.size} contacts deleted."
            send(ContactsListEffect.Deleted(notice, keys))
        }
        if (failed.isNotEmpty()) {
            send(ContactsListEffect.Toast("Couldn't delete ${failed.size} of them, so they were kept."))
        }
    }

    suspend fun undo(keys: List<Long>) {
        val text =
            when (val restored = trash.restore(keys)) {
                is RestoreResult.Done -> restored.notice
                RestoreResult.Missing -> "They are not in the Trash any more"
                RestoreResult.Refused -> "Couldn't bring them back, they are in the Trash"
            }
        send(ContactsListEffect.Toast(text))
    }

    private companion object {
        const val FALLBACK_CAP = 500
        const val MS_PER_S = 1_000
        const val BOOK_WAIT_MS = 3_000L
    }
}
