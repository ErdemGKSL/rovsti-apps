package dev.rosty.messages.starred

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import dev.rosty.core.model.chat.ReminderSlot
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.showToast
import dev.rosty.design.overlay.showUndo
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.R
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.conversation.ConversationItem
import dev.rosty.messages.conversation.ConversationUi
import dev.rosty.messages.conversation.DayLabels
import dev.rosty.messages.remind.RemindCenter
import dev.rosty.messages.remind.RemindRow
import dev.rosty.messages.remind.RemindUi
import dev.rosty.messages.remind.presentRemind
import dev.rosty.messages.remind.theme.RemindMetrics
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** [state] with the stars and reminder badges laid over its bubbles; the same object when nothing is marked. */
fun ConversationUi.withMarks(
    stars: StarMarks,
    reminders: Map<String, String>,
): ConversationUi {
    if (stars.on.isEmpty() && stars.off.isEmpty() && reminders.isEmpty()) return this
    var changed = false
    val next =
        items.map { item ->
            val bubble = (item as? ConversationItem.Bubble)?.bubble ?: return@map item
            val id = bubble.markId ?: bubble.key
            val starred = (bubble.starred || id in stars.on) && id !in stars.off
            val reminder = reminders[id]
            if (starred == bubble.starred && reminder == bubble.reminder) {
                item
            } else {
                changed = true
                ConversationItem.Bubble(bubble.copy(starred = starred, reminder = reminder))
            }
        }
    return if (changed) copy(items = next.toImmutableList()) else this
}

/** The words of Star and Remind me, from the app's resources. */
class MarkWords(
    private val res: Resources,
) {
    val star: String = res.getString(R.string.starred_star)
    val unstar: String = res.getString(R.string.starred_unstar)
    val starredToast: String = res.getString(R.string.starred_done)
    val unstarredToast: String = res.getString(R.string.starred_undone)
    val starFailed: String = res.getString(R.string.starred_failed)
    val me: String = res.getString(R.string.tools_me)
    val remindMe: String = res.getString(R.string.remind_me)
    val remindFailed: String = res.getString(R.string.remind_failed)
    val remindCancelled: String = res.getString(R.string.remind_cancelled)
    val undo: String = res.getString(R.string.inbox_undo)
    val sheetCancel: String = res.getString(R.string.inbox_cancel)

    fun slot(slot: ReminderSlot): String =
        res.getString(
            when (slot) {
                ReminderSlot.In15Min -> R.string.remind_in_15_min
                ReminderSlot.In1Hour -> R.string.remind_in_1_hour
                ReminderSlot.Tonight -> R.string.remind_tonight
                ReminderSlot.TomorrowMorning -> R.string.remind_tomorrow
            },
        )

    fun cancel(clock: String): String = res.getString(R.string.remind_cancel, clock)

    fun set(
        slot: ReminderSlot,
        clock: String,
    ): String =
        res.getString(
            if (slot == ReminderSlot.TomorrowMorning) R.string.remind_set_tomorrow else R.string.remind_set,
            clock,
        )

    fun notificationTitle(chat: String): String = res.getString(R.string.remind_notification_title, chat)
}

/**
 * Star and Remind me of one conversation page (E6.13), the same for SMS and WhatsApp: the two rows of the bubble menu,
 * the popover of slots and the pills. Decisions are [StarCenter]'s and [RemindCenter]'s (Rust behind both); this class
 * shows them.
 */
