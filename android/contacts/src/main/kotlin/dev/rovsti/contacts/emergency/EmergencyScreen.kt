package dev.rovsti.contacts.emergency

import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
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
import dev.rovsti.contacts.favorites.DragHandle
import dev.rovsti.contacts.favorites.FavoritesMetrics
import dev.rovsti.contacts.favorites.LIFT_SCALE
import dev.rovsti.contacts.favorites.PersonRow
import dev.rovsti.contacts.favorites.favoriteKey
import dev.rovsti.contacts.favorites.rememberFavoritesReorder
import dev.rovsti.contacts.people.PersonItem
import dev.rovsti.contacts.people.presentContactPicker
import dev.rovsti.design.chrome.LargeTitleScaffold
import dev.rovsti.design.chrome.LocalContentInsets
import dev.rovsti.design.chrome.NavigationButton
import dev.rovsti.design.chrome.ToolbarAction
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.controls.EmptyState
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.CustomAction
import dev.rovsti.design.list.RowSeparator
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
import sh.calvin.reorderable.ReorderableItem

/** Test tags of the Emergency Contacts page. */
object EmergencyTags {
    const val Screen = "emergency-screen"
    const val Empty = "emergency-empty"
    const val Footer = "emergency-footer"
    const val Share = "emergency-share"

    fun row(id: Long) = "emergency-row-$id"

    fun call(id: Long) = "emergency-call-$id"

    fun handle(id: Long) = "emergency-handle-$id"
}

/**
 * The Emergency Contacts page (E4.14, C43), stateless: up to five people in the order the user chose, each with a Call
 * button. Edit shows a drag handle on every row (the row follows the finger on the `select` spring, the same port of
 * Convx's reorderable list as the favorites); a long press opens Move Earlier, Move Later and Remove over the page.
 * With nobody on the list the page is an [EmptyState] that offers "Add Contact".
 */
@Composable
fun EmergencyScreen(
    state: EmergencyUiState,
    onAction: (EmergencyAction) -> Unit,
    onBack: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    overlays: OverlayController? = LocalOverlayOrNull.current,
) {
    val ready = state.phase == EmergencyPhase.Ready
    val pickTitle = "Add Emergency Contacts"
    val onAdd: () -> Unit = {
        overlays?.presentContactPicker(pickTitle, "Add", state.pickables, emptySet()) { ids ->
            onAction(EmergencyAction.AddAll(ids))
        }
    }
    val header = state.header
    val actions =
        remember(ready, header, onAction, onAdd) {
            if (!ready) {
                persistentListOf()
            } else {
                persistentListOf<ToolbarAction>().addAll(
                    header.map { button ->
                        when (button) {
                            EmergencyHeaderAction.Add -> {
                                ToolbarAction("add", RovstiIcons.Plus, "Add", onClick = onAdd)
                            }

                            EmergencyHeaderAction.Edit -> {
                                ToolbarAction("edit", RovstiIcons.Pencil, "Edit") {
                                    onAction(EmergencyAction.ToggleEdit)
                                }
                            }

                            EmergencyHeaderAction.Done -> {
                                ToolbarAction("done", RovstiIcons.Checkmark, "Done") {
                                    onAction(EmergencyAction.ToggleEdit)
                                }
                            }
                        }
                    },
                )
            }
        }
    val reorder =
        rememberFavoritesReorder(
            lazyListState = listState,
            shown = state.people,
            scrollThresholdPadding = LocalContentInsets.current,
            onDropped = { from, to -> onAction(EmergencyAction.Move(from, to)) },
        )
    LargeTitleScaffold(
        title = "Emergency Contacts",
        state = listState,
        actions = actions,
        navigation = NavigationButton(onClick = onBack),
        modifier = modifier.testTag(EmergencyTags.Screen),
    ) {
        if (ready) readyItems(state, reorder.items, reorder, onAction, onAdd, overlays)
    }
}

private fun LazyListScope.readyItems(
    state: EmergencyUiState,
    rows: List<PersonItem>,
    reorder: dev.rovsti.contacts.favorites.FavoritesReorder,
    onAction: (EmergencyAction) -> Unit,
    onAdd: () -> Unit,
    overlays: OverlayController?,
) {
    if (state.people.isEmpty()) {
        item(key = "empty", contentType = "empty") {
            val bottom = LocalContentInsets.current.calculateBottomPadding()
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = EmergencyMetrics.EmptyHeight)
                    .padding(bottom = bottom)
                    .testTag(EmergencyTags.Empty),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = RovstiIcons.MedicalCross,
                    title = "No Emergency Contacts",
                    message = "Add up to ${state.max} people to reach first. They stay in the order you choose.",
                    actionLabel = "Add Contact",
                    onAction = onAdd,
                )
            }
        }
        return
    }
    rows.forEachIndexed { index, person ->
        item(key = favoriteKey(person.id), contentType = "emergency") {
            EmergencyRow(person, index, rows.size, state.editing, reorder, overlays, onAction)
        }
    }
    item(key = "footer", contentType = "footer") {
        RovstiText(
            "Up to ${state.max} people, in the order you choose.",
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter + Spacing.m, vertical = Spacing.m)
                .testTag(EmergencyTags.Footer),
            style = RovstiTheme.type.footnote,
            color = RovstiTheme.colors.labelSecondary,
        )
    }
    if (!state.editing) {
        item(key = "share", contentType = "share") {
            RovstiText(
                "Share List",
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.touch)
                    .clickable(role = Role.Button) { onAction(EmergencyAction.Share) }
                    .padding(horizontal = Spacing.gutter + Spacing.m, vertical = Spacing.m)
                    .testTag(EmergencyTags.Share),
                style = RovstiTheme.type.body,
                color = RovstiTheme.colors.accent,
            )
        }
    }
}

