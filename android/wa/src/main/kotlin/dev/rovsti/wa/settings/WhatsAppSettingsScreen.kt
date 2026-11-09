package dev.rovsti.wa.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rovsti.core.model.qr.QrMatrix
import dev.rovsti.core.model.wa.SettingsButton
import dev.rovsti.core.model.wa.SettingsField
import dev.rovsti.core.model.wa.SettingsInput
import dev.rovsti.core.model.wa.SettingsKey
import dev.rovsti.core.model.wa.SettingsRow
import dev.rovsti.core.model.wa.SettingsView
import dev.rovsti.design.chrome.LargeTitleScaffold
import dev.rovsti.design.chrome.NavigationButton
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.controls.CapsuleStyle
import dev.rovsti.design.controls.EmptyState
import dev.rovsti.design.controls.TextButton
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ListRowChevron
import dev.rovsti.design.overlay.AlertAction
import dev.rovsti.design.overlay.AlertEntry
import dev.rovsti.design.overlay.AlertRole
import dev.rovsti.design.overlay.LocalOverlayOrNull
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.menuTrigger
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.text.GlassTextField
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import dev.rovsti.platform.files.ShareIntents
import kotlinx.collections.immutable.persistentListOf
import kotlin.math.floor

// The WhatsApp settings of every Rovsti app (E3.10). It draws the `view` of the Rust state machine and decides
// nothing: which page, which buttons, which words all come from `wa.settings_apply`. What it adds is only how a page
// kind looks (rows, fields, QR card, code, failure page) and the platform actions a button without an input stands for.

/** Space between the blocks of a page. */
private val BlockGap = Spacing.sectionGap - Spacing.s

/** Text of a note or a header sits where the text of a grouped row sits (card inset plus row gutter). */
private val NoteInset = Spacing.gutter + Spacing.gutter

private val QrSide = 240.dp
private val QrCardPadding = Spacing.l
private const val QR_QUIET_MODULES = 2
private const val LARGE_FONT = 1.3f
private val StatusDot = 10.dp
private val GatewayValueMax = 200.dp

/** The route: collects the view model and shows what its events ask for (a "Copied" toast, a share sheet). */
@Composable
fun WhatsAppSettingsRoute(
    viewModel: WhatsAppSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val overlay = LocalOverlayOrNull.current
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            try {
                when (event) {
                    is WaSettingsEvent.Toast -> {
                        overlay?.showToast(event.message)
                    }

                    is WaSettingsEvent.ShareImage -> {
                        context.startActivity(
                            ShareIntents.image(event.uri, "image/png", "WhatsApp"),
                        )
                    }

                    is WaSettingsEvent.ShareText -> {
                        context.startActivity(ShareIntents.text(event.text))
                    }
                }
            } catch (_: ActivityNotFoundException) {
                overlay?.showToast("Nothing can share this")
            }
        }
    }
    WhatsAppSettingsScreen(state = state, onAction = viewModel::onAction, modifier = modifier)
}

