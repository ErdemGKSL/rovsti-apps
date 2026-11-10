package dev.rovsti.phone.favorites

import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import dev.rovsti.design.chrome.LargeTitleScaffold
import dev.rovsti.design.chrome.LocalContentInsets
import dev.rovsti.design.chrome.ToolbarAction
import dev.rovsti.design.controls.EmptyState
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.CustomAction
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ReorderList
import dev.rovsti.design.list.RowSeparator
import dev.rovsti.design.list.SectionHeader
import dev.rovsti.design.list.TextStartWithLeading
import dev.rovsti.design.list.rememberReorderList
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.overlay.ActionSheetAction
import dev.rovsti.design.overlay.ActionSheetEntry
import dev.rovsti.design.overlay.ContextMenuStyle
import dev.rovsti.design.overlay.LocalOverlayOrNull
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.longPressMenu
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.PhoneTags
import dev.rovsti.phone.R
import dev.rovsti.phone.RecentsMetrics
import dev.rovsti.phone.RootLinks
import dev.rovsti.phone.rootToolbar
import dev.rovsti.phone.setup.SetupUiState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem

/** Test tags of the Favorites tab. */
object FavTags {
    const val Empty = "favorites-empty"
    const val Frequent = "favorites-frequent"
    const val Gate = "favorites-gate"
    const val Picker = "favorites-picker"

    fun row(id: Long) = "favorite-row-$id"

    fun handle(id: Long) = "favorite-handle-$id"

    fun info(id: Long) = "favorite-info-$id"

    fun frequentRow(key: String) = "frequent-row-$key"

    fun frequentInfo(key: String) = "frequent-info-$key"

    fun pickRow(id: Long) = "favorite-pick-$id"
}

/** The list key of a favorite's row; [ReorderableItem] and the `item` of the list must use the same one. */
internal fun favoriteKey(person: FavPerson): String = person.key

/** The glyph of the info button in its touch target, and the lift of a dragged row. */
private const val LIFT_SCALE = 1.03f

/**
 * The Favorites tab (E5.5), stateless. The favorites in the user's order as list rows (a 40 dp avatar, the name, the
 * type of the number under it, an info button at the end; a tap calls, the info button opens the person sheet, a long
 * press opens Move Earlier, Move Later and Remove over the page), then Frequently Called. Edit mode swaps the info
 * button for a drag handle: the row follows the finger on the `select` spring and the neighbours make room. An empty
 * tab says so and offers Add Favorite.
 */
@Composable
fun FavoritesContent(
    ui: FavoritesUi,
    onAction: (FavoritesAction) -> Unit,
    onInfo: (FavPerson) -> Unit,
    onAdd: () -> Unit,
    links: RootLinks,
    setup: SetupUiState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    overlays: OverlayController? = LocalOverlayOrNull.current,
) {
    val ready = ui.phase == FavoritesPhase.Ready
    val actions = rememberFavoritesToolbar(ui, ready, onAction, onAdd, setup, links)
    val reorder =
        rememberReorderList(
            lazyListState = listState,
            shown = ui.favorites,
            keyOf = ::favoriteKey,
            scrollThresholdPadding = LocalContentInsets.current,
            onDropped = { from, to -> onAction(FavoritesAction.Move(from, to)) },
        )
    Box(modifier.testTag(PhoneTags.root("favorites"))) {
        LargeTitleScaffold(title = stringResource(R.string.tab_favorites), state = listState, actions = actions) {
            when (ui.phase) {
                FavoritesPhase.Loading -> Unit
                FavoritesPhase.NoPermission -> item(key = "gate", contentType = "gate") { Gate(links) }
                FavoritesPhase.Ready -> readyItems(ui, reorder, onAction, onInfo, onAdd, overlays)
            }
        }
    }
}

