package dev.rovsti.phone.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import dev.rovsti.design.chrome.Toolbar
import dev.rovsti.design.chrome.ToolbarAction
import dev.rovsti.design.controls.EmptyState
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.RowSeparator
import dev.rovsti.design.list.SwipeAction
import dev.rovsti.design.list.SwipeRow
import dev.rovsti.design.list.SwipeRowGroup
import dev.rovsti.design.list.TextStartWithLeading
import dev.rovsti.design.overlay.ContextMenuStyle
import dev.rovsti.design.overlay.LocalOverlayOrNull
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.longPressMenu
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.design.text.SearchField
import dev.rovsti.design.text.SearchFocusHandoff
import dev.rovsti.design.text.rememberSearchFocusHandoff
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.NotesMetrics
import dev.rovsti.phone.PhoneMetrics
import dev.rovsti.phone.R
import dev.rovsti.phone.RecentsMetrics
import dev.rovsti.phone.details.CallDetailsTarget
import dev.rovsti.shell.settings.SettingsLazyPage
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the Notes page. */
object NotesTags {
    const val Page = "notes-page"
    const val Search = "notes-search"
    const val Empty = "notes-empty"
    const val Share = "toolbar-notes-share"
    const val Export = "toolbar-notes-export"

    fun row(key: String) = "notes-row-$key"
}

/**
 * The Notes page (E5.11), stateless: a search field, then the notes as one grouped card, a row to a note (the person, the
 * date and the first words, all the core's). A row opens its call, swipes to Delete (a full swipe deletes) and has a
 * long-press menu with the same two. Share all and Export as CSV sit in the top bar once there is a note. [handoff] keeps
 * the keyboard and the search field in step with the call sheet a row opens.
 */
