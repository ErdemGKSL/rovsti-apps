package dev.rosty.messages.schedule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import dev.rosty.core.model.messages.ScheduleTarget
import dev.rosty.core.model.messages.ScheduledSms
import dev.rosty.core.model.wa.ScheduledState
import dev.rosty.core.model.wa.ScheduledText
import dev.rosty.design.chrome.ScreenLazyPage
import dev.rosty.design.controls.EmptyState
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.list.SectionHeader
import dev.rosty.design.time.LocalRostyClock
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.R
import kotlinx.coroutines.launch

object ScheduledPageTags {
    const val Page = "scheduled-page"
}

/** One conversation's waiting items on the overview, soonest first. */
data class ScheduledGroup(
    val dest: ScheduleDest,
    val items: List<StripItem>,
)

/**
 * Every waiting scheduled message, by conversation (the inbox's Scheduled overview, GPUI's): SMS by number, WhatsApp by
 * chat, the conversation with the soonest item first. Sent gateway items are history and not listed.
 */
fun scheduledGroups(
    local: List<ScheduledSms>,
    remote: List<ScheduledText>,
): List<ScheduledGroup> {
    val sms =
        local
            .mapNotNull { s -> (s.target as? ScheduleTarget.Sms)?.let { ScheduleDest.Sms(it.to, it.subId) } }
            // One conversation however its number is written ("+90 555 …" and "0555…").
            .fold(emptyList<ScheduleDest.Sms>()) { acc, d ->
                if (acc.any { ScheduleRules.sameAddress(it.to, d.to) }) {
                    acc
                } else {
                    acc +
                        d
                }
            }
    val wa = remote.filter { it.state !is ScheduledState.Sent }.map { ScheduleDest.WhatsApp(it.chat) }.distinct()
    return (sms + wa)
        .map { dest -> ScheduledGroup(dest, ScheduleRules.stripItems(dest, local, remote)) }
        .filter { it.items.isNotEmpty() }
        .sortedBy { it.items.first().atMs }
}

/** How many messages wait (the inbox row's count). */
fun scheduledCount(state: ScheduleState): Int = scheduledGroups(state.local, state.remote).sumOf { it.items.size }

/** The Scheduled overview: each conversation's strip, all rows shown, with Cancel. */
@Composable
fun ScheduledPage(
    container: MessagesContainer,
    onBack: () -> Unit,
) {
    val res = LocalResources.current
    val state by container.schedule.state.collectAsState()
    val book by container.book.book.collectAsState()
    val chats by container.waChats.chats.collectAsState(emptyList())
    val words = remember(res) { scheduleWords(res) }
    val labels = rememberScheduleLabels()
    val clock = LocalRostyClock.current
    val scope = rememberCoroutineScope()
    val groups = remember(state) { scheduledGroups(state.local, state.remote) }
    ScreenLazyPage(
        title = res.getString(R.string.scheduled_title),
        modifier = Modifier.testTag(ScheduledPageTags.Page),
        onBack = onBack,
    ) {
        if (groups.isEmpty()) {
            item(key = "empty") { EmptyState(RostyIcons.Clock, res.getString(R.string.scheduled_none), "") }
        }
        groups.forEach { group ->
            val name =
                when (val d = group.dest) {
                    is ScheduleDest.Sms -> {
                        val digits = d.to.filter(Char::isDigit).takeLast(MATCH_DIGITS)
                        book.contacts.values
                            .firstOrNull { c -> c.numbers.any { it.raw.filter(Char::isDigit).endsWith(digits) } }
                            ?.displayName ?: d.to
                    }

                    is ScheduleDest.WhatsApp -> {
                        chats.firstOrNull { it.jid == d.chat }?.name?.takeIf { it.isNotBlank() }
                            ?: d.chat.substringBefore('@')
                    }
                }
            item(key = "head-${group.dest}") { SectionHeader(name) }
            item(key = "strip-${group.dest}") {
                ScheduledStrip(
                    ScheduledStripUi.of(
                        group.items,
                        expanded = true,
                        nowMs = clock.nowMillis(),
                        words = words,
                        labels = labels,
                    ),
                    words,
                    onCancel = { key -> scope.launch { container.schedule.cancel(key) } },
                    onMore = {},
                )
            }
        }
    }
}

private const val MATCH_DIGITS = 9
