package dev.rosty.contacts.emergency

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
import dev.rosty.contacts.R
import dev.rosty.contacts.favorites.DragHandle
import dev.rosty.contacts.favorites.FavoritesMetrics
import dev.rosty.contacts.favorites.LIFT_SCALE
import dev.rosty.contacts.favorites.PersonRow
import dev.rosty.contacts.favorites.favoriteKey
import dev.rosty.contacts.favorites.rememberFavoritesReorder
import dev.rosty.contacts.people.PersonItem
import dev.rosty.contacts.people.presentContactPicker
import dev.rosty.design.chrome.LargeTitleScaffold
import dev.rosty.design.chrome.LocalContentInsets
import dev.rosty.design.chrome.NavigationButton
import dev.rosty.design.chrome.ToolbarAction
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.EmptyState
import dev.rosty.design.haptics.LocalRostyHaptics
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.list.CustomAction
import dev.rosty.design.list.RowSeparator
import dev.rosty.design.list.TextStartWithLeading
import dev.rosty.design.motion.RostyMotion
import dev.rosty.design.overlay.ContextMenuStyle
import dev.rosty.design.overlay.LocalOverlayOrNull
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.longPressMenu
import dev.rosty.design.text.UiText
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
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
    val strContactsAddEmergency = stringResource(R.string.contacts_add_emergency)
    val strFavoritesAddConfirm = stringResource(R.string.favorites_add_confirm)
    val strFavoritesEdit = stringResource(R.string.favorites_edit)
    val strFavoritesDone = stringResource(R.string.favorites_done)
    val strSettingsEmergency = stringResource(R.string.settings_emergency)
    val ready = state.phase == EmergencyPhase.Ready
    val pickTitle = strContactsAddEmergency
    val onAdd: () -> Unit = {
        overlays?.presentContactPicker(
            pickTitle,
            strFavoritesAddConfirm,
            state.pickables,
            emptySet(),
        ) { ids ->
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
                                ToolbarAction(
                                    "add",
                                    RostyIcons.Plus,
                                    strFavoritesAddConfirm,
                                    onClick = onAdd,
                                )
                            }

                            EmergencyHeaderAction.Edit -> {
                                ToolbarAction("edit", RostyIcons.Pencil, strFavoritesEdit) {
                                    onAction(EmergencyAction.ToggleEdit)
                                }
                            }

                            EmergencyHeaderAction.Done -> {
                                ToolbarAction("done", RostyIcons.Checkmark, strFavoritesDone) {
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
        title = strSettingsEmergency,
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
    reorder: dev.rosty.contacts.favorites.FavoritesReorder,
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
                    icon = RostyIcons.MedicalCross,
                    title = stringResource(R.string.contacts_no_emergency),
                    message = stringResource(R.string.contacts_emergency_empty_message, state.max),
                    actionLabel = stringResource(R.string.contacts_add_contact),
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
        RostyText(
            stringResource(R.string.contacts_emergency_footer, state.max),
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter + Spacing.m, vertical = Spacing.m)
                .testTag(EmergencyTags.Footer),
            style = RostyTheme.type.footnote,
            color = RostyTheme.colors.labelSecondary,
        )
    }
    if (!state.editing) {
        item(key = "share", contentType = "share") {
            RostyText(
                stringResource(R.string.contacts_share_list),
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.touch)
                    .clickable(role = Role.Button) { onAction(EmergencyAction.Share) }
                    .padding(horizontal = Spacing.gutter + Spacing.m, vertical = Spacing.m)
                    .testTag(EmergencyTags.Share),
                style = RostyTheme.type.body,
                color = RostyTheme.colors.accent,
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
    reorder: dev.rosty.contacts.favorites.FavoritesReorder,
    overlays: OverlayController?,
    onAction: (EmergencyAction) -> Unit,
) {
    ReorderableItem(
        state = reorder.state,
        key = favoriteKey(person.id),
        animateItemModifier = Modifier.animateItem(placementSpec = RostyMotion.select(IntOffset.VisibilityThreshold)),
    ) { dragging ->
        val colors = RostyTheme.colors
        val haptics = LocalRostyHaptics.current
        val scale by animateFloatAsState(if (dragging) LIFT_SCALE else 1f, RostyMotion.select(1f), label = "lift")
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
        CapsuleButton(stringResource(R.string.emergency_call), onClick, Modifier.testTag(tag), icon = RostyIcons.Phone)
    } else {
        val colors = RostyTheme.colors
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
            RostyIcon(RostyIcons.Phone, null, tint = colors.accent, size = EmergencyMetrics.CallGlyph)
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
    val strFavoritesMoveEarlier = stringResource(R.string.favorites_move_earlier)
    val strFavoritesMoveLater = stringResource(R.string.favorites_move_later)
    val strEmergencyCall = stringResource(R.string.emergency_call)
    val strContactsRemoveFromEmergency = stringResource(R.string.contacts_remove_from_emergency)
    val earlier = strFavoritesMoveEarlier
    val later = strFavoritesMoveLater
    return remember(id, index, count, onAction, earlier, later) {
        val items = ArrayList<MenuItem>()
        items +=
            MenuItem.Action(
                strEmergencyCall,
                RostyIcons.Phone,
            ) { onAction(EmergencyAction.Call(id)) }
        if (index > 0) {
            items +=
                MenuItem.Action(earlier, RostyIcons.ArrowUp) { onAction(EmergencyAction.MoveStep(index, up = true)) }
        }
        if (index < count - 1) {
            items +=
                MenuItem.Action(later, RostyIcons.ArrowDown) { onAction(EmergencyAction.MoveStep(index, up = false)) }
        }
        items += MenuItem.Separator
        items +=
            MenuItem.Action(
                strContactsRemoveFromEmergency,
                RostyIcons.MedicalCross,
                destructive = true,
            ) {
                onAction(EmergencyAction.Remove(id))
            }
        persistentListOf<MenuItem>().addAll(items)
    }
}
