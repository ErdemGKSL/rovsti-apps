package dev.rosty.messages.inbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.rosty.core.model.messages.Channel
import dev.rosty.core.model.messages.InboxFilter
import dev.rosty.design.chrome.LargeTitleScaffold
import dev.rosty.design.chrome.ToolbarAction
import dev.rosty.design.chrome.ToolbarButton
import dev.rosty.design.chrome.ToolbarCapsule
import dev.rosty.design.chrome.ToolbarTextButton
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.controls.ChipModel
import dev.rosty.design.controls.ChipRow
import dev.rosty.design.controls.EmptyState
import dev.rosty.design.controls.MessageSource
import dev.rosty.design.controls.SelectCheck
import dev.rosty.design.controls.SelectCheckSize
import dev.rosty.design.controls.SourceGlyph
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.foundation.IosTheme
import dev.rosty.design.list.RowSeparator
import dev.rosty.design.list.SwipeAction
import dev.rosty.design.list.SwipeRow
import dev.rosty.design.list.SwipeRowGroup
import dev.rosty.design.list.TextStartWithLeading
import dev.rosty.design.overlay.ContextMenuStyle
import dev.rosty.design.overlay.LocalOverlayOrNull
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.longPressMenu
import dev.rosty.design.press.rowPress
import dev.rosty.design.text.SearchField
import dev.rosty.design.text.rtlSafe
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.messages.theme.MessagesMetrics
import dev.rosty.messages.ui.MessagesAvatar
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Test tags of the inbox. */
object InboxTags {
    const val Root = "inbox"
    const val MessagesHeader = "inbox-messages"

    fun hit(key: String) = "inbox-hit-$key"

    const val Search = "inbox-search"
    const val Chips = "inbox-chips"
    const val Empty = "inbox-empty"
    const val Archived = "inbox-archived"
    const val Spam = "inbox-spam"
    const val Back = "inbox-folder-back"
    const val Role = "inbox-role"
    const val SelectAll = "inbox-select-all"
    const val SelectCancel = "inbox-select-cancel"
    const val BulkBar = "inbox-bulk"

    fun row(key: String) = "inbox-row-$key"

    fun bulk(kind: BulkKind) = "inbox-bulk-${kind.name}"

    fun otp(key: String) = "inbox-otp-$key"
}

/** The labels the screen needs, resolved once per composition from `strings.xml`. */
@Immutable
data class InboxStrings(
    val title: String,
    val archivedTitle: String,
    val spamTitle: String,
    val search: String,
    val newMessage: String,
    val select: String,
    val selectAll: String,
    val deselectAll: String,
    val cancel: String,
    val selectNone: String,
    val selectedFormat: (Int) -> String,
    val chip: (InboxFilter) -> String,
    val empty: (InboxFilter) -> String,
    val emptyTitle: String,
    val emptyDetail: String,
    val loading: String,
    val accessOffTitle: String,
    val accessOffDetail: String,
    val archivedRow: String,
    val spamRow: String,
    val chatsCount: (Int) -> String,
    val inboxBack: String,
    val spamNote: String,
    val noResults: String,
    val all: String,
    val pin: String,
    val unpin: String,
    val markRead: String,
    val markUnread: String,
    val mute: String,
    val unmute: String,
    val archive: String,
    val unarchive: String,
    val delete: String,
    val bulk: (BulkKind) -> String,
    val copyCode: (String) -> String,
    val muted: String,
    val pinned: String,
    val unreadCount: (Int) -> String,
    /** The Messages section of the search (M7). */
    val messagesHeader: String = "",
    val resultsCount: (Int) -> String = { it.toString() },
    val partialNote: String = "",
    val smsMissingNote: String = "",
)

