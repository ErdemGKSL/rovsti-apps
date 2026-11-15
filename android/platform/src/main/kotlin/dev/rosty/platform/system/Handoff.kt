package dev.rosty.platform.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import org.json.JSONObject

/**
 * Hands a WhatsApp call or chat to a sibling Rosty app. The target is found by intent [action]
 * and kept only when it is signed with the same key as this app, so a look-alike app cannot
 * receive a contact's JID. The receiving activity holds [PERMISSION] (a signature permission)
 * and passes its intent on to the app.
 *
 * Ported from crates/rosty_android/kotlin/dev/rosty/bridge/Handoff.kt
 */
object Handoff {
    const val ACTION_WA_CALL = "dev.rosty.action.WA_CALL"
    const val ACTION_WA_CHAT = "dev.rosty.action.WA_CHAT"
    const val EXTRA_JID = "dev.rosty.extra.JID"
    const val EXTRA_NAME = "dev.rosty.extra.NAME"
    const val PERMISSION = "dev.rosty.permission.HANDOFF"

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

    /**
     * The activity of the first signature-matching app that handles [action]; null when none is installed (or
     * visible: the app manifest carries the `<queries>` entry that shows the sibling, ARCH 7.2).
     */
    @SuppressLint("QueryPermissionsNeeded")
    private fun target(
        context: Context,
        action: String,
    ): ActivityInfo? {
        val pm = context.packageManager

        @Suppress("DEPRECATION")
        return pm
            .queryIntentActivities(Intent(action), 0)
            .map { it.activityInfo }
            .firstOrNull {
                pm.checkSignatures(
                    context.packageName,
                    it.packageName,
                ) == PackageManager.SIGNATURE_MATCH
            }
    }

    /** Whether a sibling Rosty app (signed like this one) can take [action]: an action nobody takes is not offered. */
    fun canStart(
        context: Context,
        action: String,
    ): Boolean =
        isHandoffAction(action) &&
            try {
                target(context, action) != null
            } catch (_: Exception) {
                false
            }

    /** Starts the first signature-matching app that handles [action]; false when none does. */
    fun start(
        context: Context,
        action: String,
        jid: String,
        name: String,
    ): Boolean {
        if (!isHandoffAction(action) || jid.isEmpty()) return false
        return try {
            val target = target(context, action) ?: return false
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
