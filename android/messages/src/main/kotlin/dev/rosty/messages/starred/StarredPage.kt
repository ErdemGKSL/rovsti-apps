package dev.rosty.messages.starred

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.rosty.design.chrome.LargeTitleScaffold
import dev.rosty.design.chrome.NavigationButton
import dev.rosty.design.controls.EmptyState
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.list.ListRow
import dev.rosty.design.list.RowSeparator
import dev.rosty.design.list.TextStartWithLeading
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.showToast
import dev.rosty.design.overlay.showUndo
import dev.rosty.design.text.rtlSafe
import dev.rosty.design.theme.Sizes
import dev.rosty.design.time.LocalRostyClock
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.R
import dev.rosty.messages.inbox.InboxRowUi
import dev.rosty.messages.inbox.RowAvatar
import dev.rosty.messages.inbox.RowTime
import dev.rosty.messages.inbox.rememberInboxViewModel
import dev.rosty.messages.starred.theme.StarredMetrics
import dev.rosty.messages.theme.MessagesMetrics
import dev.rosty.messages.ui.LocalWantAvatar
import dev.rosty.messages.ui.MessagesAvatar
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Test tags of the Starred list. */
object StarredTags {
    const val Root = "starred"
    const val Empty = "starred-empty"

    fun row(id: String) = "starred-row-$id"

    fun unstar(id: String) = "starred-unstar-$id"
}

/** One row of the Starred list as drawn. */
@Immutable
data class StarredRowUi(
    val id: String,
    /** The chat it is in. */
    val title: String,
    /** "Me: See you at six" or, when the chat's title already names the writer, the message alone. */
    val line: String,
    /** "14:05", "Yesterday", "9 Nov". */
    val time: String,
    val avatar: RowAvatar = RowAvatar(),
)

/** The labels of the Starred list. */
@Immutable
class StarredLabels(
    val title: String,
    val emptyTitle: String,
    val emptyDetail: String,
    val unstar: (String) -> String,
)

/**
 * The rows of the list from the center's [items] (already newest first): the chat's title and avatar come from the
 * inbox row of the conversation when it has one, else from what the star kept.
 */
fun starredRows(
    items: List<StarredItem>,
    inbox: List<InboxRowUi>,
    time: RowTime,
    nowMs: Long,
    line: (author: String, preview: String) -> String,
): List<StarredRowUi> =
    items.map { item ->
        val thread =
            item.tag
                .removePrefix(SMS_TAG)
                .takeIf { item.tag.startsWith(SMS_TAG) }
                ?.toLongOrNull()
        val jid = item.tag.removePrefix(WA_TAG).takeIf { item.tag.startsWith(WA_TAG) }
        val row = inbox.firstOrNull { (thread != null && it.smsThread == thread) || (jid != null && it.waChat == jid) }
        val title = row?.title ?: item.title?.takeIf { it.isNotBlank() } ?: item.author
        StarredRowUi(
            id = item.id,
            title = title,
            line = if (item.author == title) item.preview else line(item.author, item.preview),
            time = time.label(item.atMs, nowMs),
            avatar = row?.avatar ?: RowAvatar(waJid = jid, colorKey = title),
        )
    }

/**
 * The Starred list (E6.13; M10 WhatsApp, M23 SMS/MMS): every starred message, newest first. A tap opens the
 * conversation on the message, which glows; the star at the end of a row unstars it.
 */
@Composable
fun StarredScreen(
    rows: ImmutableList<StarredRowUi>,
    labels: StarredLabels,
    listState: LazyListState,
    onOpen: (String) -> Unit,
    onUnstar: (String) -> Unit,
    modifier: Modifier = Modifier,
    navigation: NavigationButton? = null,
) {
    LargeTitleScaffold(
        title = labels.title,
        state = listState,
        actions = persistentListOf(),
        modifier = modifier.testTag(StarredTags.Root),
        navigation = navigation,
    ) {
        if (rows.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Box(
                    Modifier.fillParentMaxHeight(MessagesMetrics.EmptyShare).fillMaxWidth().testTag(StarredTags.Empty),
                    contentAlignment = Alignment.Center,
                ) { EmptyState(RostyIcons.Star, labels.emptyTitle, labels.emptyDetail) }
            }
        }
        itemsIndexed(rows, key = { _, row -> row.id }, contentType = { _, _ -> "row" }) { at, row ->
            if (at > 0) RowSeparator(inset = TextStartWithLeading)
            ListRow(
                title = row.title.rtlSafe(),
                modifier = Modifier.testTag(StarredTags.row(row.id)),
                secondary = row.line.rtlSafe(),
                leading = { MessagesAvatar(row.title, row.avatar) },
                trailing = { UnstarButton(labels.unstar(row.title), StarredTags.unstar(row.id)) { onUnstar(row.id) } },
                meta = row.time,
                onClick = { onOpen(row.id) },
                secondaryMaxLines = 2,
            )
        }
    }
}