@Composable
fun rememberInboxStrings(): InboxStrings {
    val chips =
        InboxFilter.entries.associateWith {
            stringResource(
                when (it) {
                    InboxFilter.All -> R.string.inbox_chip_all
                    InboxFilter.Unread -> R.string.inbox_chip_unread
                    InboxFilter.WhatsApp -> R.string.tab_whatsapp
                    InboxFilter.Sms -> R.string.inbox_chip_sms
                    InboxFilter.Groups -> R.string.inbox_chip_groups
                },
            )
        }
    val empties =
        InboxFilter.entries.associateWith {
            stringResource(
                when (it) {
                    InboxFilter.All -> R.string.inbox_none_all
                    InboxFilter.Unread -> R.string.inbox_none_unread
                    InboxFilter.WhatsApp -> R.string.inbox_none_whatsapp
                    InboxFilter.Sms -> R.string.inbox_none_sms
                    InboxFilter.Groups -> R.string.inbox_none_groups
                },
            )
        }
    val bulks =
        BulkKind.entries.associateWith {
            stringResource(
                when (it) {
                    BulkKind.MarkRead -> R.string.inbox_mark_read
                    BulkKind.MarkUnread -> R.string.inbox_mark_unread
                    BulkKind.Mute -> R.string.inbox_mute
                    BulkKind.Unmute -> R.string.inbox_unmute
                    BulkKind.Pin -> R.string.inbox_pin
                    BulkKind.Unpin -> R.string.inbox_unpin
                    BulkKind.Archive -> R.string.inbox_archive
                    BulkKind.Unarchive -> R.string.inbox_unarchive
                    BulkKind.Delete -> R.string.inbox_delete
                },
            )
        }
    val selected = stringResource(R.string.inbox_selected)
    val res = LocalResources.current
    val copy = stringResource(R.string.inbox_copy_code)
    val unread = stringResource(R.string.inbox_unread_count)
    return InboxStrings(
        title = stringResource(R.string.tab_chats),
        archivedTitle = stringResource(R.string.inbox_archived_title),
        spamTitle = stringResource(R.string.inbox_spam_title),
        search = stringResource(R.string.inbox_search),
        newMessage = stringResource(R.string.new_conversation_title),
        select = stringResource(R.string.inbox_select),
        selectAll = stringResource(R.string.inbox_select_all),
        deselectAll = stringResource(R.string.inbox_deselect_all),
        cancel = stringResource(R.string.inbox_cancel),
        selectNone = stringResource(R.string.inbox_select_none),
        selectedFormat = { n -> selected.format(n) },
        chip = { chips.getValue(it) },
        empty = { empties.getValue(it) },
        emptyTitle = stringResource(R.string.inbox_empty_title),
        emptyDetail = stringResource(R.string.inbox_empty_detail),
        loading = stringResource(R.string.inbox_loading),
        accessOffTitle = stringResource(R.string.inbox_access_off_title),
        accessOffDetail = stringResource(R.string.inbox_access_off_detail),
        archivedRow = stringResource(R.string.inbox_archived_title),
        spamRow = stringResource(R.string.inbox_spam_title),
        chatsCount = { n -> res.getQuantityString(R.plurals.inbox_chats, n, n) },
        inboxBack = stringResource(R.string.tab_chats),
        spamNote = stringResource(R.string.inbox_spam_note),
        noResults = stringResource(R.string.inbox_no_results),
        all = stringResource(R.string.inbox_chip_all),
        pin = bulks.getValue(BulkKind.Pin),
        unpin = bulks.getValue(BulkKind.Unpin),
        markRead = bulks.getValue(BulkKind.MarkRead),
        markUnread = bulks.getValue(BulkKind.MarkUnread),
        mute = stringResource(R.string.inbox_mute_menu),
        unmute = bulks.getValue(BulkKind.Unmute),
        archive = bulks.getValue(BulkKind.Archive),
        unarchive = bulks.getValue(BulkKind.Unarchive),
        delete = stringResource(R.string.inbox_delete_conversation),
        bulk = { bulks.getValue(it) },
        copyCode = { code -> copy.format(code) },
        muted = stringResource(R.string.inbox_muted_a11y),
        pinned = stringResource(R.string.inbox_pinned_a11y),
        unreadCount = { n -> unread.format(n) },
        messagesHeader = stringResource(R.string.inbox_messages_header),
        resultsCount = { n -> res.getQuantityString(R.plurals.inbox_results, n, n) },
        partialNote = stringResource(R.string.inbox_results_partial),
        smsMissingNote = stringResource(R.string.inbox_results_no_sms),
    )
}

