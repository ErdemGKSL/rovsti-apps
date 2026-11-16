package dev.rosty.stops.saved

import android.content.Context
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import dev.rosty.core.model.stops.StopColour
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.controls.EmptyState
import dev.rosty.design.haptics.LocalRostyHaptics
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.list.CustomAction
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRow
import dev.rosty.design.list.RowSeparator
import dev.rosty.design.list.TextStartWithLeading
import dev.rosty.design.list.rememberReorderList
import dev.rosty.design.motion.RostyMotion
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.SheetState
import dev.rosty.design.overlay.menuTrigger
import dev.rosty.design.overlay.sheetScrollConnection
import dev.rosty.design.text.GlassTextField
import dev.rosty.design.text.SearchField
import dev.rosty.design.text.text
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import dev.rosty.stops.R
import dev.rosty.stops.home.OnSheet
import dev.rosty.stops.map.RadarPalette
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem

/** Test tags of the Saved sheets. */
object SavedTags {
    const val Sheet = "saved-sheet"
    const val List = "saved-list"
    const val More = "saved-more"
    const val Edit = "saved-edit"
    const val Done = "saved-done"
    const val Search = "saved-search"
    const val Sort = "saved-sort"
    const val Empty = "saved-empty"
    const val StopSheet = "saved-stop-sheet"
    const val Rename = "saved-rename"
    const val RenameField = "saved-rename-field"
    const val RenameSave = "saved-rename-save"
    const val RenameCancel = "saved-rename-cancel"
    const val Note = "saved-note"
    const val NoteSheet = "saved-note-sheet"
    const val NoteField = "saved-note-field"
    const val NoteError = "saved-note-error"
    const val NoteSave = "saved-note-save"

    fun row(id: Long) = "saved-row-$id"

    fun rowMore(id: Long) = "saved-row-more-$id"

    fun handle(id: Long) = "saved-handle-$id"

    fun stopRow(key: String) = "saved-stop-$key"
}

/**
 * Presents the Saved list (E7.7) as a medium / large sheet over the map. A row tap opens the stop's own sheet
 * ([presentSavedStopSheet]); what leaves the sheet (show on map, share, QR, ...) comes back as [SavedEffect]s.
 */
fun OverlayController.presentSavedSheet(
    title: String,
    viewModel: SavedViewModel,
    files: SavedFileActions? = null,
    onDismissed: () -> Unit = {},
): SheetEntry =
    SheetEntry(
        title = title,
        detents = persistentListOf(Detent.Medium, Detent.Large),
        onDismissed = onDismissed,
    ) { sheet ->
        OnSheet { SavedList(viewModel, this@presentSavedSheet, sheet, files) }
    }.also { present(it) }

@Composable
private fun ColumnScope.SavedList(
    viewModel: SavedViewModel,
    overlays: OverlayController,
    sheet: SheetState,
    files: SavedFileActions?,
) {
    val state by viewModel.state.collectAsState()
    val onAction = viewModel::onAction
    Column(Modifier.fillMaxWidth().weight(1f).testTag(SavedTags.Sheet)) {
        SavedHeader(state, overlays, onAction, files)
        if (state.loading) return@Column
        if (state.stops.isEmpty()) {
            Box(Modifier.fillMaxSize().testTag(SavedTags.Empty), contentAlignment = Alignment.TopCenter) {
                EmptyState(
                    icon = dev.rosty.stops.home.StopsIcons.Bookmark,
                    title = stringResource(R.string.saved_empty_title),
                    message = stringResource(R.string.saved_empty_message),
                )
            }
            return@Column
        }
        if (state.rows.isEmpty()) {
            Box(Modifier.fillMaxSize().testTag(SavedTags.Empty), contentAlignment = Alignment.TopCenter) {
                EmptyState(
                    icon = RostyIcons.Search,
                    title = stringResource(R.string.saved_no_match_title),
                    message = stringResource(R.string.saved_no_match_message),
                )
            }
            return@Column
        }
        SavedRows(state, overlays, sheet, onAction) { id -> overlays.presentSavedStopSheet(viewModel, id) }
    }
}

