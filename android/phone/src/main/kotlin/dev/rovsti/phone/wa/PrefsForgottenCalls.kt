package dev.rovsti.phone.wa

import android.content.Context
import dev.rovsti.wa.calls.ForgottenCalls

/** The deleted WhatsApp calls in a preferences file, so a reload from the gateway does not bring them back. */
class PrefsForgottenCalls(
    context: Context,
) : ForgottenCalls {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun ids(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()

    @Synchronized
    override fun add(ids: Collection<String>) {
        // Newest ids last in a plain set is not possible; the file is capped by dropping arbitrary old ids.
        val next = (ids() + ids).toList().takeLast(MAX)
        prefs.edit().putStringSet(KEY, next.toSet()).apply()
    }

    private companion object {
        const val FILE = "wa_calls_forgotten"
        const val KEY = "ids"
        const val MAX = 500
    }
}
