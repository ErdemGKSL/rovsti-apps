package dev.rosty.messages.wa

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * The emoji of the reaction bar (M49): the ones used last come first, the standard six fill the rest. Kept in a small
 * local file (`recent-reactions.json`); `messages-prefs.json` has no field for it and Rust no op, so the list never
 * leaves this class. A double tap reacts with [DEFAULT] (M32).
 */
class WaRecentEmoji(
    private val file: File?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val disk = Mutex()
    private var used: List<String> = emptyList()
    private var loaded = false
    private val shown = MutableStateFlow(STANDARD)

    /** At most [SHOWN] emoji, most recently used first. */
    val recent: StateFlow<List<String>> = shown.asStateFlow()

    /** Reads the file once; later calls answer at once. */
    suspend fun load() {
        disk.withLock {
            if (loaded) return
            loaded = true
            used = withContext(io) { read() }
            shown.value = merged(used)
        }
    }

    /** [emoji] was just used for a reaction: it moves to the front. */
    suspend fun note(emoji: String) {
        if (emoji.isBlank()) return
        load()
        disk.withLock {
            used = (listOf(emoji) + used).distinct().take(KEPT)
            shown.value = merged(used)
            val text = Json.encodeToString(ListSerializer(String.serializer()), used)
            withContext(io) {
                try {
                    file?.parentFile?.mkdirs()
                    file?.writeText(text)
                } catch (_: IOException) {
                    // The list is a convenience: it is kept in memory for this run.
                }
            }
        }
    }

    private fun read(): List<String> =
        try {
            file?.takeIf { it.isFile }?.readText()?.let {
                Json.decodeFromString(ListSerializer(String.serializer()), it)
            } ?: emptyList()
        } catch (_: IOException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }

    companion object {
        /** What a double tap reacts with: the first quick reaction of the GPUI app. */
        const val DEFAULT = "👍"
        const val SHOWN = 6
        private const val KEPT = 12
        val STANDARD = listOf(DEFAULT, "❤️", "😂", "😮", "😢", "🙏")

        /** The used ones first, then the standard ones that are not among them. */
        fun merged(used: List<String>): List<String> = (used + STANDARD).distinct().take(SHOWN)
    }
}
