package dev.rovsti.phone.settings.blocked

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import dev.rovsti.core.model.phone.BlockReason
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.controls.CapsuleStyle
import dev.rovsti.design.controls.EmptyState
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.foundation.LocalIosBackdrop
import dev.rovsti.design.list.CustomAction
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.SwipeAction
import dev.rovsti.design.list.SwipeRow
import dev.rovsti.design.list.SwipeRowGroup
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.LocalOverlayOrNull
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.confirmDestructive
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.design.text.GlassTextField
import dev.rovsti.design.text.imeOverlapPadding
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.BlockedMetrics
import dev.rovsti.phone.R
import dev.rovsti.shell.settings.SettingsPage
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the Blocked numbers page and its sheets. */
object BlockedTags {
    const val Page = "blocked-page"
    const val Empty = "blocked-empty"
    const val AddNumbers = "blocked-add-numbers"
    const val AddPrefix = "blocked-add-prefix"
    const val Share = "blocked-share"
    const val Export = "blocked-export"
    const val Import = "blocked-import"
    const val UnblockAll = "blocked-unblock-all"
    const val PasteSheet = "blocked-paste-sheet"
    const val PasteField = "blocked-paste-field"
    const val PastePreview = "blocked-paste-preview"
    const val PasteConfirm = "blocked-paste-confirm"
    const val PasteCancel = "blocked-paste-cancel"
    const val PrefixSheet = "blocked-prefix-sheet"
    const val PrefixField = "blocked-prefix-field"
    const val PrefixPreview = "blocked-prefix-preview"
    const val PrefixConfirm = "blocked-prefix-confirm"
    const val Footer = "blocked-footer"

    fun row(key: String) = "blocked-row-$key"

    fun call(key: String) = "blocked-call-$key"
}

/** What the page asks of Android (the pickers, the share sheet, the clipboard) and of the app (the number sheet). */
interface BlockedHost {
    fun share(text: String)

    fun export(
        fileName: String,
        csv: String,
    )

    fun importFile()

    fun copy(text: String)

    fun openNumber(number: String)
}

/**
 * Settings, Blocked numbers (E5.9a) on its view model: the page, its two sheets (Block numbers, Block a prefix) and the
 * one-shot results (pills, Undo, confirmation, pickers).
 */
@Composable
fun BlockedScreen(
    model: BlockedViewModel,
    host: BlockedHost,
    modifier: Modifier = Modifier,
    onOpenSetup: () -> Unit = {},
) {
    val ui by model.state.collectAsState()
    val overlays = LocalOverlayOrNull.current
    val strings = BlockedStrings.current()
    val sheets = remember(overlays, model, strings) { overlays?.let { BlockedSheets(it, model, strings) } }
    LaunchedEffect(model) { model.onAction(BlockedAction.Opened) }
    BlockedEffects(model, host, strings, sheets)
    BlockedContent(
        ui = ui,
        onAction = model::onAction,
        onPaste = { sheets?.presentPaste() },
        onPrefix = { sheets?.presentPrefix() },
        onImport = host::importFile,
        modifier = modifier,
    )
}

/**
 * The page, stateless: an actions card (Block numbers, Block a prefix), the numbers (swipe to unblock; the same
 * action is offered to a screen reader), the prefix ranges, the tools (share, export, import, unblock all) and the
 * blocked calls. An empty list says so instead of showing empty cards.
 */
