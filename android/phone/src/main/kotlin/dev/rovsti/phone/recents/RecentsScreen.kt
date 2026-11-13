package dev.rovsti.phone.recents

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rovsti.core.model.phone.Direction
import dev.rovsti.core.model.phone.RecentsFilter
import dev.rovsti.design.chrome.LargeTitleScaffold
import dev.rovsti.design.chrome.ToolbarAction
import dev.rovsti.design.chrome.ToolbarCapsule
import dev.rovsti.design.chrome.ToolbarTextButton
import dev.rovsti.design.controls.ChipModel
import dev.rovsti.design.controls.ChipRow
import dev.rovsti.design.controls.EmptyState
import dev.rovsti.design.controls.MessageSource
import dev.rovsti.design.controls.SelectCheck
import dev.rovsti.design.controls.SelectCheckSize
import dev.rovsti.design.controls.SourceGlyph
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.RowSeparator
import dev.rovsti.design.list.SectionHeader
import dev.rovsti.design.list.SwipeAction
import dev.rovsti.design.list.SwipeRow
import dev.rovsti.design.list.SwipeRowGroup
import dev.rovsti.design.list.TextStartWithLeading
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.overlay.ActionSheetAction
import dev.rovsti.design.overlay.ActionSheetEntry
import dev.rovsti.design.overlay.ContextMenuStyle
import dev.rovsti.design.overlay.LocalOverlayOrNull
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.confirmDestructive
import dev.rovsti.design.overlay.longPressMenu
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.design.press.rowPress
import dev.rovsti.design.text.SearchField
import dev.rovsti.design.text.rememberSearchFocusHandoff
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import dev.rovsti.design.time.LocalRovstiClock
import dev.rovsti.phone.NoticeLine
import dev.rovsti.phone.PhoneMetrics
import dev.rovsti.phone.PhoneTags
import dev.rovsti.phone.R
import dev.rovsti.phone.RecentsMetrics
import dev.rovsti.phone.RootLinks
import dev.rovsti.phone.remind.RemindStrings
import dev.rovsti.phone.remind.RemindTime
import dev.rovsti.phone.remind.menuAnchor
import dev.rovsti.phone.remind.presentRemindMenu
import dev.rovsti.phone.remind.remindMenuItems
import dev.rovsti.phone.remind.showTimePicker
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * Test seam: called from a side effect of every row that composes. Null in the app; the recomposition test counts with
 * it (a row whose data did not change must not run again while the list scrolls).
 */
internal object RecentsProbe {
    @Volatile var onRowComposed: ((String) -> Unit)? = null
}

/** Test tags of Recents. */
object RecentsTags {
    const val Root = "recents-screen"
    const val Search = "recents-search"
    const val Chips = "recents-chips"
    const val Empty = "recents-empty"
    const val Select = "recents-select"
    const val SelectAll = "recents-select-all"
    const val SelectCancel = "recents-select-cancel"
    const val SelectDelete = "recents-select-delete"
    const val SelectBlock = "recents-select-block"
    const val SelectShare = "recents-select-share"
    const val SelectToolbar = "recents-select-toolbar"

    fun row(key: String) = "recents-row-$key"

    fun info(key: String) = "recents-info-$key"

    fun header(index: Int) = "recents-header-$index"

    fun sim(key: String) = "recents-sim-$key"

    fun whatsApp(key: String) = "recents-wa-$key"

    fun chip(filter: RecentsFilter) = "chip-${filter.name}"
}

/** What Recents needs from Android; the app implements it, tests record it. */
interface RecentsHost {
    fun message(
        number: String,
        body: String?,
    )

    fun addContact(number: String)

    fun share(text: String)

    /** The details sheet of a call (E5.4); until it lands the app says so. */
    fun openDetails(
        key: String,
        onDismissed: () -> Unit,
    )
}