/** What the row menu does, wired by the page (the mute durations open a second sheet there). */
class RowMenuActions(
    val onAction: (InboxAction) -> Unit,
    val onMute: (InboxRowUi) -> Unit,
    val onAvatar: (InboxRowUi) -> Unit,
    val onCopyCode: (String) -> Unit,
)

/** The row menu (GPUI `row_menu::row_menu_items`, the parts of R1): Select; Pin, read, mute, archive; Delete. */
fun rowMenu(
    row: InboxRowUi,
    strings: InboxStrings,
    actions: RowMenuActions,
): ImmutableList<MenuItem> {
    val act = actions.onAction
    val items = ArrayList<MenuItem>()
    items += MenuItem.Action(strings.select, RostyIcons.CircleCheck) { act(InboxAction.EnterSelect(row.key)) }
    items += MenuItem.Separator
    items +=
        MenuItem.Action(if (row.pinned) strings.unpin else strings.pin, RostyIcons.Star) {
            act(InboxAction.Pin(row.key, !row.pinned))
        }
    items +=
        MenuItem.Action(if (row.isUnread) strings.markRead else strings.markUnread, RostyIcons.Message) {
            act(InboxAction.MarkRead(row.key, row.isUnread))
        }
    items +=
        if (row.muted) {
            MenuItem.Action(strings.unmute, RostyIcons.Bell) { act(InboxAction.Unmute(row.key)) }
        } else {
            MenuItem.Action(strings.mute, RostyIcons.BellSlash) { actions.onMute(row) }
        }
    items +=
        MenuItem.Action(if (row.archived) strings.unarchive else strings.archive, RostyIcons.Folder) {
            act(InboxAction.Archive(row.key, !row.archived))
        }
    if (row.smsThread != null && row.waChat == null) {
        items += MenuItem.Separator
        items +=
            MenuItem.Action(strings.delete, RostyIcons.Trash, destructive = true) { act(InboxAction.Delete(row.key)) }
    }
    return items.toImmutableList()
}

/**
 * The Chats tab root (E6.2): a large-title list with the search field, the filter chips (one row, never wrapping,
 * counts as small labels), the Archived and Spam folder rows, then one row per person. Long-press opens the row menu
 * with a lifted preview; swipe right marks read or unread, swipe left archives (or pins); select mode folds the bar.
 */
