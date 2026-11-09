package dev.rovsti.contacts.list

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rovsti.core.model.contacts.QuickAction
import dev.rovsti.design.chrome.LargeTitleScaffold
import dev.rovsti.design.chrome.ToolbarAction
import dev.rovsti.design.chrome.ToolbarCapsule
import dev.rovsti.design.chrome.ToolbarTextButton
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.controls.CapsuleStyle
import dev.rovsti.design.controls.EmptyState
import dev.rovsti.design.controls.MessageSource
import dev.rovsti.design.controls.SelectCheck
import dev.rovsti.design.controls.SelectCheckSize
import dev.rovsti.design.controls.SourceGlyph
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.CustomAction
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ListRowChevron
import dev.rovsti.design.list.ListScrollRail
import dev.rovsti.design.list.ListScrollRailWidth
import dev.rovsti.design.list.RowSeparator
import dev.rovsti.design.list.SectionHeader
import dev.rovsti.design.list.TextStartWithLeading
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.overlay.ContextMenuStyle
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.LocalOverlayOrNull
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.longPressMenu
import dev.rovsti.design.overlay.sheetScrollConnection
import dev.rovsti.design.text.SearchField
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import dev.rovsti.design.transition.rovstiSharedBounds
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Test tags of the list. */
object ListTags {
    const val Search = "contacts-search"
    const val Empty = "contacts-empty-state"
    const val NoResults = "contacts-no-results"
    const val CreateFromSearch = "contacts-create-from-search"
    const val AddFromSearch = "contacts-add-from-search"
    const val Viewed = "contacts-viewed"
    const val ViewedClear = "contacts-viewed-clear"
    const val MyCard = "contacts-my-card"
    const val TopLinks = "contacts-top-links"
    const val EmergencyRow = "contacts-emergency-row"
    const val RecentRow = "contacts-recent-row"
    const val DeepTitle = "contacts-deep-title"
    const val WaGlyph = "contact-wa-glyph"
    const val Star = "contact-star"
    const val Pickables = "contacts-pickables"

    fun row(id: Long) = "contact-row-$id"

    fun header(letter: String) = "contact-header-$letter"
}

/**
 * Test seam: called from a side effect of every row that composes. Null in the app; the recomposition test counts
 * with it (a row whose data did not change must not run again while the query changes).
 */
internal object ListProbe {
    @Volatile var onRowComposed: ((Long) -> Unit)? = null
}

/**
 * The Contacts tab (E4.2), stateless: the large title with the Select and New buttons, the search field, "My Card",
 * "Recently viewed", the sections with their letter headers, the "In details" results, the empty states, and the
 * alphabet rail. Rows are 40 dp avatars with ONE secondary line, a star for favorites and the 14 dp WhatsApp glyph once
 * for a contact that has a chat; a long press lifts the row and opens the action menu over the page (nothing in the
 * list moves). [gate] draws the permission row while the book may not be read.
 */
@Composable
fun ContactsListScreen(
    state: ContactsListUiState,
    onAction: (ContactsListAction) -> Unit,
    listState: LazyListState,
    searchState: TextFieldState,
    modifier: Modifier = Modifier,
    gate: (@Composable () -> Unit)? = null,
    overlays: OverlayController? = LocalOverlayOrNull.current,
    onPasteContact: () -> Unit = {},
    onScanQr: () -> Unit = {},
) {
    val latestAction = rememberUpdatedState(onAction)
    LaunchedEffect(searchState) {
        snapshotFlow { searchState.text.toString() }.collect { latestAction.value(ContactsListAction.QueryChanged(it)) }
    }
    val ready = state.phase == ListPhase.Ready
    val select = state.selectMode
    // Back leaves select mode first (this handler is the innermost, so it runs before the tab or the page pops).
    BackHandler(enabled = select != null) { onAction(ContactsListAction.ExitSelect) }
    val actions = rememberToolbar(ready, state.rows.isNotEmpty(), onAction, onPasteContact, onScanQr)
    val searching = state.query.isNotBlank()
    val railShown = ready && state.showScrubber && !searching
    val endInset = if (railShown) ListScrollRailWidth else Dp(0f)
    val onPick: () -> Unit = {
        showAddPicker(overlays, state.pickables, onAction)
    }
    Box(modifier.testTag("contacts-list-screen")) {
        LargeTitleScaffold(
            title = select?.title ?: "Contacts",
            state = listState,
            actions = actions,
            modifier = Modifier.testTag("contacts-list-page"),
            actionsContent = select?.let { { SelectTitleButtons(it, onAction) } },
        ) {
            item(key = "search", contentType = "search") {
                SearchField(
                    state = searchState,
                    modifier =
                        Modifier
                            .padding(
                                start = Spacing.gutter,
                                end = Spacing.gutter + endInset,
                                top = Spacing.s,
                                bottom = Spacing.s,
                            ).testTag(ListTags.Search),
                )
            }
            when (state.phase) {
                ListPhase.Loading -> Unit
                ListPhase.NoPermission -> item(key = "gate", contentType = "gate") { gate?.invoke() }
                ListPhase.Ready -> readyItems(state, endInset, overlays, onAction, onPick)
            }
        }
        if (railShown) {
            ListScrollRail(
                lazyListState = listState,
                itemCount = state.scrubItems,
                sectionIndexMap = state.sectionIndex,
                sections = state.scrubberLetters,
            )
        }
    }
}

