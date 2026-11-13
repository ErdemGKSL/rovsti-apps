package dev.rovsti.phone.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.core.model.phone.CallType
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.controls.CapsuleStyle
import dev.rovsti.design.controls.MessageSource
import dev.rovsti.design.controls.SourceGlyph
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.foundation.LocalIosBackdrop
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ListRowChevron
import dev.rovsti.design.list.RowSeparator
import dev.rovsti.design.list.SectionHeader
import dev.rovsti.design.media.QrCode
import dev.rovsti.design.media.QrModules
import dev.rovsti.design.overlay.ActionSheetAction
import dev.rovsti.design.overlay.ActionSheetEntry
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.LocalOverlayOrNull
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.confirmDestructive
import dev.rovsti.design.overlay.menuTrigger
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.design.theme.RovstiShapes
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.DetailsMetrics
import dev.rovsti.phone.details.photo.PersonPhotoAvatar
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Test tags of the Contact / Number sheet. */
object PersonTags {
    const val Sheet = "person-sheet"
    const val Header = "person-header"
    const val Title = "person-title"
    const val Call = "person-call"
    const val Message = "person-message"
    const val More = "person-more"
    const val Stats = "person-stats"
    const val AddContact = "person-add-contact"
    const val DeleteHistory = "person-delete-history"
    const val Qr = "person-qr"
    const val QrSheet = "person-qr-sheet"

    fun number(raw: String) = "person-number-$raw"

    fun line(key: String) = "person-line-$key"
}

/** What the sheet needs from Android: the apps that finish a job. The app implements it, tests record it. */
interface DetailsHost {
    fun message(
        number: String,
        body: String?,
    )

    fun addContact(number: String)

    /**
     * Opens the Contacts editor of [contactId]: the hand-off of the photo menu when the app holds no write grant
     * (E5.13). Hosts that cannot do it ignore the call.
     */
    fun editContact(contactId: Long) {}

    fun share(text: String)

    /** Opens the "Save as" picker for [csv] under [fileName]; the host writes it and says so. */
    fun saveCsv(
        fileName: String,
        csv: String,
    )
}

/**
 * The glyph in front of a history line: the arrow of its type (a missed call points down, red).
 * Shared with the Recents rows' language.
 */
internal fun callTypeIcon(type: CallType): ImageVector =
    when (type) {
        CallType.Incoming, CallType.Missed -> RovstiIcons.ArrowDown
        CallType.Outgoing -> RovstiIcons.ArrowUp
        CallType.Declined -> RovstiIcons.Xmark
        CallType.Voicemail -> RovstiIcons.Voicemail
        CallType.Unknown -> RovstiIcons.Phone
    }

/** The "..." menu of the sheet: copy, share, QR, export and block (U36, U26, U18, U9, U16, quick reply). */
fun personMenu(
    ui: PersonUi,
    strings: DetailsStrings,
    onAction: (PersonAction) -> Unit,
    whatsApp: Boolean = false,
): ImmutableList<MenuItem> {
    val callable = ui.numbers.isNotEmpty()
    val multiple = ui.numbers.size > 1
    val items = mutableListOf<MenuItem>()
    items +=
        MenuItem.Action(strings.menuWhatsApp, RovstiIcons.WhatsApp, enabled = callable && whatsApp) {
            onAction(PersonAction.WhatsAppCall)
        }
    items +=
        MenuItem.Action(strings.menuReply, RovstiIcons.Forward, enabled = callable) {
            onAction(PersonAction.AskQuickReply)
        }
    items += MenuItem.Separator
    if (multiple) {
        items += MenuItem.Action(strings.menuCopyAll, RovstiIcons.Copy) { onAction(PersonAction.CopyAll) }
    } else {
        ui.numbers.firstOrNull()?.let { n ->
            items +=
                MenuItem.Action(strings.menuCopyNumber, RovstiIcons.Copy) {
                    onAction(PersonAction.CopyNumber(n.raw))
                }
        }
    }
    items +=
        MenuItem.Action(strings.menuShareDetails, RovstiIcons.Share, enabled = callable) {
            onAction(PersonAction.ShareDetails)
        }
    items +=
        MenuItem.Action(strings.menuShareStats, RovstiIcons.Share, enabled = ui.stats != null) {
            onAction(PersonAction.ShareStats)
        }
    items += MenuItem.Action(strings.menuQr, RovstiIcons.QrCode, enabled = callable) { onAction(PersonAction.ShowQr) }
    items +=
        MenuItem.Action(strings.menuExport, RovstiIcons.Download, enabled = ui.history.isNotEmpty()) {
            onAction(PersonAction.ExportCsv)
        }
    items += MenuItem.Separator
    items +=
        MenuItem.Action(strings.menuBlock, RovstiIcons.Shield, destructive = true, enabled = callable) {
            onAction(PersonAction.AskBlock)
        }
    return items.toImmutableList()
}

