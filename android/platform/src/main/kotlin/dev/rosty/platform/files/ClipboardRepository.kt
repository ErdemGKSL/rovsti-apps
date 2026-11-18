package dev.rosty.platform.files

// Ported from crates/rosty_android/kotlin/dev/rosty/platform/notify/MessageNotifications.kt copyToClipboard and
// the bridge copyText (E3.8a).

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

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

    /** Empties the clipboard. False when refused (or not supported). */
    fun clear(): Boolean = false
}

/** How long a copied secret (the WhatsApp vault passphrase, plan 4) stays on the clipboard. */
const val SECRET_CLIP_MS = 60_000L

/**
 * Copies a secret through the sensitive path (hidden from the paste preview) and clears it after [clearAfterMs] while
 * the clipboard still holds it. A clip the app can no longer read (it went to the background, Android 10+) is cleared
 * too: the secret may still be on it. A process that dies first leaves it there.
 */
fun ClipboardRepository.copySecret(
    scope: CoroutineScope,
    text: String,
    clearAfterMs: Long = SECRET_CLIP_MS,
): Boolean {
    if (!copy(text, sensitive = true)) return false
    scope.launch {
        delay(clearAfterMs)
        val now = primaryText()
        if (now == null || now == text) clear()
    }
    return true
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
            val clip = ClipData.newPlainText("Rosty", text)
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

    override fun clear(): Boolean =
        try {
            manager?.clearPrimaryClip()
            manager != null
        } catch (_: RuntimeException) {
            false
        }

    override fun changes(): Flow<Unit> =
        callbackFlow {
            val clipboard = manager
            val listener = ClipboardManager.OnPrimaryClipChangedListener { trySend(Unit) }
            clipboard?.addPrimaryClipChangedListener(listener)
            awaitClose { clipboard?.removePrimaryClipChangedListener(listener) }
        }
}
