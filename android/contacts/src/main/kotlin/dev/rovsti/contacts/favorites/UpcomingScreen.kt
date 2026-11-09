package dev.rovsti.contacts.favorites

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rovsti.contacts.R
import dev.rovsti.design.chrome.LargeTitleScaffold
import dev.rovsti.design.chrome.LocalContentInsets
import dev.rovsti.design.chrome.NavigationButton
import dev.rovsti.design.chrome.ToolbarAction
import dev.rovsti.design.controls.EmptyState
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.SectionHeader
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the See All page. */
object UpcomingTags {
    const val Screen = "upcoming-screen"
    const val Empty = "upcoming-empty"
    const val Calendar = "toolbar-calendar"

    fun group(title: String) = "upcoming-group-$title"
}

/**
 * The See All page (C9): the next 365 days under This week, This month and Later, each with the date, the age it
 * reaches and a Wish button. The toolbar's calendar button asks for the .ics file of every birthday and anniversary in
 * the address book (C50).
 */
@Composable
fun UpcomingScreen(
    state: FavoritesUiState,
    onAction: (FavoritesAction) -> Unit,
    onBack: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.upcoming_add_to_calendar)
    val toolbar =
        remember(label, onAction) {
            persistentListOf(
                ToolbarAction("calendar", RovstiIcons.CalendarPlus, label) { onAction(FavoritesAction.SaveCalendar) },
            )
        }
    LargeTitleScaffold(
        title = stringResource(R.string.upcoming_title),
        state = listState,
        actions = toolbar,
        navigation = NavigationButton(onClick = onBack),
        modifier = modifier.testTag(UpcomingTags.Screen),
    ) {
        if (state.upcomingGroups.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                val bottom = LocalContentInsets.current.calculateBottomPadding()
                Box(
                    Modifier
                        .fillParentMaxHeight()
                        .fillMaxWidth()
                        .padding(bottom = bottom)
                        .testTag(UpcomingTags.Empty),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = RovstiIcons.CalendarPlus,
                        title = "No Upcoming Dates",
                        message = "Add a birthday or anniversary to a contact to see it here.",
                    )
                }
            }
        }
        for (group in state.upcomingGroups) {
            item(key = "title-${group.title}", contentType = "header") {
                SectionHeader(group.title, Modifier.testTag(UpcomingTags.group(group.title)))
            }
            group.items.forEachIndexed { index, event ->
                item(key = "up-${event.key}", contentType = "upcoming") {
                    UpcomingRow(event, separated = index > 0, withDate = true, onAction = onAction)
                }
            }
        }
    }
}
