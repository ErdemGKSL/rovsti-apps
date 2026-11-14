package dev.rovsti.contacts.favorites

import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import dev.rovsti.contacts.R
import dev.rovsti.contacts.people.ContactAvatar
import dev.rovsti.contacts.people.PersonItem
import dev.rovsti.contacts.people.presentContactPicker
import dev.rovsti.design.chrome.LargeTitleScaffold
import dev.rovsti.design.chrome.LocalContentInsets
import dev.rovsti.design.chrome.ToolbarAction
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.controls.EmptyState
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.CustomAction
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ListRowChevron
import dev.rovsti.design.list.RowSeparator
import dev.rovsti.design.list.SectionHeader
import dev.rovsti.design.list.TextStartWithLeading
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.overlay.ContextMenuStyle
import dev.rovsti.design.overlay.LocalOverlayOrNull
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.longPressMenu
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem

/** Test tags of the Favorites tab. */
object FavTags {
    const val Screen = "favorites-screen"
    const val Empty = "favorites-empty"
    const val Frequent = "favorites-frequent"
    const val Upcoming = "favorites-upcoming"
    const val SeeAll = "favorites-see-all"
    const val Gate = "favorites-gate"

    fun row(id: Long) = "favorite-row-$id"

    fun handle(id: Long) = "favorite-handle-$id"

    fun star(id: Long) = "frequent-star-$id"

    fun wish(key: String) = "upcoming-wish-$key"

    fun upcomingRow(key: String) = "upcoming-row-$key"
}

/**
 * The Favorites tab (E4.6), stateless: the favorites in the user's order (a long press opens Move Earlier, Move Later
 * and Remove over the page; in edit mode every row has a drag handle and the row follows the finger on the `select`
 * spring), then Frequently Contacted with a star that adds a person, then the next dates under Upcoming with a Wish
 * button and a See All row. The header has Add, Edit and Done. [gate] is the call-log permission row.
 */
@Composable
fun FavoritesScreen(
    state: FavoritesUiState,
    onAction: (FavoritesAction) -> Unit,
    onOpenUpcoming: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    gate: (@Composable () -> Unit)? = null,
    overlays: OverlayController? = LocalOverlayOrNull.current,
) {
    val ready = state.phase == FavoritesPhase.Ready
    val title = stringResource(R.string.tab_favorites)
    val pickTitle = stringResource(R.string.favorites_add_title)
    val pickConfirm = stringResource(R.string.favorites_add_confirm)
    val onAdd: () -> Unit = {
        overlays?.presentContactPicker(pickTitle, pickConfirm, state.pickables, emptySet()) { ids ->
            onAction(FavoritesAction.AddAll(ids))
        }
    }
    val actions = rememberHeader(ready, state, onAction, onAdd)
    val reorder =
        rememberFavoritesReorder(
            lazyListState = listState,
            shown = state.favorites,
            scrollThresholdPadding = LocalContentInsets.current,
            onDropped = { from, to -> onAction(FavoritesAction.Move(from, to)) },
        )
    Box(modifier.testTag(FavTags.Screen)) {
        LargeTitleScaffold(title = title, state = listState, actions = actions) {
            if (ready) {
                readyItems(state, reorder, onAction, onOpenUpcoming, onAdd, gate, overlays)
            }
        }
    }
}

@Composable
private fun rememberHeader(
    ready: Boolean,
    state: FavoritesUiState,
    onAction: (FavoritesAction) -> Unit,
    onAdd: () -> Unit,
): ImmutableList<ToolbarAction> {
    val editLabel = stringResource(R.string.favorites_edit)
    val doneLabel = stringResource(R.string.favorites_done)
    val addLabel = stringResource(R.string.favorites_add_title)
    val header = state.header
    return remember(ready, header, onAction, onAdd, editLabel, doneLabel, addLabel) {
        if (!ready) {
            persistentListOf()
        } else {
            persistentListOf<ToolbarAction>().addAll(
                header.map { button ->
                    when (button) {
                        FavoritesHeaderAction.Add -> {
                            ToolbarAction("add", RovstiIcons.Plus, addLabel, onClick = onAdd)
                        }

                        FavoritesHeaderAction.Edit -> {
                            ToolbarAction(
                                "edit",
                                RovstiIcons.Pencil,
                                editLabel,
                            ) { onAction(FavoritesAction.ToggleEdit) }
                        }

                        FavoritesHeaderAction.Done -> {
                            ToolbarAction(
                                "done",
                                RovstiIcons.Checkmark,
                                doneLabel,
                            ) { onAction(FavoritesAction.ToggleEdit) }
                        }
                    }
                },
            )
        }
    }
}

