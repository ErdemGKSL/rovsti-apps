package dev.rosty.messages.schedule

import androidx.compose.runtime.Immutable
import dev.rosty.core.model.messages.ScheduleTarget
import dev.rosty.core.model.messages.ScheduledSms
import dev.rosty.core.model.wa.ScheduledState
import dev.rosty.core.model.wa.ScheduledText
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where a scheduled text goes (GPUI `schedule_ui::Dest`). */
sealed interface ScheduleDest {
    /** An SMS to one number, on [subId] (null: the default SIM). */
    data class Sms(
        val to: String,
        val subId: Int?,
    ) : ScheduleDest

    /** A WhatsApp chat: the gateway keeps and sends it. */
    data class WhatsApp(
        val chat: String,
    ) : ScheduleDest
}

/** Which queue a waiting item lives in. */
@Immutable
sealed interface StripKey {
    /** An item of the local queue (`scheduled.json`), sent by its alarm. */
    data class Local(
        val id: Long,
    ) : StripKey

    /** An item the gateway holds. */
    data class Gateway(
        val id: String,
    ) : StripKey
}

/** One waiting scheduled message of a conversation. */
@Immutable
data class StripItem(
    val key: StripKey,
    val atMs: Long,
    val body: String,
    /** The gateway tried and failed; the item stays so the user sees it. */
    val failed: Boolean = false,
)

/**
 * The pure rules of scheduled send (AD-MSG6), ported from the GPUI app.
 *
 * Ported from apps/messages/src/schedule_ui.rs
 */
object ScheduleRules {
    /** `rosty_core::schedule::MIN_LEAD_MS`: a message is scheduled at least a minute ahead. */
    const val MIN_LEAD_MS = 60_000L

    /** Rows the strip shows before "Show n more". */
    const val STRIP_ROWS = 3

    private const val PREVIEW_CHARS = 60

    /**
     * The id of a new local item: the clock, or one past the largest id queued, so ids survive restarts without
     * colliding.
     */
    fun nextId(
        existing: List<ScheduledSms>,
        nowMs: Long,
    ): Long = existing.fold(nowMs.coerceAtLeast(1)) { id, item -> maxOf(id, item.id + 1) }

    /** Two spellings of one number: the same last digits, else the same text (a sender like "BANK"). */
    fun sameAddress(
        a: String,
        b: String,
    ): Boolean {
        val da = a.filter(Char::isDigit)
        val db = b.filter(Char::isDigit)
        if (da.length >= MIN_DIGITS && db.length >= MIN_DIGITS) {
            return da.takeLast(MATCH_DIGITS) == db.takeLast(MATCH_DIGITS)
        }
        return a.trim().equals(b.trim(), ignoreCase = true)
    }

    /**
     * The waiting items of one conversation, soonest first: the local queue's SMS to its recipient, or the gateway's
     * pending (or failed) texts to its chat. Sent gateway items are history, not waiting.
     */
    fun stripItems(
        dest: ScheduleDest,
        local: List<ScheduledSms>,
        remote: List<ScheduledText>,
    ): List<StripItem> =
        when (dest) {
            is ScheduleDest.Sms -> {
                local
                    .filter { (it.target as? ScheduleTarget.Sms)?.let { t -> sameAddress(t.to, dest.to) } == true }
                    .map { StripItem(StripKey.Local(it.id), it.atMs, it.body) }
            }

            is ScheduleDest.WhatsApp -> {
                remote
                    .filter { it.chat == dest.chat && it.state !is ScheduledState.Sent }
                    .map { StripItem(StripKey.Gateway(it.id), it.sendAtMs, it.text, it.state is ScheduledState.Failed) }
            }
        }.sortedBy { it.atMs }

    /** The rows the strip shows: the first few, or all when expanded. */
    fun visible(
        items: List<StripItem>,
        expanded: Boolean,
    ): List<StripItem> = if (expanded) items else items.take(STRIP_ROWS)

    /** A one-line preview of a scheduled text. */
    fun preview(body: String): String {
        val line = body.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        return if (line.length <= PREVIEW_CHARS) line else line.take(PREVIEW_CHARS) + "…"
    }

