package dev.rovsti.people

import android.content.ContentResolver
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import dev.rovsti.core.model.people.PersonRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.io.File

/** Where an app gets its people from: Contacts' provider, or, without Contacts, the gateway directly. */
interface PeopleSource {
    /** Which of the two this is (the settings row says it). */
    val kind: Kind

    /** Emits when the data may have changed; collecting it never loads anything. */
    val changes: Flow<Unit>

    /** Every person now; blocking work runs off the main thread inside. A failing source answers an empty list. */
    suspend fun load(): List<PersonRow>

    /** The picture to draw for [row], or null. */
    fun pictureUri(row: PersonRow): String?

    enum class Kind { Provider, Gateway }
}

/** Reads Contacts' provider (`content://dev.rovsti.contacts.people`). Needs the signature permission. */
class ProviderSource(
    private val resolver: ContentResolver,
) : PeopleSource {
    override val kind = PeopleSource.Kind.Provider

    override val changes: Flow<Unit> =
        callbackFlow {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(
                        selfChange: Boolean,
                        uri: Uri?,
                    ) {
                        trySend(Unit)
                    }
                }
            resolver.registerContentObserver(PeopleContract.peopleUri, true, observer)
            awaitClose { resolver.unregisterContentObserver(observer) }
        }.conflate()

    override suspend fun load(): List<PersonRow> =
        withContext(Dispatchers.IO) {
            try {
                val rows = mutableListOf<PersonRow>()
                resolver.query(PeopleContract.peopleUri, PeopleContract.Columns.all, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) PeopleContract.rowOf(cursor)?.let(rows::add)
                }
                rows
            } catch (_: SecurityException) {
                // Not the same signing key, or the permission is not granted: the app shows initials.
                emptyList()
            } catch (_: IllegalArgumentException) {
                // The provider went away between the check and the query (Contacts was uninstalled).
                emptyList()
            }
        }

    override fun pictureUri(row: PersonRow): String? =
        row.pictureId?.let { PeopleContract.pictureUri(row.jid, it).toString() }
}

/**
 * Without Contacts: the app holds the people itself, in memory, fed by [fetch] (the gateway's chats and pictures, wired
 * where the app builds its WhatsApp client). Nothing is written to disk here; the pictures are the files the app's
 * own avatar cache ([pictureFile]) already keeps.
 */
class GatewaySource(
    private val fetch: suspend () -> List<PersonRow>,
    private val pictureFile: (PersonRow) -> File?,
) : PeopleSource {
    override val kind = PeopleSource.Kind.Gateway

    private val refreshes = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

    override val changes: Flow<Unit> get() = refreshes

    override suspend fun load(): List<PersonRow> =
        try {
            fetch()
        } catch (_: java.io.IOException) {
            emptyList()
        }

    override fun pictureUri(row: PersonRow): String? = pictureFile(row)?.let { Uri.fromFile(it).toString() }

    /** Tells readers that the gateway has news (a `profile` event, a finished sync). */
    fun changed() {
        refreshes.tryEmit(Unit)
    }
}

/** Chooses the source for this device. */
object PeopleSources {
    /** Contacts is installed with the people provider (and visible to this app, see the library manifest's queries). */
    fun providerInstalled(packages: PackageManager): Boolean =
        try {
            packages.resolveContentProvider(PeopleContract.AUTHORITY, 0) != null
        } catch (_: RuntimeException) {
            false
        }

    /** The provider's source when Contacts offers it, else the gateway's. Both are built lazily. */
    fun choose(
        packages: PackageManager,
        provider: () -> PeopleSource,
        gateway: () -> PeopleSource,
    ): PeopleSource = if (providerInstalled(packages)) provider() else gateway()
}