private fun LazyListScope.readyItems(
    state: FavoritesUiState,
    reorder: FavoritesReorder,
    onAction: (FavoritesAction) -> Unit,
    onOpenUpcoming: () -> Unit,
    onAdd: () -> Unit,
    gate: (@Composable () -> Unit)?,
    overlays: OverlayController?,
) {
    if (state.favorites.isEmpty()) {
        item(key = "empty", contentType = "empty") { EmptyBlock(state.isEmpty, onAdd) }
    }
    val rows = reorder.items
    rows.forEachIndexed { index, person ->
        item(key = favoriteKey(person.id), contentType = "favorite") {
            FavoriteRow(person, index, rows.size, state.editing, reorder, overlays, onAction)
        }
    }
    if (state.frequent.isNotEmpty() || state.frequentGate) {
        item(key = "frequent-title", contentType = "header") {
            SectionHeader(stringResource(R.string.favorites_frequent), Modifier.testTag(FavTags.Frequent))
        }
        if (state.frequentGate) {
            item(key = "frequent-gate", contentType = "gate") {
                Box(Modifier.testTag(FavTags.Gate)) { gate?.invoke() }
            }
        }
        state.frequent.forEachIndexed { index, person ->
            item(key = "freq-${person.id}", contentType = "frequent") {
                FrequentRow(person, separated = index > 0, onAction)
            }
        }
    }
    if (state.upcoming.isNotEmpty() && !state.editing) {
        item(key = "upcoming-title", contentType = "header") {
            SectionHeader(stringResource(R.string.favorites_upcoming), Modifier.testTag(FavTags.Upcoming))
        }
        state.upcoming.forEachIndexed { index, event ->
            item(key = "up-${event.key}", contentType = "upcoming") {
                UpcomingRow(event, separated = index > 0, withDate = false, onAction)
            }
        }
        item(key = "see-all", contentType = "see-all") {
            SeeAllRow(state.upcomingTotal, onOpenUpcoming)
        }
    }
}

@Composable
private fun EmptyBlock(
    alone: Boolean,
    onAdd: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xl)
            .testTag(FavTags.Empty),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            icon = RovstiIcons.Star,
            title = stringResource(R.string.favorites_empty_title),
            message = stringResource(R.string.favorites_empty_message),
            actionLabel = if (alone) stringResource(R.string.favorites_add_title) else null,
            onAction = if (alone) onAdd else null,
        )
    }
}

@Composable
private fun androidx.compose.foundation.lazy.LazyItemScope.FavoriteRow(
    person: PersonItem,
    index: Int,
    count: Int,
    editing: Boolean,
    reorder: FavoritesReorder,
    overlays: OverlayController?,
    onAction: (FavoritesAction) -> Unit,
) {
    ReorderableItem(
        state = reorder.state,
        key = favoriteKey(person.id),
        animateItemModifier = Modifier.animateItem(placementSpec = RovstiMotion.select(IntOffset.VisibilityThreshold)),
    ) { dragging ->
        val colors = RovstiTheme.colors
        val haptics = LocalRovstiHaptics.current
        val scale by animateFloatAsState(if (dragging) LIFT_SCALE else 1f, RovstiMotion.select(1f), label = "lift")
        val lift = with(LocalDensity.current) { FavoritesMetrics.LiftShadow.toPx() }
        val open = remember(person.id, onAction) { { onAction(FavoritesAction.Open(person.id)) } }
        val menu = favoriteMenu(person.id, index, count, onAction)
        Column(
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = if (dragging) lift else 0f
                }.then(if (dragging) Modifier.background(colors.page) else Modifier),
        ) {
            if (index > 0 && !dragging) RowSeparator(inset = TextStartWithLeading)
            val body: @Composable (Modifier) -> Unit = { m ->
                PersonRow(
                    person,
                    m.testTag(FavTags.row(person.id)),
                    onClick = if (editing || overlays != null) null else open,
                    actions = if (editing) stepActions(person.id, index, count, onAction) else persistentListOf(),
                    trailing =
                        if (editing) {
                            { DragHandle(person.id, this@ReorderableItem, haptics = { haptics.tick() }) }
                        } else {
                            null
                        },
                )
            }
            if (!editing && overlays != null) {
                Box(
                    Modifier.longPressMenu(
                        controller = overlays,
                        items = menu,
                        style = ContextMenuStyle.Lifted(preview = { PersonRow(person, Modifier, null, null) }),
                        onTap = open,
                    ),
                ) { body(Modifier) }
            } else {
                body(Modifier)
            }
        }
    }
}

/** The drag has a spoken twin: a screen reader moves the row one place at a time. */
@Composable
private fun stepActions(
    id: Long,
    index: Int,
    count: Int,
    onAction: (FavoritesAction) -> Unit,
): ImmutableList<CustomAction> {
    val earlier = stringResource(R.string.favorites_move_earlier)
    val later = stringResource(R.string.favorites_move_later)
    return remember(id, index, count, onAction, earlier, later) {
        val list = ArrayList<CustomAction>()
        if (index > 0) list += CustomAction(earlier) { onAction(FavoritesAction.MoveStep(index, up = true)) }
        if (index < count - 1) list += CustomAction(later) { onAction(FavoritesAction.MoveStep(index, up = false)) }
        persistentListOf<CustomAction>().addAll(list)
    }
}

