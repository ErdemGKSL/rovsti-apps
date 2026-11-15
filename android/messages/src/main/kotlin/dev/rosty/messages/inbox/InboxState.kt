package dev.rosty.messages.inbox

import androidx.compose.runtime.Immutable
import dev.rosty.core.model.messages.InboxFilter
import dev.rosty.design.text.UiText
import dev.rosty.messages.MessagesRoute
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf

/** Which list the inbox shows: the inbox itself, or one of the folders behind it. */
enum class InboxFolder { Inbox, Archived, Spam }

/** Whether the SMS provider can be read (GPUI `access::Access`). */
enum class SmsAccess { Loading, Ready, Denied }

/** A filter chip with its count (the label is localized by the screen). */
@Immutable
data class FilterChipUi(
    val filter: InboxFilter,
    val count: Int,
)

/** How long a mute lasts. */
enum class MuteFor(
    val ms: Long?,
) {
    EightHours(8 * 60 * 60 * 1000L),
    Week(7 * 24 * 60 * 60 * 1000L),
    Always(null),
}

/** The bulk actions of select mode (GPUI `inbox_bulk::actions`). */
enum class BulkKind { MarkRead, MarkUnread, Mute, Unmute, Pin, Unpin, Archive, Unarchive, Delete }

@Immutable
data class SelectUi(
    val selected: ImmutableSet<String>,
    val actions: ImmutableList<BulkKind>,
    val allSelected: Boolean,
)

/** The inbox screen's state; built on `Dispatchers.Default` by [InboxViewModel]. */
@Immutable
data class InboxUiState(
    val loaded: Boolean = false,
    val access: SmsAccess = SmsAccess.Loading,
    val roleHeld: Boolean = true,
    val folder: InboxFolder = InboxFolder.Inbox,
    /** The rows of the current folder, filtered by the chip and the search text. */
    val rows: ImmutableList<InboxRowUi> = persistentListOf(),
    val chips: ImmutableList<FilterChipUi> = persistentListOf(),
    val filter: InboxFilter = InboxFilter.All,
    val query: String = "",
    val archivedCount: Int = 0,
    val archivedUnread: Int = 0,
    val spamCount: Int = 0,
    val select: SelectUi? = null,
    /** Whether any row of the folder exists before the chip and the search narrow it. */
    val anyRows: Boolean = false,
    /** The messages that match the search text, under the matching chats; null without a search. */
    val messages: MessagesSectionUi? = null,
)

/** One-shot results of an action: navigation and pills. */
sealed interface InboxEffect {
    data class Open(
        val route: MessagesRoute,
    ) : InboxEffect

    data class Toast(
        val text: UiText,
    ) : InboxEffect

    /** A pill with Undo; [undo] reverts the action. */
    data class Undo(
        val text: UiText,
        val undo: () -> Unit,
    ) : InboxEffect

    /** Ask before deleting [keys]; [confirm] deletes. */
    data class ConfirmDelete(
        val count: Int,
        val title: String?,
        val confirm: () -> Unit,
    ) : InboxEffect

    /** The default-SMS role is needed for this action. */
    data object NeedsRole : InboxEffect
}

sealed interface InboxAction {
    data class Open(
        val key: String,
    ) : InboxAction

    data class SetFilter(
        val filter: InboxFilter,
    ) : InboxAction

    /** A search hit: its conversation, told to reveal the message. */
    data class OpenRoute(
        val route: MessagesRoute,
    ) : InboxAction

    data class SetQuery(
        val text: String,
    ) : InboxAction

    data class ShowFolder(
        val folder: InboxFolder,
    ) : InboxAction

    data class Pin(
        val key: String,
        val pinned: Boolean,
    ) : InboxAction

    data class MarkRead(
        val key: String,
        val read: Boolean,
    ) : InboxAction

    data class Mute(
        val key: String,
        val duration: MuteFor,
    ) : InboxAction

    data class Unmute(
        val key: String,
    ) : InboxAction

    data class Archive(
        val key: String,
        val archived: Boolean,
    ) : InboxAction

    data class Delete(
        val key: String,
    ) : InboxAction

    data object MarkAllRead : InboxAction

    data class EnterSelect(
        val key: String? = null,
    ) : InboxAction

    data class Toggle(
        val key: String,
    ) : InboxAction

    data object ToggleAll : InboxAction

    data object ExitSelect : InboxAction

    data class Bulk(
        val kind: BulkKind,
        val mute: MuteFor = MuteFor.Always,
    ) : InboxAction
}