private val FilterOrder =
    listOf(
        RecentsFilter.All,
        RecentsFilter.Missed,
        RecentsFilter.Incoming,
        RecentsFilter.Outgoing,
        RecentsFilter.WhatsApp,
        RecentsFilter.WithNotes,
    )

/**
 * The Recents tab (E5.3), stateless. The large title with Select and the gear, the search field, ONE row of filter
 * chips (they scroll sideways, they never wrap), then the day sections. A row is a 40 dp avatar, the name (red when
 * missed) with the count of its run, one line with the type glyph, its label and the country of an unknown number, and
 * on the right the time, the SIM chip on a two-SIM phone and the info button; WhatsApp shows once, as the 14 dp glyph
 * after the name. A long press lifts the row and opens its menu over the page (nothing in the list moves); a swipe
 * right calls, a swipe left deletes. In select mode a tap toggles the row.
 */
@Composable
fun RecentsContent(
    ui: RecentsUi,
    strings: RecentsStrings,
    state: LazyListState,
    searchState: TextFieldState,
    onAction: (RecentsAction) -> Unit,
    links: RootLinks,
    modifier: Modifier = Modifier,
    showNotice: Boolean = false,
    menuFor: (RecentsRowUi) -> ImmutableList<MenuItem> = { persistentListOf() },
    onInfo: (String, () -> Unit) -> Unit = { _, _ -> },
) {
    val latest = rememberUpdatedState(onAction)
    val handoff = rememberSearchFocusHandoff(searchState)
    val openInfo: (String) -> Unit =
        remember(onInfo, handoff) {
            { key ->
                handoff.onResultOpened()
                onInfo(key, handoff::onSheetDismissed)
            }
        }
    LaunchedEffect(searchState) {
        snapshotFlow { searchState.text.toString() }.collect { latest.value(RecentsAction.SetQuery(it)) }
    }
    val select = ui.select
    BackHandler(enabled = select != null) { onAction(RecentsAction.ExitSelect) }
    val overlays = LocalOverlayOrNull.current
    val chips =
        remember(ui.counts, strings) {
            FilterOrder.map { ChipModel(it.name, strings.filter(it), ui.counts.of(it)) }.toImmutableList()
        }
    val selectLabel = strings.select
    val settingsLabel = stringResourceSettings()
    val actions =
        remember(ui.ready, ui.rowCount, selectLabel, settingsLabel, links, onAction) {
            if (!ui.ready) {
                persistentListOf<ToolbarAction>()
            } else {
                persistentListOf(
                    ToolbarAction("select", RovstiIcons.Checkmark, selectLabel) {
                        onAction(RecentsAction.EnterSelect)
                    },
                    ToolbarAction("settings", RovstiIcons.Gear, settingsLabel, onClick = links.openSettings),
                )
            }
        }
    Box(modifier.testTag(PhoneTags.root("recents")).testTag(RecentsTags.Root)) {
        LargeTitleScaffold(
            title = if (select != null) selectTitle(strings, select) else RECENTS_TITLE,
            state = state,
            actions = actions,
            actionsContent = select?.let { { SelectTitleButtons(it, strings, onAction) } },
        ) {
            if (showNotice && select == null) {
                item(key = "notice", contentType = "notice") { NoticeLine(links.openSetup) }
            }
            item(key = "search", contentType = "search") {
                SearchField(
                    state = searchState,
                    placeholder = strings.search,
                    handoff = handoff,
                    modifier =
                        Modifier
                            .padding(horizontal = Spacing.gutter, vertical = Spacing.s)
                            .testTag(RecentsTags.Search),
                )
            }
            item(key = "chips", contentType = "chips") {
                ChipRow(
                    chips = chips,
                    selected = ui.filter.name,
                    onSelect = { id -> onAction(RecentsAction.SetFilter(RecentsFilter.valueOf(id))) },
                    modifier = Modifier.testTag(RecentsTags.Chips),
                )
            }
            if (ui.ready) {
                body(ui, strings, overlays, onAction, menuFor, openInfo)
            }
        }
    }
}

private const val RECENTS_TITLE = "Recents"

