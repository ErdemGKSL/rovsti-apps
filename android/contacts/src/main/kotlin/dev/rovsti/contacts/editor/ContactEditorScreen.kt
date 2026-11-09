package dev.rovsti.contacts.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import dev.rovsti.contacts.R
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.EventKind
import dev.rovsti.core.model.contacts.FieldKind
import dev.rovsti.core.model.contacts.RelationKind
import dev.rovsti.design.controls.TextButton
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ListRowChevron
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.menuTrigger
import dev.rovsti.design.overlay.popoverTrigger
import dev.rovsti.design.text.GlassTextField
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.roundToInt

/** Test tags of the editor. */
object EditorTags {
    const val Screen = "editor-screen"
    const val Scroll = "editor-scroll"
    const val Cancel = "editor-cancel"
    const val Done = "editor-done"
    const val Account = "editor-account"
    const val Groups = "editor-groups"
    const val Title = "editor-title"

    fun field(name: String) = "editor-field-$name"

    fun row(
        list: EditorList,
        index: Int,
    ) = "editor-${list.name.lowercase()}-$index"

    fun rowField(
        list: EditorList,
        index: Int,
    ) = "editor-${list.name.lowercase()}-field-$index"

    fun minus(
        list: EditorList,
        index: Int,
    ) = "editor-${list.name.lowercase()}-minus-$index"

    fun label(
        list: EditorList,
        index: Int,
    ) = "editor-${list.name.lowercase()}-label-$index"

    fun add(list: EditorList) = "editor-${list.name.lowercase()}-add"

    fun issue(key: String) = "editor-issue-$key"

    const val LabelPopover = "editor-label-popover"
    const val GroupsSheet = "editor-groups-sheet"
}

/** Where a focused field may be asked to scroll into view by its issue key (see [EditorEffect.Invalid]). */
private val LocalIssueTargets = compositionLocalOf<MutableMap<String, BringIntoViewRequester>> { mutableMapOf() }

/**
 * The editor form (E4.4), stateless: Cancel / title / Done, then the sections in one scrolling column. The column ends
 * above the keyboard (the window insets minus the navigation bar the sheet already pads) and a focused field is kept
 * [EditorMetrics.RevealMargin] above it. A repeatable row has a red minus on the left and its label on the right; the
 * label opens a popover over the row, so nothing moves. A row added by "add ..." grows in on the `appear` spring.
 *
 * [invalid] carries the issue keys of refused Saves; the page scrolls the first one into view.
 */
