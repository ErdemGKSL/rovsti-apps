package dev.rovsti.bridge

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts

/** Contacts pulls and number lookup (AD-9): one row per number, grouped in [ProviderJson.contacts]. */
object ContactsQuery {
    data class Match(val contactId: Long, val displayName: String, val displayNameAlt: String? = null)

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
        Phone.DISPLAY_NAME_ALTERNATIVE,
        Phone.SORT_KEY_PRIMARY,
        Phone.SORT_KEY_ALTERNATIVE,
    )

    fun contactsJson(context: Context): String = pull(context, starredOnly = false)

    /**
     * Every visible contact, with or without a number: the [Phone] rows plus the first email,
     * in the same envelope as [contactsJson] (`numbers` may be empty, `email` set).
     */
    fun allContactsJson(context: Context): String = try {
        ProviderJson.contacts(allRows(context))
    } catch (_: SecurityException) {
        ProviderJson.error("permission")
    } catch (_: Exception) {
        ProviderJson.error("unknown")
    }

    private val CONTACT_PROJECTION = arrayOf(
        Contacts._ID,
        Contacts.DISPLAY_NAME_PRIMARY,
        Contacts.STARRED,
        Contacts.PHOTO_THUMBNAIL_URI,
        Contacts.LOOKUP_KEY,
        Contacts.DISPLAY_NAME_ALTERNATIVE,
        Contacts.SORT_KEY_PRIMARY,
        Contacts.SORT_KEY_ALTERNATIVE,
        Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
    )

    private fun allRows(context: Context): List<ProviderJson.PhoneRow> {
        val phones = HashMap<Long, MutableList<ProviderJson.PhoneRow>>()
        for (row in rows(context, starredOnly = false)) phones.getOrPut(row.contactId) { ArrayList() }.add(row)
        val emails = firstEmails(context)
        val out = ArrayList<ProviderJson.PhoneRow>()
        context.contentResolver.query(
            Contacts.CONTENT_URI, CONTACT_PROJECTION, "${Contacts.IN_VISIBLE_GROUP} = 1", null,
            "${Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC, ${Contacts._ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val email = emails[id]
                val own = phones[id]
                if (own != null) {
                    val updated = c.getLong(8)
                    own.mapTo(out) { it.copy(email = email, updatedMs = updated) }
                } else {
                    out.add(
                        ProviderJson.PhoneRow(
                            contactId = id,
                            displayName = c.getString(1) ?: "",
                            numberRaw = "",
                            starred = c.getInt(2) != 0,
                            photoUri = c.getString(3),
                            lookupKey = c.getString(4),
                            displayNameAlt = c.getString(5),
                            sortKey = c.getString(6),
                            sortKeyAlt = c.getString(7),
                            email = email,
                            updatedMs = c.getLong(8),
                        )
                    )
                }
            }
        }
        return out
    }

    /** The first email per contact: the primary one when marked, else the oldest row. */
    private fun firstEmails(context: Context): Map<Long, String> {
        val out = HashMap<Long, String>()
        context.contentResolver.query(
            Email.CONTENT_URI, arrayOf(Email.CONTACT_ID, Email.ADDRESS), null, null,
            "${Email.IS_SUPER_PRIMARY} DESC, ${Email._ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(1)?.trim().orEmpty()
                if (address.isNotEmpty()) out.putIfAbsent(c.getLong(0), address)
            }
        }
        return out
    }

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
                        lookupKey = c.getString(8),
                        displayNameAlt = c.getString(9),
                        sortKey = c.getString(10),
                        sortKeyAlt = c.getString(11),
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
                arrayOf(
                    ContactsContract.PhoneLookup._ID,
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.DISPLAY_NAME_ALTERNATIVE,
                ),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) Match(c.getLong(0), c.getString(1) ?: "", c.getString(2)?.takeIf { it.isNotEmpty() }) else null
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