@Composable
private fun stringResourceSettings(): String =
    androidx.compose.ui.res
        .stringResource(R.string.settings_open)

private fun selectTitle(
    strings: RecentsStrings,
    select: RecentsSelect,
): String = if (select.selected.isEmpty()) strings.selectNone else strings.selected(select.selected.size)

private fun LazyListScope.body(
    ui: RecentsUi,
    strings: RecentsStrings,
    overlays: OverlayController?,
    onAction: (RecentsAction) -> Unit,
    menuFor: (RecentsRowUi) -> ImmutableList<MenuItem>,
    onInfo: (String) -> Unit,
) {
    if (ui.items.isEmpty()) {
        item(key = "empty", contentType = "empty") {
            Box(
                Modifier.fillParentMaxHeight(RecentsMetrics.EmptyShare).fillMaxWidth().testTag(RecentsTags.Empty),
                contentAlignment = Alignment.Center,
            ) {
                val searching = ui.query.isNotBlank() || ui.filter != RecentsFilter.All
                EmptyState(
                    icon = RovstiIcons.Clock,
                    title = if (searching) ui.emptyText.orEmpty() else strings.emptyTitle,
                    message = if (searching) "" else strings.emptyMessage,
                )
            }
        }
        return
    }
    ui.items.forEachIndexed { at, item ->
        when (item) {
            is RecentsItem.Header -> {
                item(key = item.key, contentType = "header") {
                    SectionHeader(item.label, Modifier.testTag(RecentsTags.header(item.index)))
                }
            }

            is RecentsItem.Row -> {
                val separated = ui.items.getOrNull(at - 1) is RecentsItem.Row
                item(key = item.key, contentType = "row") {
                    RecentsRowItem(item.row, ui.select, separated, strings, overlays, onAction, menuFor, onInfo)
                }
            }
        }
    }
}

@Composable
private fun SelectTitleButtons(
    select: RecentsSelect,
    strings: RecentsStrings,
    onAction: (RecentsAction) -> Unit,
) {
    ToolbarCapsule {
        ToolbarTextButton(
            if (select.allSelected) strings.deselectAll else strings.selectAll,
            { onAction(RecentsAction.ToggleAll) },
            Modifier.testTag(RecentsTags.SelectAll),
        )
        ToolbarTextButton(
            strings.cancel,
            { onAction(RecentsAction.ExitSelect) },
            Modifier.testTag(RecentsTags.SelectCancel),
            bold = true,
        )
    }
}

/** Width of the select circle's slot: the gutter and the circle; the row's own gutter is the gap after it. */
private val SelectSlotWidth = Spacing.gutter + SelectCheckSize