/** The screen. [state] is all it knows; every tap goes out through [onAction]. */
@Composable
fun WhatsAppSettingsScreen(
    state: WaSettingsUiState,
    onAction: (WaSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val page = state.page
    val back = page.back
    val latestAction by rememberUpdatedState(onAction)
    if (back != null) BackHandler { latestAction(WaSettingsAction.Press(back)) }
    ConfirmAlert(state.view, onAction)
    LaunchedEffect(page.kind) { listState.scrollToItem(0) }
    val fields =
        remember(page.kind, page.fields) { page.fields.associate { it.id to TextFieldState(it.initial) } }
    val paste = state.paste
    LaunchedEffect(paste?.id) {
        if (paste != null) {
            fields["key"]?.setTextAndPlaceCursorAtEnd(paste.text)
            latestAction(WaSettingsAction.PasteDone(paste.id))
        }
    }
    LargeTitleScaffold(
        title = page.title,
        state = listState,
        actions = persistentListOf(),
        navigation =
            back?.let { target ->
                NavigationButton(onClick = { latestAction(WaSettingsAction.Press(target)) })
            },
        modifier = modifier.testTag("wa-settings"),
    ) {
        pageItems(state, fields, onAction)
    }
}

private fun LazyListScope.pageItems(
    state: WaSettingsUiState,
    fields: Map<String, TextFieldState>,
    onAction: (WaSettingsAction) -> Unit,
) {
    val page = state.page
    val intro = page.fields.isNotEmpty() || page.qrPayload != null || page.code != null || page.keyLines.isNotEmpty()
    if (page.kind == FAILED) {
        item(key = "failed") { FailurePage(page, onAction) }
    }
    if (intro) notes(page.notes)
    if (page.rows.isNotEmpty()) item(key = "rows") { StatusRows(page.rows) }
    if (page.qrPayload != null) item(key = "qr") { QrCard(state.qr) }
    page.code?.let { code -> item(key = "code") { PairingCode(code) } }
    if (page.keyLines.isNotEmpty()) item(key = "key-lines") { KeyBlock(page.keyLines, state.keyQr, onAction) }
    for (field in page.fields) {
        item(key = "field-${field.id}") {
            FieldBlock(field, fields.getValue(field.id), page, fields, onAction)
        }
    }
    if (page.keys.isNotEmpty()) item(key = "keys") { KeyList(page.keys, onAction) }
    if (page.kind != FAILED) buttons(page, fields, onAction)
    if (!intro && page.kind != FAILED) notes(page.notes)
}

private fun LazyListScope.notes(notes: List<String>) {
    if (notes.isEmpty()) return
    item(key = "notes") {
        Column(
            Modifier.padding(horizontal = NoteInset).padding(top = Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            for (note in notes) {
                RovstiText(note, style = RovstiTheme.type.footnote, color = RovstiTheme.colors.labelSecondary)
            }
        }
    }
}

// ---- blocks

@Composable
private fun StatusRows(rows: List<SettingsRow>) {
    InsetGroupedSection(Modifier.padding(top = BlockGap).testTag("wa-status")) {
        for (r in rows) {
            row {
                ListRow(
                    title = r.label,
                    trailing = { StatusValue(r) },
                )
            }
        }
    }
}

@Composable
private fun StatusValue(row: SettingsRow) {
    val colors = RovstiTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        if (row.role == "status") {
            val dot =
                when (row.state) {
                    "connected" -> colors.success
                    "logged_out", "banned" -> colors.destructive
                    else -> colors.labelTertiary
                }
            Box(
                Modifier
                    .size(StatusDot)
                    .clip(CircleShape)
                    .background(dot)
                    .testTag("wa-status-dot"),
            )
        }
        row.value?.let { RovstiText(it, style = RovstiTheme.type.body, color = colors.labelSecondary, maxLines = 1) }
    }
}

/** A square white card with the code in it; the quiet zone is part of the card, so cameras read it on any theme. */
@Composable
private fun QrCard(matrix: QrMatrix?) {
    Box(Modifier.fillMaxWidth().padding(top = BlockGap), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .clip(RoundedCornerShape(QrCardPadding + Spacing.xs))
                .background(Color.White)
                .padding(QrCardPadding)
                .size(QrSide)
                .testTag("wa-qr"),
            contentAlignment = Alignment.Center,
        ) {
            if (matrix != null) QrImage(matrix, Modifier.size(QrSide))
        }
    }
}

