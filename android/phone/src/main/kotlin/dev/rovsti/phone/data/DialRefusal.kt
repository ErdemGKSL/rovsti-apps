package dev.rovsti.phone.data

import androidx.annotation.StringRes
import dev.rovsti.core.RustError
import dev.rovsti.phone.R

/**
 * Why the core would not dial a text (F30). The core sends a stable code (`detail.reason` of `dial.validate`) next to
 * its English sentence; the app words the code from its own resources, so a Turkish phone reads Turkish. A code this
 * build does not know is [Other], worded generically, never the core's English.
 */
enum class DialRefusal(
    val code: String?,
    @StringRes val text: Int,
) {
    Empty("empty", R.string.dial_refused_empty),
    NoDialable("no_dialable", R.string.dial_refused_no_dialable),
    Other(null, R.string.dial_refused_other),
    ;

    companion object {
        /** The refusal a stable [code] names; [Other] for null or a code this build does not know. */
        fun of(code: String?): DialRefusal = entries.firstOrNull { it.code != null && it.code == code } ?: Other

        fun of(error: RustError): DialRefusal = of(error.reason)
    }
}
