package dev.rovsti.platform.files

// Ported from crates/rovsti_android/kotlin/dev/rovsti/platform/notify/MessageNotifications.kt copyToClipboard and
// the bridge copyText (E3.8a).

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** The system clipboard. */
interface ClipboardRepository {
    /** Copies [text]; with [sensitive] the clip is hidden from the paste preview (API 33+). False when refused. */
    fun copy(
        text: String,
        sensitive: Boolean = false,
    ): Boolean

    /** The text of the primary clip, or null when it is empty or not text. */
    fun primaryText(): String?

    /** Emits each time the primary clip changes. */
    fun changes(): Flow<Unit>
}

class SystemClipboardRepository(
    context: Context,
) : ClipboardRepository {
    private val manager: ClipboardManager? = context.getSystemService(ClipboardManager::class.java)

    override fun copy(
        text: String,
        sensitive: Boolean,
    ): Boolean {
        val clipboard = manager ?: return false
        return try {
            val clip = ClipData.newPlainText("Rovsti", text)
            if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras =
                    PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            }
            clipboard.setPrimaryClip(clip)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun primaryText(): String? =
        try {
            manager
                ?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.text
                ?.toString()
        } catch (_: RuntimeException) {
            null
        }

    override fun changes(): Flow<Unit> =
        callbackFlow {
            val clipboard = manager
            val listener = ClipboardManager.OnPrimaryClipChangedListener { trySend(Unit) }
            clipboard?.addPrimaryClipChangedListener(listener)
            awaitClose { clipboard?.removePrimaryClipChangedListener(listener) }
        }
}