@Composable
internal fun DragHandle(
    id: Long,
    scope: ReorderableCollectionItemScope,
    haptics: () -> Unit,
    tag: String = FavTags.handle(id),
) {
    val label = stringResource(R.string.favorites_reorder)
    with(scope) {
        Box(
            Modifier
                .size(Sizes.touch)
                .semantics { contentDescription = label }
                .draggableHandle(onDragStarted = { haptics() })
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            RovstiIcon(
                RovstiIcons.Reorder,
                null,
                tint = RovstiTheme.colors.labelSecondary,
                size = FavoritesMetrics.HandleGlyph,
            )
        }
    }
}

@Composable
private fun favoriteMenu(
    id: Long,
    index: Int,
    count: Int,
    onAction: (FavoritesAction) -> Unit,
): ImmutableList<MenuItem> {
    val earlier = stringResource(R.string.favorites_move_earlier)
    val later = stringResource(R.string.favorites_move_later)
    val remove = stringResource(R.string.favorites_remove)
    return remember(id, index, count, onAction, earlier, later, remove) {
        val items = ArrayList<MenuItem>()
        if (index > 0) {
            items +=
                MenuItem.Action(earlier, RovstiIcons.ArrowUp) { onAction(FavoritesAction.MoveStep(index, up = true)) }
        }
        if (index < count - 1) {
            items +=
                MenuItem.Action(later, RovstiIcons.ArrowDown) { onAction(FavoritesAction.MoveStep(index, up = false)) }
        }
        if (items.isNotEmpty()) items += MenuItem.Separator
        items += MenuItem.Action(remove, RovstiIcons.Star, destructive = true) { onAction(FavoritesAction.Remove(id)) }
        persistentListOf<MenuItem>().addAll(items)
    }
}

/** The row: avatar, name, the one line under it, then [trailing]. */
@Composable
internal fun PersonRow(
    person: PersonItem,
    modifier: Modifier,
    onClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    actions: ImmutableList<CustomAction> = persistentListOf(),
) {
    ListRow(
        title = person.name,
        secondary = person.secondary,
        modifier = modifier,
        leading = {
            ContactAvatar(
                person.name,
                person.face,
                size = Sizes.avatar,
                colorKey = person.colorKey,
            )
        },
        trailing = trailing,
        onClick = onClick,
        actions = actions,
    )
}

@Composable
private fun FrequentRow(
    person: PersonItem,
    separated: Boolean,
    onAction: (FavoritesAction) -> Unit,
) {
    val add = stringResource(R.string.favorites_add_star)
    Column {
        if (separated) RowSeparator(inset = TextStartWithLeading)
        PersonRow(
            person,
            Modifier.testTag("frequent-row-${person.id}"),
            onClick = { onAction(FavoritesAction.Open(person.id)) },
            trailing = {
                Box(
                    Modifier
                        .size(Sizes.touch)
                        .semantics { contentDescription = add }
                        .clickable(role = Role.Button) { onAction(FavoritesAction.Star(person.id)) }
                        .testTag(FavTags.star(person.id)),
                    contentAlignment = Alignment.Center,
                ) {
                    RovstiIcon(
                        RovstiIcons.Star,
                        null,
                        tint = RovstiTheme.colors.accent,
                        size = FavoritesMetrics.StarGlyph,
                    )
                }
            },
        )
    }
}

/** One date: avatar, name, "In 4 days . Turns 36", and the Wish button when the contact has a number. */
@Composable
internal fun UpcomingRow(
    event: UpcomingItem,
    separated: Boolean,
    withDate: Boolean,
    onAction: (FavoritesAction) -> Unit,
) {
    val wish = stringResource(R.string.card_wish)
    Column {
        if (separated) RowSeparator(inset = TextStartWithLeading)
        ListRow(
            title = event.name,
            secondary = if (withDate) event.pageLine else event.sectionLine,
            modifier = Modifier.testTag(FavTags.upcomingRow(event.key)),
            leading = {
                ContactAvatar(
                    event.name,
                    event.face,
                    size = Sizes.avatar,
                    colorKey = event.contactId.toString(),
                )
            },
            trailing =
                if (event.canWish) {
                    {
                        CapsuleButton(
                            wish,
                            { onAction(FavoritesAction.Wish(event.contactId, event.kind, event.name)) },
                            Modifier.testTag(FavTags.wish(event.key)),
                        )
                    }
                } else {
                    null
                },
            onClick = { onAction(FavoritesAction.Open(event.contactId)) },
        )
    }
}

@Composable
private fun SeeAllRow(
    total: Int,
    onClick: () -> Unit,
) {
    Column {
        RowSeparator(inset = TextStartWithLeading)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = Spacing.gutter, vertical = Spacing.m)
                .testTag(FavTags.SeeAll),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            RovstiText(
                stringResource(R.string.favorites_see_all),
                Modifier.weight(1f),
                style = RovstiTheme.type.body,
                color = RovstiTheme.colors.accent,
            )
            RovstiText(total.toString(), style = RovstiTheme.type.body, color = RovstiTheme.colors.labelSecondary)
            ListRowChevron()
        }
    }
}