@Composable
private fun rememberToolbar(
    ready: Boolean,
    hasRows: Boolean,
    onAction: (ContactsListAction) -> Unit,
    onPasteContact: () -> Unit,
    onScanQr: () -> Unit,
): ImmutableList<ToolbarAction> =
    remember(ready, hasRows, onAction, onPasteContact, onScanQr) {
        if (!ready) {
            persistentListOf()
        } else {
            // "+" opens a menu over the page: New Contact, then the ways to bring one in (E4.10).
            val menu =
                persistentListOf<MenuItem>(
                    MenuItem.Action("New Contact", RovstiIcons.PersonAdd) { onAction(ContactsListAction.NewContact) },
                    MenuItem.Separator,
                    MenuItem.Action("Paste Contact", RovstiIcons.Copy, onClick = onPasteContact),
                    MenuItem.Action("Scan QR Code", RovstiIcons.QrCode, onClick = onScanQr),
                )
            val add =
                ToolbarAction("add", RovstiIcons.Plus, "New contact", menu) { onAction(ContactsListAction.NewContact) }
            if (hasRows) {
                persistentListOf(
                    ToolbarAction("select", RovstiIcons.Checkmark, "Select") {
                        onAction(ContactsListAction.EnterSelect(null))
                    },
                    add,
                )
            } else {
                persistentListOf(add)
            }
        }
    }

private fun LazyListScope.readyItems(
    state: ContactsListUiState,
    endInset: Dp,
    overlays: OverlayController?,
    onAction: (ContactsListAction) -> Unit,
    onPick: () -> Unit,
) {
    val searching = state.query.isNotBlank()
    val select = state.selectMode
    if (!searching && select == null) {
        state.myCard?.let { card ->
            item(key = "my-card", contentType = "group") { MyCardSection(card, endInset, onAction) }
        }
        if (state.emergencyDetail != null || state.recentEntry != null) {
            item(key = "top-links", contentType = "group") { TopLinksSection(state, endInset, onAction) }
        }
        if (state.viewed.isNotEmpty()) {
            item(key = "viewed", contentType = "group") { ViewedSection(state, endInset, overlays, onAction) }
        }
    }
    if (state.emptyKind != EmptyKind.None) {
        item(key = "empty", contentType = "empty") { EmptyBlock(state, onAction, onPick) }
    }
    var first = true
    for (row in state.rows) {
        if (row.header) {
            item(key = "h-${row.letter}", contentType = "header") {
                SectionHeader(row.letter, Modifier.testTag(ListTags.header(row.letter)))
            }
        }
        val separated = !first && !row.header
        first = false
        item(key = row.id, contentType = "contact") {
            ContactRowItem(
                row,
                state.quick[row.menuKey],
                separated,
                endInset,
                overlays,
                onAction,
                checked = select?.let { row.id in it.selected },
            )
        }
    }
    if (state.deepRows.isNotEmpty()) {
        item(key = "deep-title", contentType = "header") {
            SectionHeader(state.deepTitle, Modifier.testTag(ListTags.DeepTitle))
        }
        var firstDeep = true
        for (row in state.deepRows) {
            val separated = !firstDeep
            firstDeep = false
            item(key = "deep-${row.id}", contentType = "contact") {
                ContactRowItem(
                    row,
                    state.quick[row.menuKey],
                    separated,
                    endInset,
                    overlays,
                    onAction,
                    checked = select?.let { row.id in it.selected },
                )
            }
        }
    }
}