@Composable
fun InboxScreen(
    state: InboxUiState,
    strings: InboxStrings,
    listState: LazyListState,
    searchState: TextFieldState,
    actions: RowMenuActions,
    onNewMessage: () -> Unit,
    modifier: Modifier = Modifier,
    banner: (@Composable () -> Unit)? = null,
) {
    val onAction = rememberUpdatedState(actions.onAction)
    LaunchedEffect(searchState) {
        snapshotFlow { searchState.text.toString() }.collect { onAction.value(InboxAction.SetQuery(it)) }
    }
    val select = state.select
    BackHandler(enabled = select != null) { actions.onAction(InboxAction.ExitSelect) }
    BackHandler(enabled = select == null && state.folder != InboxFolder.Inbox) {
        actions.onAction(InboxAction.ShowFolder(InboxFolder.Inbox))
    }
    val title =
        when {
            select != null -> {
                if (select.selected.isEmpty()) {
                    strings.selectNone
                } else {
                    strings.selectedFormat(
                        select.selected.size,
                    )
                }
            }

            state.folder == InboxFolder.Archived -> {
                strings.archivedTitle
            }

            state.folder == InboxFolder.Spam -> {
                strings.spamTitle
            }

            else -> {
                strings.title
            }
        }
    val toolbar =
        remember(state.loaded, strings, onNewMessage) {
            persistentListOf(
                ToolbarAction(
                    "select",
                    RostyIcons.Checkmark,
                    strings.select,
                ) { onAction.value(InboxAction.EnterSelect()) },
                ToolbarAction("new", RostyIcons.Pencil, strings.newMessage, onClick = onNewMessage),
            )
        }
    // One swipe group: opening a row closes the one open before.
    SwipeRowGroup {
        Box(modifier.testTag(InboxTags.Root)) {
            LargeTitleScaffold(
                title = title,
                state = listState,
                actions = toolbar,
                actionsContent = select?.let { { SelectButtons(it, strings, actions.onAction) } },
            ) {
                banner?.let { content -> item(key = "banner", contentType = "banner") { content() } }
                item(key = "search", contentType = "search") {
                    SearchField(
                        state = searchState,
                        placeholder = strings.search,
                        modifier =
                            Modifier
                                .padding(
                                    horizontal = Spacing.gutter,
                                    vertical = Spacing.s,
                                ).testTag(InboxTags.Search),
                    )
                }
                if (state.folder != InboxFolder.Inbox) {
                    item(key = "back", contentType = "folder") {
                        FolderRow(
                            icon = RostyIcons.Back,
                            title = strings.inboxBack,
                            detail = if (state.folder == InboxFolder.Spam) strings.spamNote else null,
                            modifier = Modifier.testTag(InboxTags.Back),
                        ) { actions.onAction(InboxAction.ShowFolder(InboxFolder.Inbox)) }
                    }
                } else if (state.chips.size > 1) {
                    item(key = "chips", contentType = "chips") {
                        val models =
                            remember(state.chips, strings) {
                                state.chips
                                    .map {
                                        ChipModel(
                                            it.filter.name,
                                            strings.chip(it.filter),
                                            it.count.takeIf { c ->
                                                it.filter !=
                                                    InboxFilter.All &&
                                                    c > 0
                                            },
                                        )
                                    }.toImmutableList()
                            }
                        ChipRow(
                            chips = models,
                            selected = state.filter.name,
                            onSelect = { actions.onAction(InboxAction.SetFilter(InboxFilter.valueOf(it))) },
                            modifier = Modifier.testTag(InboxTags.Chips),
                        )
                    }
                }
                if (state.folder == InboxFolder.Inbox && select == null && state.query.isBlank()) {
                    if (state.archivedCount > 0) {
                        item(key = "archived", contentType = "folder") {
                            FolderRow(
                                icon = RostyIcons.Folder,
                                title = strings.archivedRow,
                                detail = strings.chatsCount(state.archivedCount),
                                badge = state.archivedUnread,
                                modifier = Modifier.testTag(InboxTags.Archived),
                            ) { actions.onAction(InboxAction.ShowFolder(InboxFolder.Archived)) }
                        }
                    }
                    if (state.spamCount > 0) {
                        item(key = "spam", contentType = "folder") {
                            FolderRow(
                                icon = RostyIcons.Shield,
                                title = strings.spamRow,
                                detail = strings.chatsCount(state.spamCount),
                                modifier = Modifier.testTag(InboxTags.Spam),
                            ) { actions.onAction(InboxAction.ShowFolder(InboxFolder.Spam)) }
                        }
                    }
                }
                body(state, strings, actions)
                state.messages?.let { messagesSection(it, strings, actions.onAction) }
            }
        }
    }
}

