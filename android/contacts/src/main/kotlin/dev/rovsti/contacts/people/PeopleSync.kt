package dev.rovsti.contacts.people

import android.content.SharedPreferences
import android.util.Base64
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.people.PersonRow
import dev.rovsti.core.model.people.PersonUpdate
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.model.wa.PictureAnswer
import dev.rovsti.core.model.wa.PictureBody
import dev.rovsti.core.model.wa.WaProfile
import dev.rovsti.core.ops.WaGateway
import dev.rovsti.wa.WaLinkState
import dev.rovsti.wa.WaRepository
import dev.rovsti.wa.avatars.AvatarCodec
import dev.rovsti.wa.avatars.BitmapAvatarCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/** How the last sync ended, as the settings row words it. */
enum class SyncOutcome { Never, Ok, Failed }

/** What the settings row shows. */
data class PeopleSyncStatus(
    /** WhatsApp is linked: the cache is only fed while it is. */
    val linked: Boolean = false,
    val running: Boolean = false,
    /** When the last sync finished (0: never). */
    val lastSyncMs: Long = 0,
    val outcome: SyncOutcome = SyncOutcome.Never,
    /** People on WhatsApp known after the last sync. */
    val people: Int = 0,
)

/** What one run did, for the worker (and the tests). */
sealed interface SyncResult {
    /** WhatsApp is not linked: nothing was asked, nothing changed. */
    data object NotLinked : SyncResult

    /** The gateway could not be reached or answered with an error; worth trying again later. */
    data object Failed : SyncResult

    data class Synced(
        /** People on WhatsApp in the cache. */
        val people: Int,
        /** Pictures downloaded (new or replaced ones only). */
        val downloaded: Int,
        /** Picture files dropped (removed or replaced pictures, eviction). */
        val dropped: Int,
    ) : SyncResult
}

/** Where the last sync is remembered between runs. */
class PeopleSyncLog(
    private val prefs: SharedPreferences,
) {
    fun load(): PeopleSyncStatus =
        PeopleSyncStatus(
            lastSyncMs = prefs.getLong(LAST, 0),
            outcome =
                when {
                    prefs.getBoolean(OK, false) -> SyncOutcome.Ok
                    prefs.contains(LAST) -> SyncOutcome.Failed
                    else -> SyncOutcome.Never
                },
            people = prefs.getInt(PEOPLE, 0),
        )

    fun save(
        at: Long,
        ok: Boolean,
        people: Int,
    ) {
        prefs
            .edit()
            .putLong(LAST, at)
            .putBoolean(OK, ok)
            .putInt(PEOPLE, people)
            .apply()
    }

    private companion object {
        const val LAST = "last_ms"
        const val OK = "ok"
        const val PEOPLE = "people"
    }
}

/** What the settings row can ask of the sync. */
interface PeopleSyncControl {
    val status: StateFlow<PeopleSyncStatus>

    /** Sync now (the "Sync now" row); returns at once. */
    fun syncNow()
}

