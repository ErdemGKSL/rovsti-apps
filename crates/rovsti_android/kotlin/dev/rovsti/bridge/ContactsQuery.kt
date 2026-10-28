package dev.rovsti.bridge

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone

/** Contacts pulls and number lookup (AD-9): one row per number, grouped in [ProviderJson.contacts]. */
object ContactsQuery {
    data class Match(val contactId: Long, val displayName: String)

    private val PROJECTION = arrayOf(
        Phone.CONTACT_ID,
        Phone.DISPLAY_NAME_PRIMARY,
        Phone.NUMBER,
        Phone.TYPE,
        Phone.LABEL,
        Phone.IS_SUPER_PRIMARY,
        Phone.STARRED,
        Phone.PHOTO_THUMBNAIL_URI,
        Phone.LOOKUP_KEY,
    )

    fun contactsJson(context: Context): String = pull(context, starredOnly = false)

    fun favoritesJson(context: Context): String = pull(context, starredOnly = true)

    private fun pull(context: Context, starredOnly: Boolean): String = try {
        ProviderJson.contacts(rows(context, starredOnly))
    } catch (_: SecurityException) {
        ProviderJson.error("permission")
    } catch (_: Exception) {
        ProviderJson.error("unknown")
    }

    private fun rows(context: Context, starredOnly: Boolean): List<ProviderJson.PhoneRow> {
        val selection = buildString {
            append("${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1")
            if (starredOnly) append(" AND ${Phone.STARRED} = 1")
        }
        val out = ArrayList<ProviderJson.PhoneRow>()
        val resources = context.resources
        context.contentResolver.query(
            Phone.CONTENT_URI, PROJECTION, selection, null,
            "${Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC, ${Phone.CONTACT_ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(2) ?: continue
                val label = Phone.getTypeLabel(resources, c.getInt(3), c.getString(4))?.toString()
                    ?.takeIf { it.isNotEmpty() }
                out.add(
                    ProviderJson.PhoneRow(
                        contactId = c.getLong(0),
                        displayName = c.getString(1) ?: "",
                        numberRaw = number,
                        label = label,
                        superPrimary = c.getInt(5) != 0,
                        starred = c.getInt(6) != 0,
                        photoUri = c.getString(7),
                    )
                )
            }
        }
        return out
    }

    /**
     * Story 8.5: writes `STARRED` on the contact (the only contacts write). False when Android
     * refused (no `WRITE_CONTACTS`) or the contact is gone.
     */
    fun setStarred(context: Context, contactId: Long, starred: Boolean): Boolean = try {
        val values = ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0) }
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        context.contentResolver.update(uri, values, null, null) > 0
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }

    /** Resolves a dialled/incoming number to a contact; null when unknown or not permitted. */
    fun lookup(context: Context, number: String): Match? {
        if (number.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) Match(c.getLong(0), c.getString(1) ?: "") else null
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