@Composable
private fun RecentsRowItem(
    row: RecentsRowUi,
    select: RecentsSelect?,
    separated: Boolean,
    strings: RecentsStrings,
    overlays: OverlayController?,
    onAction: (RecentsAction) -> Unit,
    menuFor: (RecentsRowUi) -> ImmutableList<MenuItem>,
    onInfo: (String) -> Unit,
) {
    androidx.compose.runtime.SideEffect { RecentsProbe.onRowComposed?.invoke(row.key) }
    val checked = select?.let { row.key in it.selected }
    val slot by animateDpAsState(
        targetValue = if (checked != null) SelectSlotWidth else RecentsMetrics.None,
        animationSpec = RovstiMotion.select(Dp.VisibilityThreshold),
        label = "select-slot",
    )
    val colors = RovstiTheme.colors
    val menu = remember(row, menuFor) { menuFor(row) }
    val callBack = remember(row.key, onAction) { { onAction(RecentsAction.CallBack(row.key)) } }
    val toggle = remember(row.key, onAction) { { onAction(RecentsAction.Toggle(row.key)) } }
    val swipeLeading =
        remember(row.key, row.callable, strings, colors) {
            if (row.callable) {
                persistentListOf(
                    SwipeAction(strings.swipeCall, colors.success, { SwipeGlyph(RovstiIcons.Phone) }) {
                        onAction(RecentsAction.CallBack(row.key))
                    },
                )
            } else {
                persistentListOf()
            }
        }
    val swipeTrailing =
        remember(row.key, strings, colors) {
            persistentListOf(
                SwipeAction(strings.swipeDelete, colors.destructive, { SwipeGlyph(RovstiIcons.Trash) }) {
                    onAction(RecentsAction.Delete(listOf(row.key)))
                },
            )
        }
    Column {
        if (separated) RowSeparator(inset = TextStartWithLeading + slot)
        val tag = Modifier.testTag(RecentsTags.row(row.key))
        if (checked != null) {
            Box(tag.semantics { selected = checked }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(slot).clipToBounds(), contentAlignment = Alignment.CenterStart) {
                        SelectCheck(checked, Modifier.padding(start = Spacing.gutter).requiredWidth(SelectCheckSize))
                    }
                    Box(Modifier.weight(1f)) {
                        RowBody(row, strings, onClick = toggle, onInfo = null)
                    }
                }
            }
        } else {
            SwipeRow(leading = swipeLeading, trailing = swipeTrailing, rowKey = row.key, modifier = tag) {
                val body: Modifier =
                    if (overlays != null && menu.isNotEmpty()) {
                        Modifier.longPressMenu(
                            controller = overlays,
                            items = menu,
                            style = ContextMenuStyle.Lifted(preview = { RowBody(row, strings, null, null) }),
                            onTap = if (row.callable) callBack else null,
                        )
                    } else {
                        Modifier
                    }
                Box(body) {
                    RowBody(row, strings, onClick = null, onInfo = { onInfo(row.key) })
                }
            }
        }
    }
}

@Composable
private fun SwipeGlyph(icon: ImageVector) {
    RovstiIcon(icon, null, tint = Color.White, size = RecentsMetrics.SwipeGlyph)
}

/** The row itself. [onClick] is for select mode (the whole row toggles); the long-press menu supplies the tap otherwise. */
@Composable
private fun RowBody(
    row: RecentsRowUi,
    strings: RecentsStrings,
    onClick: (() -> Unit)?,
    onInfo: (() -> Unit)?,
) {
    val colors = RovstiTheme.colors
    val type = RovstiTheme.type
    val source = remember { MutableInteractionSource() }
    val clickable =
        if (onClick != null) {
            Modifier.rowPress(source).clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
        } else {
            Modifier
        }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.rowMinTwoLine)
            .then(clickable)
            .semantics(mergeDescendants = true) { contentDescription = row.aria }
            .padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.s, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            name = if (row.named) row.title else "",
            size = Sizes.avatar,
            colorKey = row.number.ifEmpty { row.title },
        )
        Box(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RovstiText(
                    row.title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = type.body,
                    color = if (row.missed) colors.destructive else colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.count != null) {
                    RovstiText(
                        row.count,
                        modifier = Modifier.padding(start = Spacing.xs),
                        style = type.body,
                        color = colors.labelSecondary,
                        maxLines = 1,
                    )
                }
                if (row.whatsapp) {
                    SourceGlyph(
                        MessageSource.WhatsApp,
                        Modifier.padding(start = Spacing.s).testTag(RecentsTags.whatsApp(row.key)),
                        contentDescription = "",
                    )
                }
            }
            Row(Modifier.padding(top = Spacing.textGap), verticalAlignment = Alignment.CenterVertically) {
                RovstiIcon(
                    directionIcon(row.direction),
                    null,
                    tint = if (row.missed) colors.destructive else colors.labelSecondary,
                    size = Sizes.sourceGlyph,
                )
                RovstiText(
                    listOfNotNull(row.subtitle.ifBlank { null }, row.country).joinToString(" · "),
                    modifier = Modifier.padding(start = Spacing.xs),
                    style = type.subheadline,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.padding(start = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            if (row.sim != null) {
                SimDot(row.sim, Modifier.testTag(RecentsTags.sim(row.key)))
            }
            RovstiText(
                row.time,
                modifier = Modifier.padding(start = if (row.sim != null) Spacing.s else RecentsMetrics.None),
                style = type.footnote,
                color = colors.labelSecondary,
                maxLines = 1,
            )
            if (onInfo != null) {
                Box(
                    Modifier
                        .size(Sizes.touch)
                        .testTag(RecentsTags.info(row.key))
                        .semantics {
                            contentDescription = strings.info
                            role = Role.Button
                        }.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onInfo,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    RovstiIcon(RovstiIcons.Info, null, tint = colors.accent, size = RecentsMetrics.InfoGlyph)
                }
            }
        }
    }
}

