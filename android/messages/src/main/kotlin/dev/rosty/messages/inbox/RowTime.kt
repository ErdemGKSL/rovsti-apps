package dev.rosty.messages.inbox

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The time on an inbox row (GPUI `inbox::row_time`): the clock today ("14:05" or "2:05 PM" by the phone's setting),
 * "Yesterday", the day and month this year ("9 Nov"), the full date before ("9 Nov 2025"). Pure: the zone, the locale
 * and the 24-hour setting are arguments, so the labels are tested on the JVM in English and Turkish.
 */
class RowTime(
    private val zone: ZoneId,
    private val locale: Locale,
    private val is24Hour: Boolean,
    private val yesterday: String,
) {
    private val clock24 = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val clock12 = DateTimeFormatter.ofPattern("h:mm a", locale)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
    private val full = DateTimeFormatter.ofPattern("d MMM yyyy", locale)

    fun label(
        atMs: Long,
        nowMs: Long,
    ): String {
        val at = Instant.ofEpochMilli(atMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val day: LocalDate = at.toLocalDate()
        return when {
            day == today || day.isAfter(today) -> (if (is24Hour) clock24 else clock12).format(at)
            day == today.minusDays(1) -> yesterday
            day.year == today.year -> dayMonth.format(at)
            else -> full.format(at)
        }
    }

    /** The clock alone ("14:05"), for bubbles and "Muted until". */
    fun clock(atMs: Long): String = (if (is24Hour) clock24 else clock12).format(Instant.ofEpochMilli(atMs).atZone(zone))
}
