package dev.rovsti.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rovsti.design.chrome.LargeTitleScaffold
import dev.rovsti.design.chrome.NavigationButton
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ListRowChevron
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.design.transition.rovstiSharedBounds
import kotlinx.collections.immutable.persistentListOf

internal const val DemoContactCount = 30

private val LargeAvatar = 96.dp

/** Tag of the avatar of contact [id] in the list. */
internal fun listAvatarTag(id: Int) = "avatar-list-$id"

/** Tag of the avatar of contact [id] on the detail page. */
internal fun detailAvatarTag(id: Int) = "avatar-detail-$id"

/** The "Demo" tab root: a contact list. A row pushes the detail page, its avatar flies to the header. */
@Composable
internal fun ContactsPage(navigator: TabNavigator<CatalogRoute>) {
    val state = rememberLazyListState()
    LaunchedEffect(navigator, state) {
        navigator.scrollToTop.collect { if (it.tab == DemoTab) state.animateScrollToItem(0) }
    }
    CatalogPageFrame {
        LargeTitleScaffold(
            title = "Contacts",
            state = state,
            actions = persistentListOf(),
            modifier = Modifier.testTag("contacts-page"),
        ) {
            itemsIndexed(List(DemoContactCount) { it }, key = { _, i -> i }) { _, i ->
                ListRow(
                    title = contactName(i),
                    secondary = "Mobile, +49 170 555 01${i.toString().padStart(2, '0')}",
                    leading = {
                        Avatar(
                            contactName(i),
                            modifier = Modifier.rovstiSharedBounds("avatar-$i").testTag(listAvatarTag(i)),
                        )
                    },
                    trailing = { ListRowChevron() },
                    onClick = { navigator.push(CatalogRoute.ContactDetail(i)) },
                    modifier = Modifier.testTag("contact-row-$i"),
                )
            }
        }
    }
}

/** Detail page: the large avatar is the other end of the shared flight. A row pushes the notes sub-page. */
@Composable
internal fun ContactDetailPage(
    id: Int,
    navigator: TabNavigator<CatalogRoute>,
) {
    val state = rememberLazyListState()
    CatalogPageFrame {
        LargeTitleScaffold(
            title = contactName(id),
            state = state,
            actions = persistentListOf(),
            navigation = NavigationButton(onClick = { navigator.pop() }),
            modifier = Modifier.testTag("contact-detail-page"),
        ) {
            item(key = "avatar") {
                Box(Modifier.fillMaxWidth().padding(vertical = Spacing.l), contentAlignment = Alignment.Center) {
                    Avatar(
                        contactName(id),
                        size = LargeAvatar,
                        modifier = Modifier.rovstiSharedBounds("avatar-$id").testTag(detailAvatarTag(id)),
                    )
                }
            }
            item(
                key = "mobile",
            ) { ListRow(title = "Mobile", secondary = "+49 170 555 01${id.toString().padStart(2, '0')}") }
            item(key = "email") { ListRow(title = "Email", secondary = "contact$id@example.com") }
            item(key = "notes") {
                ListRow(
                    title = "Notes",
                    secondary = "Met at the station",
                    trailing = { ListRowChevron() },
                    onClick = { navigator.push(CatalogRoute.ContactNotes(id)) },
                    modifier = Modifier.testTag("notes-row"),
                )
            }
        }
    }
}

/** Sub-detail: the second push of the stack; it hides the tab bar. */
@Composable
internal fun ContactNotesPage(
    id: Int,
    navigator: TabNavigator<CatalogRoute>,
) {
    val state = rememberLazyListState()
    CatalogPageFrame {
        LargeTitleScaffold(
            title = "Notes",
            state = state,
            actions = persistentListOf(),
            navigation = NavigationButton(onClick = { navigator.pop() }),
            modifier = Modifier.testTag("contact-notes-page"),
        ) {
            item(key = "intro") {
                Column(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.m)) {
                    RovstiText(
                        "Notes for ${contactName(id)}",
                        style = RovstiTheme.type.headline,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    RovstiText(
                        "A third level of the Demo stack. The tab bar is hidden here.",
                        style = RovstiTheme.type.subheadline,
                        color = RovstiTheme.colors.labelSecondary,
                    )
                }
            }
            itemsIndexed(NoteLines, key = { i, _ -> "note-$i" }) { _, line -> ListRow(title = line) }
        }
    }
}

private val NoteLines =
    listOf("Met at the station", "Prefers messages after 6 pm", "Birthday in March", "Owes me a coffee")
