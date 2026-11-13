package dev.rovsti.people

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dev.rovsti.core.model.people.PersonRow

/**
 * The wire between the app that owns the people cache (Contacts) and the apps that read it. The provider itself is
 * Contacts'; this object is the part both sides must agree on: the authority, the signature permission that guards it,
 * the columns of the `people` table and the URIs `openFile` serves pictures under.
 *
 * - `content://dev.rovsti.contacts.people/people`: one row per person (see [Columns]); its notification URI changes
 *   whenever the cache does, so a reader re-queries instead of polling.
 * - `content://dev.rovsti.contacts.people/picture/<jid>/<pictureId>`: the picture file, read with `openFile`. The
 *   picture id is part of the URI so an image cache keyed by URI never shows a replaced picture.
 */
object PeopleContract {
    const val AUTHORITY = "dev.rovsti.contacts.people"

    /** Signature permission: only apps signed with the same key as Contacts may read the cache. */
    const val PERMISSION = "dev.rovsti.permission.READ_PEOPLE"

    const val PEOPLE_PATH = "people"
    const val PICTURE_PATH = "picture"

    val peopleUri: Uri get() = Uri.parse("content://$AUTHORITY/$PEOPLE_PATH")

    /** The column names of the `people` table. */
    object Columns {
        const val JID = "jid"

        /** E.164 numbers, one per line. */
        const val NUMBERS = "numbers"
        const val LID = "lid"
        const val NAME = "name"
        const val ON_WHATSAPP = "on_whatsapp"
        const val PICTURE_ID = "picture_id"
        const val PICTURE_STALE = "picture_stale"

        val all: Array<String> = arrayOf(JID, NUMBERS, LID, NAME, ON_WHATSAPP, PICTURE_ID, PICTURE_STALE)
    }

    private const val NUMBER_SEPARATOR = '\n'

    /** The URI of the picture [pictureId] of [jid]. */
    fun pictureUri(
        jid: String,
        pictureId: String,
    ): Uri =
        Uri
            .Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(PICTURE_PATH)
            .appendPath(jid)
            .appendPath(pictureId)
            .build()

    /** `(jid, pictureId)` of a picture URI of this contract, null for any other URI. */
    fun parsePictureUri(uri: Uri): Pair<String, String>? {
        if (uri.authority != AUTHORITY) return null
        val parts = uri.pathSegments
        if (parts.size != 3 || parts[0] != PICTURE_PATH) return null
        return parts[1] to parts[2]
    }

    /** An empty `people` cursor with the contract's columns, for the provider to fill with [add]. */
    fun newCursor(capacity: Int = 16): MatrixCursor = MatrixCursor(Columns.all, capacity)

    fun MatrixCursor.addPerson(row: PersonRow) {
        addRow(
            arrayOf<Any?>(
                row.jid,
                row.numbers.joinToString(NUMBER_SEPARATOR.toString()),
                row.lid,
                row.name,
                if (row.onWhatsapp) 1 else 0,
                row.pictureId,
                if (row.pictureStale) 1 else 0,
            ),
        )
    }

    /** The row at the cursor's position, or null when a column is missing (an older provider). */
    fun rowOf(cursor: Cursor): PersonRow? {
        val jid = cursor.string(Columns.JID) ?: return null
        return PersonRow(
            jid = jid,
            numbers =
                cursor
                    .string(Columns.NUMBERS)
                    ?.split(NUMBER_SEPARATOR)
                    ?.filter { it.isNotBlank() }
                    .orEmpty(),
            lid = cursor.string(Columns.LID),
            name = cursor.string(Columns.NAME),
            onWhatsapp = cursor.int(Columns.ON_WHATSAPP) != 0,
            pictureId = cursor.string(Columns.PICTURE_ID),
            pictureStale = cursor.int(Columns.PICTURE_STALE) != 0,
        )
    }

    private fun Cursor.string(column: String): String? {
        val at = getColumnIndex(column)
        return if (at < 0 || isNull(at)) null else getString(at)
    }

    private fun Cursor.int(column: String): Int {
        val at = getColumnIndex(column)
        return if (at < 0 || isNull(at)) 0 else getInt(at)
    }
}
