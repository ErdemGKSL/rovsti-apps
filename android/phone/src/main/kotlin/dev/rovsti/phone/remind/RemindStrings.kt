package dev.rovsti.phone.remind

import android.content.res.Resources
import dev.rovsti.phone.R

/** The words of the remind menu that the app (not the core) says. */
class RemindStrings(
    private val res: Resources,
) {
    val pickTime: String = res.getString(R.string.remind_pick_time)

    /** "Reminder at 18:05". */
    fun setAt(clock: String): String = res.getString(R.string.remind_set_at, clock)
}
