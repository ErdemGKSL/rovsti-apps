package dev.rovsti.phone.remind

import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import dev.rovsti.core.model.chat.ReminderSlot
import dev.rovsti.core.model.chat.ReminderSlotOption
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.overlay.ButtonMenuEntry
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.OverlayController
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * The "Remind me later" menu (U13, U44): the core's four slots (15 minutes, an hour, this evening, tomorrow morning)
 * and "Pick a time...". It is a [ButtonMenuEntry] that grows out of [anchor] like every other menu of the app; it
 * replaces the action sheet of round 12.
 */
fun remindMenuItems(
    slots: List<ReminderSlotOption>,
    pickTimeLabel: String,
    onSlot: (ReminderSlot) -> Unit,
    onPickTime: () -> Unit,
): ImmutableList<MenuItem> =
    buildList<MenuItem> {
        for (slot in slots) add(MenuItem.Action(slot.label, RovstiIcons.Clock) { onSlot(slot.key) })
        add(MenuItem.Separator)
        add(MenuItem.Action(pickTimeLabel, RovstiIcons.Clock, onClick = onPickTime))
    }.toImmutableList()

/** Presents the remind menu at [anchor] (window pixels). */
fun OverlayController.presentRemindMenu(
    anchor: Rect,
    items: ImmutableList<MenuItem>,
) {
    present(ButtonMenuEntry(anchor, items))
}

/**
 * Where the menu opens when the thing that asked for it (a row's long-press menu that has just closed) has no rect of
 * its own: a small rect at the horizontal centre, [fraction] of the way down the window.
 */
fun menuAnchor(
    window: androidx.compose.ui.unit.IntSize,
    fraction: Float,
): Rect {
    val side = ANCHOR_SIDE
    return Rect(
        Offset(window.width / 2f - side / 2f, window.height * fraction - side / 2f),
        Size(side, side),
    )
}

private const val ANCHOR_SIDE = 8f

/** Pure time arithmetic of "Pick a time". */
object RemindTime {
    /** The next moment at [hour]:[minute] local time after [nowMs]: today when still ahead, else tomorrow. */
    fun next(
        nowMs: Long,
        hour: Int,
        minute: Int,
        zone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val cal = GregorianCalendar(zone).apply { timeInMillis = nowMs }
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= nowMs) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    /** "18:05" in the phone's own clock style. */
    fun clock(
        context: Context,
        atMs: Long,
    ): String = DateFormat.getTimeFormat(context).format(Date(atMs))
}

/** The system time picker; calls [onPicked] with the next such time in milliseconds. */
fun showTimePicker(
    context: Context,
    nowMs: Long,
    onPicked: (Long) -> Unit,
) {
    val start = GregorianCalendar().apply { timeInMillis = nowMs + HOUR_MS }
    TimePickerDialog(
        context,
        { _, hour, minute -> onPicked(RemindTime.next(nowMs, hour, minute)) },
        start.get(Calendar.HOUR_OF_DAY),
        start.get(Calendar.MINUTE),
        DateFormat.is24HourFormat(context),
    ).show()
}

private const val HOUR_MS = 3_600_000L