class MarkHost(
    private val stars: StarCenter,
    private val reminders: RemindCenter,
    private val overlays: OverlayController,
    private val scope: CoroutineScope,
    private val words: MarkWords,
    val tag: String,
    private val title: () -> String,
    /** "18:05" in the phone's 12/24-hour setting. */
    val clock: (Long) -> String,
    /** Where the popover grows from, given the bubble's rect ([remindAnchor] with the window's numbers). */
    private val popoverAnchor: (Rect) -> Rect = { it },
) {
    private var anchor: Pair<String, Rect>? = null

    /** The menu of [bubble] opened at [rect]: the popover grows out of the same bubble. */
    fun menuOpened(
        bubble: BubbleUi,
        rect: Rect,
    ) {
        anchor = bubble.key to rect
    }

    /** "Star" or "Unstar", then "Remind me"; nothing for a message that cannot be marked. */
    fun menu(bubble: BubbleUi): List<MenuItem> {
        if (bubble.markTarget(tag, title(), words.me) == null) return emptyList()
        return listOf(
            MenuItem.Action(
                if (bubble.starred) words.unstar else words.star,
                if (bubble.starred) RostyIcons.StarFilled else RostyIcons.Star,
            ) { toggleStar(bubble) },
            MenuItem.Action(words.remindMe, RostyIcons.Bell) { remind(bubble) },
        )
    }

    fun toggleStar(bubble: BubbleUi) {
        val target = bubble.markTarget(tag, title(), words.me) ?: return
        val starred = !bubble.starred
        scope.launch {
            val ok = stars.set(target, starred)
            overlays.showToast(
                when {
                    !ok -> words.starFailed
                    starred -> words.starredToast
                    else -> words.unstarredToast
                },
            )
        }
    }

    /** Opens the slots for [bubble] (M33). */
    fun remind(bubble: BubbleUi) {
        val target = bubble.markTarget(tag, title(), words.me) ?: return
        val rect = anchor?.takeIf { it.first == bubble.key }?.second?.let(popoverAnchor)
        scope.launch {
            val slots = reminders.slots()
            if (slots.isEmpty()) {
                overlays.showToast(words.remindFailed)
                return@launch
            }
            val existing = reminders.of(target.id)
            val ui =
                RemindUi(
                    title = words.remindMe,
                    rows = slots.map { RemindRow(it.key, words.slot(it.key), clock(it.dueMs)) }.toImmutableList(),
                    cancel = existing?.let { words.cancel(clock(it.atMs)) },
                    cancelSheet = words.sheetCancel,
                )
            overlays.presentRemind(
                rect,
                ui,
                onPick = { slot -> arm(target, slot) },
                onCancel = {
                    scope.launch {
                        if (existing != null && reminders.cancel(existing.id)) overlays.showToast(words.remindCancelled)
                    }
                },
            )
        }
    }

    private fun arm(
        target: MarkTarget,
        slot: ReminderSlot,
    ) {
        scope.launch {
            val armed = reminders.arm(target, slot, words::notificationTitle)
            if (armed == null) {
                overlays.showToast(words.remindFailed)
            } else {
                overlays.showUndo(
                    words.set(slot, clock(armed.atMs)),
                    onUndo = { scope.launch { reminders.cancel(armed.id) } },
                    actionLabel = words.undo,
                )
            }
        }
    }

    /** [state] as the page draws it: stars and reminder badges on the bubbles. */
    @Composable
    fun marked(state: ConversationUi): ConversationUi {
        val starMarks by remember(this) { stars.marks(tag) }.collectAsState(StarMarks())
        val badges by remember(this) { reminders.badges(tag) }.collectAsState(emptyMap())
        return remember(state, starMarks, badges) { state.withMarks(starMarks, badges.mapValues { clock(it.value) }) }
    }
}

/**
 * The line the "Remind me" popover grows from, so the message it is about stays readable: the bubble's lower edge
 * (the card opens below it) while a card of [panelPx] fits between there and the window's end, else its upper edge
 * (the card opens above it).
 */
fun remindAnchor(
    bubble: Rect,
    windowHeightPx: Float,
    gapPx: Float,
    panelPx: Float,
): Rect {
    val below = bubble.bottom + gapPx
    val y = if (below + panelPx <= windowHeightPx) below else bubble.top - gapPx
    return Rect(bubble.left, y, bubble.right, y)
}

/** The [MarkHost] of the conversation [tag]. */
@Composable
fun rememberMarkHost(
    container: MessagesContainer,
    overlays: OverlayController,
    tag: String,
    days: DayLabels,
    title: () -> String,
): MarkHost {
    val res = LocalResources.current
    // The page's own scope (main thread): the pills and the popover are shown from it.
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val windowDp = LocalConfiguration.current.screenHeightDp
    return remember(container, overlays, tag, res, days, density, windowDp) {
        MarkHost(
            stars = container.stars,
            reminders = container.reminders,
            overlays = overlays,
            scope = scope,
            words = MarkWords(res),
            tag = tag,
            title = title,
            clock = days::clock,
            popoverAnchor = { rect ->
                with(density) {
                    remindAnchor(rect, windowDp * this.density, Spacing.s.toPx(), RemindMetrics.CardRoom.toPx())
                }
            },
        )
    }
}