/** Draws [matrix] square, with whole pixels per module so the edges stay sharp. */
@Composable
internal fun QrImage(
    matrix: QrMatrix,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .semantics { contentDescription = "QR code" }
            .drawWithCache {
                val cells = matrix.side + 2 * QR_QUIET_MODULES
                val cell = floor(size.minDimension / cells)
                val origin = (size.minDimension - cell * cells) / 2f
                val dark = Path()
                for (y in 0 until matrix.side) {
                    var x = 0
                    while (x < matrix.side) {
                        if (!matrix[x, y]) {
                            x++
                            continue
                        }
                        val start = x
                        while (x < matrix.side && matrix[x, y]) x++
                        dark.addRect(
                            Rect(
                                Offset(
                                    origin + (start + QR_QUIET_MODULES) * cell,
                                    origin + (y + QR_QUIET_MODULES) * cell,
                                ),
                                Size((x - start) * cell, cell),
                            ),
                        )
                    }
                }
                onDrawBehind { drawPath(dark, Color.Black) }
            },
    )
}

/** "ABCD-1234" in two groups of four, monospaced and large; at a very large font size the groups stack. */
@Composable
private fun PairingCode(code: String) {
    val groups = code.filter { it.isLetterOrDigit() }.chunked(CODE_GROUP).take(CODE_GROUPS)
    val style = RovstiTheme.type.title1.copy(fontFamily = FontFamily.Monospace, letterSpacing = 3.sp)
    val stacked = LocalDensity.current.fontScale > LARGE_FONT
    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(top = BlockGap)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Spacing.xl))
                .background(RovstiTheme.colors.groupedSurface)
                .padding(vertical = Spacing.xl, horizontal = Spacing.l)
                .semantics(mergeDescendants = true) {
                    contentDescription =
                        groups.joinToString(" ") { it.toList().joinToString(" ") }
                }.testTag("wa-code"),
            contentAlignment = Alignment.Center,
        ) {
            if (stacked) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    for (g in groups) RovstiText(g, style = style, maxLines = 1)
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (g in groups) RovstiText(g, style = style, maxLines = 1)
                }
            }
        }
    }
}

/** The connect key on the "Show connect key" page: monospaced lines in a card, with share and QR below. */
@Composable
private fun KeyBlock(
    lines: List<String>,
    keyQr: QrMatrix?,
    onAction: (WaSettingsAction) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = BlockGap)) {
        Box(
            Modifier
                .padding(horizontal = Spacing.gutter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Spacing.xl))
                .background(RovstiTheme.colors.groupedSurface)
                .padding(Spacing.l)
                .testTag("wa-key-lines"),
        ) {
            Column {
                for (line in lines) {
                    RovstiText(line, style = RovstiTheme.type.callout.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
        InsetGroupedSection(Modifier.padding(top = BlockGap)) {
            row { ActionRow("Share key…", { onAction(WaSettingsAction.ShareKey) }, "wa-share-key") }
            row {
                ActionRow(
                    if (keyQr ==
                        null
                    ) {
                        "Show as QR code"
                    } else {
                        "Hide QR code"
                    },
                    { onAction(WaSettingsAction.ToggleKeyQr) },
                    "wa-key-qr-toggle",
                )
            }
        }
        if (keyQr != null) QrCard(keyQr)
    }
}

@Composable
private fun FieldBlock(
    field: SettingsField,
    state: TextFieldState,
    page: SettingsView,
    fields: Map<String, TextFieldState>,
    onAction: (WaSettingsAction) -> Unit,
) {
    val submit = page.buttons.firstOrNull { it.field == field.id }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(top = BlockGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        GlassTextField(
            state = state,
            placeholder = field.placeholder,
            modifier = Modifier.weight(1f).testTag("wa-field-${field.id}"),
            keyboardOptions = keyboardFor(field.keyboard),
            onSubmit = { submit?.let { press(it, fields, onAction) } },
        )
        if (field.keyboard == "key") {
            TextButton(
                "Paste",
                onClick = { onAction(WaSettingsAction.PasteClipboard) },
                modifier = Modifier.testTag("wa-paste"),
            )
        }
    }
}

private fun keyboardFor(kind: String): KeyboardOptions =
    when (kind) {
        "url" -> {
            KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            )
        }

        "phone" -> {
            KeyboardOptions(keyboardType = KeyboardType.Phone)
        }

        else -> {
            KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            )
        }
    }