@Composable
fun ContactEditorScreen(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
    overlays: OverlayController = LocalOverlay.current,
    invalid: Flow<String> = emptyFlow(),
) {
    val targets = remember { mutableMapOf<String, BringIntoViewRequester>() }
    LaunchedEffect(invalid, targets) {
        invalid.collect { key -> targets[key]?.bringIntoView() }
    }
    CompositionLocalProvider(LocalIssueTargets provides targets) {
        Column(modifier.fillMaxSize().testTag(EditorTags.Screen)) {
            Header(state, onAction)
            if (state.phase == EditorPhase.Ready) {
                Form(state, onAction, overlays)
            } else {
                Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Header(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(EditorMetrics.HeaderHeight)
            .padding(horizontal = Spacing.s),
    ) {
        TextButton(
            "Cancel",
            onClick = { onAction(EditorAction.RequestClose) },
            modifier = Modifier.align(Alignment.CenterStart).testTag(EditorTags.Cancel),
        )
        RovstiText(
            if (state.isNew) "New Contact" else "Edit Contact",
            style = RovstiTheme.type.headline,
            modifier = Modifier.align(Alignment.Center).testTag(EditorTags.Title).semantics { heading() },
            maxLines = 1,
        )
        TextButton(
            "Done",
            onClick = { onAction(EditorAction.Save) },
            enabled = state.canSave,
            modifier = Modifier.align(Alignment.CenterEnd).testTag(EditorTags.Done),
        )
    }
}

@Composable
private fun ColumnScope.Form(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    overlays: OverlayController,
) {
    val draft = state.draft
    val initialKeys = remember { state.keys }
    val latest = rememberUpdatedState(state)
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))
            .verticalScroll(rememberScrollState())
            .testTag(EditorTags.Scroll),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = Sizes.contentMaxWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = Spacing.s), contentAlignment = Alignment.Center) {
                Avatar(
                    draft.displayName(),
                    size = EditorMetrics.Avatar,
                    colorKey =
                        draft.id?.toString() ?: draft.displayName(),
                )
            }
            NameSection(state, onAction)
            CompanySection(state, onAction)
            RepeatSection(EditorList.Numbers, "Phone", "add phone", state, onAction, initialKeys, Phone)
            RepeatSection(EditorList.Emails, "Email", "add email", state, onAction, initialKeys, Email)
            RepeatSection(EditorList.Addresses, "Address", "add address", state, onAction, initialKeys, Address)
            RepeatSection(EditorList.Events, "Dates", "add date", state, onAction, initialKeys, Dates)
            RepeatSection(EditorList.Websites, "Websites", "add website", state, onAction, initialKeys, Web)
            RepeatSection(
                EditorList.Relations,
                "Related People",
                "add related person",
                state,
                onAction,
                initialKeys,
                Related,
            )
            InsetGroupedSection(header = "Nickname") {
                row {
                    TextRow(draft.nickname, "Nickname", state.external, last = false, tag = "nickname") {
                        onAction(EditorAction.Text(EditorText.Nickname, it))
                    }
                }
            }
            InsetGroupedSection(header = "Notes") {
                row {
                    TextRow(
                        draft.note,
                        "Notes",
                        state.external,
                        last = true,
                        tag = "note",
                        maxLines = EditorMetrics.NoteLines,
                        minLines = NOTE_MIN_LINES,
                    ) { onAction(EditorAction.Text(EditorText.Note, it)) }
                }
            }
            AccountAndGroups(state, onAction, overlays, latest)
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

private fun ContactDraft.displayName(): String =
    listOf(given, family).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { org.ifBlank { "?" } }

@Composable
private fun NameSection(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
) {
    val d = state.draft
    val issue = state.issues[EditorIssueKey.NAME]
    InsetGroupedSection {
        row {
            TextRow(
                d.given,
                "First name",
                state.external,
                tag = "given",
                issueKey = EditorIssueKey.NAME,
                issue = issue,
            ) {
                onAction(EditorAction.Text(EditorText.Given, it))
            }
        }
        row {
            TextRow(d.family, "Last name", state.external, tag = "family") {
                onAction(EditorAction.Text(EditorText.Family, it))
            }
        }
        row {
            TextRow(d.phoneticGiven, "Phonetic first name", state.external, tag = "phonetic-given") {
                onAction(EditorAction.Text(EditorText.PhoneticGiven, it))
            }
        }
        row {
            TextRow(d.phoneticFamily, "Phonetic last name", state.external, tag = "phonetic-family") {
                onAction(EditorAction.Text(EditorText.PhoneticFamily, it))
            }
        }
    }
}

@Composable
private fun CompanySection(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
) {
    val d = state.draft
    InsetGroupedSection {
        row {
            TextRow(d.org, "Company", state.external, tag = "org") { onAction(EditorAction.Text(EditorText.Org, it)) }
        }
        row {
            TextRow(d.title, "Job title", state.external, tag = "title") {
                onAction(EditorAction.Text(EditorText.Title, it))
            }
        }
    }
}

/** What differs between the repeatable lists. */
private class ListKind(
    val placeholder: String,
    val keyboard: KeyboardOptions,
    val maxLines: Int = 1,
)

private val Phone = ListKind("Phone", KeyboardOptions(keyboardType = KeyboardType.Phone))
private val Email = ListKind("Email", KeyboardOptions(keyboardType = KeyboardType.Email))
private val Address =
    ListKind(
        "Address",
        KeyboardOptions(capitalization = KeyboardCapitalization.Words, keyboardType = KeyboardType.Text),
        maxLines = 3,
    )
private val Dates = ListKind("YYYY-MM-DD or MM-DD", KeyboardOptions(keyboardType = KeyboardType.Text))
private val Web = ListKind("Website", KeyboardOptions(keyboardType = KeyboardType.Uri))
private val Related = ListKind("Name", KeyboardOptions(capitalization = KeyboardCapitalization.Words))

