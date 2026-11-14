package dev.rovsti.contacts.people

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.contacts.R
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.RowSeparator
import dev.rovsti.design.list.TextStartWithLeading
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.SheetState
import dev.rovsti.design.overlay.sheetScrollConnection
import dev.rovsti.design.text.SearchField
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import dev.rovsti.people.ui.ContactAvatar
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the contact picker sheet. */
object PickerTags {
    const val Sheet = "contact-picker"
    const val Search = "contact-picker-search"
    const val List = "contact-picker-list"
    const val Cancel = "contact-picker-cancel"
    const val Confirm = "contact-picker-confirm"
    const val Empty = "contact-picker-empty"

    fun row(id: Long) = "contact-picker-row-$id"
}

/**
 * A multi-select list of contacts in a large sheet (E4.6 "Add Favorites", E4.7 "Add Members"): a bar with Cancel, the
 * [title] and the confirm button, a search field, then one row per person with a check circle at the end. [initial] is
 * what is ticked when it opens; the confirm button stays off until the ticked set differs from it and then hands the
 * ticked ids to [onDone] and closes. The page behind keeps still: nothing in it moves while the sheet is up.
 */
fun OverlayController.presentContactPicker(
    title: String,
    confirmLabel: String,
    rows: ImmutableList<PersonItem>,
    initial: Set<Long>,
    onDone: (Set<Long>) -> Unit,
) {
    present(
        SheetEntry(title = title, detents = persistentListOf(Detent.Large)) { sheet ->
            ContactPickerContent(title, confirmLabel, rows, initial, sheet, onDone)
        },
    )
}

/**
 * One contact out of the book in a large sheet (E4.10 "Add to Existing Contact"): the same bar and search field as
 * [presentContactPicker], but a tap on a row is the answer, so there are no check circles and no confirm button.
 */
fun OverlayController.presentContactChooser(
    title: String,
    rows: ImmutableList<PersonItem>,
    onPick: (Long) -> Unit,
) {
    present(
        SheetEntry(title = title, detents = persistentListOf(Detent.Large)) { sheet ->
            ContactPickerContent(title, "", rows, emptySet(), sheet, { it.firstOrNull()?.let(onPick) }, single = true)
        },
    )
}

@Composable
internal fun ColumnScope.ContactPickerContent(
    title: String,
    confirmLabel: String,
    rows: ImmutableList<PersonItem>,
    initial: Set<Long>,
    sheet: SheetState?,
    onDone: (Set<Long>) -> Unit,
    single: Boolean = false,
) {
    var chosen by remember { mutableStateOf(initial) }
    val search = rememberTextFieldState()
    val query = search.text.toString().trim()
    val shown =
        remember(rows, query) {
            if (query.isEmpty()) rows else rows.filter { it.searchText.contains(query, ignoreCase = true) }
        }
    val changed = chosen != initial
    val colors = RovstiTheme.colors
    Column(Modifier.testTag(PickerTags.Sheet)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RovstiText(
                stringResource(R.string.picker_cancel),
                Modifier
                    .heightIn(min = PickerMetrics.ButtonHeight)
                    .clickable(role = Role.Button) { sheet?.dismiss() }
                    .padding(vertical = Spacing.m)
                    .testTag(PickerTags.Cancel),
                style = RovstiTheme.type.body,
                color = colors.accent,
            )
            RovstiText(
                title,
                Modifier.weight(1f).padding(horizontal = Spacing.s),
                style = RovstiTheme.type.headline,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (single) {
                Spacer(Modifier.width(PickerMetrics.CancelWidth))
            } else {
                RovstiText(
                    confirmLabel,
                    Modifier
                        .heightIn(min = PickerMetrics.ButtonHeight)
                        .clickable(enabled = changed, role = Role.Button) {
                            sheet?.dismiss()
                            onDone(chosen)
                        }.padding(vertical = Spacing.m)
                        .testTag(PickerTags.Confirm),
                    style = RovstiTheme.type.headline,
                    color = if (changed) colors.accent else colors.labelTertiary,
                )
            }
        }
        SearchField(
            search,
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Spacing.gutter,
                    vertical = Spacing.s,
                ).testTag(PickerTags.Search),
        )
        if (shown.isEmpty()) {
            RovstiText(
                stringResource(R.string.picker_no_results),
                Modifier.fillMaxWidth().padding(Spacing.xl).testTag(PickerTags.Empty),
                style = RovstiTheme.type.body,
                color = colors.labelSecondary,
                textAlign = TextAlign.Center,
            )
        }
        val scroll = if (sheet != null) Modifier.nestedScroll(sheetScrollConnection(sheet)) else Modifier
        LazyColumn(scroll.testTag(PickerTags.List)) {
            items(shown, key = { it.id }, contentType = { "person" }) { person ->
                val on = person.id in chosen
                Column {
                    ListRow(
                        title = person.name,
                        secondary = person.secondary,
                        leading = {
                            ContactAvatar(
                                person.name,
                                person.face,
                                size = Sizes.avatar,
                                colorKey = person.colorKey,
                            )
                        },
                        trailing = if (single) null else ({ CheckCircle(on) }),
                        onClick =
                            if (single) {
                                {
                                    sheet?.dismiss()
                                    onDone(setOf(person.id))
                                }
                            } else {
                                { chosen = if (on) chosen - person.id else chosen + person.id }
                            },
                        extraSemantics =
                            if (single) {
                                null
                            } else {
                                {
                                    role = Role.Checkbox
                                    selected = on
                                    toggleableState = if (on) ToggleableState.On else ToggleableState.Off
                                }
                            },
                        modifier = Modifier.testTag(PickerTags.row(person.id)),
                    )
                    RowSeparator(inset = TextStartWithLeading)
                }
            }
        }
    }
}

/** The tick at the end of a picker row: a filled accent circle with a check, or an empty ring. */
@Composable
internal fun CheckCircle(
    on: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = RovstiTheme.colors
    Box(
        modifier
            .size(PeopleMetrics.CheckSize)
            .then(
                if (on) {
                    Modifier.background(colors.accent, CircleShape)
                } else {
                    Modifier.border(PeopleMetrics.CheckStroke, colors.labelTertiary, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (on) {
            RovstiIcon(RovstiIcons.Checkmark, null, tint = colors.onAccent, size = PeopleMetrics.CheckGlyph)
        }
    }
}