@Composable
private fun KeyList(
    keys: List<SettingsKey>,
    onAction: (WaSettingsAction) -> Unit,
) {
    InsetGroupedSection(Modifier.padding(top = BlockGap).testTag("wa-keys")) {
        for (key in keys) {
            row {
                ListRow(
                    title = key.label,
                    secondary = key.detail,
                    secondaryMaxLines = SECONDARY_LINES,
                    trailing = { KeyMenuButton(key, onAction) },
                    modifier = Modifier.testTag("wa-key-${key.keyId}"),
                )
            }
        }
    }
}

/** The row's "…": opens a menu that floats over the rows (it never takes room in the list). */
@Composable
private fun KeyMenuButton(
    key: SettingsKey,
    onAction: (WaSettingsAction) -> Unit,
) {
    val overlay = LocalOverlayOrNull.current ?: return
    val items =
        remember(key) {
            persistentListOf<MenuItem>(
                MenuItem.Action(
                    "Revoke",
                    destructive = true,
                    onClick = { onAction(WaSettingsAction.Press(key.revoke)) },
                ),
            )
        }
    Box(
        Modifier
            .size(Sizes.touch)
            .menuTrigger(overlay, items, label = "More for ${key.label}")
            .testTag("wa-key-menu-${key.keyId}"),
        contentAlignment = Alignment.Center,
    ) {
        RovstiIcon(RovstiIcons.Ellipsis, null, tint = RovstiTheme.colors.labelSecondary)
    }
}

// ---- buttons

private fun LazyListScope.buttons(
    page: SettingsView,
    fields: Map<String, TextFieldState>,
    onAction: (WaSettingsAction) -> Unit,
) {
    val shown =
        page.buttons.filter { b ->
            // The nav button is Back; the key field has its own Paste button.
            !(b.input?.kind == BACK && page.back != null) && b.id != PASTE_CLIPBOARD
        }
    var group = mutableListOf<SettingsButton>()
    var index = 0

    fun flush() {
        if (group.isEmpty()) return
        val rows = group
        val at = index++
        group = mutableListOf()
        item(key = "group-$at") { ButtonGroup(rows, fields, onAction) }
    }
    for (b in shown) {
        when {
            b.role == PRIMARY -> {
                flush()
                val at = index++
                item(key = "primary-$at") {
                    CapsuleButton(
                        text = b.label,
                        onClick = { press(b, fields, onAction) },
                        style = CapsuleStyle.Filled,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = Spacing.gutter,
                                ).padding(top = BlockGap)
                                .testTag("wa-${b.id}"),
                    )
                }
            }

            b.section != null || (b.destructive && group.isNotEmpty()) -> {
                flush()
                group += b
            }

            else -> {
                group += b
            }
        }
    }
    flush()
}

@Composable
private fun ButtonGroup(
    buttons: List<SettingsButton>,
    fields: Map<String, TextFieldState>,
    onAction: (WaSettingsAction) -> Unit,
) {
    InsetGroupedSection(Modifier.padding(top = BlockGap), header = buttons.first().section) {
        for (b in buttons) {
            row { ButtonRow(b) { press(b, fields, onAction) } }
        }
    }
}