@Composable
private fun ContactRowItem(
    item: ContactItem,
    quick: ImmutableList<QuickAction>?,
    separated: Boolean,
    endInset: Dp,
    overlays: OverlayController?,
    onAction: (ContactsListAction) -> Unit,
    // Null: not selecting. Otherwise whether this row is selected; a tap toggles it and the long press is off.
    checked: Boolean? = null,
) {
    SideEffect { ListProbe.onRowComposed?.invoke(item.id) }
    val menu = remember(item.id, quick, onAction) { menuFor(item.id, quick, onAction) }
    val open = remember(item.id, onAction) { { onAction(ContactsListAction.Open(item.id)) } }
    val rowActions = remember(menu) { accessibilityActionsOf(menu) }
    val toggle = remember(item.id, onAction) { { onAction(ContactsListAction.ToggleSelected(item.id)) } }
    val slot by animateDpAsState(
        targetValue = if (checked != null) SelectSlotWidth else SelectMetrics.None,
        animationSpec = RovstiMotion.select(Dp.VisibilityThreshold),
        label = "select-slot",
    )
    Column {
        // Stops a gap before the rail column so the hairline never runs toward the letters.
        if (separated) {
            RowSeparator(
                Modifier.padding(end = if (endInset > Dp(0f)) endInset + Spacing.s else endInset),
                inset = TextStartWithLeading + slot,
            )
        }
        val body =
            Modifier.testTag(ListTags.row(item.id)).semantics {
                if (checked != null) selected = checked
            }
        if (checked != null) {
            Box(body) { RowBody(item, endInset, toggle, shared = true, slot = slot, checked = checked) }
        } else if (overlays != null && menu.isNotEmpty()) {
            Box(
                body.longPressMenu(
                    controller = overlays,
                    items = menu,
                    style = ContextMenuStyle.Lifted(preview = { RowBody(item, endInset, null) }),
                    onTap = open,
                ),
            ) {
                RowBody(item, endInset, null, shared = true, slot = slot, actions = rowActions)
            }
        } else {
            Box(body) { RowBody(item, endInset, open, shared = true, slot = slot, actions = rowActions) }
        }
    }
}

/** Width of the select circle's slot: the gutter and the circle; the row's own gutter is the gap after it. */
private val SelectSlotWidth = Spacing.gutter + SelectCheckSize

/** The row itself: avatar, name, one secondary line, then the star and the WhatsApp glyph. */
@Composable
private fun RowBody(
    item: ContactItem,
    endInset: Dp,
    onClick: (() -> Unit)?,
    avatarSize: Dp = Sizes.avatar,
    // The avatar of a list row is the source of the shared flight to the card (`avatar-<id>`, E4.3); the lifted copy of
    // the context menu and the "Recently viewed" strip are not, so the key is never on two nodes at once.
    shared: Boolean = false,
    // The animated width of the select slot (0 while not selecting) and whether the row is selected.
    slot: Dp = SelectMetrics.None,
    checked: Boolean? = null,
    actions: ImmutableList<CustomAction> = persistentListOf(),
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (slot > SelectMetrics.None) {
            Box(Modifier.width(slot).clipToBounds(), contentAlignment = Alignment.CenterStart) {
                SelectCheck(checked == true, Modifier.padding(start = Spacing.gutter).requiredWidth(SelectCheckSize))
            }
        }
        Box(Modifier.weight(1f)) { RowListRow(item, endInset, onClick, avatarSize, shared, actions) }
    }
}

@Composable
private fun RowListRow(
    item: ContactItem,
    endInset: Dp,
    onClick: (() -> Unit)?,
    avatarSize: Dp,
    shared: Boolean,
    actions: ImmutableList<CustomAction>,
) {
    ListRow(
        actions = actions,
        title = item.name,
        secondary = item.secondary,
        leading = {
            Avatar(
                item.name,
                modifier = if (shared) Modifier.rovstiSharedBounds("avatar-${item.id}") else Modifier,
                size = avatarSize,
                photoModel = item.photoUri,
                colorKey = item.id.toString(),
            )
        },
        trailing = if (item.starred || item.whatsapp) ({ Marks(item) }) else null,
        onClick = onClick,
        endInset = endInset,
    )
}

@Composable
private fun Marks(item: ContactItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        if (item.starred) {
            RovstiIcon(
                RovstiIcons.Star,
                null,
                Modifier.testTag(ListTags.Star),
                tint = RovstiTheme.colors.labelSecondary,
                size = Sizes.sourceGlyph,
            )
        }
        if (item.whatsapp) {
            SourceGlyph(MessageSource.WhatsApp, Modifier.testTag(ListTags.WaGlyph))
        }
    }
}

