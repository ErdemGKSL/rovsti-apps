package dev.rosty.messages.wa.status

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rosty.core.model.wa.StatusItem
import dev.rosty.core.model.wa.StatusList
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.foundation.IosTheme
import dev.rosty.design.list.ListRow
import dev.rosty.design.list.SectionHeader
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.inbox.RowAvatar
import dev.rosty.messages.inbox.UnreadBadge
import dev.rosty.messages.inbox.numberOfJid
import dev.rosty.messages.ui.MessagesAvatar
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Test tags of the Status row, list and viewer. */
object StatusTags {
    const val Row = "status-row"
    const val Sheet = "status-sheet"
    const val Viewer = "status-viewer"
    const val Close = "status-close"
    const val Message = "status-message"

    fun person(author: String) = "status-person-$author"
}

/** The sizes of the Status views that are not design tokens. */
internal object StatusMetrics {
    val Ring = 2.dp
    val RingGap = 2.dp

    /** The avatar inside the ring, so ring and avatar together fill the row's 40 dp leading slot. */
    val RowAvatar = 32.dp
    val ViewerAvatar = 36.dp
    val Bar = 3.dp
    val BarGap = 4.dp
    val TextMaxWidth = 360.dp
    val Play = 64.dp
    val PlayGlyph = 28.dp
    val MessageLift = 96.dp
}

/** One person on the Status list and in the viewer, frozen when it opened (a reload does not move the updates). */
@Immutable
data class StatusPerson(
    /** The author's JID; my own updates use [MINE]. */
    val author: String,
    val title: String,
    val avatar: RowAvatar,
    val mine: Boolean,
    val items: ImmutableList<StatusItem>,
    /** "12 min ago", the time of the newest update. */
    val time: String,
) {
    val unseen: Int get() = if (mine) 0 else items.count { !it.viewed }

    companion object {
        const val MINE = "me"
    }
}

/** The Status row of the WhatsApp tab (E6.2 AC3). */
@Immutable
data class StatusRowUi(
    /** The person whose update is the latest; null when nobody posted. */
    val latest: StatusPerson? = null,
    /** "3 new updates", "No new updates". */
    val subtitle: String = "",
    val unseen: Int = 0,
    val people: ImmutableList<StatusPerson> = persistentListOf(),
)

/** The words of the Status views, from `strings.xml`. */
class StatusWords(
    val title: String,
    val none: String,
    val newUpdates: (Int) -> String,
    val mine: String,
    val recent: String,
    val viewed: String,
    val justNow: String,
    val minutesAgo: (Int) -> String,
    /** The clock of an update older than an hour. */
    val clock: (Long) -> String,
    val empty: String,
)

/** "Just now", "12 min ago", else the clock time (GPUI `ago_label`). */
fun StatusWords.ago(
    nowMs: Long,
    thenMs: Long,
): String {
    val minutes = ((nowMs - thenMs).coerceAtLeast(0) / MS_PER_MINUTE).toInt()
    return when {
        minutes == 0 -> justNow
        minutes < MINUTES_PER_HOUR -> minutesAgo(minutes)
        else -> clock(thenMs)
    }
}

/**
 * The people of a status list as the views draw them. [titleOf] is the name rule of the app (address book, WhatsApp
 * name, number) for an author JID with the name its update carried.
 */
fun statusRowOf(
    list: StatusList,
    words: StatusWords,
    nowMs: Long,
    titleOf: (jid: String, carried: String?) -> String,
): StatusRowUi {
    val people = ArrayList<StatusPerson>()
    if (list.mine.isNotEmpty()) {
        people +=
            StatusPerson(
                author = StatusPerson.MINE,
                title = words.mine,
                avatar = RowAvatar(colorKey = StatusPerson.MINE),
                mine = true,
                items = list.mine.toImmutableList(),
                time = words.ago(nowMs, list.mine.maxOf { it.timestampMs }),
            )
    }
    for (author in list.authors) {
        if (author.items.isEmpty()) continue
        val number = numberOfJid(author.author)
        people +=
            StatusPerson(
                author = author.author,
                title = titleOf(author.author, author.name),
                avatar =
                    RowAvatar(
                        numbers = listOfNotNull(number).toImmutableList(),
                        waJid = author.author,
                        colorKey = number ?: author.author,
                    ),
                mine = false,
                items = author.items.toImmutableList(),
                time = words.ago(nowMs, author.items.maxOf { it.timestampMs }),
            )
    }
    val unseen = list.unviewed()
    return StatusRowUi(
        // The list is ordered unseen first, then newest: its first author is the latest update worth opening.
        latest = people.firstOrNull { !it.mine } ?: people.firstOrNull(),
        subtitle = if (unseen == 0) words.none else words.newUpdates(unseen),
        unseen = unseen,
        people = people.toImmutableList(),
    )
}

/** An avatar inside a ring: green while the person has unseen updates, grey after. */
@Composable
fun RingAvatar(
    person: StatusPerson,
    modifier: Modifier = Modifier,
    size: Dp = StatusMetrics.RowAvatar,
) {
    val ring = if (person.unseen > 0) IosTheme.colors.systemGreen else RostyTheme.colors.labelTertiary
    Box(
        modifier
            .border(StatusMetrics.Ring, ring, CircleShape)
            .padding(StatusMetrics.Ring + StatusMetrics.RingGap),
    ) {
        MessagesAvatar(person.title, person.avatar, size = size)
    }
}

/**
 * The Status row (E6.2 AC3): ONE ring avatar of the latest update and "N new updates", never a stack of avatars. A
 * tap opens the list of people.
 */
@Composable
fun StatusRow(
    state: StatusRowUi,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListRow(
        title = title,
        modifier = modifier.testTag(StatusTags.Row),
        secondary = state.subtitle,
        leading = {
            val latest = state.latest
            if (latest != null) {
                RingAvatar(latest)
            } else {
                Box(Modifier.size(Sizes.avatar), contentAlignment = Alignment.Center) {
                    RostyIcon(RostyIcons.Circle, null, tint = IosTheme.colors.systemGreen)
                }
            }
        },
        trailing = if (state.unseen > 0) ({ UnreadBadge(state.unseen, quiet = false) }) else null,
        onClick = onClick,
    )
}

/** The list of people with updates (GPUI Status page): mine, recent, viewed. A tap plays that person's updates. */
@Composable
fun StatusPeople(
    state: StatusRowUi,
    words: StatusWords,
    onOpen: (StatusPerson) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(StatusTags.Sheet)) {
        if (state.people.isEmpty()) {
            RostyText(
                words.empty,
                modifier = Modifier.padding(Spacing.xl),
                color = RostyTheme.colors.labelSecondary,
            )
        }
        state.people.firstOrNull { it.mine }?.let { PersonRow(it, onOpen) }
        val recent = state.people.filter { !it.mine && it.unseen > 0 }
        val viewed = state.people.filter { !it.mine && it.unseen == 0 }
        if (recent.isNotEmpty()) {
            SectionHeader(words.recent)
            recent.forEach { PersonRow(it, onOpen) }
        }
        if (viewed.isNotEmpty()) {
            SectionHeader(words.viewed)
            viewed.forEach { PersonRow(it, onOpen) }
        }
    }
}

@Composable
private fun PersonRow(
    person: StatusPerson,
    onOpen: (StatusPerson) -> Unit,
) {
    ListRow(
        title = person.title,
        modifier = Modifier.testTag(StatusTags.person(person.author)),
        secondary = person.time,
        leading = { RingAvatar(person) },
        onClick = { onOpen(person) },
    )
}

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60
