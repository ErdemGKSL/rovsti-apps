package dev.rovsti.people.sync

import android.content.ContentResolver
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.people.PeopleLookupRequest
import dev.rovsti.core.model.people.PeoplePlanAnswer
import dev.rovsti.core.model.people.PeoplePlanRequest
import dev.rovsti.core.model.people.PeopleSnapshotRequest
import dev.rovsti.core.model.people.PeopleUpsertAnswer
import dev.rovsti.core.model.people.PeopleUpsertRequest
import dev.rovsti.core.model.people.PersonRow
import dev.rovsti.core.model.people.PersonUpdate
import dev.rovsti.core.model.wa.AvatarsPlanRequest
import dev.rovsti.core.model.wa.DiskFile
import dev.rovsti.core.ops.PeopleOps
import dev.rovsti.core.ops.WaOps
import dev.rovsti.people.PeopleContract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * What an app knows about the people it meets on WhatsApp (X.1b; Contacts, and Phone without Contacts). The records
 * live in `people.json` in the Rust core's data directory (the `people.*` ops; [dataDir] names another one, for tests);
 * the pictures are files in [dir] (`filesDir/people/`), named by the `wa.avatars_plan` rules so the same JID and
 * picture id always give the same name. Moved here from `:contacts` unchanged (people sync v2, S6).
 *
 * Only the sync ([PeopleSyncRunner] and the app's live events) writes. Readers use [snapshot] for the rows and
 * [pictureFile] for a picture. Every method may be called from any thread; file work runs on [io].
 */
class PeopleStore(
    private val ops: PeopleOps,
    private val waOps: WaOps,
    val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The `people.*` data directory; null: the core's own. */
    val dataDir: String? = null,
) {
    /** Every person, by name. Empty when the core cannot answer. */
    suspend fun snapshot(): List<PersonRow> =
        (ops.snapshot(PeopleSnapshotRequest(dir = dataDir)) as? Outcome.Ok)?.value?.people.orEmpty()

    /**
     * The picture file of [jid], when [pictureId] is the id it is stored for and the file exists; null otherwise. A
     * stale picture is still served until the new one arrives (the id in the URI is the old id).
     */
    suspend fun pictureFile(
        jid: String,
        pictureId: String,
    ): File? {
        val person =
            (ops.lookup(PeopleLookupRequest(dir = dataDir, key = jid)) as? Outcome.Ok)?.value?.person ?: return null
        val name = person.pictureFile ?: return null
        if (person.pictureId != pictureId) return null
        return withContext(io) { inside(name)?.takeIf { it.isFile && it.length() > 0 } }
    }

    /** Applies [updates] and deletes the files that belong to nobody any more. Null when the core failed. */
    suspend fun upsert(updates: List<PersonUpdate>): PeopleUpsertAnswer? {
        if (updates.isEmpty()) return PeopleUpsertAnswer(0, emptyList(), emptyList())
        val answer =
            (
                ops.upsert(
                    PeopleUpsertRequest(dir = dataDir, nowMs = clock(), people = updates),
                ) as? Outcome.Ok
            )?.value
        if (answer != null) delete(answer.dropFiles)
        return answer
    }

    /** Which pictures to fetch and which files to delete, given what is on disk now. */
    suspend fun plan(maxBytes: Long = MAX_BYTES): PeoplePlanAnswer? {
        val files = files()
        val request = PeoplePlanRequest(dir = dataDir, nowMs = clock(), files = files, maxBytes = maxBytes)
        return (ops.plan(request) as? Outcome.Ok)?.value
    }

    /** The picture files on disk, with size and age (what `people.plan` and the sync's picture stage read). */
    suspend fun files(): List<DiskFile> =
        withContext(io) { listFiles().map { DiskFile(it.path, it.length(), it.lastModified()) } }

    /** The name a picture is stored under, from the shared avatar rules; null when the core failed. */
    suspend fun fileName(
        jid: String,
        pictureId: String,
    ): String? =
        (waOps.avatarsPlan(AvatarsPlanRequest(emptyList(), MAX_BYTES, jid, pictureId)) as? Outcome.Ok)?.value?.fileName

    /** Stores [bytes] as the picture [pictureId] of [jid]; the file name, or null when it could not be written. */
    suspend fun store(
        jid: String,
        pictureId: String,
        bytes: ByteArray,
    ): String? {
        val name = fileName(jid, pictureId) ?: return null
        return withContext(io) {
            try {
                dir.mkdirs()
                val part = File(dir, "$name.part")
                part.writeBytes(bytes)
                if (part.renameTo(File(dir, name))) name else null
            } catch (_: IOException) {
                null
            }
        }
    }

    /** Whether the picture file [name] is on disk. */
    suspend fun has(name: String): Boolean = withContext(io) { inside(name)?.isFile == true }

    /** Deletes files by name or path; anything that resolves outside [dir] is ignored. */
    suspend fun delete(names: List<String>) {
        if (names.isEmpty()) return
        withContext(io) { names.forEach { inside(it)?.delete() } }
    }

    /** Tells readers of the provider that the cache changed. */
    fun notifyChanged(resolver: ContentResolver) {
        resolver.notifyChange(PeopleContract.peopleUri, null)
    }

    private fun listFiles(): List<File> = dir.listFiles { f -> f.isFile && !f.name.endsWith(PART) }?.toList().orEmpty()

    /** [name] (a bare file name or a path of a file in [dir]) as a file of [dir]; null for anything else. */
    private fun inside(name: String): File? {
        val leaf = File(name).name
        if (leaf.isEmpty() || leaf == "." || leaf == "..") return null
        val parent = File(name).parentFile
        if (parent != null && parent.canonicalPath != dir.canonicalPath) return null
        return File(dir, leaf)
    }

    companion object {
        /** The folder inside the app's files directory. */
        const val DIR_NAME = "people"

        /** The disk budget of the picture files. */
        const val MAX_BYTES = 16L * 1024 * 1024

        private const val PART = ".part"
    }
}
