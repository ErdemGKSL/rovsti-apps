package dev.rovsti.wa.avatars

import android.util.Base64
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.model.wa.AvatarsPlanRequest
import dev.rovsti.core.model.wa.DiskFile
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.model.wa.PictureAnswer
import dev.rovsti.core.model.wa.PictureBody
import dev.rovsti.core.model.wa.WaNamesRequest
import dev.rovsti.core.model.wa.WaProfile
import dev.rovsti.core.ops.WaOps
import dev.rovsti.wa.WaLinkState
import dev.rovsti.wa.WaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException

/**
 * WhatsApp profile pictures and profiles for the whole app: the Kotlin half of `rovsti_wa_ui::avatars`.
 *
 * Rows call [want] (or [wantAll]) for each avatar they are about to draw and read it with `WaAvatar(jid)` as the
 * image model (see [WaAvatarFetcher]). What happens then:
 * - [AvatarBook] decides whether a fetch is due: one per jid at a time, [AvatarBook.MAX_PARALLEL] at once, none while
 *   a 429 back-off holds (it doubles from 5 s to 5 min and resets on the first good answer);
 * - the job runs on [io]: the stored file first, then [dev.rovsti.core.ops.WaGateway.picture], decoded down to
 *   [slotPx] with `inSampleSize` ([BitmapAvatarCodec]) before it is stored;
 * - files live in [dir] (`wa-avatars/`), named and pruned by the `wa.avatars_plan` op (oldest first, over [DISK_BYTES]);
 * - [revision] changes whenever a picture or profile arrived or a back-off ended; rows ask again then.
 *
 * Thread safety: every method may be called from any thread.
 */