@Composable
fun NotesScreen(
    state: NotesUi,
    searchState: TextFieldState,
    handoff: SearchFocusHandoff,
    onAction: (NotesAction) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
) {
    val latest = rememberUpdatedState(onAction)
    LaunchedEffect(searchState) {
        snapshotFlow { searchState.text.toString() }.collect { latest.value(NotesAction.SetQuery(it)) }
    }
    val shareLabel = stringResource(R.string.notes_share)
    val exportLabel = stringResource(R.string.notes_export)
    val toolbar =
        remember(state.total > 0, shareLabel, exportLabel) {
            if (state.total == 0) {
                persistentListOf<ToolbarAction>()
            } else {
                persistentListOf(
                    ToolbarAction("notes-share", RovstiIcons.Share, shareLabel) { latest.value(NotesAction.ShareAll) },
                    ToolbarAction("notes-export", RovstiIcons.Download, exportLabel) {
                        latest.value(NotesAction.ExportCsv)
                    },
                )
            }
        }
    SwipeRowGroup {
        SettingsLazyPage(
            title = stringResource(R.string.notes_title),
            modifier = modifier.testTag(NotesTags.Page),
            onBack = onBack,
            onBackLongClick = onBackLongClick,
            actions = { Toolbar(toolbar) },
        ) {
            item(key = "search", contentType = "search") {
                SearchField(
                    state = searchState,
                    placeholder = stringResource(R.string.notes_search),
                    focusRequester = handoff.requester,
                    modifier =
                        Modifier
                            .padding(bottom = NotesMetrics.SearchBottom)
                            .testTag(NotesTags.Search),
                )
            }
            if (state.ready) {
                if (state.rows.isEmpty()) {
                    item(key = "empty", contentType = "empty") { NotesEmpty(state) }
                } else {
                    val last = state.rows.size - 1
                    state.rows.forEachIndexed { at, row ->
                        item(key = row.key, contentType = "note") {
                            NoteRow(row, first = at == 0, last = at == last, onAction = onAction)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotesEmpty(state: NotesUi) {
    val none = state.total == 0
    Box(
        Modifier.fillMaxWidth().padding(top = Spacing.xl).testTag(NotesTags.Empty),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            icon = RovstiIcons.Pencil,
            title = stringResource(if (none) R.string.notes_empty_title else R.string.notes_no_match_title),
            message = stringResource(if (none) R.string.notes_empty_message else R.string.notes_no_match_message),
        )
    }
}

@Composable
private fun NoteRow(
    row: NoteRowUi,
    first: Boolean,
    last: Boolean,
    onAction: (NotesAction) -> Unit,
) {
    val colors = RovstiTheme.colors
    val radius = PhoneMetrics.CardRadius
    val shape =
        RoundedCornerShape(
            topStart = if (first) radius else NotesMetrics.Flat,
            topEnd = if (first) radius else NotesMetrics.Flat,
            bottomStart = if (last) radius else NotesMetrics.Flat,
            bottomEnd = if (last) radius else NotesMetrics.Flat,
        )
    val open = remember(row.key, onAction) { { onAction(NotesAction.Open(row.key)) } }
    val delete = remember(row.key, onAction) { { onAction(NotesAction.Delete(row.key)) } }
    val deleteLabel = stringResource(R.string.notes_delete)
    val openLabel = stringResource(R.string.notes_open)
    val trailing =
        remember(row.key, deleteLabel, colors) {
            persistentListOf(SwipeAction(deleteLabel, colors.destructive, { SwipeGlyph(RovstiIcons.Trash) }, delete))
        }
    val menu =
        remember(row.key, deleteLabel, openLabel) {
            persistentListOf<MenuItem>(
                MenuItem.Action(openLabel, RovstiIcons.Phone, onClick = open),
                MenuItem.Action(deleteLabel, RovstiIcons.Trash, destructive = true, onClick = delete),
            )
        }
    val overlays = LocalOverlayOrNull.current
    Box(Modifier.fillMaxWidth().clip(shape).testTag(NotesTags.row(row.key))) {
        SwipeRow(trailing = trailing, rowKey = row.key) {
            val press: Modifier =
                if (overlays != null) {
                    Modifier.longPressMenu(
                        controller = overlays,
                        items = menu,
                        style = ContextMenuStyle.Lifted(preview = { RowBody(row, null) }),
                        onTap = open,
                    )
                } else {
                    Modifier
                }
            Box(press) { RowBody(row, if (overlays == null) open else null, separated = !first) }
        }
    }
}

@Composable
private fun RowBody(
    row: NoteRowUi,
    onClick: (() -> Unit)?,
    separated: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().background(IosTheme.colors.secondarySystemGroupedBackground)) {
        if (separated) RowSeparator(inset = TextStartWithLeading)
        ListRow(
            title = row.title,
            secondary = row.subtitle,
            secondaryMaxLines = 2,
            leading = { Avatar(row.name ?: row.title, size = NotesMetrics.Avatar, colorKey = row.number) },
            onClick = onClick,
        )
    }
}

@Composable
private fun SwipeGlyph(icon: ImageVector) {
    RovstiIcon(icon, null, tint = Color.White, size = RecentsMetrics.SwipeGlyph)
}

/** The Android side of the page: the share sheet and the CSV picker. */
interface NotesHost {
    fun share(text: String)

    fun saveCsv(
        fileName: String,
        csv: String,
    )
}

/**
 * [NotesScreen] over its view model. The effects open the call sheet (the search field lets go of the keyboard and takes it
 * back when the sheet closes), show the Undo pill, the notices, the share sheet and the CSV picker.
 */
@Composable
fun NotesPage(
    viewModel: NotesViewModel,
    host: NotesHost,
    onOpenCall: (CallDetailsTarget, onDismissed: () -> Unit) -> Unit,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val searchState = rememberTextFieldState()
    val handoff = rememberSearchFocusHandoff(searchState)
    val overlays = LocalOverlayOrNull.current
    val haptics = LocalRovstiHaptics.current
    val latestHost by rememberUpdatedState(host)
    val latestOpen by rememberUpdatedState(onOpenCall)
    val deleted = stringResource(R.string.notes_deleted)
    val undo = stringResource(R.string.notes_undo)
    val goneText = stringResource(R.string.notes_notice_call_gone)
    val deleteFailed = stringResource(R.string.notes_notice_delete_failed)
    val undoFailed = stringResource(R.string.notes_notice_undo_failed)
    val exportFailed = stringResource(R.string.notes_notice_export_failed)
    LaunchedEffect(viewModel, overlays) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is NotesEffect.OpenCall -> {
                    handoff.onResultOpened()
                    latestOpen(effect.target, handoff::onSheetDismissed)
                }

                is NotesEffect.Undoable -> {
                    overlays?.showUndo(
                        message = deleted,
                        onUndo = { viewModel.onAction(NotesAction.Undo(effect.note)) },
                        actionLabel = undo,
                    )
                }

                is NotesEffect.Say -> {
                    overlays?.showToast(
                        when (effect.notice) {
                            NotesNotice.CallGone -> goneText
                            NotesNotice.DeleteFailed -> deleteFailed
                            NotesNotice.UndoFailed -> undoFailed
                            NotesNotice.ExportFailed -> exportFailed
                        },
                    )
                }

                is NotesEffect.ShareText -> {
                    latestHost.share(effect.text)
                }

                is NotesEffect.SaveCsv -> {
                    latestHost.saveCsv(effect.fileName, effect.csv)
                }

                NotesEffect.Reject -> {
                    haptics.reject()
                }
            }
        }
    }
    NotesScreen(
        state = state,
        searchState = searchState,
        handoff = handoff,
        onAction = viewModel::onAction,
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}