/**
 * The body of the Contact and Number sheets (E5.4), stateless. A centred header (the 72 dp avatar, the name, the
 * label or
 * the country of an unknown number), three equal capsules (Call, Message, and "More" which opens the menu over the
 * sheet), the numbers (tap calls, hold chooses a SIM), the statistics card (U7), the recent calls and, last,
 * Delete call
 * history. Everything scrolls as one column, so a long history never pushes the actions away; the sheet's drag works
 * from the top of it (nested scroll).
 */
@Composable
fun ColumnScope.PersonSheetContent(
    ui: PersonUi,
    strings: DetailsStrings,
    menu: ImmutableList<MenuItem>,
    onAction: (PersonAction) -> Unit,
    overlays: OverlayController?,
    modifier: Modifier = Modifier,
) {
    // The sheet is already glass over the blurred page: buttons and fields inside it take the flat fill (a second lens
    // would show the page's rows through the labels).
    CompositionLocalProvider(LocalIosBackdrop provides null) {
        Column(
            modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .testTag(PersonTags.Sheet),
        ) {
            Header(ui)
            Actions(ui, strings, menu, onAction, overlays)
            if (ui.numbers.isNotEmpty()) {
                Box(Modifier.height(Spacing.l))
                NumbersSection(ui, strings, onAction)
            }
            if (!ui.named && ui.numbers.isNotEmpty()) {
                Box(Modifier.height(Spacing.m))
                InsetGroupedSection {
                    row {
                        ListRow(
                            title = strings.addContact,
                            titleColor = RovstiTheme.colors.accent,
                            leading = {
                                RovstiIcon(
                                    RovstiIcons.PersonAdd,
                                    null,
                                    tint = RovstiTheme.colors.accent,
                                    size = DetailsMetrics.RowGlyph,
                                )
                            },
                            onClick = { onAction(PersonAction.AddContact) },
                            modifier = Modifier.testTag(PersonTags.AddContact),
                        )
                    }
                }
            }
            ui.stats?.let {
                Box(Modifier.height(Spacing.sectionGap - Spacing.s))
                StatsCard(it, strings)
            }
            if (ui.history.isNotEmpty()) {
                Box(Modifier.height(Spacing.sectionGap - Spacing.s))
                HistorySection(ui, strings, onAction)
                Box(Modifier.height(Spacing.m))
                InsetGroupedSection {
                    row {
                        ListRow(
                            title = strings.deleteHistory,
                            titleColor = RovstiTheme.colors.destructive,
                            onClick = { onAction(PersonAction.AskDeleteHistory) },
                            modifier = Modifier.testTag(PersonTags.DeleteHistory),
                        )
                    }
                }
            }
            Box(Modifier.height(Spacing.xl))
        }
    }
}