@Composable
private fun rememberFavoritesToolbar(
    ui: FavoritesUi,
    ready: Boolean,
    onAction: (FavoritesAction) -> Unit,
    onAdd: () -> Unit,
    setup: SetupUiState,
    links: RootLinks,
): ImmutableList<ToolbarAction> {
    val editLabel = stringResource(R.string.fav_edit)
    val doneLabel = stringResource(R.string.fav_done)
    val addLabel = stringResource(R.string.fav_add)
    val addTitle = stringResource(R.string.fav_add_title)
    val root = rootToolbar(setup, links)
    return remember(ready, ui.editing, ui.canEdit, root, onAction, onAdd, editLabel, doneLabel, addLabel, addTitle) {
        if (!ready) {
            root
        } else if (ui.editing) {
            persistentListOf(
                ToolbarAction("done", RovstiIcons.Checkmark, doneLabel) { onAction(FavoritesAction.ToggleEdit) },
            ).addAll(root)
        } else if (ui.canEdit) {
            // One "+" button with a menu keeps the bar at two buttons beside the gear.
            val menu =
                persistentListOf<MenuItem>(
                    MenuItem.Action(addTitle, RovstiIcons.Plus, onClick = onAdd),
                    MenuItem.Action(editLabel, RovstiIcons.Pencil) { onAction(FavoritesAction.ToggleEdit) },
                )
            persistentListOf(ToolbarAction("add", RovstiIcons.Plus, addLabel, menu, onAdd)).addAll(root)
        } else {
            persistentListOf(ToolbarAction("add", RovstiIcons.Plus, addLabel, onClick = onAdd)).addAll(root)
        }
    }
}

private fun LazyListScope.readyItems(
    ui: FavoritesUi,
    reorder: ReorderList<FavPerson>,
    onAction: (FavoritesAction) -> Unit,
    onInfo: (FavPerson) -> Unit,
    onAdd: () -> Unit,
    overlays: OverlayController?,
) {
    if (ui.isEmpty) {
        item(key = "empty", contentType = "empty") { EmptyBlock(onAdd) }
        return
    }
    val rows = reorder.items
    rows.forEachIndexed { index, person ->
        item(key = favoriteKey(person), contentType = "favorite") {
            FavoriteRow(person, index, rows.size, ui.editing, reorder, overlays, onAction, onInfo)
        }
    }
    if (ui.frequent.isNotEmpty()) {
        item(key = "frequent-title", contentType = "header") {
            SectionHeader(stringResource(R.string.fav_frequent), Modifier.testTag(FavTags.Frequent))
        }
        ui.frequent.forEachIndexed { index, person ->
            item(key = "freq-${person.key}", contentType = "frequent") {
                FrequentRow(person, separated = index > 0, overlays, onAction, onInfo)
            }
        }
    }
}

@Composable
private fun Gate(links: RootLinks) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = Spacing.xl).testTag(FavTags.Gate),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            icon = RovstiIcons.People,
            title = stringResource(R.string.people_gate_title),
            message = stringResource(R.string.people_gate_message),
            actionLabel = stringResource(R.string.setup_open),
            onAction = links.openSetup,
        )
    }
}

@Composable
private fun EmptyBlock(onAdd: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = Spacing.xl).testTag(FavTags.Empty),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            icon = RovstiIcons.Star,
            title = stringResource(R.string.fav_empty_title),
            message = stringResource(R.string.fav_empty_message),
            actionLabel = stringResource(R.string.fav_add_title),
            onAction = onAdd,
        )
    }
}

