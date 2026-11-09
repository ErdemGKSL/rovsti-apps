package dev.rovsti.platform.contacts

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.ContactsContract.Contacts
import dev.rovsti.core.model.contacts.ContactOptions

/**
 * Per-contact call options: the custom ringtone and "send to voicemail" columns of `Contacts`, and the system ringtone
 * picker as an intent (the caller starts it and hands the result to [parsePicked]). A null ringtone column is the
 * default, "" is silent.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/ContactOptions.kt. The object is named
 * `ContactCallOptions` because `ContactOptions` is the `:core-bridge` model it returns.
 */
object ContactCallOptions {
    /** What the ringtone picker answered. */
    sealed interface Pick {
        data object Cancelled : Pick

        /** [uri] null: the default ringtone; "": silent. */
        data class Chosen(
            val uri: String?,
        ) : Pick
    }

    /** The options of a contact, or null when it is gone. */
    fun read(
        context: Context,
        id: Long,
    ): ContactOptions? =
        try {
            val projection = arrayOf(Contacts.CUSTOM_RINGTONE, Contacts.SEND_TO_VOICEMAIL)
            context.contentResolver.query(contactUri(id), projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val uri = if (c.isNull(0)) null else c.getString(0)
                ContactOptions(
                    ringtoneUri = uri,
                    ringtoneTitle = if (uri.isNullOrEmpty()) null else title(context, uri),
                    toVoicemail = !c.isNull(1) && c.getInt(1) != 0,
                )
            }
        } catch (_: RuntimeException) {
            null
        }

    private fun title(
        context: Context,
        uri: String,
    ): String? =
        try {
            RingtoneManager.getRingtone(context, Uri.parse(uri))?.getTitle(context)
        } catch (_: RuntimeException) {
            null
        }

    private fun contactUri(id: Long): Uri = ContentUris.withAppendedId(Contacts.CONTENT_URI, id)

    /** [uri] null = default, "" = silent. */
    fun setRingtone(
        context: Context,
        id: Long,
        uri: String?,
    ): ContactResult<Unit> = update(context, id, ContentValues().apply { put(Contacts.CUSTOM_RINGTONE, uri) })

    fun setVoicemail(
        context: Context,
        id: Long,
        on: Boolean,
    ): ContactResult<Unit> =
        update(
            context,
            id,
            ContentValues().apply {
                put(Contacts.SEND_TO_VOICEMAIL, if (on) 1 else 0)
            },
        )

    private fun update(
        context: Context,
        id: Long,
        values: ContentValues,
    ): ContactResult<Unit> =
        guarded {
            if (context.contentResolver.update(contactUri(id), values, null, null) > 0) {
                ContactResult.Done
            } else {
                ContactResult.failed(ContactResult.Reason.NotFound)
            }
        }

    /** Pure: the picker's answer. A picked null is silent, the system default uri is the default ringtone. */
    fun parsePicked(
        resultCode: Int,
        picked: Uri?,
        hasExtra: Boolean,
    ): Pick =
        when {
            resultCode != Activity.RESULT_OK || !hasExtra -> Pick.Cancelled
            picked == null -> Pick.Chosen("")
            picked == RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) -> Pick.Chosen(null)
            else -> Pick.Chosen(picked.toString())
        }

    /** [parsePicked] for the result [data] of the picker activity. */
    fun parsePicked(
        resultCode: Int,
        data: Intent?,
    ): Pick {
        val hasExtra = data?.hasExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) == true

        @Suppress("DEPRECATION")
        val picked: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        return parsePicked(resultCode, picked, hasExtra)
    }

    /** The system ringtone picker; [current] null = default, "" = silent. */
    fun pickerIntent(current: String?): Intent {
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        return Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, default)
            .putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                when {
                    current == null -> default
                    current.isEmpty() -> null
                    else -> Uri.parse(current)
                },
            )
    }
}