@Composable
fun BlockedContent(
    ui: BlockedUi,
    onAction: (BlockedAction) -> Unit,
    onPaste: () -> Unit,
    onPrefix: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RovstiTheme.colors
    val unblock = stringResource(R.string.blocked_unblock)
    // The cards bring their own 16 dp gutters, so the page itself has none.
    SettingsPage(
        title = stringResource(R.string.blocked_title),
        modifier = modifier.testTag(BlockedTags.Page),
        horizontalPadding = BlockedMetrics.NoGutter,
    ) {
        SwipeRowGroup {
            InsetGroupedSection(
                footer = if (ui.ready && !ui.canWrite) stringResource(R.string.blocked_footer_cannot) else null,
            ) {
                row { ActionRow(RovstiIcons.Plus, R.string.blocked_block_numbers, BlockedTags.AddNumbers, onPaste) }
                row { ActionRow(RovstiIcons.Block, R.string.blocked_block_prefix, BlockedTags.AddPrefix, onPrefix) }
            }
            if (ui.ready && ui.isEmpty) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = Spacing.xl).testTag(BlockedTags.Empty),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = RovstiIcons.Block,
                        title = stringResource(R.string.blocked_empty_title),
                        message = stringResource(R.string.blocked_empty_message),
                    )
                }
            }
            if (ui.numbers.isNotEmpty()) {
                Section()
                InsetGroupedSection(header = stringResource(R.string.blocked_group_numbers)) {
                    ui.numbers.forEach { item -> row { UnblockRow(item, unblock, onAction) } }
                }
            }
            if (ui.ranges.isNotEmpty()) {
                Section()
                InsetGroupedSection(header = stringResource(R.string.blocked_group_ranges)) {
                    ui.ranges.forEach { item -> row { UnblockRow(item, unblock, onAction) } }
                }
            }
            Section()
            InsetGroupedSection {
                if (!ui.isEmpty) {
                    row {
                        ActionRow(
                            RovstiIcons.Share,
                            R.string.blocked_tools_share,
                            BlockedTags.Share,
                        ) { onAction(BlockedAction.Share) }
                    }
                    row {
                        ActionRow(
                            RovstiIcons.Download,
                            R.string.blocked_tools_export,
                            BlockedTags.Export,
                        ) { onAction(BlockedAction.Export) }
                    }
                }
                row { ActionRow(RovstiIcons.Folder, R.string.blocked_tools_import, BlockedTags.Import, onImport) }
                if (!ui.isEmpty) {
                    row {
                        ListRow(
                            title = stringResource(R.string.blocked_tools_unblock_all),
                            titleColor = colors.destructive,
                            onClick = { onAction(BlockedAction.AskUnblockAll) },
                            modifier = Modifier.testTag(BlockedTags.UnblockAll),
                        )
                    }
                }
            }
            if (ui.calls.isNotEmpty()) {
                Section()
                InsetGroupedSection(header = stringResource(R.string.blocked_group_calls)) {
                    ui.calls.forEach { call -> row { CallRow(call, onAction) } }
                }
            }
        }
    }
}