@Composable
private fun RepeatSection(
    list: EditorList,
    header: String,
    addText: String,
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    initialKeys: EditorKeys,
    kind: ListKind,
) {
    val keys = state.keys.of(list)
    InsetGroupedSection(header = header) {
        keys.forEachIndexed { index, rowKey ->
            row {
                key(rowKey) {
                    RepeatRow(list, index, rowKey, kind, state, onAction, animate = rowKey !in initialKeys.of(list))
                }
            }
        }
        if (keys.size < ContactEditorViewModel.MAX_ROWS) {
            row { AddRow(addText, EditorTags.add(list)) { onAction(EditorAction.AddRow(list)) } }
        }
    }
}

@Composable
private fun AddRow(
    text: String,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.rowMin)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.gutter, vertical = EditorMetrics.RowVertical)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Box(Modifier.size(EditorMetrics.Slot, EditorMetrics.Minus), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(EditorMetrics.Minus).clip(CircleShape).background(RovstiTheme.colors.success),
                contentAlignment = Alignment.Center,
            ) {
                RovstiIcon(RovstiIcons.Plus, null, tint = Color.White, size = EditorMetrics.MinusBar + Spacing.xs)
            }
        }
        RovstiText(text, style = RovstiTheme.type.body, color = RovstiTheme.colors.labelPrimary, maxLines = 1)
    }
}

@Composable
private fun RepeatRow(
    list: EditorList,
    index: Int,
    rowKey: Long,
    kind: ListKind,
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    animate: Boolean,
) {
    val d = state.draft
    val value = rowValue(d, list, index)
    val issueKey = EditorIssueKey.row(list, rowKey)
    val issue = state.issues[issueKey]
    val hint = state.hints[issueKey]
    val custom = customText(d, list, index)
    Column(Modifier.appearing(animate).testTag(EditorTags.row(list, index))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = EditorMetrics.RowVertical),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            MinusButton(list, index) { onAction(EditorAction.RemoveRow(list, rowKey)) }
            EditorField(
                value = value,
                placeholder = kind.placeholder,
                external = state.external,
                modifier = Modifier.weight(1f).testTag(EditorTags.rowField(list, index)),
                keyboard = kind.keyboard,
                maxLines = kind.maxLines,
                issueKey = issueKey,
                onLeft = { onAction(EditorAction.Left(issueKey)) },
            ) { onAction(EditorAction.RowValue(list, rowKey, it)) }
            if (list != EditorList.Websites) {
                LabelButton(list, index, rowKey, d, canDefault = state.keys.of(list).size > 1, onAction = onAction)
            }
        }
        if (custom != null) {
            Row(
                Modifier.fillMaxWidth().padding(
                    start = Spacing.gutter + EditorMetrics.Slot + Spacing.s,
                    end = Spacing.gutter,
                    bottom = EditorMetrics.RowVertical,
                ),
            ) {
                EditorField(
                    value = custom,
                    placeholder = "Label",
                    external = state.external,
                    modifier = Modifier.weight(1f).testTag("editor-${list.name.lowercase()}-custom-$index"),
                ) { onAction(EditorAction.CustomLabel(list, rowKey, it)) }
            }
        }
        if (hint != null && issue == null) {
            RovstiText(
                hint,
                style = RovstiTheme.type.footnote,
                color = RovstiTheme.colors.labelSecondary,
                modifier =
                    Modifier.padding(
                        start = Spacing.gutter + EditorMetrics.Slot + Spacing.s + EditorMetrics.HintIndent,
                        bottom = Spacing.s,
                    ),
            )
        }
        IssueText(issueKey, issue, indent = Spacing.gutter + EditorMetrics.Slot + Spacing.s + EditorMetrics.HintIndent)
    }
}

@Composable
private fun IssueText(
    key: String,
    issue: String?,
    indent: Dp,
) {
    if (issue == null) return
    RovstiText(
        issue,
        style = RovstiTheme.type.footnote,
        color = RovstiTheme.colors.destructive,
        modifier =
            Modifier
                .padding(
                    start = indent,
                    end = Spacing.gutter,
                    bottom = Spacing.s,
                ).testTag(EditorTags.issue(key)),
    )
}

