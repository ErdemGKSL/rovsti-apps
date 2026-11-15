package dev.rosty.messages.conversation

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Day separators and bubble clocks (GPUI `day_label`): "Today", "Yesterday", "Mon 9 Nov" this year, "9 Nov 2025"
 * before. Pure: zone, locale and the 24-hour setting are arguments.
 */
class DayLabels(
    private val zone: ZoneId,
    locale: Locale,
    private val is24Hour: Boolean,
    private val today: String,
    private val yesterday: String,
) {
    private val weekday = DateTimeFormatter.ofPattern("EEE d MMM", locale)
    private val full = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
    private val clock24 = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val clock12 = DateTimeFormatter.ofPattern("h:mm a", locale)

    fun day(atMs: Long): LocalDate = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()

    fun label(
        atMs: Long,
        nowMs: Long,
    ): String {
        val day = day(atMs)
        val now = day(nowMs)
        return when {
            day == now -> today
            day == now.minusDays(1) -> yesterday
            day.year == now.year -> weekday.format(day)
            else -> full.format(day)
        }
    }

    fun clock(atMs: Long): String = (if (is24Hour) clock24 else clock12).format(Instant.ofEpochMilli(atMs).atZone(zone))
}

/**
 * Puts day separators between [bubbles] (oldest first in, newest first out, the order of a reverse-layout list) and
 * marks the first bubble of each run of one side and sender.
 */
fun withDays(
    bubbles: List<BubbleUi>,
    days: DayLabels,
    nowMs: Long,
    divider: Pair<String, String>? = null,
): List<ConversationItem> {
    val out = ArrayList<ConversationItem>(bubbles.size + 8)
    var lastDay: LocalDate? = null
    var previous: BubbleUi? = null
    for (b in bubbles) {
        val day = days.day(b.atMs)
        if (day != lastDay) {
            out += ConversationItem.Day("day-$day", days.label(b.atMs, nowMs))
            lastDay = day
            previous = null
        }
        if (divider != null && b.key == divider.first) out += ConversationItem.Divider("unread", divider.second)
        val first = previous == null || previous.outgoing != b.outgoing || previous.sender != b.sender
        out += ConversationItem.Bubble(b.copy(firstOfRun = first))
        previous = b
    }
    return out.asReversed()
}
