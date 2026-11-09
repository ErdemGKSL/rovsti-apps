package dev.rovsti.platform.contacts

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.model.search.WireNumber
import dev.rovsti.platform.wire.ProviderJson

/**
 * Contacts pulls and number lookup (AD-9): one provider row per number, grouped into [WireContact]s. The queries,
 * projections and orders are the ones of the GPUI bridge, so the lists come out the same.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/ContactsQuery.kt
 */
object ContactsQuery {
    data class Match(
        val contactId: Long,
        val displayName: String,
        val displayNameAlt: String? = null,
    )

    /** One row of a pull; [number] is "" for a contact with no number. */
    internal data class PhoneRow(
        val contactId: Long,
        val displayName: String,
        val number: String,
        val label: String? = null,
        val superPrimary: Boolean = false,
        val starred: Boolean = false,
        val photoUri: String? = null,
        val lookupKey: String? = null,
        val displayNameAlt: String? = null,
        val sortKey: String? = null,
        val sortKeyAlt: String? = null,
        val email: String? = null,
        val updatedMs: Long = 0,
    )

    private val PROJECTION =
        arrayOf(
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

    private val CONTACT_PROJECTION =
        arrayOf(
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

    /** Contacts with a number. */
    fun withNumbers(context: Context): List<WireContact> = group(phoneRows(context, starredOnly = false))

    /** Starred contacts with a number. */
    fun favorites(context: Context): List<WireContact> = group(phoneRows(context, starredOnly = true))

    /** Every visible contact, with or without a number: the [Phone] rows plus the first email. */
    fun all(context: Context): List<WireContact> = group(allRows(context))

    private fun allRows(context: Context): List<PhoneRow> {
        val phones = HashMap<Long, MutableList<PhoneRow>>()
        for (row in phoneRows(context, starredOnly = false)) phones.getOrPut(row.contactId) { ArrayList() }.add(row)
        val emails = firstEmails(context)
        val out = ArrayList<PhoneRow>()
        context.contentResolver
            .query(
                Contacts.CONTENT_URI,
                CONTACT_PROJECTION,
                "${Contacts.IN_VISIBLE_GROUP} = 1",
                null,
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
                            PhoneRow(
                                contactId = id,
                                displayName = c.getString(1).orEmpty(),
                                number = "",
                                starred = c.getInt(2) != 0,
                                photoUri = c.getString(3),
                                lookupKey = c.getString(4),
                                displayNameAlt = c.getString(5),
                                sortKey = c.getString(6),
                                sortKeyAlt = c.getString(7),
                                email = email,
                                updatedMs = c.getLong(8),
                            ),
                        )
                    }
                }
            }
        return out
    }

    /** The first email per contact: the primary one when marked, else the oldest row. */
    private fun firstEmails(context: Context): Map<Long, String> {
        val out = HashMap<Long, String>()
        context.contentResolver
            .query(
                Email.CONTENT_URI,
                arrayOf(Email.CONTACT_ID, Email.ADDRESS),
                null,
                null,
                "${Email.IS_SUPER_PRIMARY} DESC, ${Email._ID} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val address = c.getString(1)?.trim().orEmpty()
                    if (address.isNotEmpty()) out.putIfAbsent(c.getLong(0), address)
                }
            }
        return out
    }

    private fun phoneRows(
        context: Context,
        starredOnly: Boolean,
    ): List<PhoneRow> {
        val selection =
            buildString {
                append("${Contacts.HAS_PHONE_NUMBER} = 1")
                if (starredOnly) append(" AND ${Phone.STARRED} = 1")
            }
        val out = ArrayList<PhoneRow>()
        val resources = context.resources
        context.contentResolver
            .query(
                Phone.CONTENT_URI,
                PROJECTION,
                selection,
                null,
                "${Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC, ${Phone.CONTACT_ID} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(2) ?: continue
                    val label =
                        Phone
                            .getTypeLabel(resources, c.getInt(3), c.getString(4))
                            ?.toString()
                            ?.takeIf { it.isNotEmpty() }
                    out.add(
                        PhoneRow(
                            contactId = c.getLong(0),
                            displayName = c.getString(1).orEmpty(),
                            number = number,
                            label = label,
                            superPrimary = c.getInt(5) != 0,
                            starred = c.getInt(6) != 0,
                            photoUri = c.getString(7),
                            lookupKey = c.getString(8),
                            displayNameAlt = c.getString(9),
                            sortKey = c.getString(10),
                            sortKeyAlt = c.getString(11),
                        ),
                    )
                }
            }
        return out
    }

    /** Groups phone rows by contact (first appearance order); super-primary numbers come first, repeats are dropped. */
    internal fun group(rows: List<PhoneRow>): List<WireContact> {
        val byContact = LinkedHashMap<Long, MutableList<PhoneRow>>()
        for (r in rows) byContact.getOrPut(r.contactId) { ArrayList() }.add(r)
        return byContact.map { (id, rowsOf) ->
            val seen = HashSet<String>()
            val numbers =
                rowsOf
                    .sortedByDescending { it.superPrimary }
                    .filter {
                        it.number.isNotEmpty() &&
                            seen.add(ProviderJson.normalize(it.number).ifEmpty { it.number })
                    }.map { WireNumber(raw = it.number, label = it.label, default = it.superPrimary) }
            val first = rowsOf[0]
            WireContact(
                id = id,
                displayName = first.displayName,
                numbers = numbers,
                starred = rowsOf.any { it.starred },
                photoUri = rowsOf.firstNotNullOfOrNull { it.photoUri },
                lookupKey = first.lookupKey,
                displayNameAlt = first.displayNameAlt?.takeIf { it.isNotEmpty() },
                sortKey = first.sortKey?.takeIf { it.isNotEmpty() },
                sortKeyAlt = first.sortKeyAlt?.takeIf { it.isNotEmpty() },
                email = rowsOf.firstNotNullOfOrNull { it.email?.takeIf { e -> e.isNotEmpty() } },
                updatedMs = rowsOf.maxOf { it.updatedMs }.takeIf { it > 0 },
            )
        }
    }

    /** Writes `STARRED` on the contact. False when Android refused (no `WRITE_CONTACTS`) or the contact is gone. */
    fun setStarred(
        context: Context,
        contactId: Long,
        starred: Boolean,
    ): Boolean =
        try {
            val values = ContentValues().apply { put(Contacts.STARRED, if (starred) 1 else 0) }
            val uri = android.content.ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId)
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    /** Resolves a dialled or incoming number to a contact; null when unknown or not permitted. */
    fun lookup(
        context: Context,
        number: String,
    ): Match? {
        if (number.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver
                .query(
                    uri,
                    arrayOf(
                        ContactsContract.PhoneLookup._ID,
                        ContactsContract.PhoneLookup.DISPLAY_NAME,
                        ContactsContract.PhoneLookup.DISPLAY_NAME_ALTERNATIVE,
                    ),
                    null,
                    null,
                    null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        Match(c.getLong(0), c.getString(1).orEmpty(), c.getString(2)?.takeIf { it.isNotEmpty() })
                    } else {
                        null
                    }
                }
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}