/** The gap between two cards (the header of the next one adds its own top padding). */
@Composable
private fun Section() {
    Box(Modifier.height(Spacing.sectionGap))
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: Int,
    tag: String,
    onClick: () -> Unit,
) {
    val accent = RovstiTheme.colors.accent
    ListRow(
        title = stringResource(title),
        titleColor = accent,
        leading = { RovstiIcon(icon, null, tint = accent, size = BlockedMetrics.Glyph) },
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun UnblockRow(
    item: BlockedRowUi,
    unblock: String,
    onAction: (BlockedAction) -> Unit,
) {
    val colors = RovstiTheme.colors
    val remove = { onAction(BlockedAction.Unblock(item.key)) }
    val swipe =
        remember(item.key, unblock, colors.destructive) {
            persistentListOf(SwipeAction(unblock, colors.destructive, null, remove))
        }
    SwipeRow(trailing = swipe, rowKey = item.key, modifier = Modifier.testTag(BlockedTags.row(item.key))) {
        ListRow(
            title = item.title,
            secondary = item.subtitle,
            actions = remember(item.key, unblock) { persistentListOf(CustomAction(unblock, remove)) },
        )
    }
}

@Composable
private fun CallRow(
    call: BlockedCallUi,
    onAction: (BlockedAction) -> Unit,
) {
    val reason =
        when (call.reason) {
            BlockReason.User -> stringResource(R.string.blocked_reason_user)
            BlockReason.System -> stringResource(R.string.blocked_reason_system)
            else -> null
        }
    val line = listOfNotNull(reason, call.whenText.takeIf { it.isNotBlank() }).joinToString(CALL_SEPARATOR)
    ListRow(
        title = call.title,
        secondary = line.ifBlank { null },
        onClick = { onAction(BlockedAction.OpenCall(call.key)) },
        modifier = Modifier.testTag(BlockedTags.call(call.key)),
    )
}

private const val CALL_SEPARATOR = " · "

// ---- sheets -----------------------------------------------------------------------------------------------------------

/** Presents the Block numbers and Block a prefix sheets over the page, one at a time. */
class BlockedSheets(
    private val overlays: OverlayController,
    private val model: BlockedViewModel,
    private val strings: BlockedStrings,
) {
    private var entry: SheetEntry? = null

    fun presentPaste() =
        present(strings.pasteTitle, BlockedAction.PasteClosed) { sheet -> PasteSheet(model, sheet::dismiss) }

    fun presentPrefix() =
        present(strings.prefixTitle, BlockedAction.PrefixClosed) { sheet -> PrefixSheet(model, sheet::dismiss) }

    fun close() {
        entry?.sheet?.dismiss()
    }

    private fun present(
        title: String,
        closed: BlockedAction,
        content: @Composable ColumnScope.(dev.rovsti.design.overlay.SheetState) -> Unit,
    ) {
        val open = entry
        if (open != null && !open.isRemovable) return
        entry =
            overlays.present(
                SheetEntry(
                    title = title,
                    detents = persistentListOf(Detent.Medium, Detent.Large),
                    initialDetent = Detent.Large,
                    onDismissed = { model.onAction(closed) },
                    content = content,
                ),
            ) as? SheetEntry
    }
}

@Composable
private fun ColumnScope.PasteSheet(
    model: BlockedViewModel,
    onClose: () -> Unit,
) {
    val ui by model.state.collectAsState()
    val field = rememberTextFieldState()
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.collect { model.onAction(BlockedAction.PasteTyped(it)) }
    }
    PasteSheetContent(ui, field, model::onAction, onClose)
}

/** The Block numbers sheet, stateless: a multi-line field, the core's preview of what it holds, Cancel and Block. */
@Composable
fun ColumnScope.PasteSheetContent(
    ui: BlockedUi,
    field: TextFieldState,
    onAction: (BlockedAction) -> Unit,
    onClose: () -> Unit,
) {
    val preview = ui.paste
    val line =
        when {
            preview == null -> stringResource(R.string.blocked_paste_hint)
            !preview.found -> stringResource(R.string.blocked_preview_none)
            else -> previewText(preview)
        }
    SheetBody(BlockedTags.PasteSheet, stringResource(R.string.blocked_block_numbers)) {
        GlassTextField(
            state = field,
            placeholder = stringResource(R.string.blocked_paste_placeholder),
            modifier = Modifier.fillMaxWidth().testTag(BlockedTags.PasteField),
            maxLines = PASTE_MAX_LINES,
            minLines = PASTE_MIN_LINES,
        )
        PreviewLine(line, BlockedTags.PastePreview, warn = false)
        SheetButtons(
            cancelTag = BlockedTags.PasteCancel,
            confirmTag = BlockedTags.PasteConfirm,
            confirmEnabled = (preview?.toBlock ?: 0) > 0,
            onCancel = onClose,
            onConfirm = { onAction(BlockedAction.PasteConfirm) },
        )
    }
}

@Composable
private fun previewText(preview: PastePreview): String =
    listOfNotNull(
        if (preview.toBlock > 0) stringResource(R.string.blocked_preview_found, preview.toBlock) else null,
        if (preview.emergency > 0) stringResource(R.string.blocked_preview_emergency, preview.emergency) else null,
        if (preview.alreadyBlocked >
            0
        ) {
            stringResource(R.string.blocked_preview_already, preview.alreadyBlocked)
        } else {
            null
        },
    ).joinToString(PREVIEW_SEPARATOR)

private const val PREVIEW_SEPARATOR = ", "
private const val PASTE_MIN_LINES = 4
private const val PASTE_MAX_LINES = 8

@Composable
private fun ColumnScope.PrefixSheet(
    model: BlockedViewModel,
    onClose: () -> Unit,
) {
    val ui by model.state.collectAsState()
    val field = rememberTextFieldState()
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.collect { model.onAction(BlockedAction.PrefixTyped(it)) }
    }
    PrefixSheetContent(ui, field, model::onAction, onClose)
}