@Composable
private fun MinusButton(
    list: EditorList,
    index: Int,
    onClick: () -> Unit,
) {
    val delete = stringResource(R.string.editor_delete)
    // A 32 x 48 dp touch target around the 24 dp red disc (the add row uses the same slot, so the discs line up).
    Box(
        Modifier
            .size(EditorMetrics.Slot, Sizes.touch)
            .clickable(role = Role.Button, onClickLabel = delete, onClick = onClick)
            .semantics { contentDescription = delete }
            .testTag(EditorTags.minus(list, index)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(EditorMetrics.Minus).clip(CircleShape).background(RovstiTheme.colors.destructive),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(EditorMetrics.MinusBar, EditorMetrics.MinusBarThickness)
                    .background(Color.White),
            )
        }
    }
}

// ---- labels

private class LabelOption(
    val title: String,
    val label: RowLabel,
)

private fun optionsFor(list: EditorList): List<LabelOption> =
    when (list) {
        EditorList.Numbers -> {
            listOf(FieldKind.Mobile, FieldKind.Home, FieldKind.Work, FieldKind.Other).map {
                LabelOption(fieldTitle(it), RowLabel.Field(it))
            }
        }

        EditorList.Emails, EditorList.Addresses -> {
            listOf(
                FieldKind.Home,
                FieldKind.Work,
                FieldKind.Other,
            ).map { LabelOption(fieldTitle(it), RowLabel.Field(it)) }
        }

        EditorList.Events -> {
            listOf(EventKind.Birthday, EventKind.Anniversary, EventKind.Other).map {
                LabelOption(eventTitle(it), RowLabel.Event(it))
            }
        }

        EditorList.Relations -> {
            RelationKind.entries.filter { it != RelationKind.Custom }.map {
                LabelOption(relationTitle(it), RowLabel.Relation(it))
            }
        }

        EditorList.Websites -> {
            emptyList()
        }
    }

private fun customLabel(list: EditorList): RowLabel? =
    when (list) {
        EditorList.Numbers, EditorList.Emails, EditorList.Addresses -> RowLabel.Field(FieldKind.Custom)
        EditorList.Events -> RowLabel.Event(EventKind.Custom)
        EditorList.Relations -> RowLabel.Relation(RelationKind.Custom)
        EditorList.Websites -> null
    }

private fun fieldTitle(kind: FieldKind) =
    when (kind) {
        FieldKind.Mobile -> "Mobile"
        FieldKind.Home -> "Home"
        FieldKind.Work -> "Work"
        FieldKind.Custom -> "Custom"
        FieldKind.Other -> "Other"
    }

private fun eventTitle(kind: EventKind) =
    when (kind) {
        EventKind.Birthday -> "Birthday"
        EventKind.Anniversary -> "Anniversary"
        EventKind.Custom -> "Custom"
        EventKind.Other -> "Other"
    }

private fun relationTitle(kind: RelationKind) = kind.name

private fun rowValue(
    d: ContactDraft,
    list: EditorList,
    index: Int,
): String =
    when (list) {
        EditorList.Numbers -> d.numbers.getOrNull(index)?.value
        EditorList.Emails -> d.emails.getOrNull(index)?.value
        EditorList.Addresses -> d.addresses.getOrNull(index)?.value
        EditorList.Websites -> d.websites.getOrNull(index)
        EditorList.Events -> d.events.getOrNull(index)?.date
        EditorList.Relations -> d.relations.getOrNull(index)?.name
    }.orEmpty()

/** The row's label as the button shows it. */
private fun currentLabel(
    d: ContactDraft,
    list: EditorList,
    index: Int,
): RowLabel? =
    when (list) {
        EditorList.Numbers -> d.numbers.getOrNull(index)?.let { RowLabel.Field(it.kind) }
        EditorList.Emails -> d.emails.getOrNull(index)?.let { RowLabel.Field(it.kind) }
        EditorList.Addresses -> d.addresses.getOrNull(index)?.let { RowLabel.Field(it.kind) }
        EditorList.Events -> d.events.getOrNull(index)?.let { RowLabel.Event(it.kind) }
        EditorList.Relations -> d.relations.getOrNull(index)?.let { RowLabel.Relation(it.kind) }
        EditorList.Websites -> null
    }