@Composable
private fun UnstarButton(
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(Sizes.touch)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ).semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { RostyIcon(RostyIcons.StarFilled, null, tint = StarredMetrics.StarTint, size = StarredMetrics.StarGlyph) }
}

/** The Starred page over [StarCenter]: the rows, open on tap, unstar with Undo. */
@Composable
fun StarredEntry(
    container: MessagesContainer,
    navigator: TabNavigator<MessagesRoute>,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val overlays = LocalOverlay.current
    val clock = LocalRostyClock.current
    val scope = rememberCoroutineScope()
    val items by container.stars.items.collectAsState()
    val inbox = rememberInboxViewModel(container)
    val inboxState by inbox.state.collectAsState()
    LaunchedEffect(container) { container.stars.rescan() }
    val time =
        remember(res) {
            RowTime(
                ZoneId.systemDefault(),
                res.configuration.locales[0],
                android.text.format.DateFormat
                    .is24HourFormat(context),
                res.getString(R.string.inbox_yesterday),
            )
        }
    val rows =
        remember(items, inboxState.rows, time) {
            starredRows(items, inboxState.rows, time, clock.nowMillis()) { author, preview ->
                res.getString(R.string.starred_line, author, preview)
            }.toImmutableList()
        }
    val labels =
        remember(res) {
            StarredLabels(
                title = res.getString(R.string.starred_title),
                emptyTitle = res.getString(R.string.starred_empty_title),
                emptyDetail = res.getString(R.string.starred_empty_detail),
                unstar = { res.getString(R.string.starred_unstar_a11y, it) },
            )
        }
    val target: (String) -> MarkTarget? = { id ->
        items.firstOrNull { it.id == id }?.let {
            MarkTarget(
                it.tag,
                it.id,
                it.atMs,
                it.author,
                it.preview,
                it.title.orEmpty(),
            )
        }
    }
    CompositionLocalProvider(LocalWantAvatar provides container.avatars::want) {
        StarredScreen(
            rows = rows,
            labels = labels,
            listState = rememberLazyListState(),
            onOpen = { id ->
                items.firstOrNull { it.id == id }?.let { item ->
                    starredRoute(item, inboxState.rows)?.let(navigator::push)
                }
            },
            onUnstar = { id ->
                val star = target(id)
                if (star != null) {
                    scope.launch {
                        if (container.stars.set(star, false)) {
                            overlays.showUndo(
                                res.getString(R.string.starred_undone),
                                onUndo = { container.scope.launch { container.stars.set(star, true) } },
                                actionLabel = res.getString(R.string.inbox_undo),
                            )
                        } else {
                            overlays.showToast(res.getString(R.string.starred_failed))
                        }
                    }
                }
            },
        )
    }
}

/** The conversation of a starred message, opened on the message so it glows (the search hit's `reveal`). */
fun starredRoute(
    item: StarredItem,
    inbox: List<InboxRowUi> = emptyList(),
): MessagesRoute? {
    if (item.tag.startsWith(WA_TAG)) {
        return MessagesRoute.WaThread(item.tag.removePrefix(WA_TAG), reveal = item.id, revealAtMs = item.atMs)
    }
    val thread =
        item.tag
            .removePrefix(SMS_TAG)
            .toLongOrNull()
            ?.takeIf { it > 0 } ?: return null
    val sender = inbox.firstOrNull { it.smsThread == thread }?.smsSender
    return MessagesRoute.SmsThread(thread, listOfNotNull(sender), reveal = item.id, revealAtMs = item.atMs)
}
