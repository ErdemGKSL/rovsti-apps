package dev.rovsti.phone.contacts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import dev.rovsti.design.chrome.LargeTitleScaffold
import dev.rovsti.design.chrome.ToolbarAction
import dev.rovsti.design.controls.EmptyState
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ListScrollRail
import dev.rovsti.design.list.ListScrollRailWidth
import dev.rovsti.design.list.RowSeparator
import dev.rovsti.design.list.SectionHeader
import dev.rovsti.design.list.TextStartWithLeading
import dev.rovsti.design.text.SearchField
import dev.rovsti.design.text.rememberSearchFocusHandoff
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.PhoneTags
import dev.rovsti.phone.R
import dev.rovsti.phone.RootLinks
import dev.rovsti.phone.rootToolbar
import dev.rovsti.phone.setup.SetupUiState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the Contacts tab. */
object PeopleTags {
    const val Search = "people-search"
    const val Empty = "people-empty"
    const val NoResults = "people-no-results"
    const val Gate = "people-gate"
    const val Star = "people-star"

    fun row(id: Long) = "people-row-$id"

    fun header(letter: String) = "people-header-$letter"
}

/**
 * The Contacts tab (E5.5), stateless: the large title with "+" and the gear, the search field, the sections with their
 * letter headers and the alphabet rail. A row is a 40 dp avatar, the name and ONE line under it (the number's type and
 * the number), with a star for a favorite. A tap opens the person sheet; "+" hands over to the Contacts app's editor.
 * While searching there are no sections and no rail, and the matches keep the index's ranking.
 */
@Composable
fun PhoneContactsContent(
    ui: PeopleUi,
    searchState: TextFieldState,
    listState: LazyListState,
    onAction: (PeopleAction) -> Unit,
    onOpen: (Long, () -> Unit) -> Unit,
    onNew: () -> Unit,
    links: RootLinks,
    setup: SetupUiState,
    modifier: Modifier = Modifier,
) {
    val latest = rememberUpdatedState(onAction)
    val handoff = rememberSearchFocusHandoff(searchState)
    val openResult: (Long) -> Unit =
        remember(onOpen, handoff) {
            { id ->
                handoff.onResultOpened()
                onOpen(id, handoff::onSheetDismissed)
            }
        }
    LaunchedEffect(searchState) {
        snapshotFlow { searchState.text.toString() }.collect { latest.value(PeopleAction.SetQuery(it)) }
    }
    val ready = ui.phase == PeoplePhase.Ready
    val newLabel = stringResource(R.string.people_new)
    val root = rootToolbar(setup, links)
    val actions: ImmutableList<ToolbarAction> =
        remember(ready, root, onNew, newLabel) {
            if (ready) {
                persistentListOf(ToolbarAction("add", RovstiIcons.Plus, newLabel, onClick = onNew)).addAll(root)
            } else {
                root
            }
        }
    val endInset: Dp = if (ready && ui.showRail) ListScrollRailWidth else Dp(0f)
    Box(modifier.testTag(PhoneTags.root("contacts"))) {
        LargeTitleScaffold(title = stringResource(R.string.tab_contacts), state = listState, actions = actions) {
            if (ui.phase != PeoplePhase.Loading) {
                item(key = "search", contentType = "search") {
                    SearchField(
                        state = searchState,
                        placeholder = stringResource(R.string.people_search),
                        handoff = handoff,
                        modifier =
                            Modifier
                                .padding(
                                    start = Spacing.gutter,
                                    end = Spacing.gutter + endInset,
                                    top = Spacing.s,
                                    bottom = Spacing.s,
                                ).testTag(PeopleTags.Search),
                    )
                }
            }
            when (ui.phase) {
                PeoplePhase.Loading -> Unit
                PeoplePhase.NoPermission -> item(key = "gate", contentType = "gate") { Gate(links) }
                PeoplePhase.Ready -> readyItems(ui, endInset, openResult)
            }
        }
        if (ready && ui.showRail) {
            ListScrollRail(
                lazyListState = listState,
                itemCount = ui.scrubItems,
                sectionIndexMap = ui.sectionIndex,
                sections = ui.letters,
            )
        }
    }
}