private fun LazyListScope.body(
    state: InboxUiState,
    strings: InboxStrings,
    actions: RowMenuActions,
) {
    if (state.rows.isEmpty()) {
        item(key = "empty", contentType = "empty") {
            Box(
                Modifier
                    .fillParentMaxHeight(MessagesMetrics.EmptyShare)
                    .fillMaxWidth()
                    .testTag(InboxTags.Empty),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    !state.loaded -> {
                        EmptyState(RostyIcons.Message, strings.loading, "")
                    }

                    state.access == SmsAccess.Denied && !state.anyRows -> {
                        EmptyState(RostyIcons.Sms, strings.accessOffTitle, strings.accessOffDetail)
                    }

                    state.query.isNotBlank() && state.messages?.hits.isNullOrEmpty() -> {
                        EmptyState(RostyIcons.Search, strings.noResults, "")
                    }

                    state.query.isNotBlank() -> {}

                    state.anyRows -> {
                        EmptyState(
                            RostyIcons.Message,
                            strings.empty(state.filter),
                            "",
                            actionLabel = strings.all,
                            onAction = { actions.onAction(InboxAction.SetFilter(InboxFilter.All)) },
                        )
                    }

                    else -> {
                        EmptyState(RostyIcons.Message, strings.emptyTitle, strings.emptyDetail)
                    }
                }
            }
        }
        return
    }
    state.rows.forEachIndexed { at, row ->
        item(key = row.key, contentType = "row") { InboxRowItem(row, at > 0, state.select, strings, actions) }
    }
}

@Composable
private fun SelectButtons(
    select: SelectUi,
    strings: InboxStrings,
    onAction: (InboxAction) -> Unit,
) {
    ToolbarCapsule {
        ToolbarTextButton(
            if (select.allSelected) strings.deselectAll else strings.selectAll,
            { onAction(InboxAction.ToggleAll) },
            Modifier.testTag(InboxTags.SelectAll),
        )
        ToolbarTextButton(strings.cancel, {
            onAction(InboxAction.ExitSelect)
        }, Modifier.testTag(InboxTags.SelectCancel), bold = true)
    }
}

/** The glyph of a bulk action on the select toolbar. */
private fun BulkKind.icon(): ImageVector =
    when (this) {
        BulkKind.MarkRead, BulkKind.MarkUnread -> RostyIcons.Message
        BulkKind.Mute -> RostyIcons.BellSlash
        BulkKind.Unmute -> RostyIcons.Bell
        BulkKind.Pin -> RostyIcons.Star
        BulkKind.Unpin -> RostyIcons.StarFilled
        BulkKind.Archive, BulkKind.Unarchive -> RostyIcons.Folder
        BulkKind.Delete -> RostyIcons.Trash
    }

/**
 * The actions of select mode (GPUI `inbox_bulk::actions`), as the tab bar's accessory: the bar folds into the circle of
 * the Chats tab and this toolbar rides beside it, like Recents in Phone (E5.3).
 */
@Composable
fun InboxSelectToolbar(
    select: SelectUi,
    strings: InboxStrings,
    onAction: (InboxAction) -> Unit,
    modifier: Modifier = Modifier,
    isInline: Boolean = true,
) {
    val any = select.selected.isNotEmpty()
    Row(
        modifier
            .heightIn(min = if (isInline) Sizes.touch else Sizes.chip + Spacing.l)
            .padding(horizontal = Spacing.s)
            .testTag(InboxTags.BulkBar),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (kind in select.actions) {
            ToolbarButton(kind.icon(), strings.bulk(kind), {
                onAction(InboxAction.Bulk(kind))
            }, Modifier.testTag(InboxTags.bulk(kind)), enabled = any)
        }
    }
}

@Composable
private fun FolderRow(
    icon: ImageVector,
    title: String,
    detail: String?,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    val colors = RostyTheme.colors
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.rowMin)
            .rowPress(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(MessagesMetrics.FolderTile)
                .clip(RoundedCornerShape(MessagesMetrics.FolderTileCorner))
                .background(colors.fillTertiary),
            contentAlignment = Alignment.Center,
        ) {
            RostyIcon(icon, null, tint = colors.labelSecondary, size = MessagesMetrics.FolderGlyph)
        }
        Column(Modifier.weight(1f).padding(start = Spacing.m)) {
            RostyText(title, style = RostyTheme.type.body, color = colors.labelPrimary, maxLines = 1)
            detail?.let {
                RostyText(it, style = RostyTheme.type.footnote, color = colors.labelSecondary, maxLines = 2)
            }
        }
        if (badge > 0) UnreadBadge(badge, quiet = true)
    }
}