/** The title, Edit / Done and the "..." menu, then the search field and the sort pill when they are worth it. */
@Composable
private fun SavedHeader(
    state: SavedUiState,
    overlays: OverlayController,
    onAction: (SavedAction) -> Unit,
    files: SavedFileActions?,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = Spacing.gutter, end = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RostyText(
            stringResource(R.string.sheet_saved_title),
            Modifier.weight(1f),
            style = RostyTheme.type.title2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val count = state.stops.size
        if (!state.editing && !showSearch(count) && showSort(count)) {
            SortPill(state.sort, overlays, onAction)
            Spacer(Modifier.width(Spacing.s))
        }
        if (state.editing) {
            CapsuleButton(
                stringResource(R.string.saved_done),
                { onAction(SavedAction.Editing(false)) },
                Modifier.testTag(SavedTags.Done),
                style = CapsuleStyle.Filled,
            )
        } else if (state.canReorder && state.stops.size > 1) {
            CapsuleButton(
                stringResource(R.string.saved_edit),
                { onAction(SavedAction.Editing(true)) },
                Modifier.testTag(SavedTags.Edit),
            )
        }
        if (!state.editing) {
            val copy = stringResource(R.string.saved_copy_list)
            val share = stringResource(R.string.saved_share_list)
            val gpx = stringResource(R.string.places_export_gpx)
            val json = stringResource(R.string.places_export_json)
            val import = stringResource(R.string.places_import)
            val listed = state.rows.isNotEmpty()
            val any = state.stops.isNotEmpty()
            val items =
                remember(copy, share, listed, any, onAction, files) {
                    val list =
                        mutableListOf<MenuItem>(
                            MenuItem.Action(copy, RostyIcons.Copy, enabled = listed) { onAction(SavedAction.CopyAll) },
                            MenuItem.Action(
                                share,
                                RostyIcons.Share,
                                enabled = listed,
                            ) { onAction(SavedAction.ShareAll) },
                        )
                    // S5: the whole list to a file and back.
                    if (files != null) {
                        list += MenuItem.Separator
                        list += MenuItem.Action(gpx, enabled = any) { files.export(PlacesTransfer.FORMAT_GPX) }
                        list += MenuItem.Action(json, enabled = any) { files.export(PlacesTransfer.FORMAT_JSON) }
                        list += MenuItem.Action(import) { files.import() }
                    }
                    list.toImmutableList()
                }
            MoreButton(overlays, items, SavedTags.More)
        }
    }
    // Above five stops the search field gets its own row, with the sort pill beside it.
    if (!state.editing && showSearch(state.stops.size)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Box(Modifier.weight(1f)) { SearchBox(state.query, onAction) }
            SortPill(state.sort, overlays, onAction)
        }
    }
}

@Composable
private fun SearchBox(
    query: String,
    onAction: (SavedAction) -> Unit,
) {
    val field: TextFieldState = rememberTextFieldState(query)
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.distinctUntilChanged().collect { onAction(SavedAction.Query(it)) }
    }
    SearchField(
        field,
        Modifier.fillMaxWidth().testTag(SavedTags.Search),
        placeholder = stringResource(R.string.saved_search),
    )
}

@Composable
private fun SortPill(
    sort: SavedSort,
    overlays: OverlayController,
    onAction: (SavedAction) -> Unit,
) {
    val labels =
        SavedSort.entries.associateWith { stringResource(sortLabel(it)) }
    val items =
        remember(sort, labels, onAction) {
            SavedSort.entries
                .map { s -> MenuItem.Action(labels.getValue(s), checked = s == sort) { onAction(SavedAction.Sort(s)) } }
                .toImmutableList<MenuItem>()
        }
    val colors = RostyTheme.colors
    Box(
        Modifier
            .heightIn(min = Sizes.touch)
            .clip(RoundedCornerShape(percent = 50))
            .background(colors.fillTertiary)
            .menuTrigger(overlays, items, stringResource(R.string.saved_sort_menu))
            .consumeTaps()
            .padding(horizontal = Spacing.m)
            .testTag(SavedTags.Sort),
        contentAlignment = Alignment.Center,
    ) {
        RostyText(
            stringResource(R.string.saved_sort, labels.getValue(sort)),
            style = RostyTheme.type.subheadline,
            color = colors.accent,
            maxLines = 1,
        )
    }
}

private fun sortLabel(sort: SavedSort): Int =
    when (sort) {
        SavedSort.Saved -> R.string.saved_sort_saved
        SavedSort.Name -> R.string.saved_sort_name
        SavedSort.Nearest -> R.string.saved_sort_nearest
    }