/** The custom label text of a row whose kind is Custom, else null (no second field). */
private fun customText(
    d: ContactDraft,
    list: EditorList,
    index: Int,
): String? =
    when (list) {
        EditorList.Numbers -> {
            d.numbers
                .getOrNull(index)
                ?.takeIf { it.kind == FieldKind.Custom }
                ?.label
                .orEmpty()
        }

        EditorList.Emails -> {
            d.emails
                .getOrNull(index)
                ?.takeIf { it.kind == FieldKind.Custom }
                ?.label
                .orEmpty()
        }

        EditorList.Addresses -> {
            d.addresses
                .getOrNull(index)
                ?.takeIf { it.kind == FieldKind.Custom }
                ?.label
                .orEmpty()
        }

        EditorList.Events -> {
            d.events
                .getOrNull(index)
                ?.takeIf { it.kind == EventKind.Custom }
                ?.label
                .orEmpty()
        }

        EditorList.Relations -> {
            d.relations
                .getOrNull(index)
                ?.takeIf { it.kind == RelationKind.Custom }
                ?.label
        }

        EditorList.Websites -> {
            null
        }
    }.takeIf { list != EditorList.Websites && currentLabel(d, list, index).isCustom() }

private fun RowLabel?.isCustom() =
    (this is RowLabel.Field && kind == FieldKind.Custom) ||
        (this is RowLabel.Event && kind == EventKind.Custom) ||
        (this is RowLabel.Relation && kind == RelationKind.Custom)

private fun buttonTitle(
    d: ContactDraft,
    list: EditorList,
    index: Int,
): String {
    val label = currentLabel(d, list, index)
    val custom = customText(d, list, index)
    return when {
        custom != null -> custom.ifBlank { "Custom" }
        label is RowLabel.Field -> fieldTitle(label.kind)
        label is RowLabel.Event -> eventTitle(label.kind)
        label is RowLabel.Relation -> relationTitle(label.kind)
        else -> ""
    }
}

private fun isDefault(
    d: ContactDraft,
    list: EditorList,
    index: Int,
): Boolean =
    when (list) {
        EditorList.Numbers -> d.numbers.getOrNull(index)?.primary
        EditorList.Emails -> d.emails.getOrNull(index)?.primary
        EditorList.Addresses -> d.addresses.getOrNull(index)?.primary
        else -> null
    } == true

@Composable
private fun LabelButton(
    list: EditorList,
    index: Int,
    rowKey: Long,
    draft: ContactDraft,
    canDefault: Boolean,
    onAction: (EditorAction) -> Unit,
) {
    val overlays = LocalOverlay.current
    val title = buttonTitle(draft, list, index)
    val current = currentLabel(draft, list, index)
    val primary = isDefault(draft, list, index)
    val showDefault = canDefault && list in DefaultLists
    val trigger =
        Modifier.popoverTrigger(overlays, label = "Label") {
            LabelPopover(
                options = optionsFor(list),
                current = current,
                custom = customLabel(list),
                primary = primary.takeIf { showDefault },
                onPick = { picked ->
                    overlays.dismissTop()
                    onAction(EditorAction.Label(list, rowKey, picked))
                },
                onDefault = {
                    overlays.dismissTop()
                    onAction(EditorAction.ToggleDefault(list, rowKey))
                },
            )
        }
    Row(
        Modifier
            .widthIn(min = EditorMetrics.LabelButtonMin, max = EditorMetrics.LabelButtonMax)
            .heightIn(min = Sizes.touch)
            .then(trigger)
            .testTag(EditorTags.label(list, index)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        RovstiText(
            if (primary) "$title ★" else title,
            style = RovstiTheme.type.body,
            color = RovstiTheme.colors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(Spacing.xs))
        ListRowChevron()
    }
}

private val DefaultLists = setOf(EditorList.Numbers, EditorList.Emails, EditorList.Addresses)

@Composable
private fun LabelPopover(
    options: List<LabelOption>,
    current: RowLabel?,
    custom: RowLabel?,
    primary: Boolean?,
    onPick: (RowLabel) -> Unit,
    onDefault: () -> Unit,
) {
    Column(Modifier.width(EditorMetrics.PopoverWidth).testTag(EditorTags.LabelPopover)) {
        options.forEach { option ->
            PopoverRow(option.title, selected = current == option.label) { onPick(option.label) }
        }
        if (custom != null) PopoverRow("Custom", selected = current.isCustom()) { onPick(custom) }
        if (primary != null) {
            Box(Modifier.fillMaxWidth().height(Spacing.xs).background(RovstiTheme.colors.separator.copy(alpha = 0.3f)))
            PopoverRow(if (primary) "Clear Default" else "Set as Default", selected = false, onClick = onDefault)
        }
    }
}

@Composable
private fun PopoverRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = EditorMetrics.PopoverRow)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RovstiText(title, style = RovstiTheme.type.body, modifier = Modifier.weight(1f), maxLines = 1)
        if (selected) RovstiIcon(RovstiIcons.Checkmark, "Selected", tint = RovstiTheme.colors.accent, size = Spacing.l)
    }
}