@Composable
private fun ButtonRow(
    b: SettingsButton,
    onClick: () -> Unit,
) {
    val colors = RovstiTheme.colors
    val tag = Modifier.testTag("wa-${b.id}")
    when {
        // The gateway address: its value is an URL, so it shortens in the middle and keeps both ends readable.
        b.id == EDIT_URL -> {
            ListRow(
                title = b.label,
                modifier = tag,
                onClick = onClick,
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        b.detail?.let {
                            RovstiText(
                                it,
                                modifier = Modifier.widthIn(max = GatewayValueMax).testTag("wa-gateway-value"),
                                style = RovstiTheme.type.body,
                                color = colors.labelSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                        }
                        ListRowChevron()
                    }
                },
            )
        }

        b.role == OPTION -> {
            ListRow(
                title = b.label,
                secondary = b.detail,
                secondaryMaxLines = SECONDARY_LINES,
                titleMaxLines = TITLE_LINES,
                modifier = tag,
                onClick = onClick,
                trailing = { ListRowChevron() },
            )
        }

        else -> {
            ActionRow(b.label, onClick, "wa-${b.id}", destructive = b.destructive)
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    onClick: () -> Unit,
    tag: String,
    destructive: Boolean = false,
) {
    val colors = RovstiTheme.colors
    ListRow(
        title = label,
        titleColor = if (destructive) colors.destructive else colors.accent,
        modifier = Modifier.testTag(tag),
        onClick = onClick,
    )
}

/** A failed call: what went wrong, and Try again. */
@Composable
private fun FailurePage(
    page: SettingsView,
    onAction: (WaSettingsAction) -> Unit,
) {
    val retry = page.buttons.firstOrNull { it.role == PRIMARY }
    Box(Modifier.fillMaxWidth().padding(top = BlockGap), contentAlignment = Alignment.Center) {
        EmptyState(
            icon = RovstiIcons.WhatsApp,
            title = page.title,
            message = page.error.orEmpty(),
            actionLabel = retry?.label,
            onAction = retry?.input?.let { input -> { onAction(WaSettingsAction.Press(input)) } },
            modifier = Modifier.testTag("wa-failed"),
        )
    }
    Spacer(Modifier.height(Spacing.l))
}

// ---- helpers

/** A button's input with its field's text, or the platform action a button without an input stands for. */
private fun press(
    button: SettingsButton,
    fields: Map<String, TextFieldState>,
    onAction: (WaSettingsAction) -> Unit,
) {
    val input: SettingsInput? = button.input
    when {
        input != null -> {
            val text = button.field?.let { fields[it]?.text?.toString() }
            onAction(WaSettingsAction.Press(if (text != null) input.withText(text.trim()) else input))
        }

        button.id == SHARE_QR -> {
            onAction(WaSettingsAction.ShareQr)
        }

        button.id == PASTE_CLIPBOARD -> {
            onAction(WaSettingsAction.PasteClipboard)
        }
    }
}

/**
 * A confirmation of the machine (`confirm_*`) is an alert over the page it came from: Cancel is the page's Back, the
 * destructive button is the confirm button of the view. For deleting the instance this is the second of the two taps.
 */
@Composable
private fun ConfirmAlert(
    view: SettingsView,
    onAction: (WaSettingsAction) -> Unit,
) {
    val overlay = LocalOverlayOrNull.current ?: return
    val latest by rememberUpdatedState(onAction)
    LaunchedEffect(view.kind, view.title) {
        if (!view.kind.startsWith("confirm_")) return@LaunchedEffect
        val confirm = view.buttons.firstOrNull { it.destructive }?.input ?: return@LaunchedEffect
        val cancel = view.back ?: view.buttons.firstOrNull { it.input?.kind == BACK }?.input
        overlay.present(
            AlertEntry(
                heading = view.title,
                message = view.notes.joinToString("\n\n").ifEmpty { null },
                actions =
                    persistentListOf(
                        AlertAction("Cancel", AlertRole.Cancel) { cancel?.let { latest(WaSettingsAction.Press(it)) } },
                        AlertAction(
                            view.buttons.first { it.destructive }.label,
                            AlertRole.Destructive,
                        ) { latest(WaSettingsAction.Press(confirm)) },
                    ),
            ),
        )
    }
}

private const val FAILED = "failed"
private const val BACK = "back"
private const val PRIMARY = "primary"
private const val OPTION = "option"
private const val EDIT_URL = "edit_url"
private const val SHARE_QR = "share_qr"
private const val PASTE_CLIPBOARD = "paste_clipboard"
private const val CODE_GROUP = 4
private const val CODE_GROUPS = 2
private const val SECONDARY_LINES = 3
private const val TITLE_LINES = 3
