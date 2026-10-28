package dev.rovsti.bridge

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract

/**
 * One `ContentObserver` each for `CallLog.Calls` and `ContactsContract`. They
 * only emit `call_log_changed` / `contacts_changed`, debounced 300 ms (AD-9).
 */
object ProviderObservers {
    const val DEBOUNCE_MS = 300L
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    @Synchronized
    fun register(context: Context) {
        if (registered) return
        registered = true
        val resolver = context.contentResolver
        try {
            resolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, Debounced("call_log_changed"))
            resolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, Debounced("contacts_changed"))
        } catch (_: SecurityException) {
            registered = false
        }
    }

    private class Debounced(private val kind: String) : ContentObserver(handler) {
        private val fire = Runnable { RovstiBridge.emit(kind, "{\"v\":1}") }

        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(fire)
            handler.postDelayed(fire, DEBOUNCE_MS)
        }
    }
}