/** A 48 dp "..." target that opens [items] on touch down. */
@Composable
private fun MoreButton(
    overlays: OverlayController,
    items: ImmutableList<MenuItem>,
    tag: String,
) {
    val label = stringResource(R.string.saved_more)
    Box(
        Modifier
            .size(Sizes.touch)
            .semantics { contentDescription = label }
            .menuTrigger(overlays, items, label)
            .consumeTaps()
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        RostyIcon(RostyIcons.Ellipsis, null, tint = RostyTheme.colors.labelSecondary, size = MORE_GLYPH)
    }
}

@Composable
private fun ColumnScope.SavedRows(
    state: SavedUiState,
    overlays: OverlayController,
    sheet: SheetState,
    onAction: (SavedAction) -> Unit,
    onOpen: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    val reorder =
        rememberReorderList(listState, state.rows, { it.stop.id }, PaddingValues()) { from, to ->
            val stops = state.rows.map { it.stop }
            stops.getOrNull(from)?.let { onAction(SavedAction.Move(it.id, groupDelta(stops, from, to))) }
        }
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .nestedScroll(sheetScrollConnection(sheet))
            .testTag(SavedTags.List),
        state = listState,
        contentPadding = PaddingValues(bottom = Spacing.l),
    ) {
        val rows = reorder.items
        rows.forEachIndexed { index, row ->
            item(key = row.stop.id, contentType = "saved") {
                ReorderableItem(
                    reorder.state,
                    key = row.stop.id,
                    animateItemModifier =
                        Modifier.animateItem(
                            placementSpec = RostyMotion.select(IntOffset.VisibilityThreshold),
                        ),
                ) { dragging ->
                    SavedRow(row, index, state, dragging, overlays, onAction, onOpen)
                }
            }
        }
    }
}

@Composable
private fun ReorderableCollectionItemScope.SavedRow(
    row: SavedRowUi,
    index: Int,
    state: SavedUiState,
    dragging: Boolean,
    overlays: OverlayController,
    onAction: (SavedAction) -> Unit,
    onOpen: (Long) -> Unit,
) {
    val id = row.stop.id
    val colors = RostyTheme.colors
    val scale by animateFloatAsState(if (dragging) LIFT_SCALE else 1f, RostyMotion.select(1f), label = "lift")
    val lift = with(LocalDensity.current) { LIFT_SHADOW.toPx() }
    val haptics = LocalRostyHaptics.current
    val reorder = state.canReorder
    val up = reorder && canMove(state.stops, id, -1)
    val down = reorder && canMove(state.stops, id, 1)
    val menu = rowMenu(id, up, down, onAction)
    val steps = stepActions(id, up, down, onAction)
    Column(
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = if (dragging) lift else 0f
            }.then(if (dragging) Modifier.background(colors.groupedSurface) else Modifier),
    ) {
        if (index > 0 && !dragging) RowSeparator(inset = TextStartWithLeading)
        ListRow(
            title = rowTitle(row.stop),
            modifier = Modifier.testTag(SavedTags.row(id)),
            // S44: the note under the detail, on a second line.
            secondary =
                row.stop.note
                    .takeIf { it.isNotBlank() }
                    ?.let { "${detailLine(row)}\n$it" } ?: detailLine(row),
            secondaryMaxLines = 2,
            leading = { StopBadge(row.stop.colour) },
            trailing = {
                if (state.editing) {
                    DragHandle(id) { haptics.tick() }
                } else {
                    MoreButton(overlays, menu, SavedTags.rowMore(id))
                }
            },
            onClick = if (state.editing) null else ({ onOpen(id) }),
            actions = steps,
        )
    }
}

/** The row subtitle (GPUI `stop_detail`): the gate length, how far away when the position is known, how often used. */
@Composable
private fun detailLine(row: SavedRowUi): String {
    val base =
        row.distance?.let { stringResource(R.string.saved_gate_away, row.length, it) }
            ?: stringResource(R.string.saved_gate, row.length)
    return row.usage?.let { "$base · $it" } ?: base
}