@Composable
private fun Header(ui: PersonUi) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter)
            .padding(top = Spacing.s)
            .testTag(PersonTags.Header),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PersonPhotoAvatar(
            name = if (ui.named) ui.title else "",
            size = DetailsMetrics.HeaderAvatar,
            colorKey = ui.colorKey.ifEmpty { ui.title },
            photo = ui.photo,
        )
        RovstiText(
            ui.title,
            modifier = Modifier.padding(top = Spacing.m).testTag(PersonTags.Title).semantics { heading() },
            style = RovstiTheme.type.title2,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (ui.subtitle != null) {
            RovstiText(
                ui.subtitle,
                modifier = Modifier.padding(top = Spacing.textGap),
                style = RovstiTheme.type.subheadline,
                color = RovstiTheme.colors.labelSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Actions(
    ui: PersonUi,
    strings: DetailsStrings,
    menu: ImmutableList<MenuItem>,
    onAction: (PersonAction) -> Unit,
    overlays: OverlayController?,
) {
    val callable = ui.numbers.isNotEmpty()
    val first = ui.numbers.firstOrNull()?.raw
    val stacked = LocalDensity.current.fontScale > STACK_FONT_SCALE
    val call: @Composable (Modifier) -> Unit = { m ->
        CapsuleButton(
            strings.call,
            onClick = { first?.let { onAction(PersonAction.Call(it)) } },
            modifier = m.testTag(PersonTags.Call),
            style = CapsuleStyle.Filled,
            enabled = callable,
            fillWidth = true,
        )
    }
    val message: @Composable (Modifier) -> Unit = { m ->
        CapsuleButton(
            strings.message,
            onClick = { onAction(PersonAction.Message) },
            modifier = m.testTag(PersonTags.Message),
            enabled = callable,
            fillWidth = true,
        )
    }
    val more: @Composable (Modifier) -> Unit = { m ->
        val trigger = if (overlays != null) Modifier.menuTrigger(overlays, menu, strings.more) else Modifier
        Box(m.then(trigger).testTag(PersonTags.More)) {
            CapsuleButton(strings.more, onClick = {}, fillWidth = true)
        }
    }
    val pad = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(top = Spacing.l)
    if (stacked) {
        // Three labels no longer fit side by side at a large font: one full-width capsule each.
        Column(pad, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            call(Modifier.fillMaxWidth())
            message(Modifier.fillMaxWidth())
            more(Modifier.fillMaxWidth())
        }
    } else {
        Row(pad, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            call(Modifier.weight(1f))
            message(Modifier.weight(1f))
            more(Modifier.weight(1f))
        }
    }
}

private const val STACK_FONT_SCALE = 1.3f

@Composable
private fun NumbersSection(
    ui: PersonUi,
    strings: DetailsStrings,
    onAction: (PersonAction) -> Unit,
) {
    val accent = RovstiTheme.colors.accent
    InsetGroupedSection(header = strings.numbers) {
        for (n in ui.numbers) {
            row {
                ListRow(
                    title = n.display,
                    secondary = n.label,
                    trailing = { RovstiIcon(RovstiIcons.Phone, null, tint = accent, size = DetailsMetrics.SmallGlyph) },
                    onClick = { onAction(PersonAction.Call(n.raw)) },
                    onLongClick = { onAction(PersonAction.AskSim(n.raw)) },
                    modifier = Modifier.testTag(PersonTags.number(n.raw)),
                )
            }
        }
    }
}

/** U7: four counts side by side and, under them, the core's sentences (all time, longest call, this week). */
@Composable
private fun StatsCard(
    stats: StatsUi,
    strings: DetailsStrings,
) {
    val colors = RovstiTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).testTag(PersonTags.Stats)) {
        SectionHeader(strings.stats, horizontal = Spacing.s)
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.groupedSurface, RovstiShapes.grouped),
        ) {
            val miss = colors.destructive.takeIf { stats.missed > 0 } ?: colors.labelPrimary
            val tiles: List<Triple<Int, String, androidx.compose.ui.graphics.Color>> =
                listOf(
                    Triple(stats.calls, strings.statCalls, colors.labelPrimary),
                    Triple(stats.incoming, strings.statIncoming, colors.labelPrimary),
                    Triple(stats.outgoing, strings.statOutgoing, colors.labelPrimary),
                    Triple(stats.missed, strings.statMissed, miss),
                )
            // Four tiles in a row; two by two at a large font, where "Incoming" would be cut.
            for (chunk in tiles.chunked(if (LocalDensity.current.fontScale > STACK_FONT_SCALE) 2 else 4)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s, vertical = Spacing.m)) {
                    for ((value, label, color) in chunk) StatTile(value, label, color, Modifier.weight(1f))
                }
            }
            for (line in stats.lines) {
                RowSeparator(inset = Spacing.gutter)
                RovstiText(
                    line,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.m),
                    style = RovstiTheme.type.subheadline,
                    color = colors.labelSecondary,
                )
            }
        }
    }
}