/** The SIM as a small capsule with its colour dot and its short label (S26), only on a phone with two SIMs. */
@Composable
private fun SimDot(
    sim: SimChipUi,
    modifier: Modifier = Modifier,
) {
    val colors = RovstiTheme.colors
    Row(
        modifier
            .clip(CircleShape)
            .background(colors.fillTertiary)
            .padding(horizontal = Spacing.s, vertical = RecentsMetrics.SimChipVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(RecentsMetrics.SimDot).clip(CircleShape).background(Color(sim.colorArgb)))
        RovstiText(
            sim.label,
            modifier = Modifier.padding(start = Spacing.xs),
            style = RovstiTheme.type.caption1,
            color = colors.labelSecondary,
            maxLines = 1,
        )
    }
}

private fun directionIcon(direction: Direction): ImageVector =
    when (direction) {
        Direction.Incoming, Direction.Missed -> RovstiIcons.ArrowDown
        Direction.Outgoing -> RovstiIcons.ArrowUp
        Direction.Declined -> RovstiIcons.Xmark
        Direction.Voicemail -> RovstiIcons.Voicemail
        Direction.Unknown -> RovstiIcons.Phone
    }

// ---- the select toolbar (the tab bar's accessory)

/**
 * The bulk toolbar of select mode: Delete, Block and Share, in the room beside the folded tab-bar circle. A button that
 * would do nothing (no row chosen) is dimmed and does nothing.
 */
@Composable
fun RecentsSelectToolbar(
    select: RecentsSelect,
    strings: RecentsStrings,
    onAction: (RecentsAction) -> Unit,
    modifier: Modifier = Modifier,
    isInline: Boolean = true,
) {
    val any = select.selected.isNotEmpty()
    Row(
        modifier
            .heightIn(min = if (isInline) RecentsMetrics.ToolbarInline else RecentsMetrics.ToolbarExpanded)
            .padding(horizontal = RecentsMetrics.ToolbarSide)
            .testTag(RecentsTags.SelectToolbar),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dev.rovsti.design.chrome.ToolbarButton(
            RovstiIcons.Trash,
            strings.delete,
            { onAction(RecentsAction.DeleteSelected) },
            Modifier.testTag(RecentsTags.SelectDelete),
            enabled = any,
        )
        dev.rovsti.design.chrome.ToolbarButton(
            RovstiIcons.Shield,
            strings.block,
            { onAction(RecentsAction.BlockSelected) },
            Modifier.testTag(RecentsTags.SelectBlock),
            enabled = any,
        )
        dev.rovsti.design.chrome.ToolbarButton(
            RovstiIcons.Share,
            strings.share,
            { onAction(RecentsAction.ShareSelected) },
            Modifier.testTag(RecentsTags.SelectShare),
            enabled = any,
        )
    }
}

/** Where the remind menu opens, as a fraction of the window height (a row's menu has just closed there). */
private const val REMIND_MENU_AT = 0.42f

// ---- menus and sheets (what the effects present)