/** The stop's map colour as a round badge with the bookmark glyph (the marker colours of the radar). */
@Composable
private fun StopBadge(colour: StopColour) {
    val palette = RadarPalette.of(RostyTheme.colors.isDark)
    Box(
        Modifier.size(BADGE).background(Color(palette.saved[colour.ordinal]), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        RostyIcon(dev.rosty.stops.home.StopsIcons.Bookmark, null, tint = Color.White, size = BADGE_GLYPH)
    }
}

@Composable
private fun ReorderableCollectionItemScope.DragHandle(
    id: Long,
    haptics: () -> Unit,
) {
    val label = stringResource(R.string.saved_reorder)
    Box(
        Modifier
            .size(Sizes.touch)
            .semantics { contentDescription = label }
            .draggableHandle(onDragStarted = { haptics() })
            .testTag(SavedTags.handle(id)),
        contentAlignment = Alignment.Center,
    ) {
        RostyIcon(RostyIcons.Reorder, null, tint = RostyTheme.colors.labelSecondary, size = MORE_GLYPH)
    }
}

/** The row "..." menu (GPUI `row_menu_model`): Move up / down are dimmed at a group's edge or out of the own order. */
@Composable
private fun rowMenu(
    id: Long,
    up: Boolean,
    down: Boolean,
    onAction: (SavedAction) -> Unit,
): ImmutableList<MenuItem> {
    val show = stringResource(R.string.saved_show_on_map)
    val maps = stringResource(R.string.saved_open_in_maps)
    val moveUp = stringResource(R.string.saved_move_up)
    val moveDown = stringResource(R.string.saved_move_down)
    val delete = stringResource(R.string.saved_delete)
    return remember(id, up, down, onAction, show, maps, moveUp, moveDown, delete) {
        persistentListOf(
            MenuItem.Action(show, RostyIcons.Map) { onAction(SavedAction.Open(id)) },
            MenuItem.Action(maps, RostyIcons.Globe) { onAction(SavedAction.OpenInMaps(id)) },
            MenuItem.Separator,
            MenuItem.Action(moveUp, RostyIcons.ArrowUp, enabled = up) { onAction(SavedAction.Move(id, -1)) },
            MenuItem.Action(moveDown, RostyIcons.ArrowDown, enabled = down) { onAction(SavedAction.Move(id, 1)) },
            MenuItem.Separator,
            MenuItem.Action(delete, RostyIcons.Trash, destructive = true) { onAction(SavedAction.Delete(id)) },
        )
    }
}

/** The drag's spoken twin: a screen reader moves the row one place at a time. */
@Composable
private fun stepActions(
    id: Long,
    up: Boolean,
    down: Boolean,
    onAction: (SavedAction) -> Unit,
): ImmutableList<CustomAction> {
    val moveUp = stringResource(R.string.saved_move_up)
    val moveDown = stringResource(R.string.saved_move_down)
    return remember(id, up, down, onAction, moveUp, moveDown) {
        val list = ArrayList<CustomAction>(2)
        if (up) list += CustomAction(moveUp) { onAction(SavedAction.Move(id, -1)) }
        if (down) list += CustomAction(moveDown) { onAction(SavedAction.Move(id, 1)) }
        list.toImmutableList()
    }
}

/**
 * The sheet of one saved stop (GPUI `saved_stop_body`): its name and detail, then Start, Show on map, Share, Open in
 * maps, Favourite, Rename, Colour, QR code and Home screen. It closes itself when the stop goes away.
 */
fun OverlayController.presentSavedStopSheet(
    viewModel: SavedViewModel,
    id: Long,
): SheetEntry {
    val name =
        viewModel.state.value.stops
            .firstOrNull { it.id == id }
            ?.name
            .orEmpty()
    return SheetEntry(title = name, detents = persistentListOf(Detent.Medium, Detent.Large)) { sheet ->
        OnSheet { SavedStopBody(viewModel, id, this@presentSavedStopSheet, sheet) }
    }.also { present(it) }
}

@Composable
private fun SavedStopBody(
    viewModel: SavedViewModel,
    id: Long,
    overlays: OverlayController,
    sheet: SheetState,
) {
    val state by viewModel.state.collectAsState()
    val row = state.rows.firstOrNull { it.stop.id == id }
    val stop = row?.stop ?: state.stops.firstOrNull { it.id == id }
    LaunchedEffect(stop == null, state.loading) { if (stop == null && !state.loading) sheet.dismiss() }
    stop ?: return
    val onAction = viewModel::onAction
    val leave: (SavedAction) -> Unit = { action ->
        sheet.dismiss()
        onAction(action)
    }
    val colourText = stringResource(R.string.saved_colour, stringResource(SavedViewModel.colourName(stop.colour)))
    Column(
        Modifier
            .fillMaxWidth()
            .nestedScroll(sheetScrollConnection(sheet))
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.l)
            .testTag(SavedTags.StopSheet),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Column(Modifier.padding(horizontal = Spacing.gutter)) {
            RostyText(rowTitle(stop), style = RostyTheme.type.title2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (row != null) {
                RostyText(
                    detailLine(row),
                    style = RostyTheme.type.footnote,
                    color = RostyTheme.colors.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (stop.note.isNotBlank()) {
                RostyText(
                    stop.note,
                    Modifier.padding(top = Spacing.xs).testTag(SavedTags.Note),
                    style = RostyTheme.type.subheadline,
                    maxLines = NOTE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val start = stringResource(R.string.saved_start)
        val show = stringResource(R.string.saved_show_on_map)
        val share = stringResource(R.string.saved_share)
        val maps = stringResource(R.string.saved_open_in_maps)
        val favourite = stringResource(if (stop.favourite) R.string.saved_unfavourite else R.string.saved_favourite)
        val rename = stringResource(R.string.saved_rename)
        val qr = stringResource(R.string.saved_qr)
        val home = stringResource(R.string.saved_home_screen)
        val renameTitle = stringResource(R.string.saved_rename_title)
        val noteLabel = stringResource(if (stop.note.isBlank()) R.string.saved_add_note else R.string.saved_edit_note)
        val context = androidx.compose.ui.platform.LocalContext.current
        InsetGroupedSection {
            row { SheetRow(start, "start") { leave(SavedAction.Start(id)) } }
            row { SheetRow(show, "show") { leave(SavedAction.Open(id)) } }
            row { SheetRow(share, "share") { onAction(SavedAction.Share(id)) } }
            row { SheetRow(maps, "maps") { onAction(SavedAction.OpenInMaps(id)) } }
        }
        InsetGroupedSection {
            row { SheetRow(favourite, "favourite") { onAction(SavedAction.Favourite(id)) } }
            row {
                SheetRow(rename, "rename") {
                    overlays.presentRenameSheet(context, renameTitle, stop.name, state.maxNameChars) { name ->
                        onAction(SavedAction.Rename(id, name))
                    }
                }
            }
            row {
                SheetRow(noteLabel, "note") { overlays.presentNoteSheet(context, viewModel, id, stop.note) }
            }
            row { SheetRow(colourText, "colour") { onAction(SavedAction.CycleColour(id)) } }
        }
        InsetGroupedSection {
            row { SheetRow(qr, "qr") { onAction(SavedAction.Qr(id)) } }
            row { SheetRow(home, "home") { onAction(SavedAction.HomeScreen(id)) } }
        }
    }
}

@Composable
private fun SheetRow(
    label: String,
    key: String,
    onClick: () -> Unit,
) {
    ListRow(label, Modifier.testTag(SavedTags.stopRow(key)), onClick = onClick, titleColor = RostyTheme.colors.accent)
}

/**
 * S44: the note of stop [id] in a field of a few lines, at most [SavedUiState.NOTE_MAX_CHARS] characters; an empty note
 * clears it. A note the core refuses stays in the field with the reason under it; a saved one closes the sheet.
 */
fun OverlayController.presentNoteSheet(
    context: Context,
    viewModel: SavedViewModel,
    id: Long,
    note: String,
): SheetEntry =
    SheetEntry(
        title = context.getString(R.string.saved_note_title),
        detents = persistentListOf(Detent.Medium),
    ) { sheet ->
        OnSheet { NoteBody(context, viewModel, id, note, sheet) }
    }.also { present(it) }

@Composable
private fun NoteBody(
    context: Context,
    viewModel: SavedViewModel,
    id: Long,
    note: String,
    sheet: SheetState,
) {
    val state by viewModel.state.collectAsState()
    val field = rememberTextFieldState(note)
    var sent by remember { mutableStateOf<String?>(null) }
    val stored = state.stops.firstOrNull { it.id == id }?.note
    val error = state.noteError?.takeIf { it.id == id && sent != null }
    // Saved: the stored note is what was sent and nothing was refused.
    LaunchedEffect(stored, sent, error) {
        val wanted = sent ?: return@LaunchedEffect
        if (error == null && stored == wanted.trim()) sheet.dismiss()
    }
    val save = {
        sent = field.text.toString()
        viewModel.onAction(SavedAction.Note(id, field.text.toString()))
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).testTag(SavedTags.NoteSheet),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        RostyText(context.getString(R.string.saved_note_title), style = RostyTheme.type.title3)
        GlassTextField(
            field,
            placeholder = context.getString(R.string.saved_note_placeholder),
            modifier = Modifier.fillMaxWidth().testTag(SavedTags.NoteField),
            maxLines = NOTE_LINES,
            minLines = NOTE_MIN_LINES,
        )
        if (error != null) {
            RostyText(
                error.text.text(),
                Modifier.testTag(SavedTags.NoteError),
                style = RostyTheme.type.footnote,
                color = RostyTheme.colors.destructive,
            )
        } else {
            RostyText(
                context.resources.getQuantityString(
                    R.plurals.saved_name_limit,
                    SavedUiState.NOTE_MAX_CHARS,
                    SavedUiState.NOTE_MAX_CHARS,
                ),
                style = RostyTheme.type.footnote,
                color = RostyTheme.colors.labelSecondary,
            )
        }
        CapsuleButton(
            context.getString(R.string.saved_save),
            onClick = save,
            modifier = Modifier.fillMaxWidth().testTag(SavedTags.NoteSave),
            style = CapsuleStyle.Filled,
            fillWidth = true,
        )
    }
}

/** Whether [name] can be a stop's name: 1 to [maxChars] characters once trimmed (the core checks it again). */
internal fun nameFits(
    name: CharSequence,
    maxChars: Int,
): Boolean = name.toString().trim().let { it.isNotEmpty() && it.codePointCount(0, it.length) <= maxChars }

/**
 * The name of a stop (Rename, and "Save as stop" with the core's suggestion in the field): one field holding
 * [name], at most [maxChars] characters, then Save and, with [cancel], Cancel. [detail] is a line under the title
 * (the pair being saved). Save waits for a name that fits; the core checks it again and a refusal is a toast.
 */
fun OverlayController.presentRenameSheet(
    context: Context,
    title: String,
    name: String,
    maxChars: Int,
    detail: String? = null,
    cancel: Boolean = false,
    onSave: (String) -> Unit,
): SheetEntry =
    SheetEntry(title = title, detents = persistentListOf(Detent.Medium)) { sheet ->
        val field = rememberTextFieldState(name)
        val fits = nameFits(field.text, maxChars)
        OnSheet {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).testTag(SavedTags.Rename),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                RostyText(title, style = RostyTheme.type.title3)
                detail?.let {
                    RostyText(it, style = RostyTheme.type.subheadline, color = RostyTheme.colors.labelSecondary)
                }
                GlassTextField(
                    field,
                    placeholder = context.getString(R.string.saved_name),
                    modifier = Modifier.fillMaxWidth().testTag(SavedTags.RenameField),
                    onSubmit = {
                        if (fits) {
                            sheet.dismiss()
                            onSave(field.text.toString())
                        }
                    },
                )
                RostyText(
                    context.resources.getQuantityString(R.plurals.saved_name_limit, maxChars, maxChars),
                    style = RostyTheme.type.footnote,
                    color = RostyTheme.colors.labelSecondary,
                )
                CapsuleButton(
                    context.getString(R.string.saved_save),
                    onClick = {
                        sheet.dismiss()
                        onSave(field.text.toString())
                    },
                    modifier = Modifier.fillMaxWidth().testTag(SavedTags.RenameSave),
                    style = CapsuleStyle.Filled,
                    enabled = fits,
                    fillWidth = true,
                )
                if (cancel) {
                    CapsuleButton(
                        context.getString(R.string.saved_cancel),
                        onClick = { sheet.dismiss() },
                        modifier = Modifier.fillMaxWidth().testTag(SavedTags.RenameCancel),
                        fillWidth = true,
                    )
                }
            }
        }
    }.also { present(it) }

/**
 * Takes the touches of a menu trigger inside a clickable row: the trigger opens its menu on the down (it reads
 * consumed events too), and the row under it must not take the same tap as its own click.
 */
private fun Modifier.consumeTaps(): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false).consume()
            waitForUpOrCancellation()?.consume()
        }
    }

private const val NOTE_LINES = 4
private const val NOTE_MIN_LINES = 3
private const val LIFT_SCALE = 1.03f