private fun LazyListScope.readyItems(
    ui: PeopleUi,
    endInset: Dp,
    onOpen: (Long) -> Unit,
) {
    when (ui.empty) {
        PeopleEmpty.None -> {
            Unit
        }

        PeopleEmpty.NoContacts -> {
            item(key = "empty", contentType = "empty") { EmptyBlock(PeopleTags.Empty, null) }
        }

        PeopleEmpty.NoResults -> {
            item(key = "empty", contentType = "empty") { EmptyBlock(PeopleTags.NoResults, ui.query.trim()) }
        }
    }
    var first = true
    for (row in ui.rows) {
        if (row.header) {
            item(key = "h-${row.letter}", contentType = "header") {
                SectionHeader(row.letter, Modifier.testTag(PeopleTags.header(row.letter)))
            }
        }
        val separated = !first && !row.header
        first = false
        item(key = row.id, contentType = "contact") { PersonRow(row, separated, endInset, onOpen) }
    }
}

@Composable
private fun PersonRow(
    row: PersonRowUi,
    separated: Boolean,
    endInset: Dp,
    onOpen: (Long) -> Unit,
) {
    val open = remember(row.id, onOpen) { { onOpen(row.id) } }
    Column {
        // Stops a gap before the rail column so the hairline never runs toward the letters.
        if (separated) {
            RowSeparator(
                Modifier.padding(
                    end =
                        if (endInset >
                            Dp(0f)
                        ) {
                            endInset + Spacing.s
                        } else {
                            endInset
                        },
                ),
                TextStartWithLeading,
            )
        }
        ListRow(
            title = row.name,
            secondary = row.subtitle,
            modifier = Modifier.testTag(PeopleTags.row(row.id)),
            leading = {
                Avatar(
                    row.name,
                    size = Sizes.avatar,
                    photoModel = row.photoUri,
                    colorKey = row.id.toString(),
                )
            },
            trailing =
                if (row.starred) {
                    {
                        RovstiIcon(
                            RovstiIcons.Star,
                            null,
                            Modifier.testTag(PeopleTags.Star),
                            tint = RovstiTheme.colors.labelSecondary,
                            size = Sizes.sourceGlyph,
                        )
                    }
                } else {
                    null
                },
            onClick = open,
            endInset = endInset,
        )
    }
}

@Composable
private fun EmptyBlock(
    tag: String,
    query: String?,
) {
    Box(Modifier.fillMaxWidth().padding(vertical = Spacing.xl).testTag(tag), contentAlignment = Alignment.Center) {
        EmptyState(
            icon = RovstiIcons.People,
            title =
                if (query == null) {
                    stringResource(R.string.people_empty_title)
                } else {
                    stringResource(R.string.people_no_results, query)
                },
            message = if (query == null) stringResource(R.string.people_empty_message) else "",
        )
    }
}

@Composable
private fun Gate(links: RootLinks) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = Spacing.xl).testTag(PeopleTags.Gate),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            icon = RovstiIcons.People,
            title = stringResource(R.string.people_gate_title),
            message = stringResource(R.string.people_gate_message),
            actionLabel = stringResource(R.string.setup_open),
            onAction = links.openSetup,
        )
    }
}

/** The Contacts tab wired to its model. */
@Composable
fun PhoneContactsScreen(
    model: PhoneContactsViewModel,
    links: RootLinks,
    setup: SetupUiState,
    onOpen: (Long, () -> Unit) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by model.state.collectAsState()
    val listState = rememberLazyListState()
    val searchState = rememberTextFieldState()
    PhoneContactsContent(ui, searchState, listState, model::onAction, onOpen, onNew, links, setup, modifier)
}
