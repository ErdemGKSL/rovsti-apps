package dev.rovsti.bridge

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.ContactsContract.Contacts
import org.json.JSONObject

/**
 * Per-contact call options for Rovsti Contacts: the custom ringtone and "send to voicemail" columns of
 * `Contacts`, and the system ringtone picker. A null ringtone column is the default, "" is silent.
 * Never throws; a failure is `{}` / false.
 */
object ContactOptions {
    /** `{ringtone_uri, ringtone_title, to_voicemail}` (`rovsti_core::contact_options::ContactOptions`). */
    fun read(ctx: Context, id: Long): String {
        return try {
            val projection = arrayOf(Contacts.CUSTOM_RINGTONE, Contacts.SEND_TO_VOICEMAIL)
            ctx.contentResolver.query(contactUri(id), projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return "{}"
                val uri = if (c.isNull(0)) null else c.getString(0)
                val o = JSONObject()
                    .put("ringtone_uri", uri ?: JSONObject.NULL)
                    .put("ringtone_title", if (uri.isNullOrEmpty()) JSONObject.NULL else title(ctx, uri) ?: JSONObject.NULL)
                    .put("to_voicemail", !c.isNull(1) && c.getInt(1) != 0)
                o.toString()
            } ?: "{}"
        } catch (_: RuntimeException) {
            "{}"
        }
    }

    private fun title(ctx: Context, uri: String): String? = try {
        RingtoneManager.getRingtone(ctx, Uri.parse(uri))?.getTitle(ctx)
    } catch (_: RuntimeException) {
        null
    }

    private fun contactUri(id: Long): Uri = ContentUris.withAppendedId(Contacts.CONTENT_URI, id)

    /** [uri] null = default, "" = silent. */
    fun setRingtone(ctx: Context, id: Long, uri: String?): Boolean = update(ctx, id, ContentValues().apply {
        put(Contacts.CUSTOM_RINGTONE, uri)
    })

    fun setVoicemail(ctx: Context, id: Long, on: Boolean): Boolean = update(ctx, id, ContentValues().apply {
        put(Contacts.SEND_TO_VOICEMAIL, if (on) 1 else 0)
    })

    private fun update(ctx: Context, id: Long, values: ContentValues): Boolean = try {
        ctx.contentResolver.update(contactUri(id), values, null, null) > 0
    } catch (_: RuntimeException) {
        false
    }

    /** Pure: the picker's answer as the `ringtone_picked` event; the default uri is null, silent is "". */
    fun pickedJson(resultCode: Int, picked: Uri?, hasExtra: Boolean): String {
        val o = JSONObject()
        if (resultCode != Activity.RESULT_OK || !hasExtra) return o.put("uri", JSONObject.NULL).put("cancelled", true).toString()
        val uri: Any = when {
            picked == null -> ""
            picked == RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) -> JSONObject.NULL
            else -> picked.toString()
        }
        return o.put("uri", uri).put("cancelled", false).toString()
    }

    /** Opens the system ringtone picker; [current] null = default, "" = silent. */
    fun pickRingtone(activity: Activity, current: String?): Boolean = try {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            .putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                when {
                    current == null -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    current.isEmpty() -> null
                    else -> Uri.parse(current)
                },
            )
        @Suppress("DEPRECATION")
        activity.startActivityForResult(intent, Documents.REQUEST_RINGTONE)
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    }

    /** Completes the picker; true when [requestCode] was ours. [emit] gets the `ringtone_picked` JSON. */
    fun handle(requestCode: Int, resultCode: Int, data: Intent?, emit: (String) -> Unit): Boolean {
        if (requestCode != Documents.REQUEST_RINGTONE) return false
        val hasExtra = data?.hasExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) == true
        @Suppress("DEPRECATION")
        val picked: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        emit(pickedJson(resultCode, picked, hasExtra))
        return true
    }
}