/** The long-press menu of a row: the core words the row, this lists what can be done with it. */
fun recentsMenu(
    row: RecentsRowUi,
    strings: RecentsStrings,
    onAction: (RecentsAction) -> Unit,
    whatsApp: Boolean = false,
): ImmutableList<MenuItem> {
    val key = row.key
    val call = row.callable
    return persistentListOf<MenuItem>(
        MenuItem.Action(strings.callBack, RovstiIcons.Phone, enabled = call) { onAction(RecentsAction.CallBack(key)) },
        MenuItem.Action(strings.message, RovstiIcons.Message, enabled = call) { onAction(RecentsAction.Message(key)) },
        MenuItem.Action(strings.whatsApp, RovstiIcons.WhatsApp, enabled = call && whatsApp) {
            onAction(RecentsAction.WhatsAppCall(key))
        },
        MenuItem.Separator,
        MenuItem.Action(strings.quickReply, RovstiIcons.Forward, enabled = call) {
            onAction(RecentsAction.AskQuickReply(key))
        },
        MenuItem.Action(strings.remind, RovstiIcons.Clock, enabled = call) { onAction(RecentsAction.AskRemind(key)) },
        MenuItem.Separator,
        MenuItem.Action(strings.copyNumber, RovstiIcons.Copy, enabled = call) {
            onAction(RecentsAction.CopyNumber(key, withCountry = false))
        },
        MenuItem.Action(strings.copyCountry, RovstiIcons.Globe, enabled = call) {
            onAction(RecentsAction.CopyNumber(key, withCountry = true))
        },
        MenuItem.Action(strings.copyAs, RovstiIcons.Copy, enabled = call) {
            onAction(RecentsAction.AskFormats(key, share = false))
        },
        MenuItem.Action(strings.shareAs, RovstiIcons.Share, enabled = call) {
            onAction(RecentsAction.AskFormats(key, share = true))
        },
        MenuItem.Separator,
    ).let { base ->
        var list = base
        if (!row.named && call) {
            list =
                list.add(
                    MenuItem.Action(strings.addContact, RovstiIcons.PersonAdd) {
                        onAction(RecentsAction.AddContact(key))
                    },
                )
        }
        list =
            list
                .add(
                    MenuItem.Action(strings.block, RovstiIcons.Shield, destructive = true, enabled = call) {
                        onAction(RecentsAction.AskBlock(listOf(key)))
                    },
                ).add(
                    MenuItem.Action(strings.delete, RovstiIcons.Trash, destructive = true, enabled = row.deletable) {
                        onAction(RecentsAction.Delete(listOf(key)))
                    },
                )
        list
    }
}

/**
 * Runs the view model's one-shot results: toasts, the Undo pill, the confirmation and the choosers. [host] opens the
 * apps that finish the job (SMS composer, contact editor, share sheet).
 */