    /** True when [after] is failed and [before] was not (unknown counts as not failed). */
    fun newlyFailed(
        before: ScheduledText?,
        after: ScheduledText,
    ): Boolean = after.state is ScheduledState.Failed && before?.state !is ScheduledState.Failed

    /** Puts [item] into the gateway list, replacing the same id. */
    fun upsert(
        list: List<ScheduledText>,
        item: ScheduledText,
    ): List<ScheduledText> =
        if (list.any { it.id == item.id }) {
            list.map {
                if (it.id ==
                    item.id
                ) {
                    item
                } else {
                    it
                }
            }
        } else {
            list + item
        }

    /** The text to put back in the composer after a cancel: the body, but never over what is being typed. */
    fun textAfterCancel(
        composer: String,
        body: String,
    ): String? = body.takeIf { composer.isBlank() }

    private const val MIN_DIGITS = 7
    private const val MATCH_DIGITS = 10
}

/** The day words of the schedule labels. */
class ScheduleDayWords(
    val today: String,
    val tomorrow: String,
)

/**
 * "Today 18:00", "Tomorrow 09:00" or "12 Nov 09:00" (GPUI `when_label`), in the phone's zone, language and clock
 * setting. Pure: all three are arguments.
 */
class ScheduleLabels(
    private val zone: ZoneId,
    locale: Locale,
    is24Hour: Boolean,
    private val words: ScheduleDayWords,
) {
    private val date = DateTimeFormatter.ofPattern("d MMM", locale)
    private val time = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale)

    fun dayOf(atMs: Long): LocalDate = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()

    fun clock(atMs: Long): String = time.format(Instant.ofEpochMilli(atMs).atZone(zone))

    fun day(
        atMs: Long,
        nowMs: Long,
    ): String {
        val day = dayOf(atMs)
        val now = dayOf(nowMs)
        return when (day) {
            now -> words.today
            now.plusDays(1) -> words.tomorrow
            else -> date.format(day)
        }
    }

    fun whenLabel(
        atMs: Long,
        nowMs: Long,
    ): String = "${day(atMs, nowMs)} ${clock(atMs)}"

    /** The instant of [pick] counted from the day of [nowMs]. */
    fun atMs(
        pick: CustomPick,
        nowMs: Long,
    ): Long {
        val local = LocalDateTime.of(dayOf(nowMs).plusDays(pick.day.toLong()), java.time.LocalTime.MIDNIGHT)
        return local
            .plusMinutes(pick.minutes.toLong())
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    /** The next quarter hour at least 15 minutes from [nowMs] (GPUI `CustomPick::initial`). */
    fun initialPick(nowMs: Long): CustomPick {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val minuteOfDay = now.hour * MINUTES_PER_HOUR + now.minute
        val target = (minuteOfDay + 2 * CustomPick.STEP - 1) / CustomPick.STEP * CustomPick.STEP
        return CustomPick(day = target / CustomPick.DAY_MINUTES, minutes = target % CustomPick.DAY_MINUTES)
    }

    /** Whether [pick] is far enough ahead to schedule. */
    fun isSchedulable(
        pick: CustomPick,
        nowMs: Long,
    ): Boolean = atMs(pick, nowMs) >= nowMs + ScheduleRules.MIN_LEAD_MS

    private companion object {
        const val MINUTES_PER_HOUR = 60
    }
}

/**
 * A send time picked with the sheet's day and time steppers: a day offset from today and a time of day in 15-minute
 * steps (GPUI `CustomPick`).
 */
@Immutable
data class CustomPick(
    /** 0 is today, at most [MAX_DAY]. */
    val day: Int,
    /** Minutes since local midnight, always a multiple of [STEP]. */
    val minutes: Int,
) {
    /** Moves by whole days, clamped to today..[MAX_DAY] days ahead. */
    fun stepDay(delta: Int): CustomPick = copy(day = (day + delta).coerceIn(0, MAX_DAY))

    /** Moves by quarter hours and wraps within the day. */
    fun stepMinutes(delta: Int): CustomPick = copy(minutes = Math.floorMod(minutes + delta * STEP, DAY_MINUTES))

    companion object {
        const val MAX_DAY = 30
        const val STEP = 15
        const val DAY_MINUTES = 24 * 60
    }
}