/**
 * Fills the [PeopleStore] from the owner's gateway, only while WhatsApp is linked (X.1b).
 *
 * One run ([run]):
 * 1. the numbers of the device contacts and the recent callers ([numbers], E.164) become phone JIDs; the ones the cache
 *    does not know yet go to `wa.lookup_users` in batches of [BATCH];
 * 2. every person on WhatsApp gets its profile (`wa.profiles`, batched): names and the current picture id;
 * 3. `people.plan` says which pictures to fetch (stale, missing, never asked, older than a week) and which files to
 *    drop; a picture whose id the cache already holds is asked for with `known_id`, so an unchanged one costs no bytes;
 * 4. the provider's observers are told ([onChanged]) when anything changed.
 *
 * Live: a gateway `profile` event updates that person at once and fetches the new picture a moment later
 * ([start]). Runs are serialized; a run never overlaps another.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class PeopleSync(
    private val store: PeopleStore,
    private val wa: WaRepository,
    private val numbers: suspend () -> List<String>,
    private val log: PeopleSyncLog,
    private val scope: CoroutineScope,
    private val codec: AvatarCodec = BitmapAvatarCodec,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onChanged: () -> Unit = {},
) : PeopleSyncControl {
    private val lock = Mutex()
    private val state = MutableStateFlow(log.load())
    private var started = false

    override val status: StateFlow<PeopleSyncStatus> = state.asStateFlow()

    /** Follows the link and the gateway's `profile` events. Idempotent. */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            wa.linkState.collect { link -> state.value = state.value.copy(linked = link is WaLinkState.Connected) }
        }
        scope.launch {
            wa.events
                .filterIsInstance<GatewayEvent.Profile>()
                .onEach { onProfile(it.profile()) }
                .debounce(LIVE_DEBOUNCE_MS)
                .collect { fetchPictures() }
        }
    }

    override fun syncNow() {
        scope.launch { run() }
    }

    /** Runs a sync when the last one is older than [maxAgeMs] (the foreground trigger). */
    fun syncIfStale(maxAgeMs: Long = PERIOD_MS) {
        val last = state.value.lastSyncMs
        if (clock() - last >= maxAgeMs) syncNow()
    }

    /** A full sync (the worker, "Sync now"). */
    suspend fun run(): SyncResult =
        lock.withLock {
            if (wa.linkState.value !is WaLinkState.Connected) return@withLock SyncResult.NotLinked
            state.value = state.value.copy(running = true, linked = true)
            val result =
                try {
                    sync()
                } catch (_: IOException) {
                    SyncResult.Failed
                }
            finish(result)
            result
        }

    private suspend fun sync(): SyncResult {
        val gateway = wa.gateway() ?: return SyncResult.Failed
        var changed = 0
        var dropped = 0

        // 1. who is on WhatsApp
        val known = store.snapshot().mapTo(HashSet(), PersonRow::jid)
        val asked = numbers().mapNotNull { e164 -> jidOf(e164)?.let { it to e164 } }.distinctBy { it.first }
        val unknown = asked.filter { it.first !in known }
        val numberOf = asked.toMap()
        for (batch in unknown.map { it.first }.chunked(BATCH)) {
            val users = (gateway.lookupUsers(batch) as? Outcome.Ok)?.value ?: return SyncResult.Failed
            val found = users.filter { it.onWhatsapp }
            val answer =
                store.upsert(
                    found.map {
                        PersonUpdate(
                            jid = it.jid,
                            number = numberOf[it.jid],
                            onWhatsapp = true,
                            verifiedName = it.verifiedName,
                        )
                    },
                ) ?: return SyncResult.Failed
            changed += answer.changed
            dropped += answer.dropFiles.size
        }

        // 2. names and picture ids of everybody on WhatsApp
        val people = store.snapshot().filter { it.onWhatsapp }
        for (batch in people.map { it.jid }.chunked(BATCH)) {
            val profiles = (gateway.profiles(batch) as? Outcome.Ok)?.value ?: return SyncResult.Failed
            val answer = store.upsert(profiles.map(::profileUpdate)) ?: return SyncResult.Failed
            changed += answer.changed
            dropped += answer.dropFiles.size
        }

        // 3. the pictures that changed, and what to drop
        val (downloaded, removed) = pictures(gateway) ?: return SyncResult.Failed
        dropped += removed
        if (changed > 0 || downloaded > 0 || dropped > 0) onChanged()
        return SyncResult.Synced(store.snapshot().count { it.onWhatsapp }, downloaded, dropped)
    }

    /** Downloads what `people.plan` asks for and deletes what it lists; `(downloaded, dropped)`, null on a core error. */
    private suspend fun pictures(gateway: WaGateway): Pair<Int, Int>? {
        val plan = store.plan() ?: return null
        var downloaded = 0
        var dropped = 0
        var failures = 0
        val rows = store.snapshot().associateBy(PersonRow::jid)
        for (refresh in plan.refresh.take(MAX_PICTURES)) {
            if (failures >= MAX_FAILURES) break
            val row = rows[refresh.jid]
            // A stale picture is asked for with no known id, so the bytes come; a held one revalidates for free.
            val known = refresh.pictureId?.takeIf { row?.pictureStale != true && row?.pictureFile != null }
            val answer = (gateway.picture(PictureBody(refresh.jid, knownId = known)) as? Outcome.Ok)?.value
            if (answer == null) {
                failures++
                continue
            }
            when (answer.kind) {
                "image" -> {
                    val id = answer.id
                    val name = id?.let { saveBytes(refresh.jid, it, answer) }
                    if (id == null || name == null) {
                        failures++
                        continue
                    }
                    val done = store.upsert(listOf(PersonUpdate(refresh.jid, pictureId = id, pictureFile = name)))
                    dropped += done?.dropFiles?.size ?: 0
                    downloaded++
                }

                "unchanged" -> {
                    val file = row?.pictureFile
                    val id = row?.pictureId
                    if (file != null && id != null && store.has(file)) {
                        store.upsert(listOf(PersonUpdate(refresh.jid, pictureId = id, pictureFile = file)))
                    } else {
                        failures++
                    }
                }

                "none" -> {
                    val done = store.upsert(listOf(PersonUpdate(refresh.jid, pictureRemoved = true)))
                    dropped += done?.dropFiles?.size ?: 0
                }

                else -> {
                    failures++
                }
            }
        }
        store.delete(plan.remove)
        dropped += plan.remove.size
        return downloaded to dropped
    }

    private suspend fun saveBytes(
        jid: String,
        id: String,
        answer: PictureAnswer,
    ): String? {
        val bytes = readBytes(answer)?.let { codec.downscale(it, PICTURE_PX) } ?: return null
        return store.store(jid, id, bytes)
    }

    private fun readBytes(answer: PictureAnswer): ByteArray? =
        try {
            when {
                answer.dataB64 != null -> {
                    Base64.decode(answer.dataB64, Base64.DEFAULT)
                }

                answer.path != null -> {
                    val file = File(answer.path!!)
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

    // ---- live events

    private suspend fun onProfile(profile: WaProfile) {
        if (wa.linkState.value !is WaLinkState.Connected || profile.jid.endsWith(GROUP_SUFFIX)) return
        val answer = store.upsert(listOf(profileUpdate(profile))) ?: return
        if (answer.changed > 0 || answer.dropFiles.isNotEmpty()) onChanged()
    }

    private suspend fun fetchPictures() {
        lock.withLock {
            if (wa.linkState.value !is WaLinkState.Connected) return
            val gateway = wa.gateway() ?: return
            val (downloaded, dropped) = pictures(gateway) ?: return
            if (downloaded > 0 || dropped > 0) onChanged()
        }
    }

    private fun finish(result: SyncResult) {
        val now = clock()
        val ok = result is SyncResult.Synced
        val people = (result as? SyncResult.Synced)?.people ?: state.value.people
        log.save(now, ok, people)
        state.value =
            state.value.copy(
                running = false,
                lastSyncMs = now,
                outcome = if (ok) SyncOutcome.Ok else SyncOutcome.Failed,
                people = people,
            )
    }

    companion object {
        /** How often the worker runs. */
        const val PERIOD_MS = 6L * 3_600_000

        /** Lookups and profiles per gateway call. */
        const val BATCH = 50

        /** Numbers asked about per run (recent callers first). */
        const val MAX_NUMBERS = 2_000

        private const val MAX_PICTURES = 300
        private const val MAX_FAILURES = 5
        private const val PICTURE_PX = 256
        private const val LIVE_DEBOUNCE_MS = 2_000L
        private const val PHONE_SUFFIX = "@s.whatsapp.net"
        private const val GROUP_SUFFIX = "@g.us"

        /** `+905321234567` -> `905321234567@s.whatsapp.net`; null when it is not a plausible number. */
        fun jidOf(e164: String): String? {
            val digits = e164.removePrefix("+")
            return if (digits.length in 7..15 && digits.all { it in '0'..'9' }) digits + PHONE_SUFFIX else null
        }

        private fun profileUpdate(profile: WaProfile): PersonUpdate {
            val hasAnswer = profile.pictureId != null || profile.fetchedMs > 0
            return PersonUpdate(
                jid = profile.jid,
                pushName = profile.pushName,
                verifiedName = profile.verifiedName,
                pictureId = profile.pictureId,
                // A profile the gateway never asked WhatsApp about knows no picture id: only a real answer says none.
                pictureRemoved = if (profile.pictureId == null && hasAnswer) true else null,
            )
        }
    }
}