class WaAvatars(
    private val repo: WaRepository,
    private val ops: WaOps,
    private val dir: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val codec: AvatarCodec = BitmapAvatarCodec,
    private val slotPx: Int = SLOT_PX,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Called with the jid when its stored picture changed, so Coil can drop its memory-cache entry. */
    private val invalidate: (String) -> Unit = {},
) {
    private val lock = Any()
    private val book = AvatarBook()
    private val profiles = HashMap<String, WaProfile>()
    private val profileQueue = LinkedHashSet<String>()
    private val profileAsked = HashSet<String>()
    private var profileJob: kotlinx.coroutines.Job? = null
    private var wakeAt = -1L
    private var started = false

    private val changes = MutableStateFlow(0)

    /** Counts changes: a picture or profile arrived, or a back-off ended. Rows re-run [want] when it moves. */
    val revision: StateFlow<Int> = changes.asStateFlow()

    /** Follows the link and the gateway events. Idempotent. */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        scope.launch(io) { prune() }
        scope.launch {
            repo.linkState
                .map { (it as? WaLinkState.Connected)?.status?.instanceId }
                .distinctUntilChanged()
                .collect(::followAccount)
        }
        scope.launch { repo.events.collect(::onEvent) }
    }

    /** Call for each avatar about to be drawn; [pictureId] is the id the caller knows (null: none). Cheap when idle. */
    fun want(
        jid: String,
        pictureId: String? = null,
    ) {
        if (jid.isEmpty()) return
        val now = clock()
        val job = synchronized(lock) { book.request(jid, pictureId, now) }
        if (job == null) {
            wakeAfterBackoff(now)
            return
        }
        scope.launch(io) {
            val (found, wrote) = fetch(job)
            val now2 = clock()
            val before = synchronized(lock) { book.heldId(jid) }
            synchronized(lock) { book.arrived(job, found, now2) }
            val after = synchronized(lock) { book.heldId(jid) }
            if (found is AvatarBook.Found.Image && (before != after || wrote)) invalidate(jid)
            if (wrote) prune()
            bump()
        }
    }

    /** Several avatars at once (the rows on screen): `(jid, pictureId)` pairs. */
    fun wantAll(wanted: Iterable<Pair<String, String?>>) {
        for ((jid, id) in wanted) want(jid, id)
    }

    /** The stored picture of [jid] (the newest file of that jid), or null when none is stored. */
    suspend fun file(jid: String): File? =
        withContext(io) {
            val files = listFiles()
            val prefix = plan(files, jid, "x")?.fileName?.substringBefore(NAME_SEP, "") ?: return@withContext null
            files
                .filter { it.name.startsWith(prefix + NAME_SEP) && it.name.endsWith(EXT) }
                .maxByOrNull { it.lastModified() }
        }

    /** What WhatsApp told about [jid], once known (see [wantProfile] and the `profile` events). */
    fun profile(jid: String): WaProfile? = synchronized(lock) { profiles[jid] }

    /**
     * The name to show for [jid]: the saved contact name, else WhatsApp's name, else the number (`wa.names`).
     * [contact] is the name from the phone's contacts when the caller has one.
     */
    suspend fun name(
        jid: String,
        contact: String? = null,
    ): String {
        val known = profile(jid)
        val answer =
            ops.names(WaNamesRequest(contact = contact ?: known?.contactName, waName = known?.displayName(), jid = jid))
        return (answer as? Outcome.Ok)?.value?.name ?: jid
    }

    /** Asks for the profile of [jid]: batched with the other asks of the next 40 ms, once per link. */
    fun wantProfile(jid: String) {
        synchronized(lock) {
            if (jid.isEmpty() || jid in profiles || !profileAsked.add(jid)) return
            profileQueue += jid
            if (profileJob != null) return
            profileJob = scope.launch { flushProfiles() }
        }
    }

    private suspend fun flushProfiles() {
        while (true) {
            val wait =
                synchronized(lock) {
                    val now = clock()
                    if (book.backoffActive(now)) book.backoffUntilMs - now else PROFILE_BATCH_MS
                }
            delay(wait)
            val batch =
                synchronized(lock) {
                    val take = profileQueue.take(MAX_PROFILES)
                    profileQueue.removeAll(take.toSet())
                    take
                }
            if (batch.isEmpty()) {
                synchronized(lock) {
                    if (profileQueue.isEmpty()) {
                        profileJob = null
                        return
                    }
                }
                continue
            }
            val gateway = repo.gateway()
            val result = withContext(io) { gateway?.profiles(batch) }
            synchronized(lock) {
                when (result) {
                    is Outcome.Ok -> {
                        result.value.forEach(::learn)
                    }

                    is Outcome.Err -> {
                        if (result.error.isRateLimited()) book.rateLimited(clock())
                        profileAsked.removeAll(batch.toSet())
                    }

                    null -> {
                        profileAsked.removeAll(batch.toSet())
                    }
                }
            }
            bump()
        }
    }

    private fun learn(profile: WaProfile) {
        // A profile the gateway never asked WhatsApp about knows no picture id: only a real answer says what is current.
        if (profile.pictureId != null || profile.fetchedMs > 0) book.onPictureId(profile.jid, profile.pictureId)
        profiles[profile.jid] = profile
    }

    private fun onEvent(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.Profile -> {
                synchronized(lock) { learn(event.profile()) }
                bump()
            }

            is GatewayEvent.ChatMetaChanged -> {
                synchronized(lock) { book.onPictureId(event.jid, event.meta.pictureId) }
                bump()
            }

            GatewayEvent.Resync, is GatewayEvent.ChatMerged -> {
                synchronized(lock) { book.forgetMisses() }
                bump()
            }

            else -> {
                Unit
            }
        }
    }

    private fun followAccount(instance: String?) {
        synchronized(lock) {
            if (instance == null) {
                book.clear()
                profiles.clear()
                profileAsked.clear()
                profileQueue.clear()
            } else {
                // Misses recorded while offline are not real answers.
                book.forgetMisses()
            }
        }
        bump()
    }

    private fun wakeAfterBackoff(now: Long) {
        val until =
            synchronized(lock) {
                if (!book.backoffActive(now) || wakeAt == book.backoffUntilMs) return
                book.backoffUntilMs.also { wakeAt = it }
            }
        scope.launch {
            delay(until - now + WAKE_SLACK_MS)
            synchronized(lock) { wakeAt = -1L }
            bump()
        }
    }

    private fun bump() {
        changes.value += 1
    }

    // ---- the job: disk first, then the gateway

    private class Held(
        val id: String,
        val file: File,
    )

    /** Returns what was found and whether a file was written. Runs on [io]. */
    private suspend fun fetch(job: AvatarBook.Job): Pair<AvatarBook.Found, Boolean> {
        val files = listFiles()
        var fromDisk: Held? = null
        if (job.held == null) {
            fromDisk = findOnDisk(files, job.jid, job.wanted)
            if (fromDisk != null && job.wanted == fromDisk.id) {
                // The caller named this very picture: no need to ask.
                return AvatarBook.Found.Image(fromDisk.id, verified = true) to false
            }
        }
        val known = job.held ?: fromDisk?.id

        fun unverified(fallback: AvatarBook.Found): AvatarBook.Found =
            fromDisk?.let { AvatarBook.Found.Image(it.id, verified = false) } ?: fallback

        val gateway = repo.gateway() ?: return unverified(AvatarBook.Found.Failed) to false
        return when (val answer = gateway.picture(PictureBody(job.jid, knownId = known))) {
            is Outcome.Err -> {
                val found = if (answer.error.isRateLimited()) AvatarBook.Found.RateLimited else AvatarBook.Found.Failed
                unverified(found) to false
            }

            is Outcome.Ok -> {
                fromGateway(job, answer.value, fromDisk)
            }
        }
    }

    private suspend fun fromGateway(
        job: AvatarBook.Job,
        picture: PictureAnswer,
        fromDisk: Held?,
    ): Pair<AvatarBook.Found, Boolean> =
        when (picture.kind) {
            "image" -> {
                val id = picture.id
                val bytes = id?.let { readBytes(picture) }?.let { codec.downscale(it, slotPx) }
                if (id == null || bytes == null) {
                    AvatarBook.Found.Failed to false
                } else {
                    val stored = store(job.jid, id, bytes)
                    (if (stored) AvatarBook.Found.Image(id, verified = true) else AvatarBook.Found.Failed) to stored
                }
            }

            "unchanged" -> {
                (fromDisk?.let { AvatarBook.Found.Image(it.id, verified = true) } ?: AvatarBook.Found.Unchanged) to
                    false
            }

            "none" -> {
                removeAllOf(job.jid)
                AvatarBook.Found.None to false
            }

            else -> {
                AvatarBook.Found.Failed to false
            }
        }

    private fun readBytes(picture: PictureAnswer): ByteArray? =
        try {
            when {
                picture.dataB64 != null -> {
                    Base64.decode(picture.dataB64, Base64.DEFAULT)
                }

                picture.path != null -> {
                    val file = File(picture.path!!)
                    try {
                        file.readBytes()
                    } finally {
                        file.delete()
                    }
                }

                else -> {
                    null
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    // ---- disk, named and pruned by the wa.avatars_plan op

    private fun listFiles(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) }?.toList().orEmpty()

    private fun diskFiles(files: List<File>) = files.map { DiskFile(it.path, it.length(), it.lastModified()) }

    private suspend fun plan(
        files: List<File>,
        jid: String?,
        pictureId: String?,
        maxBytes: Long = DISK_BYTES,
    ) = (ops.avatarsPlan(AvatarsPlanRequest(diskFiles(files), maxBytes, jid, pictureId)) as? Outcome.Ok)?.value

    private suspend fun findOnDisk(
        files: List<File>,
        jid: String,
        wanted: String?,
    ): Held? {
        val id = wanted ?: plan(files, jid, null)?.heldId ?: return null
        val name = plan(emptyList(), jid, id)?.fileName ?: return null
        val file = File(dir, name)
        return if (file.isFile && file.length() > 0) Held(id, file) else null
    }

    private suspend fun store(
        jid: String,
        id: String,
        bytes: ByteArray,
    ): Boolean {
        val name = plan(emptyList(), jid, id)?.fileName ?: return false
        return try {
            dir.mkdirs()
            val target = File(dir, name)
            val part = File(dir, "$name.part")
            part.writeBytes(bytes)
            if (!part.renameTo(target)) return false
            // Older pictures of the same jid go.
            val prefix = name.substringBefore(NAME_SEP) + NAME_SEP
            listFiles().filter { it.name.startsWith(prefix) && it.name != name }.forEach { it.delete() }
            true
        } catch (_: IOException) {
            false
        }
    }

    private suspend fun removeAllOf(jid: String) {
        val prefix = plan(emptyList(), jid, "x")?.fileName?.substringBefore(NAME_SEP) ?: return
        listFiles().filter { it.name.startsWith(prefix + NAME_SEP) }.forEach { it.delete() }
    }

    /** Deletes the oldest files over [DISK_BYTES], as the plan lists them. */
    suspend fun prune() {
        withContext(io) {
            val remove = plan(listFiles(), null, null)?.remove.orEmpty()
            remove.forEach { File(it).delete() }
        }
    }

    companion object {
        /** The cache folder inside the app's files dir. */
        const val DIR_NAME = "wa-avatars"

        /** The decode size of a stored picture, in pixels on the short side (the largest avatar slot). */
        const val SLOT_PX = 128

        /** Disk budget of the picture cache. */
        const val DISK_BYTES = 8L * 1024 * 1024

        /** A fade-in takes this long (E1.20). */
        const val FADE_MS = 220

        private const val PROFILE_BATCH_MS = 40L
        private const val MAX_PROFILES = 50
        private const val WAKE_SLACK_MS = 50L
        private const val NAME_SEP = '~'
        private const val EXT = ".jpg"
    }
}

/** A 429 from the gateway: an `invalid` error whose detail says `rate_limited`. */
fun RustError.isRateLimited(): Boolean =
    this is RustError.Invalid &&
        ((detail as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull == "rate_limited")