// ---- text fields

@Composable
private fun TextRow(
    value: String,
    placeholder: String,
    external: Int,
    last: Boolean = false,
    tag: String,
    issueKey: String? = null,
    issue: String? = null,
    maxLines: Int = 1,
    minLines: Int = 1,
    onChange: (String) -> Unit,
) {
    val cap = if (tag in Words) KeyboardCapitalization.Words else KeyboardCapitalization.Sentences
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = EditorMetrics.RowVertical)) {
        EditorField(
            value = value,
            placeholder = placeholder,
            external = external,
            modifier = Modifier.fillMaxWidth().testTag(EditorTags.field(tag)),
            keyboard = KeyboardOptions(capitalization = cap),
            maxLines = maxLines,
            minLines = minLines,
            last = last,
            issueKey = issueKey,
            onChange = onChange,
        )
        if (issueKey != null) IssueText(issueKey, issue, indent = EditorMetrics.IssueIndent + Spacing.s)
    }
}

private val Words = setOf("given", "family", "phonetic-given", "phonetic-family", "org", "title", "nickname")

/**
 * A [GlassTextField] over the model's text. The field owns its [androidx.compose.foundation.text.input.TextFieldState]
 * (caret, selection and composition stay with the platform); typing reports each change, and the model's text is taken
 * back only when [external] changes (a restored draft), never in reply to the user's own typing.
 */
@Composable
private fun EditorField(
    value: String,
    placeholder: String,
    external: Int,
    modifier: Modifier = Modifier,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    maxLines: Int = 1,
    minLines: Int = 1,
    last: Boolean = false,
    issueKey: String? = null,
    onLeft: () -> Unit = {},
    onChange: (String) -> Unit,
) {
    val text = rememberTextFieldState(value)
    val latest = rememberUpdatedState(onChange)
    val current = rememberUpdatedState(value)
    val focus = LocalFocusManager.current
    LaunchedEffect(external) {
        if (text.text.toString() != current.value) text.setTextAndPlaceCursorAtEnd(current.value)
    }
    LaunchedEffect(text) {
        snapshotFlow { text.text.toString() }.drop(1).distinctUntilChanged().collect { latest.value(it) }
    }
    val multiline = maxLines > 1
    GlassTextField(
        state = text,
        placeholder = placeholder,
        modifier = modifier.revealWhenFocused(issueKey, onLeft),
        imeAction =
            if (multiline) {
                ImeAction.Default
            } else if (last) {
                ImeAction.Done
            } else {
                ImeAction.Next
            },
        keyboardOptions = keyboard,
        onSubmit = { if (last) focus.clearFocus() else focus.moveFocus(FocusDirection.Next) },
        maxLines = maxLines,
        minLines = minLines,
    )
}

/**
 * Keeps this element [EditorMetrics.RevealMargin] clear of the keyboard while it has the focus: on focus and on every
 * change of the keyboard's height it asks the scrolling column to bring it into view with that margin around it.
 * [issueKey] also registers it as the target of a refused Save.
 */
