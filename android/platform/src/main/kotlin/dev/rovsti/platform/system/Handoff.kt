package dev.rovsti.platform.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONObject

/**
 * Hands a WhatsApp call or chat to a sibling Rovsti app. The target is found by intent [action]
 * and kept only when it is signed with the same key as this app, so a look-alike app cannot
 * receive a contact's JID. The receiving activity holds [PERMISSION] (a signature permission)
 * and passes its intent on to the app.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/Handoff.kt
 */
object Handoff {
    const val ACTION_WA_CALL = "dev.rovsti.action.WA_CALL"
    const val ACTION_WA_CHAT = "dev.rovsti.action.WA_CHAT"
    const val EXTRA_JID = "dev.rovsti.extra.JID"
    const val EXTRA_NAME = "dev.rovsti.extra.NAME"
    const val PERMISSION = "dev.rovsti.permission.HANDOFF"

    /** Class-name suffix of the permission-guarded `activity-alias` that receives hand-offs. */
    const val ALIAS_SUFFIX = ".WaHandoff"

    /** Pure: whether [action] is one this bridge hands off. */
    fun isHandoffAction(action: String?): Boolean = action == ACTION_WA_CALL || action == ACTION_WA_CHAT

    /** Pure: the one-shot `handoff` payload, or null when [action] is unknown or [jid] is empty. */
    fun payload(
        action: String?,
        jid: String?,
        name: String?,
    ): String? {
        if (!isHandoffAction(action) || jid.isNullOrEmpty()) return null
        return JSONObject()
            .put("action", action)
            .put("jid", jid)
            .put("name", name.orEmpty())
            .toString()
    }

    /** Starts the first signature-matching app that handles [action]; false when none does. The app manifest carries
     * the `<queries>` entry for the hand-off actions (ARCH 7.2). */
    @SuppressLint("QueryPermissionsNeeded")
    fun start(
        context: Context,
        action: String,
        jid: String,
        name: String,
    ): Boolean {
        if (!isHandoffAction(action) || jid.isEmpty()) return false
        return try {
            val pm = context.packageManager
            val probe = Intent(action)

            @Suppress("DEPRECATION")
            val target =
                pm
                    .queryIntentActivities(probe, 0)
                    .map { it.activityInfo }
                    .firstOrNull {
                        pm.checkSignatures(
                            context.packageName,
                            it.packageName,
                        ) == PackageManager.SIGNATURE_MATCH
                    }
                    ?: return false
            val intent =
                Intent(action)
                    .setClassName(target.packageName, target.name)
                    .putExtra(EXTRA_JID, jid)
                    .putExtra(EXTRA_NAME, name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