@Composable
private fun LazyItemScope.EmergencyRow(
    person: PersonItem,
    index: Int,
    count: Int,
    editing: Boolean,
    reorder: dev.rovsti.contacts.favorites.FavoritesReorder,
    overlays: OverlayController?,
    onAction: (EmergencyAction) -> Unit,
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
        val open = remember(person.id, onAction) { { onAction(EmergencyAction.Open(person.id)) } }
        val menu = rowMenu(person.id, index, count, onAction)
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
                    m.testTag(EmergencyTags.row(person.id)),
                    onClick = if (editing || overlays != null) null else open,
                    actions = stepActions(person.id, index, count, editing, onAction),
                    trailing = {
                        if (editing) {
                            DragHandle(
                                person.id,
                                this@ReorderableItem,
                                haptics = { haptics.tick() },
                                tag = EmergencyTags.handle(person.id),
                            )
                        } else {
                            CallButton({ onAction(EmergencyAction.Call(person.id)) }, EmergencyTags.call(person.id))
                        }
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

/**
 * The Call button: icon and word, or the icon alone in a round 48 dp target once the text is scaled past 1.3x, so the
 * number beside it is never cut off at large font sizes.
 */
@Composable
private fun CallButton(
    onClick: () -> Unit,
    tag: String,
) {
    if (LocalDensity.current.fontScale <= LARGE_TEXT) {
        CapsuleButton("Call", onClick, Modifier.testTag(tag), icon = RovstiIcons.Phone)
    } else {
        val colors = RovstiTheme.colors
        val label = stringResource(R.string.emergency_call)
        Box(
            Modifier
                .size(Sizes.touch)
                .background(colors.fillTertiary, CircleShape)
                .semantics { contentDescription = label }
                .clickable(role = Role.Button, onClick = onClick)
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            RovstiIcon(RovstiIcons.Phone, null, tint = colors.accent, size = EmergencyMetrics.CallGlyph)
        }
    }
}

private const val LARGE_TEXT = 1.3f

/** The drag has a spoken twin: a screen reader moves the row one place at a time. */
@Composable
private fun stepActions(
    id: Long,
    index: Int,
    count: Int,
    editing: Boolean,
    onAction: (EmergencyAction) -> Unit,
): ImmutableList<CustomAction> {
    val earlier = stringResource(R.string.favorites_move_earlier)
    val later = stringResource(R.string.favorites_move_later)
    return remember(id, index, count, editing, onAction, earlier, later) {
        val list = ArrayList<CustomAction>()
        if (editing) {
            if (index > 0) list += CustomAction(earlier) { onAction(EmergencyAction.MoveStep(index, up = true)) }
            if (index < count - 1) list += CustomAction(later) { onAction(EmergencyAction.MoveStep(index, up = false)) }
        }
        persistentListOf<CustomAction>().addAll(list)
    }
}

@Composable
private fun rowMenu(
    id: Long,
    index: Int,
    count: Int,
    onAction: (EmergencyAction) -> Unit,
): ImmutableList<MenuItem> {
    val earlier = stringResource(R.string.favorites_move_earlier)
    val later = stringResource(R.string.favorites_move_later)
    return remember(id, index, count, onAction, earlier, later) {
        val items = ArrayList<MenuItem>()
        items += MenuItem.Action("Call", RovstiIcons.Phone) { onAction(EmergencyAction.Call(id)) }
        if (index > 0) {
            items +=
                MenuItem.Action(earlier, RovstiIcons.ArrowUp) { onAction(EmergencyAction.MoveStep(index, up = true)) }
        }
        if (index < count - 1) {
            items +=
                MenuItem.Action(later, RovstiIcons.ArrowDown) { onAction(EmergencyAction.MoveStep(index, up = false)) }
        }
        items += MenuItem.Separator
        items +=
            MenuItem.Action("Remove from Emergency Contacts", RovstiIcons.MedicalCross, destructive = true) {
                onAction(EmergencyAction.Remove(id))
            }
        persistentListOf<MenuItem>().addAll(items)
    }
}
