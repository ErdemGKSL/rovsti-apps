package dev.rovsti.phone.data

import android.content.Context
import android.text.format.DateFormat
import dev.rovsti.core.model.phone.DateOrder
import dev.rovsti.core.model.phone.LocaleTime
import dev.rovsti.core.model.phone.TimeFormat
import java.util.TimeZone

/** What the device says about local time, for the ops that never read the clock themselves (`recents.build`). */
object DeviceLocaleTime {
    fun now(
        context: Context,
        nowMs: Long,
    ): LocaleTime =
        LocaleTime(
            tzOffsetS = TimeZone.getDefault().getOffset(nowMs) / MS_PER_S,
            format =
                TimeFormat(
                    hour12 = !DateFormat.is24HourFormat(context),
                    dateOrder = order(DateFormat.getDateFormatOrder(context)),
                ),
        )

    /** `DateFormat.getDateFormatOrder` answers the pattern letters in order, e.g. `d M y`. */
    fun order(letters: CharArray): DateOrder =
        when (letters.firstOrNull { it == 'd' || it == 'M' || it == 'y' }) {
            'M' -> DateOrder.MonthDayYear
            'y' -> DateOrder.YearMonthDay
            else -> DateOrder.DayMonthYear
        }

    private const val MS_PER_S = 1000
}