private fun iconOf(id: String): ImageVector? =
    when (id) {
        "call" -> RovstiIcons.Phone
        "message" -> RovstiIcons.Message
        "whatsapp" -> RovstiIcons.WhatsApp
        "share" -> RovstiIcons.Share
        "copy_number" -> RovstiIcons.Copy
        "add_to_home" -> RovstiIcons.AddToHome
        "star", "unstar" -> RovstiIcons.Star
        else -> null
    }

/** The row menu: the quick actions of `contacts.quick`, then "Select". */
private fun menuFor(
    id: Long,
    quick: ImmutableList<QuickAction>?,
    onAction: (ContactsListAction) -> Unit,
): ImmutableList<MenuItem> {
    if (quick == null) return persistentListOf()
    val items = ArrayList<MenuItem>()
    for (action in quick) {
        items +=
            MenuItem.Action(
                action.label,
                iconOf(action.id),
                onClick = { onAction(ContactsListAction.Quick(id, action.id)) },
            )
    }
    items += MenuItem.Separator
    items +=
        MenuItem.Action("Select", RovstiIcons.Checkmark, onClick = { onAction(ContactsListAction.EnterSelect(id)) })
    return kotlinx.collections.immutable
        .persistentListOf<MenuItem>()
        .addAll(items)
}

/** The row menu mirrored as TalkBack custom actions (the long press itself is not reachable by a screen reader). */
internal fun accessibilityActionsOf(menu: ImmutableList<MenuItem>): ImmutableList<CustomAction> =
    menu
        .filterIsInstance<MenuItem.Action>()
        .filter { it.enabled }
        .map { CustomAction(it.label, it.onClick) }
        .toImmutableList()

@Composable
private fun MyCardSection(
    card: MyCardItem,
    endInset: Dp,
    onAction: (ContactsListAction) -> Unit,
) {
    Box(Modifier.padding(bottom = Spacing.s, end = endInset).testTag(ListTags.MyCard)) {
        InsetGroupedSection {
            row {
                ListRow(
                    title = card.title,
                    secondary = card.detail,
                    leading = {
                        if (card.contactId != null) {
                            Avatar(card.detail, size = Sizes.avatar, colorKey = card.contactId.toString())
                        } else {
                            TileIcon(RovstiIcons.Person)
                        }
                    },
                    trailing = { ListRowChevron() },
                    onClick = { onAction(ContactsListAction.OpenMyCard) },
                )
            }
        }
    }
}

/** "Emergency Contacts" and the "Recent" row (Recently Added / Edited) in one group under My Card. */
@Composable
private fun TopLinksSection(
    state: ContactsListUiState,
    endInset: Dp,
    onAction: (ContactsListAction) -> Unit,
) {
    Box(Modifier.padding(bottom = Spacing.s, end = endInset).testTag(ListTags.TopLinks)) {
        InsetGroupedSection {
            state.emergencyDetail?.let { detail ->
                row {
                    ListRow(
                        title = "Emergency Contacts",
                        secondary = detail,
                        leading = { TileIcon(RovstiIcons.MedicalCross) },
                        trailing = { ListRowChevron() },
                        onClick = { onAction(ContactsListAction.OpenEmergency) },
                        modifier = Modifier.testTag(ListTags.EmergencyRow),
                    )
                }
            }
            state.recentEntry?.let { entry ->
                row {
                    ListRow(
                        title = entry.title,
                        secondary = entry.detail,
                        leading = { TileIcon(RovstiIcons.Clock) },
                        trailing = { ListRowChevron() },
                        onClick = { onAction(ContactsListAction.OpenRecent) },
                        modifier = Modifier.testTag(ListTags.RecentRow),
                    )
                }
            }
        }
    }
}