@Composable
private fun Modifier.revealWhenFocused(
    issueKey: String?,
    onLeft: () -> Unit,
): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val targets = LocalIssueTargets.current
    if (issueKey != null) {
        DisposableEffect(issueKey) {
            targets[issueKey] = requester
            onDispose { targets.remove(issueKey) }
        }
    }
    val density = LocalDensity.current
    var focused by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val margin = with(density) { EditorMetrics.RevealMargin.toPx() }
    val ime = WindowInsets.ime
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        snapshotFlow { ime.getBottom(density) }.collectLatest {
            requester.bringIntoView(Rect(0f, -margin, size.width.toFloat(), size.height + margin))
        }
    }
    return this
        .bringIntoViewRequester(requester)
        .onSizeChanged { size = it }
        .onFocusChanged {
            if (focused && !it.isFocused) onLeft()
            focused = it.isFocused
        }
}

/** Grows the element in from nothing on the `appear` spring when [animate]; an element that was there at once is not touched. */
@Composable
private fun Modifier.appearing(animate: Boolean): Modifier {
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { if (progress.value < 1f) progress.animateTo(1f, RovstiMotion.appear(0.001f)) }
    if (!animate && progress.value >= 1f) return this
    return this
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val h = (placeable.height * progress.value.coerceIn(0f, 1f)).roundToInt()
            layout(placeable.width, h) { placeable.place(0, 0) }
        }.clipToBounds()
        .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
}

// ---- account and groups

@Composable
private fun AccountAndGroups(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    overlays: OverlayController,
    latest: State<EditorUiState>,
) {
    val showAccount = state.accounts.size > 1
    val showGroups = state.groups.isNotEmpty()
    if (!showAccount && !showGroups) return
    InsetGroupedSection {
        if (showGroups) {
            row {
                ListRow(
                    title = "Groups",
                    meta = state.groupsLabel,
                    trailing = { ListRowChevron() },
                    onClick = { overlays.present(groupsSheet(latest, onAction)) },
                    modifier = Modifier.testTag(EditorTags.Groups),
                )
            }
        }
        if (showAccount) {
            row {
                if (state.isNew) {
                    val items =
                        state.accounts
                            .map { account ->
                                MenuItem.Action(
                                    label = account.label,
                                    checked = account.key == state.draft.account,
                                    onClick = { onAction(EditorAction.SetAccount(account.key)) },
                                )
                            }.toImmutableList()
                    Box(Modifier.menuTrigger(overlays, items, label = "Save to").testTag(EditorTags.Account)) {
                        ListRow(
                            title = "Save to",
                            meta = state.accountLabel,
                            trailing = { ListRowChevron() },
                            titleMaxLines = 1,
                        )
                    }
                } else {
                    ListRow(
                        title = "Account",
                        meta = state.accountLabel,
                        modifier = Modifier.testTag(EditorTags.Account),
                    )
                }
            }
        }
    }
}

private fun groupsSheet(
    latest: State<EditorUiState>,
    onAction: (EditorAction) -> Unit,
) = SheetEntry(
    title = "Groups",
    detents = persistentListOf(Detent.Medium),
    initialDetent = Detent.Medium,
) { sheet ->
    val state = latest.value
    Column(Modifier.fillMaxWidth().testTag(EditorTags.GroupsSheet)) {
        Box(Modifier.fillMaxWidth().height(EditorMetrics.HeaderHeight).padding(horizontal = Spacing.s)) {
            RovstiText(
                "Groups",
                style = RovstiTheme.type.headline,
                modifier = Modifier.align(Alignment.Center).semantics { heading() },
            )
            TextButton("Done", onClick = { sheet.dismiss() }, modifier = Modifier.align(Alignment.CenterEnd))
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            InsetGroupedSection {
                state.groups.forEach { group ->
                    row {
                        ListRow(
                            title = group.title,
                            trailing = {
                                if (group.id in state.draft.groups) {
                                    RovstiIcon(RovstiIcons.Checkmark, "Selected", tint = RovstiTheme.colors.accent)
                                }
                            },
                            onClick = { onAction(EditorAction.ToggleGroup(group.id)) },
                            modifier = Modifier.testTag("editor-group-${group.id}"),
                        )
                    }
                }
            }
        }
    }
}