/** Width of the select circle's slot: the gutter and the circle; the row's own gutter is the gap after it. */
private val SelectSlotWidth = Spacing.gutter + SelectCheckSize

@Composable
private fun InboxRowItem(
    row: InboxRowUi,
    separated: Boolean,
    select: SelectUi?,
    strings: InboxStrings,
    actions: RowMenuActions,
) {
    val colors = RostyTheme.colors
    val ios = IosTheme.colors
    val overlays = LocalOverlayOrNull.current
    val tag = Modifier.testTag(InboxTags.row(row.key))
    Column {
        if (separated) RowSeparator(inset = TextStartWithLeading)
        if (select != null) {
            val checked = row.key in select.selected
            Row(
                tag.semantics { selected = checked },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(SelectSlotWidth).padding(start = Spacing.gutter)) { SelectCheck(checked) }
                Box(Modifier.weight(1f)) {
                    RowBody(
                        row,
                        strings,
                        onClick = { actions.onAction(InboxAction.Toggle(row.key)) },
                        onAvatar = null,
                        onCopyCode = null,
                    )
                }
            }
            return@Column
        }
        val menu = remember(row, strings, actions) { rowMenu(row, strings, actions) }
        val leading =
            remember(row.key, row.isUnread, strings, colors) {
                persistentListOf(
                    SwipeAction(if (row.isUnread) strings.markRead else strings.markUnread, colors.accent, {
                        SwipeGlyph(RostyIcons.Message)
                    }) {
                        actions.onAction(InboxAction.MarkRead(row.key, row.isUnread))
                    },
                )
            }
        val trailing =
            remember(row.key, row.archived, row.pinned, strings, ios) {
                persistentListOf(
                    SwipeAction(
                        if (row.pinned) strings.unpin else strings.pin,
                        ios.systemOrange,
                        { SwipeGlyph(RostyIcons.Star) },
                    ) {
                        actions.onAction(InboxAction.Pin(row.key, !row.pinned))
                    },
                    SwipeAction(if (row.archived) strings.unarchive else strings.archive, ios.systemGray, {
                        SwipeGlyph(RostyIcons.Folder)
                    }) {
                        actions.onAction(InboxAction.Archive(row.key, !row.archived))
                    },
                )
            }
        SwipeRow(leading = leading, trailing = trailing, rowKey = row.key, modifier = tag) {
            val open = remember(row.key) { { actions.onAction(InboxAction.Open(row.key)) } }
            val body =
                if (overlays != null) {
                    Modifier.longPressMenu(
                        controller = overlays,
                        items = menu,
                        style = ContextMenuStyle.Lifted(preview = { RowBody(row, strings, null, null, null) }),
                        onTap = open,
                    )
                } else {
                    Modifier.clickable(onClick = open)
                }
            Box(body.background(colors.page)) {
                RowBody(
                    row,
                    strings,
                    onClick = null,
                    onAvatar = { actions.onAvatar(row) },
                    onCopyCode = actions.onCopyCode,
                )
            }
        }
    }
}

@Composable
private fun SwipeGlyph(icon: ImageVector) {
    RostyIcon(icon, null, tint = Color.White, size = MessagesMetrics.SwipeGlyph)
}

