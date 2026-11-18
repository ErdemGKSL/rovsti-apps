package dev.rosty.messages.notify

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a WhatsApp message notification shows (round 7b; Settings, Notifications, "WhatsApp previews"). It applies to
 * every WhatsApp notification: the ones posted while the vault is open and the ones posted from a sealed push while it
 * is locked. On the lock screen each of them shows only its public version ("WhatsApp", "N new messages") until the
 * phone itself is unlocked.
 */
enum class WaPreviewMode(
    val key: String,
) {
    /** The sender (or the chat) and the message text. The default. */
    NameAndPreview("name_and_preview"),

    /** The sender (or the chat) and "N new messages". */
    NameOnly("name_only"),

    /** "WhatsApp" and "N new messages": nobody named, no text. */
    NoContent("no_content"),
    ;

    companion object {
        val DEFAULT = NameAndPreview

        fun of(key: String?): WaPreviewMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** Where the choice is kept: the app's own preferences, readable by a cold process woken by a push. */
interface WaPreviewStore {
    val mode: StateFlow<WaPreviewMode>

    fun set(mode: WaPreviewMode)

    /** A store in memory (tests, previews). */
    class InMemory(
        initial: WaPreviewMode = WaPreviewMode.DEFAULT,
    ) : WaPreviewStore {
        private val flow = MutableStateFlow(initial)
        override val mode: StateFlow<WaPreviewMode> = flow.asStateFlow()

        override fun set(mode: WaPreviewMode) {
            flow.value = mode
        }
    }
}

/**
 * [WaPreviewStore] in the `rosty_wa_preview` preferences: the choice only (a setting, no content). The notifiers read
 * it through [WaPreviews.mode], so a push in a process with no UI follows it too.
 */
class PrefsWaPreviewStore(
    context: Context,
) : WaPreviewStore {
    private val app = context.applicationContext
    private val flow = MutableStateFlow(WaPreviews.mode(app))
    override val mode: StateFlow<WaPreviewMode> = flow.asStateFlow()

    override fun set(mode: WaPreviewMode) {
        WaPreviews.set(app, mode)
        flow.value = mode
    }
}

/** The stored choice. */
object WaPreviews {
    private const val PREFS = "rosty_wa_preview"
    private const val KEY_MODE = "mode"

    fun mode(context: Context): WaPreviewMode =
        try {
            WaPreviewMode.of(prefs(context).getString(KEY_MODE, null))
        } catch (_: RuntimeException) {
            WaPreviewMode.DEFAULT
        }

    fun set(
        context: Context,
        mode: WaPreviewMode,
    ) {
        prefs(context).edit().putString(KEY_MODE, mode.key).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
