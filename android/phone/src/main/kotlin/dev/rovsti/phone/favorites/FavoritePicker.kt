package dev.rovsti.phone.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
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
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.R
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * "Add Favorite": a large sheet with a Cancel button, the title, a search field and one row per person who is not a
 * favorite yet. A tap on a row stars that person and closes the sheet; the page behind keeps still.
 */
fun presentFavoritePicker(
    overlays: OverlayController,
    title: String,
    rows: ImmutableList<FavPerson>,
    onAction: (FavoritesAction) -> Unit,
) {
    overlays.present(
        SheetEntry(title = title, detents = persistentListOf(Detent.Large)) { sheet ->
            FavoritePickerContent(title, rows, sheet) { id -> onAction(FavoritesAction.Add(id)) }
        },
    )
}

@Composable
internal fun FavoritePickerContent(
    title: String,
    rows: ImmutableList<FavPerson>,
    sheet: SheetState?,
    onPick: (Long) -> Unit,
) {
    val search = rememberTextFieldState()
    val query = search.text.toString().trim()
    val shown =
        remember(rows, query) {
            if (query.isEmpty()) {
                rows
            } else {
                rows.filter {
                    it.name.contains(query, true) ||
                        it.number.orEmpty().contains(query)
                }
            }
        }
    val colors = RovstiTheme.colors
    Column(Modifier.testTag(FavTags.Picker)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RovstiText(
                stringResource(R.string.fav_picker_cancel),
                Modifier
                    .heightIn(min = Spacing.xl)
                    .clickable(role = Role.Button) { sheet?.dismiss() }
                    .padding(vertical = Spacing.m),
                style = RovstiTheme.type.body,
                color = colors.accent,
            )
            RovstiText(
                title,
                Modifier.weight(1f).padding(end = Spacing.xl),
                style = RovstiTheme.type.headline,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        SearchField(
            search,
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.s),
            placeholder = stringResource(R.string.fav_picker_search),
        )
        if (shown.isEmpty()) {
            RovstiText(
                stringResource(R.string.fav_picker_none),
                Modifier.fillMaxWidth().padding(Spacing.xl),
                style = RovstiTheme.type.body,
                color = colors.labelSecondary,
                textAlign = TextAlign.Center,
            )
        }
        val scroll = if (sheet != null) Modifier.nestedScroll(sheetScrollConnection(sheet)) else Modifier
        LazyColumn(scroll) {
            items(shown, key = { it.key }, contentType = { "person" }) { person ->
                Column {
                    PersonListRow(
                        person,
                        Modifier.testTag(FavTags.pickRow(person.contactId ?: 0)),
                        onClick = {
                            sheet?.dismiss()
                            person.contactId?.let(onPick)
                        },
                        trailing = null,
                    )
                    RowSeparator(inset = TextStartWithLeading)
                }
            }
        }
    }
}