/** One row: avatar, name + one source glyph (+ pin/mute), time; one preview line and the unread badge; the code chip. */
@Composable
private fun RowBody(
    row: InboxRowUi,
    strings: InboxStrings,
    onClick: (() -> Unit)?,
    onAvatar: (() -> Unit)?,
    onCopyCode: ((String) -> Unit)?,
) {
    val colors = RostyTheme.colors
    val type = RostyTheme.type
    val source = remember { MutableInteractionSource() }
    val clickable =
        if (onClick != null) {
            Modifier
                .rowPress(
                    source,
                ).clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
        } else {
            Modifier
        }
    val description =
        listOfNotNull(
            row.title,
            row.snippet,
            row.time,
            if (row.unread > 0) strings.unreadCount(row.unread) else null,
            if (row.pinned) strings.pinned else null,
            if (row.muted) strings.muted else null,
        ).joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.rowMinTwoLine)
            .then(clickable)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = Spacing.gutter, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val avatarTap =
            if (onAvatar != null) {
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onAvatar,
                )
            } else {
                Modifier
            }
        MessagesAvatar(row.title, row.avatar, avatarTap)
        Column(Modifier.weight(1f).padding(start = Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    RostyText(
                        row.title.rtlSafe(),
                        modifier = Modifier.weight(1f, fill = false),
                        style = if (row.isUnread) type.body.copy(fontWeight = FontWeight.SemiBold) else type.body,
                        color = colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    SourceGlyph(
                        if (row.latest == Channel.WhatsApp) MessageSource.WhatsApp else MessageSource.Sms,
                        Modifier.padding(start = Spacing.xs),
                        contentDescription = "",
                    )
                    if (row.pinned) Accessory(RostyIcons.Star)
                    if (row.muted) Accessory(RostyIcons.BellSlash)
                }
                RostyText(
                    row.time,
                    modifier = Modifier.padding(start = Spacing.s),
                    style = type.footnote,
                    color = if (row.isUnread && !row.muted) colors.accent else colors.labelSecondary,
                    maxLines = 1,
                )
            }
            Row(Modifier.padding(top = Spacing.textGap), verticalAlignment = Alignment.CenterVertically) {
                RostyText(
                    row.snippet.rtlSafe(),
                    modifier = Modifier.weight(1f),
                    style = type.subheadline,
                    color = if (row.draft) colors.destructive else colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    row.unread > 0 -> UnreadBadge(row.unread, quiet = row.muted, Modifier.padding(start = Spacing.s))
                    row.markedUnread -> UnreadBadge(0, quiet = row.muted, Modifier.padding(start = Spacing.s))
                }
            }
            val code = row.otp
            if (code != null && onCopyCode != null) {
                CodeChip(strings.copyCode(code), { onCopyCode(code) }, Modifier.testTag(InboxTags.otp(row.key)))
            }
        }
    }
}

/** The small "Copy 482913" pill under a passcode row: tapping it copies, it never opens the chat. */
@Composable
private fun CodeChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RostyTheme.colors
    // The pill is small; its touch target is the full 48 dp height.
    Box(
        modifier
            .heightIn(min = Sizes.touch)
            .clickable(
                interactionSource =
                    remember {
                        MutableInteractionSource()
                    },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        RostyText(
            text,
            modifier =
                Modifier
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = CHIP_FILL_ALPHA))
                    .padding(horizontal = Spacing.m, vertical = MessagesMetrics.OtpChipVertical),
            style = RostyTheme.type.footnote,
            color = colors.accent,
            maxLines = 1,
        )
    }
}

@Composable
private fun Accessory(icon: ImageVector) {
    RostyIcon(
        icon,
        null,
        tint = RostyTheme.colors.labelSecondary,
        size = MessagesMetrics.RowAccessory,
        modifier = Modifier.padding(start = Spacing.xs),
    )
}

/** The unread count ("999+" above 999), or an empty dot for "marked unread"; grey when the chat is muted. */
@Composable
fun UnreadBadge(
    count: Int,
    quiet: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = RostyTheme.colors
    val fill = if (quiet) colors.labelTertiary else colors.accent
    if (count <= 0) {
        Box(modifier.size(Sizes.badgeDot).background(fill, CircleShape))
        return
    }
    Box(
        modifier
            .defaultMinSize(minWidth = MessagesMetrics.UnreadBadgeMin, minHeight = MessagesMetrics.UnreadBadgeMin)
            .background(fill, CircleShape)
            .padding(horizontal = MessagesMetrics.UnreadBadgePad),
        contentAlignment = Alignment.Center,
    ) {
        RostyText(
            if (count > BADGE_CAP) "$BADGE_CAP+" else count.toString(),
            style = RostyTheme.type.caption1,
            color = colors.onAccent,
            maxLines = 1,
        )
    }
}

private const val BADGE_CAP = 999
private const val CHIP_FILL_ALPHA = 0.14f
