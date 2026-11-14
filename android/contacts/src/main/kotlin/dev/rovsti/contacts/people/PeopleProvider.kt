package dev.rovsti.contacts.people

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import dev.rovsti.contacts.ContactsContainer
import dev.rovsti.people.PeopleContract
import dev.rovsti.people.PeopleContract.addPerson
import dev.rovsti.shell.HasContainer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileNotFoundException

/**
 * The people cache for the sibling apps (X.1b): `content://dev.rovsti.contacts.people`. Exported, but guarded by the
 * signature permission [PeopleContract.PERMISSION] in the manifest (`readPermission`), so only apps signed with the same
 * key can read it; nothing here writes.
 *
 * - `query(/people)`: one row per person ([PeopleContract.Columns]); the cursor is registered for the change
 *   notification, which [PeopleStore.notifyChanged] sends after a sync.
 * - `openFile(/picture/<jid>/<pictureId>, "r")`: the stored picture, read-only.
 *
 * [onCreate] does no work (a provider is created before `Application.onCreate` returns, rule b): the first query
 * builds the container's Rust core and waits for `Rust.init` for at most [READY_WAIT_MS]; when it is not ready by then
 * the answer is an empty cursor, and the reader asks again after the next notification.
 */
class PeopleProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private val matcher =
        UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(PeopleContract.AUTHORITY, PeopleContract.PEOPLE_PATH, PEOPLE)
            addURI(PeopleContract.AUTHORITY, "${PeopleContract.PICTURE_PATH}/*/*", PICTURE)
        }

    private fun container(): ContactsContainer? =
        (context?.applicationContext as? HasContainer<*>)?.container as? ContactsContainer

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (matcher.match(uri) != PEOPLE) throw IllegalArgumentException("Unknown URI $uri")
        val cursor = PeopleContract.newCursor()
        cursor.setNotificationUri(context?.contentResolver, PeopleContract.peopleUri)
        val container = container() ?: return cursor
        runBlocking {
            if (withTimeoutOrNull(READY_WAIT_MS) { container.rustReady() } != true) return@runBlocking
            withTimeoutOrNull(QUERY_WAIT_MS) { container.peopleStore.snapshot() }?.forEach { cursor.addPerson(it) }
        }
        return cursor
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
        cancellationSignal: CancellationSignal?,
    ): Cursor = query(uri, projection, selection, selectionArgs, sortOrder)

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (matcher.match(uri) != PICTURE || mode != "r") throw FileNotFoundException("Not readable: $uri")
        val (jid, pictureId) = PeopleContract.parsePictureUri(uri) ?: throw FileNotFoundException("Bad URI $uri")
        val container = container() ?: throw FileNotFoundException("No container")
        val file =
            runBlocking {
                if (withTimeoutOrNull(READY_WAIT_MS) { container.rustReady() } != true) return@runBlocking null
                withTimeoutOrNull(QUERY_WAIT_MS) { container.peopleStore.pictureFile(jid, pictureId) }
            } ?: throw FileNotFoundException("No picture for $uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? =
        when (matcher.match(uri)) {
            PEOPLE -> "vnd.android.cursor.dir/vnd.dev.rovsti.person"
            PICTURE -> "image/jpeg"
            else -> null
        }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = throw UnsupportedOperationException("Read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Read-only")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Read-only")

    private companion object {
        const val PEOPLE = 1
        const val PICTURE = 2

        /** How long a query waits for `Rust.init` (cold start of the process by the reader's query). */
        const val READY_WAIT_MS = 3_000L
        const val QUERY_WAIT_MS = 3_000L
    }
}
