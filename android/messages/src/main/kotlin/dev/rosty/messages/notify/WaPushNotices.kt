package dev.rosty.messages.notify

import android.content.Context

/**
 * What a WhatsApp message push does in Messages (`plans/wa-vault.md` 6, 13.6). The push carries `kind`, `chat` and
 * `count`, never text. While the vault is open in this process, the app's event socket brings the message and the
 * announcer posts it. Otherwise (locked, or a cold process, where the vault is always locked) one content-free notice
 * counts it; nothing is fetched from the gateway: no request goes out while locked.
 */
object WaPushNotices {
    /** Handles [data] of a push; true when the locked notice was posted. Calls and call ends are not messages. */
    fun onPush(
        context: Context,
        data: Map<String, String>,
        unlocked: Boolean,
    ): Boolean {
        if (data["kind"] != KIND_MESSAGE || unlocked) return false
        val count = data["count"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        return LockedWhatsAppNotice.add(context, count)
    }

    private const val KIND_MESSAGE = "message"
}