@Composable
private fun LazyItemScope.FavoriteRow(
    person: FavPerson,
    index: Int,
    count: Int,
    editing: Boolean,
    reorder: ReorderList<FavPerson>,
    overlays: OverlayController?,
    onAction: (FavoritesAction) -> Unit,
    onInfo: (FavPerson) -> Unit,
) {
    val id = person.contactId ?: return
    ReorderableItem(
        state = reorder.state,
        key = favoriteKey(person),
        animateItemModifier = Modifier.animateItem(placementSpec = RovstiMotion.select(IntOffset.VisibilityThreshold)),
    ) { dragging ->
        val colors = RovstiTheme.colors
        val haptics = LocalRovstiHaptics.current
        val scale by animateFloatAsState(if (dragging) LIFT_SCALE else 1f, RovstiMotion.select(1f), label = "lift")
        val lift = with(LocalDensity.current) { LiftShadow.toPx() }
        val call = remember(person.number, onAction) { person.number?.let { { onAction(FavoritesAction.Call(it)) } } }
        val menu = favoriteMenu(id, index, count, onAction)
        Column(
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = if (dragging) lift else 0f
                }.then(if (dragging) Modifier.background(colors.page) else Modifier),
        ) {
            if (index > 0 && !dragging) RowSeparator(inset = TextStartWithLeading)
            val body: @Composable () -> Unit = {
                PersonListRow(
                    person,
                    Modifier.testTag(FavTags.row(id)),
                    onClick = if (editing || overlays != null) null else call,
                    actions = if (editing) stepActions(id, index, count, onAction) else persistentListOf(),
                    trailing = {
                        if (editing) {
                            DragHandle(id, this@ReorderableItem, haptics = { haptics.tick() })
                        } else {
                            InfoButton(person, FavTags.info(id), onInfo)
                        }
                    },
                )
            }
            if (!editing && overlays != null) {
                Box(
                    Modifier.longPressMenu(
                        controller = overlays,
                        items = menu,
                        style = ContextMenuStyle.Lifted(preview = { PersonListRow(person, Modifier, null, null) }),
                        onTap = call ?: {},
                    ),
                ) { body() }
            } else {
                body()
            }
        }
    }
}

private val LiftShadow = Spacing.s

/** The drag has a spoken twin: a screen reader moves the row one place at a time. */
@Composable
private fun stepActions(
    id: Long,
    index: Int,
    count: Int,
    onAction: (FavoritesAction) -> Unit,
): ImmutableList<CustomAction> {
    val earlier = stringResource(R.string.fav_move_earlier)
    val later = stringResource(R.string.fav_move_later)
    return remember(id, index, count, onAction, earlier, later) {
        val list = ArrayList<CustomAction>()
        if (index > 0) list += CustomAction(earlier) { onAction(FavoritesAction.MoveStep(index, up = true)) }
        if (index < count - 1) list += CustomAction(later) { onAction(FavoritesAction.MoveStep(index, up = false)) }
        list.toImmutableList()
    }
}

