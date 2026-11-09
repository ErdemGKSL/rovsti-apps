package dev.rovsti.platform.system

/**
 * The phone's clock style for Recents time labels.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/MissedCalls.kt (LocaleTimeInfo)
 */
object LocaleTimeInfo {
    /**
     * `"<tz offset seconds>,<12|24>,<dmy|mdy|ymd>"`; [order] is the characters
     * of `DateFormat.getDateFormatOrder` (`d`, `M`, `y`).
     */
    fun encode(
        offsetMs: Int,
        is24: Boolean,
        order: CharArray,
    ): String {
        val letters = order.map { it.lowercaseChar() }.filter { it == 'd' || it == 'm' || it == 'y' }
        val code = if (letters.size == 3) letters.joinToString("") else "dmy"
        return "${offsetMs / 1000},${if (is24) 24 else 12},$code"
    }
}