@Composable
fun RecentsEffects(
    model: RecentsViewModel,
    host: RecentsHost,
    strings: RecentsStrings,
) {
    val overlays = LocalOverlayOrNull.current ?: return
    val haptics = LocalRovstiHaptics.current
    val context = LocalContext.current
    val clock = LocalRovstiClock.current
    val windowSize = LocalWindowInfo.current.containerSize
    val remindStrings = remember(context) { RemindStrings(context.resources) }
    val currentHost by rememberUpdatedState(host)
    LaunchedEffect(model, overlays, strings) {
        model.effect.collect { effect ->
            when (effect) {
                RecentsEffect.Reject -> {
                    haptics.reject()
                }

                is RecentsEffect.Say -> {
                    overlays.showToast(strings.say(effect.notice))
                }

                is RecentsEffect.Core -> {
                    overlays.showToast(effect.text)
                }

                is RecentsEffect.Reminded -> {
                    overlays.showToast(effect.text)
                }

                is RecentsEffect.ChooseSim -> {
                    overlays.present(simChooser(effect, model::onAction))
                }

                is RecentsEffect.ConfirmBlock -> {
                    overlays.confirmDestructive(
                        destructiveLabel = strings.blockAction,
                        title = strings.blockTitle(effect.name, effect.numbers.size),
                        message = strings.blockMessage,
                        cancelLabel = strings.cancel,
                        onConfirm = { model.onAction(RecentsAction.ConfirmBlock(effect.numbers)) },
                    )
                }

                is RecentsEffect.ChooseReply -> {
                    overlays.present(
                        ActionSheetEntry(
                            title = strings.replyTitle,
                            actions =
                                effect.choices
                                    .map { text ->
                                        ActionSheetAction(text) {
                                            model.onAction(RecentsAction.QuickReply(effect.key, text))
                                        }
                                    }.toImmutableList(),
                            cancelLabel = strings.cancel,
                        ),
                    )
                }

                is RecentsEffect.ChooseRemind -> {
                    overlays.presentRemindMenu(
                        menuAnchor(windowSize, REMIND_MENU_AT),
                        remindMenuItems(
                            effect.slots,
                            remindStrings.pickTime,
                            onSlot = { model.onAction(RecentsAction.Remind(effect.key, it)) },
                            onPickTime = {
                                showTimePicker(context, clock.nowMillis()) { at ->
                                    model.onAction(RecentsAction.RemindAtTime(effect.key, at))
                                }
                            },
                        ),
                    )
                }

                is RecentsEffect.RemindedAt -> {
                    overlays.showToast(remindStrings.setAt(RemindTime.clock(context, effect.atMs)))
                }

                is RecentsEffect.ChooseFormat -> {
                    overlays.present(
                        ActionSheetEntry(
                            title = strings.copyAsTitle(effect.share),
                            actions =
                                effect.forms
                                    .map { (form, text) ->
                                        ActionSheetAction("${strings.form(form)}: $text") {
                                            if (effect.share) currentHost.share(text) else model.copyText(text)
                                        }
                                    }.toImmutableList(),
                            cancelLabel = strings.cancel,
                        ),
                    )
                }

                is RecentsEffect.Undoable -> {
                    overlays.showUndo(
                        message = effect.text ?: strings.undoable(effect.kind, effect.count),
                        onUndo = { model.onAction(RecentsAction.Undo(effect.undo)) },
                        actionLabel = strings.undo,
                    )
                }

                is RecentsEffect.SendMessage -> {
                    currentHost.message(effect.number, effect.body)
                }

                is RecentsEffect.AddContact -> {
                    currentHost.addContact(effect.number)
                }

                is RecentsEffect.ShareText -> {
                    currentHost.share(effect.text)
                }
            }
        }
    }
}

private fun simChooser(
    effect: RecentsEffect.ChooseSim,
    act: (RecentsAction) -> Unit,
): ActionSheetEntry =
    ActionSheetEntry(
        title = effect.number,
        actions =
            effect.choices
                .map { sim -> ActionSheetAction(sim.title) { act(RecentsAction.CallOn(effect.number, sim.key)) } }
                .toImmutableList(),
    )

/** The Recents tab wired to its view model. */
@Composable
fun RecentsScreen(
    model: RecentsViewModel,
    host: RecentsHost,
    links: RootLinks,
    modifier: Modifier = Modifier,
    showNotice: Boolean = false,
) {
    val ui by model.state.collectAsState()
    val strings = RecentsStrings.current()
    val listState = rememberLazyListState()
    val searchState = rememberTextFieldState()
    // The tab is on screen: the missed calls count as seen (U39).
    LaunchedEffect(model) { model.onAction(RecentsAction.Opened) }
    RecentsEffects(model, host, strings)
    val whatsApp by model.whatsAppAvailable.collectAsState()
    val menuFor: (RecentsRowUi) -> ImmutableList<MenuItem> =
        remember(strings, model, whatsApp) { { row -> recentsMenu(row, strings, model::onAction, whatsApp) } }
    SwipeRowGroup {
        RecentsContent(
            ui = ui,
            strings = strings,
            state = listState,
            searchState = searchState,
            onAction = model::onAction,
            links = links,
            modifier = modifier,
            showNotice = showNotice,
            menuFor = menuFor,
            onInfo = host::openDetails,
        )
    }
}