@Composable
internal fun DragHandle(
    id: Long,
    scope: ReorderableCollectionItemScope,
    haptics: () -> Unit,
) {
    val label = stringResource(R.string.fav_reorder)
    with(scope) {
        Box(
            Modifier
                .size(Sizes.touch)
                .semantics { contentDescription = label }
                .draggableHandle(onDragStarted = { haptics() })
                .testTag(FavTags.handle(id)),
            contentAlignment = Alignment.Center,
        ) {
            RovstiIcon(
                RovstiIcons.Reorder,
                null,
                tint = RovstiTheme.colors.labelSecondary,
                size = RecentsMetrics.InfoGlyph,
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
    val earlier = stringResource(R.string.fav_move_earlier)
    val later = stringResource(R.string.fav_move_later)
    val remove = stringResource(R.string.fav_remove)
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
        items.toImmutableList()
    }
}

/** The row: avatar, name, the type of the number under it, then [trailing]. */
@Composable
internal fun PersonListRow(
    person: FavPerson,
    modifier: Modifier,
    onClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    actions: ImmutableList<CustomAction> = persistentListOf(),
) {
    ListRow(
        title = person.name,
        secondary = person.label,
        modifier = modifier,
        leading = {
            Avatar(person.name, size = Sizes.avatar, photoModel = person.photoUri, colorKey = person.colorKey)
        },
        trailing = trailing,
        onClick = onClick,
        actions = actions,
    )
}

/** The info button at the end of a row: opens the person sheet. */
@Composable
internal fun InfoButton(
    person: FavPerson,
    tag: String,
    onInfo: (FavPerson) -> Unit,
) {
    val label = stringResource(R.string.fav_info)
    Box(
        Modifier
            .size(Sizes.touch)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button) { onInfo(person) }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        RovstiIcon(RovstiIcons.Info, null, tint = RovstiTheme.colors.accent, size = RecentsMetrics.InfoGlyph)
    }
}

@Composable
private fun FrequentRow(
    person: FavPerson,
    separated: Boolean,
    overlays: OverlayController?,
    onAction: (FavoritesAction) -> Unit,
    onInfo: (FavPerson) -> Unit,
) {
    val addLabel = stringResource(R.string.fav_add_title)
    val call = remember(person.number, onAction) { person.number?.let { { onAction(FavoritesAction.Call(it)) } } }
    val id = person.contactId
    val menu =
        remember(id, addLabel, onAction) {
            if (id == null) {
                persistentListOf()
            } else {
                persistentListOf<MenuItem>(
                    MenuItem.Action(addLabel, RovstiIcons.Star) { onAction(FavoritesAction.Add(id)) },
                )
            }
        }
    Column {
        if (separated) RowSeparator(inset = TextStartWithLeading)
        val row: @Composable (Modifier) -> Unit = { m ->
            PersonListRow(
                person,
                m.testTag(FavTags.frequentRow(person.key)),
                onClick = if (overlays != null && menu.isNotEmpty()) null else call,
                trailing = { InfoButton(person, FavTags.frequentInfo(person.key), onInfo) },
            )
        }
        if (overlays != null && menu.isNotEmpty()) {
            Box(
                Modifier.longPressMenu(
                    controller = overlays,
                    items = menu,
                    style = ContextMenuStyle.Lifted(preview = { PersonListRow(person, Modifier, null, null) }),
                    onTap = call ?: {},
                ),
            ) { row(Modifier) }
        } else {
            row(Modifier)
        }
    }
}

/** The Favorites tab wired to its model. */
@Composable
fun FavoritesScreen(
    model: FavoritesViewModel,
    links: RootLinks,
    setup: SetupUiState,
    onInfo: (FavPerson) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by model.state.collectAsState()
    val listState = rememberLazyListState()
    val overlays = LocalOverlayOrNull.current
    FavoritesEffects(model)
    val title = stringResource(R.string.fav_add_title)
    val latest = rememberUpdatedState(ui)
    val onAdd: () -> Unit =
        remember(overlays, model, title) {
            { overlays?.let { presentFavoritePicker(it, title, latest.value.pickables, model::onAction) } }
        }
    FavoritesContent(
        ui = ui,
        onAction = model::onAction,
        onInfo = onInfo,
        onAdd = onAdd,
        links = links,
        setup = setup,
        listState = listState,
        modifier = modifier,
    )
}

/** Shows what the model says once: the toasts and the SIM chooser. */
@Composable
fun FavoritesEffects(model: FavoritesViewModel) {
    val overlays = LocalOverlayOrNull.current ?: return
    val haptics = LocalRovstiHaptics.current
    val added = stringResource(R.string.fav_added)
    val removed = stringResource(R.string.fav_removed)
    val failed = stringResource(R.string.fav_failed)
    LaunchedEffect(model, overlays, added, removed, failed) {
        model.effect.collect { effect ->
            when (effect) {
                FavoritesEffect.Added -> {
                    overlays.showToast(added)
                }

                FavoritesEffect.Removed -> {
                    overlays.showToast(removed)
                }

                FavoritesEffect.Failed -> {
                    haptics.reject()
                    overlays.showToast(failed)
                }

                is FavoritesEffect.Core -> {
                    overlays.showToast(effect.text)
                }

                is FavoritesEffect.ChooseSim -> {
                    overlays.present(
                        ActionSheetEntry(
                            title = effect.number,
                            actions =
                                effect.choices
                                    .map { sim ->
                                        ActionSheetAction(sim.title) {
                                            model.onAction(FavoritesAction.CallOn(effect.number, sim.key))
                                        }
                                    }.toImmutableList(),
                        ),
                    )
                }
            }
        }
    }
}