/** The round glyph at the start of a row that has no person: the avatar's size, a quiet fill. */
@Composable
private fun TileIcon(icon: ImageVector) {
    val colors = RovstiTheme.colors
    Box(
        Modifier.size(Sizes.avatar).background(colors.fillTertiary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        RovstiIcon(icon, null, tint = colors.accent, size = ListMetrics.TileGlyph)
    }
}

@Composable
private fun ViewedSection(
    state: ContactsListUiState,
    endInset: Dp,
    overlays: OverlayController?,
    onAction: (ContactsListAction) -> Unit,
) {
    Column(Modifier.padding(bottom = Spacing.s, end = endInset).testTag(ListTags.Viewed)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter + Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RovstiText(
                "Recently viewed",
                Modifier.weight(1f),
                style = RovstiTheme.type.footnote,
                color = RovstiTheme.colors.labelSecondary,
            )
            RovstiText(
                "Clear",
                Modifier
                    .heightIn(min = Sizes.touch)
                    .clickable(role = Role.Button) { onAction(ContactsListAction.AskClearViewed) }
                    .padding(vertical = Spacing.m)
                    .testTag(ListTags.ViewedClear),
                style = RovstiTheme.type.footnote,
                color = RovstiTheme.colors.accent,
            )
        }
        InsetGroupedSection {
            for (item in state.viewed) {
                row {
                    val menu =
                        remember(
                            item.id,
                            state.quick,
                            onAction,
                        ) { menuFor(item.id, state.quick[item.menuKey], onAction) }
                    val open = { onAction(ContactsListAction.Open(item.id)) }
                    if (overlays != null && menu.isNotEmpty()) {
                        Box(
                            Modifier.longPressMenu(
                                controller = overlays,
                                items = menu,
                                style = ContextMenuStyle.Lifted(preview = { ViewedBody(item, null) }),
                                onTap = open,
                            ),
                        ) { ViewedBody(item, null) }
                    } else {
                        ViewedBody(item, open)
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewedBody(
    item: ContactItem,
    onClick: (() -> Unit)?,
) {
    ListRow(
        title = item.name,
        leading = {
            Avatar(
                item.name,
                size = VIEWED_AVATAR,
                photoModel = item.photoUri,
                colorKey = item.id.toString(),
            )
        },
        onClick = onClick,
        modifier = Modifier.heightIn(min = Sizes.touch),
    )
}

private val VIEWED_AVATAR = Sizes.avatar - Spacing.s

@Composable
private fun EmptyBlock(
    state: ContactsListUiState,
    onAction: (ContactsListAction) -> Unit,
    onPick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        when (state.emptyKind) {
            EmptyKind.NoContacts -> {
                EmptyState(
                    icon = RovstiIcons.People,
                    title = "No Contacts",
                    message = "Tap + to add someone, or import a .vcf file in Settings.",
                    modifier = Modifier.testTag("contacts-empty-book"),
                )
            }

            EmptyKind.NoResults -> {
                EmptyState(
                    icon = RovstiIcons.Search,
                    title = "No Results",
                    message = "No contact matches “${state.queryEcho}”.",
                    modifier = Modifier.testTag(ListTags.NoResults),
                )
                if (state.offer.create != null) {
                    CapsuleButton(
                        "Create Contact",
                        { onAction(ContactsListAction.CreateFromQuery) },
                        Modifier.testTag(ListTags.CreateFromSearch),
                        style = CapsuleStyle.Filled,
                    )
                }
                if (state.offer.addition != null) {
                    CapsuleButton("Add to a Contact", onPick, Modifier.testTag(ListTags.AddFromSearch))
                }
            }

            EmptyKind.None -> {
                Unit
            }
        }
    }
}

/** "Add to a Contact": a sheet listing the book; the choice goes back as [ContactsListAction.AddToContact]. */
private fun showAddPicker(
    overlays: OverlayController?,
    pickables: ImmutableList<ContactItem>,
    onAction: (ContactsListAction) -> Unit,
) {
    overlays ?: return
    overlays.present(
        SheetEntry(
            title = "Add to a Contact",
            detents = persistentListOf(Detent.Medium, Detent.Large),
        ) { sheet ->
            RovstiText(
                "Add to a Contact",
                Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
                style = RovstiTheme.type.title3,
            )
            LazyColumn(Modifier.nestedScroll(sheetScrollConnection(sheet)).testTag(ListTags.Pickables)) {
                items(pickables, key = { it.id }, contentType = { "contact" }) { item ->
                    ListRow(
                        title = item.name,
                        secondary = item.secondary,
                        leading = {
                            Avatar(
                                item.name,
                                size = Sizes.avatar,
                                photoModel = item.photoUri,
                                colorKey = item.id.toString(),
                            )
                        },
                        onClick = {
                            sheet.dismiss()
                            onAction(ContactsListAction.AddToContact(item.id))
                        },
                    )
                }
            }
        },
    )
}

/** Select All / Deselect All and Cancel, in the title bar's capsule (they replace Select and New). */
@Composable
private fun SelectTitleButtons(
    select: SelectMode,
    onAction: (ContactsListAction) -> Unit,
) {
    ToolbarCapsule {
        ToolbarTextButton(
            if (select.allSelected) "Deselect All" else "Select All",
            { onAction(ContactsListAction.ToggleSelectAll) },
            Modifier.testTag(SelectTags.SelectAll),
        )
        ToolbarTextButton(
            "Cancel",
            { onAction(ContactsListAction.ExitSelect) },
            Modifier.testTag(SelectTags.Cancel),
            bold = true,
        )
    }
}