/** The Block a prefix sheet, stateless: one field, the range as it will read, and why it cannot be added yet. */
@Composable
fun ColumnScope.PrefixSheetContent(
    ui: BlockedUi,
    field: TextFieldState,
    onAction: (BlockedAction) -> Unit,
    onClose: () -> Unit,
) {
    val preview = ui.prefix
    val problem = preview?.problem
    val line =
        when {
            preview == null -> stringResource(R.string.blocked_prefix_hint)
            problem == PrefixProblem.Invalid -> stringResource(R.string.blocked_prefix_invalid)
            problem == PrefixProblem.TooShort -> stringResource(R.string.blocked_prefix_short)
            problem == PrefixProblem.Emergency -> stringResource(R.string.blocked_prefix_emergency)
            problem == PrefixProblem.Already -> stringResource(R.string.blocked_prefix_already)
            else -> stringResource(R.string.blocked_prefix_ok, "${preview.display} *")
        }
    SheetBody(BlockedTags.PrefixSheet, stringResource(R.string.blocked_block_prefix)) {
        GlassTextField(
            state = field,
            placeholder = stringResource(R.string.blocked_prefix_placeholder),
            modifier = Modifier.fillMaxWidth().testTag(BlockedTags.PrefixField),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            onSubmit = { if (preview?.display != null && problem == null) onAction(BlockedAction.PrefixConfirm) },
        )
        PreviewLine(line, BlockedTags.PrefixPreview, warn = problem != null && problem != PrefixProblem.TooShort)
        SheetButtons(
            cancelTag = null,
            confirmTag = BlockedTags.PrefixConfirm,
            confirmEnabled = preview?.display != null && problem == null,
            onCancel = onClose,
            onConfirm = { onAction(BlockedAction.PrefixConfirm) },
        )
    }
}

/**
 * The frame both sheets share: a title, then the content in a column that pads itself by the keyboard's overlap
 * ([imeOverlapPadding], never `imePadding`: some vendor builds resize the window too), so the field stays above the
 * keyboard and the sheet does not jump.
 */
@Composable
private fun ColumnScope.SheetBody(
    tag: String,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalIosBackdrop provides null) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .imeOverlapPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter)
                .testTag(tag),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            RovstiText(
                title,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.s).semantics { heading() },
                style = RovstiTheme.type.title2,
                textAlign = TextAlign.Center,
            )
            content()
            Box(Modifier.height(Spacing.l))
        }
    }
}

@Composable
private fun PreviewLine(
    text: String,
    tag: String,
    warn: Boolean,
) {
    val colors = RovstiTheme.colors
    RovstiText(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s).testTag(tag),
        style = RovstiTheme.type.footnote,
        color = if (warn) colors.destructive else colors.labelSecondary,
    )
}

@Composable
private fun SheetButtons(
    cancelTag: String?,
    confirmTag: String,
    confirmEnabled: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        CapsuleButton(
            stringResource(R.string.blocked_cancel),
            onClick = onCancel,
            modifier = Modifier.weight(1f).then(if (cancelTag != null) Modifier.testTag(cancelTag) else Modifier),
            fillWidth = true,
        )
        CapsuleButton(
            stringResource(R.string.blocked_block),
            onClick = onConfirm,
            modifier = Modifier.weight(1f).testTag(confirmTag),
            style = CapsuleStyle.Filled,
            enabled = confirmEnabled,
            fillWidth = true,
        )
    }
}

// ---- one-shot results ---------------------------------------------------------------------------------------------------

/** Runs the view model's one-shot results: pills, the Undo pill, the confirmation and the pickers. */
@Composable
fun BlockedEffects(
    model: BlockedViewModel,
    host: BlockedHost,
    strings: BlockedStrings,
    sheets: BlockedSheets?,
) {
    val overlays = LocalOverlayOrNull.current ?: return
    val currentHost by rememberUpdatedState(host)
    LaunchedEffect(model, overlays, strings, sheets) {
        model.effect.collect { effect ->
            when (effect) {
                is BlockedEffect.Say -> {
                    overlays.showToast(strings.notice(effect.notice))
                }

                is BlockedEffect.Undoable -> {
                    overlays.showUndo(
                        message = strings.pill(effect.kind, effect.count),
                        onUndo = { model.onAction(BlockedAction.Undo(effect.undo)) },
                        actionLabel = strings.undo,
                    )
                }

                is BlockedEffect.ConfirmUnblockAll -> {
                    overlays.confirmDestructive(
                        destructiveLabel = strings.unblockAll,
                        title = strings.confirmTitle(effect.count),
                        message = strings.confirmMessage,
                        cancelLabel = strings.cancel,
                        onConfirm = { model.onAction(BlockedAction.ConfirmUnblockAll) },
                    )
                }

                is BlockedEffect.Share -> {
                    currentHost.share(effect.text)
                }

                is BlockedEffect.Export -> {
                    currentHost.export(effect.fileName, effect.csv)
                }

                is BlockedEffect.OpenNumber -> {
                    currentHost.openNumber(effect.number)
                }

                is BlockedEffect.Copy -> {
                    currentHost.copy(effect.text)
                    overlays.showToast(strings.notice(BlockedNotice.Copied))
                }

                BlockedEffect.CloseSheet -> {
                    sheets?.close()
                }
            }
        }
    }
}