@Composable
private fun StatTile(
    value: Int,
    label: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        RovstiText(value.toString(), style = RovstiTheme.type.title2, color = color, maxLines = 1)
        RovstiText(
            label,
            modifier = Modifier.padding(top = Spacing.textGap),
            style = RovstiTheme.type.caption1,
            color = RovstiTheme.colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HistorySection(
    ui: PersonUi,
    strings: DetailsStrings,
    onAction: (PersonAction) -> Unit,
) {
    val colors = RovstiTheme.colors
    InsetGroupedSection(header = strings.history, separatorInset = Spacing.gutter + HistoryLeading + Spacing.m) {
        for (line in ui.history) {
            row {
                ListRow(
                    title = line.title,
                    secondary = line.detail.ifBlank { null },
                    titleColor =
                        if (line.missed) colors.destructive else androidx.compose.ui.graphics.Color.Unspecified,
                    leading = {
                        RovstiIcon(
                            callTypeIcon(line.callType),
                            null,
                            tint = if (line.missed) colors.destructive else colors.labelSecondary,
                            size = DetailsMetrics.SmallGlyph,
                        )
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (line.whatsapp) {
                                SourceGlyph(
                                    MessageSource.WhatsApp,
                                    Modifier.padding(end = Spacing.s),
                                    contentDescription = "",
                                )
                            }
                            ListRowChevron()
                        }
                    },
                    onClick = { onAction(PersonAction.OpenCall(line.key)) },
                    modifier = Modifier.testTag(PersonTags.line(line.key)),
                )
            }
        }
    }
}

private val HistoryLeading = dev.rovsti.design.list.LeadingSlotWidth

/**
 * The stateful sheet: collects the model, runs its effects (toasts, the Undo pill, the confirmations and choosers) and
 * hands the jobs that need Android to [host]. [onOpenCall] and [onShowQr] present the sheets that stack above this one.
 */
@Composable
fun ColumnScope.PersonSheet(
    model: PersonSheetViewModel,
    host: DetailsHost,
    onOpenCall: (CallDetailsTarget) -> Unit,
    onShowQr: (PersonQr) -> Unit,
    onFinished: () -> Unit = {},
) {
    val ui by model.state.collectAsState()
    val strings = DetailsStrings.current()
    val overlays = LocalOverlayOrNull.current
    DisposableEffect(model) { onDispose { model.close() } }
    PersonEffects(model, host, strings, onOpenCall, onShowQr)
    val whatsApp by model.whatsAppAvailable.collectAsState()
    val menu = remember(ui, strings, model, whatsApp) { personMenu(ui, strings, model::onAction, whatsApp) }
    PersonSheetContent(ui, strings, menu, model::onAction, overlays)
}

/** Runs the model's one-shot results. */
@Composable
fun PersonEffects(
    model: PersonSheetViewModel,
    host: DetailsHost,
    strings: DetailsStrings,
    onOpenCall: (CallDetailsTarget) -> Unit,
    onShowQr: (PersonQr) -> Unit,
) {
    val overlays = LocalOverlayOrNull.current ?: return
    val haptics = LocalRovstiHaptics.current
    val currentHost by rememberUpdatedState(host)
    val openCall by rememberUpdatedState(onOpenCall)
    val showQr by rememberUpdatedState(onShowQr)
    LaunchedEffect(model, overlays, strings) {
        model.effect.collect { effect ->
            when (effect) {
                PersonEffect.Reject -> {
                    haptics.reject()
                }

                is PersonEffect.Say -> {
                    if (effect.notice == PersonNotice.Copied || effect.notice == PersonNotice.CopiedAll) {
                        haptics.confirm()
                    }
                    overlays.showToast(strings.say(effect.notice))
                }

                is PersonEffect.Core -> {
                    haptics.confirm()
                    overlays.showToast(strings.refused(effect.refusal))
                }

                is PersonEffect.ChooseSim -> {
                    overlays.present(
                        ActionSheetEntry(
                            title = effect.number,
                            actions =
                                effect.choices
                                    .map { sim ->
                                        ActionSheetAction(sim.title) {
                                            model.onAction(PersonAction.CallOn(effect.number, sim.key))
                                        }
                                    }.toImmutableList(),
                            cancelLabel = strings.cancel,
                        ),
                    )
                }

                is PersonEffect.ChooseReply -> {
                    overlays.present(
                        ActionSheetEntry(
                            title = strings.replyTitle,
                            actions =
                                effect.choices
                                    .map { text ->
                                        ActionSheetAction(text) { model.onAction(PersonAction.QuickReply(text)) }
                                    }.toImmutableList(),
                            cancelLabel = strings.cancel,
                        ),
                    )
                }

                is PersonEffect.ConfirmBlock -> {
                    overlays.confirmDestructive(
                        destructiveLabel = strings.blockAction,
                        title = strings.blockTitle(effect.name),
                        message = strings.blockMessage,
                        cancelLabel = strings.cancel,
                        onConfirm = { model.onAction(PersonAction.ConfirmBlock) },
                    )
                }

                is PersonEffect.ConfirmDeleteHistory -> {
                    overlays.confirmDestructive(
                        destructiveLabel = strings.deleteHistoryAction(effect.calls),
                        title = strings.deleteHistoryTitle,
                        message = strings.deleteHistoryMessage(effect.name, effect.calls),
                        cancelLabel = strings.cancel,
                        onConfirm = { model.onAction(PersonAction.ConfirmDeleteHistory) },
                    )
                }

                is PersonEffect.Undoable -> {
                    haptics.confirm()
                    overlays.showUndo(
                        message = strings.undoable(effect.kind, effect.count),
                        onUndo = { model.onAction(PersonAction.Undo(effect.undo)) },
                        actionLabel = strings.undo,
                    )
                }

                is PersonEffect.SendMessage -> {
                    currentHost.message(effect.number, effect.body)
                }

                is PersonEffect.AddContact -> {
                    currentHost.addContact(effect.number)
                }

                is PersonEffect.ShareText -> {
                    currentHost.share(effect.text)
                }

                is PersonEffect.SaveCsv -> {
                    currentHost.saveCsv(effect.fileName, effect.csv)
                }

                is PersonEffect.ShowQr -> {
                    showQr(effect.qr)
                }

                is PersonEffect.OpenCall -> {
                    openCall(CallDetailsTarget(effect.line, effect.who, model.state.value.photo))
                }
            }
        }
    }
}

/** The QR sheet of a person (U9): the white code card, the name under it and a Share button for the vCard text. */
@Composable
fun ColumnScope.PersonQrContent(
    qr: PersonQr,
    strings: DetailsStrings,
    onShare: () -> Unit,
) {
    val modules = remember(qr) { QrModules(qr.matrix.side) { x, y -> qr.matrix[x, y] } }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).testTag(PersonTags.QrSheet),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        QrCode(modules, Modifier.width(DetailsMetrics.QrSize).testTag(PersonTags.Qr))
        RovstiText(
            qr.name,
            Modifier.fillMaxWidth(),
            style = RovstiTheme.type.title3,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RovstiText(
            strings.qrCaption,
            style = RovstiTheme.type.footnote,
            color = RovstiTheme.colors.labelSecondary,
            textAlign = TextAlign.Center,
        )
        CapsuleButton(
            strings.qrShare,
            onClick = onShare,
            icon = RovstiIcons.Share,
            style = CapsuleStyle.Filled,
            modifier = Modifier.fillMaxWidth(),
            fillWidth = true,
        )
    }
}

/** Medium for a short sheet, Large when there is a history to scroll; both are reachable by a drag. */
internal val PersonDetents = persistentListOf(Detent.Medium, Detent.Large)

internal fun personSheetEntry(
    title: String,
    content: @Composable ColumnScope.(dev.rovsti.design.overlay.SheetState) -> Unit,
): SheetEntry = SheetEntry(title = title, detents = PersonDetents, initialDetent = Detent.Medium, content = content)
