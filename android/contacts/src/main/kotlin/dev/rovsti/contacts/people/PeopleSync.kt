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
import dev.rovsti.wa.avatars.isRateLimited
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
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
import kotlin.math.ceil

/**
 * How the last sync ended, as the settings row words it. [Partial]: what was asked is stored, the gateway's allowance
 * ran out before the rest; the next run continues.
 */
enum class SyncOutcome { Never, Ok, Partial, Failed }

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

    /**
     * The gateway could not be reached or answered with an error, or it kept answering "rate limited" before anything
     * was stored; worth trying again later.
     */
    data object Failed : SyncResult

    data class Synced(
        /** People on WhatsApp in the cache. */
        val people: Int,
        /** Pictures downloaded (new or replaced ones only). */
        val downloaded: Int,
        /** Picture files dropped (removed or replaced pictures, eviction). */
        val dropped: Int,
    ) : SyncResult

    /**
     * The gateway's rate limit (or this run's waiting budget) stopped the run after some answers were stored: the
     * looked-up people, their profiles and the downloaded pictures are kept, and the next run continues where this one
     * stopped (numbers asked about in a partial run go last). Not a failure: the settings row says "partly synced".
     */
    data class Partial(
        val people: Int,
        val downloaded: Int,
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
                    prefs.getBoolean(OK, false) && prefs.getBoolean(PARTIAL, false) -> SyncOutcome.Partial
                    prefs.getBoolean(OK, false) -> SyncOutcome.Ok
                    prefs.contains(LAST) -> SyncOutcome.Failed
                    else -> SyncOutcome.Never
                },
            people = prefs.getInt(PEOPLE, 0),
        )

    fun save(
        at: Long,
        outcome: SyncOutcome,
        people: Int,
    ) {
        prefs
            .edit()
            .putLong(LAST, at)
            .putBoolean(OK, outcome == SyncOutcome.Ok || outcome == SyncOutcome.Partial)
            .putBoolean(PARTIAL, outcome == SyncOutcome.Partial)
            .putInt(PEOPLE, people)
            .apply()
    }

    /** JIDs a partial run already asked about (and that are not on WhatsApp): the next run asks the others first. */
    fun deferred(): Set<String> = prefs.getStringSet(DEFERRED, null)?.toSet().orEmpty()

    fun defer(jids: Set<String>) {
        prefs.edit().putStringSet(DEFERRED, jids).apply()
    }

    private companion object {
        const val LAST = "last_ms"
        const val OK = "ok"
        const val PARTIAL = "partial"
        const val PEOPLE = "people"
        const val DEFERRED = "deferred"
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
 * Pacing: the gateway rate-limits every instance with token buckets (`services/wa_gateway`: lookups 60 at once then
 * 1 per second, profiles and pictures together 120 then 2 per second) and answers 429 `rate_limited` beyond them. The
 * run keeps its own copy of each bucket ([Allowance]) and waits ([pause]) before a call the copy says would be
 * refused, so a first sync of a few hundred numbers is not refused at all; a 429 anyway (another app used the
 * allowance) empties the copy, waits for the refill and asks again, at most [MAX_RETRIES] times per call. The waits
 * of one run are capped ([LOOKUP_WAIT_MS] for lookups, [RUN_WAIT_MS] in all) to stay well inside WorkManager's ten
 * minutes: when a wait would not fit, the run stops that stage cleanly and ends [SyncResult.Partial] (or
 * [SyncResult.Failed] when nothing was answered at all). A rate-limited picture never counts as a failed one.
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
    /** Waits out the gateway's allowance; the tests make it instant. */
    private val pause: suspend (Long) -> Unit = { delay(it) },
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
        var answered = 0
        var short = false

        // 1. who is on WhatsApp; the numbers a partial run already asked about go last
        val known = store.snapshot().mapTo(HashSet(), PersonRow::jid)
        val asked = numbers().mapNotNull { e164 -> jidOf(e164)?.let { it to e164 } }.distinctBy { it.first }
        val deferred = log.deferred()
        val unknown = asked.filter { it.first !in known }.sortedBy { it.first in deferred }
        val numberOf = asked.toMap()
        val lookups = Allowance(LOOKUP_BURST, LOOKUP_PER_SEC, Wait(pause, LOOKUP_WAIT_MS), clock)
        val looked = HashSet<String>()
        for (batch in unknown.map { it.first }.chunked(BATCH)) {
            val users =
                when (val answer = paced(lookups, batch.size) { gateway.lookupUsers(batch) }) {
                    is Paced.Done -> {
                        answer.value
                    }

                    Paced.Error -> {
                        return SyncResult.Failed
                    }

                    Paced.OutOfTime -> {
                        short = true
                        break
                    }
                }
            answered++
            looked += batch
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
        log.defer(if (short) (deferred + looked).intersect(numberOf.keys) else emptySet())

        // 2. names and picture ids of everybody on WhatsApp (profiles and pictures share one allowance)
        val queries = Allowance(PROFILE_BURST, PROFILE_PER_SEC, Wait(pause, RUN_WAIT_MS - lookups.waitedMs), clock)
        val people = store.snapshot().filter { it.onWhatsapp }
        for (batch in people.map { it.jid }.chunked(BATCH)) {
            val profiles =
                when (val answer = paced(queries, batch.size) { gateway.profiles(batch) }) {
                    is Paced.Done -> {
                        answer.value
                    }

                    Paced.Error -> {
                        return SyncResult.Failed
                    }

                    Paced.OutOfTime -> {
                        short = true
                        break
                    }
                }
            answered++
            val answer = store.upsert(profiles.map(::profileUpdate)) ?: return SyncResult.Failed
            changed += answer.changed
            dropped += answer.dropFiles.size
        }

        // 3. the pictures that changed, and what to drop
        val pictures = pictures(gateway, queries) ?: return SyncResult.Failed
        dropped += pictures.dropped
        answered += pictures.answered
        short = short || pictures.short
        if (changed > 0 || pictures.downloaded > 0 || dropped > 0) onChanged()
        val onWhatsapp = store.snapshot().count { it.onWhatsapp }
        return when {
            !short -> SyncResult.Synced(onWhatsapp, pictures.downloaded, dropped)
            answered > 0 -> SyncResult.Partial(onWhatsapp, pictures.downloaded, dropped)
            else -> SyncResult.Failed
        }
    }

    /** What one picture pass did; [short]: the allowance stopped it before the plan was done. */
    private class PictureRun(
        val downloaded: Int,
        val dropped: Int,
        val answered: Int,
        val short: Boolean,
    )

    /** Downloads what `people.plan` asks for and deletes what it lists; null on a core error. */
    private suspend fun pictures(
        gateway: WaGateway,
        allowance: Allowance,
    ): PictureRun? {
        val plan = store.plan() ?: return null
        var downloaded = 0
        var dropped = 0
        var failures = 0
        var answered = 0
        var short = false
        val rows = store.snapshot().associateBy(PersonRow::jid)
        for (refresh in plan.refresh.take(MAX_PICTURES)) {
            if (failures >= MAX_FAILURES) break
            val row = rows[refresh.jid]
            // A stale picture is asked for with no known id, so the bytes come; a held one revalidates for free.
            val known = refresh.pictureId?.takeIf { row?.pictureStale != true && row?.pictureFile != null }
            val answer =
                when (val got = paced(allowance, 1) { gateway.picture(PictureBody(refresh.jid, knownId = known)) }) {
                    is Paced.Done -> {
                        got.value
                    }

                    Paced.Error -> {
                        failures++
                        continue
                    }

                    Paced.OutOfTime -> {
                        short = true
                        break
                    }
                }
            answered++
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
        return PictureRun(downloaded, dropped, answered, short)
    }

    /**
     * Calls [call] once [allowance] holds [cost] tokens. A 429 empties the allowance and asks again after the refill,
     * at most [MAX_RETRIES] times; [Paced.OutOfTime] when the run's waiting budget cannot pay for the wait.
     */
    private suspend fun <T> paced(
        allowance: Allowance,
        cost: Int,
        call: suspend () -> Outcome<T>,
    ): Paced<T> {
        repeat(MAX_RETRIES + 1) {
            if (!allowance.take(cost)) return Paced.OutOfTime
            when (val answer = call()) {
                is Outcome.Ok -> return Paced.Done(answer.value)
                is Outcome.Err -> if (answer.error.isRateLimited()) allowance.empty() else return Paced.Error
            }
        }
        return Paced.OutOfTime
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
            val allowance = Allowance(PROFILE_BURST, PROFILE_PER_SEC, Wait(pause, LIVE_WAIT_MS), clock)
            val run = pictures(gateway, allowance) ?: return
            if (run.downloaded > 0 || run.dropped > 0) onChanged()
        }
    }

    private fun finish(result: SyncResult) {
        val now = clock()
        val (outcome, people) =
            when (result) {
                is SyncResult.Synced -> SyncOutcome.Ok to result.people
                is SyncResult.Partial -> SyncOutcome.Partial to result.people
                SyncResult.Failed, SyncResult.NotLinked -> SyncOutcome.Failed to state.value.people
            }
        log.save(now, outcome, people)
        state.value = state.value.copy(running = false, lastSyncMs = now, outcome = outcome, people = people)
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

        /** The gateway's per-instance buckets (`services/wa_gateway/src/lib.rs`): burst, then tokens per second. */
        const val LOOKUP_BURST = 60
        const val LOOKUP_PER_SEC = 1
        const val PROFILE_BURST = 120
        const val PROFILE_PER_SEC = 2

        /** Asks after a 429 before the run gives the stage up. */
        const val MAX_RETRIES = 3

        /** Waiting budgets: lookups of one run, everything of one run, a live picture pass. */
        const val LOOKUP_WAIT_MS = 4L * 60_000
        const val RUN_WAIT_MS = 6L * 60_000
        const val LIVE_WAIT_MS = 30_000L
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

/** What a paced gateway call came to. */
private sealed interface Paced<out T> {
    data class Done<out T>(
        val value: T,
    ) : Paced<T>

    /** Not a rate limit: a real error. */
    data object Error : Paced<Nothing>

    /** The rate limit outlasted the run's waiting budget or [PeopleSync.MAX_RETRIES]. */
    data object OutOfTime : Paced<Nothing>
}

/** A run's waiting budget: [pay] waits only while the budget lasts. */
internal class Wait(
    private val pause: suspend (Long) -> Unit,
    private var leftMs: Long,
) {
    /** Waits [ms]; false (and no wait) when the budget cannot pay for it. */
    suspend fun pay(ms: Long): Boolean {
        if (ms > leftMs) return false
        leftMs -= ms
        pause(ms)
        return true
    }
}

/**
 * The client's copy of one of the gateway's token buckets: it starts full ([burst]), refills [perSec] tokens per second
 * by [clock], and [take] waits (paid from [wait]) for what is missing. The gateway charges only what it really asks
 * WhatsApp for (its own cache is free), so the copy errs on the slow side; [empty] after a 429 resets it to the truth.
 */
internal class Allowance(
    private val burst: Int,
    private val perSec: Int,
    private val wait: Wait,
    private val clock: () -> Long,
) {
    private var tokens = burst.toDouble()
    private var lastMs = clock()

    /** What this allowance waited in all. */
    var waitedMs = 0L
        private set

    /** Takes [cost] tokens, first waiting for the missing ones; false when the wait does not fit the budget. */
    suspend fun take(cost: Int): Boolean {
        refill()
        val want = cost.coerceAtMost(burst).toDouble()
        val missing = want - tokens
        if (missing > 0) {
            val ms = ceil(missing * MS_PER_S / perSec).toLong()
            if (!wait.pay(ms)) return false
            waitedMs += ms
            tokens = want
            lastMs = clock()
        }
        tokens -= want
        return true
    }

    /** The gateway said 429: nothing is left. */
    fun empty() {
        tokens = 0.0
        lastMs = clock()
    }

    private fun refill() {
        val now = clock()
        tokens = (tokens + (now - lastMs).coerceAtLeast(0) * perSec / MS_PER_S).coerceAtMost(burst.toDouble())
        lastMs = now
    }

    private companion object {
        const val MS_PER_S = 1_000.0
    }
}
