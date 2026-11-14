package dev.rovsti.phone.people

import dev.rovsti.core.log.RovstiLog
import dev.rovsti.core.model.people.SyncWire
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.ops.PeopleOps
import dev.rovsti.people.GatewaySource
import dev.rovsti.people.sync.PartNotifier
import dev.rovsti.people.sync.PeopleStore
import dev.rovsti.people.sync.PeopleSyncRunner
import dev.rovsti.people.sync.SliceResult
import dev.rovsti.wa.WaLinkState
import dev.rovsti.wa.WaRepository
import dev.rovsti.wa.avatars.AvatarCodec
import dev.rovsti.wa.avatars.BitmapAvatarCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

/**
 * Phone without Contacts (X.1d, people sync v2 S10): Phone keeps its own people cache on disk (`people.json` in its
 * Rust data directory, pictures in [PeopleStore.dir]) and fills it with the same slow, resumable engine as Contacts
 * ([PeopleSyncRunner]), so the two apps never add up to a burst against WhatsApp's limit from the same account.
 *
 * Phone has no worker: a slice runs (without a time budget) when the link is read at start, when WhatsApp becomes
 * linked or reconnects, and again when a rate limit's wait ends while the process lives; the next start resumes the
 * rest. Live `profile` events update a person at once and fetch a changed picture shortly after. On unlink the stored
 * run is interrupted and the cache is deleted ([clear]): Phone keeps nobody's name for a link that is gone.
 * [source] is what the app's `PeopleIndex` follows; it is told after every committed part (at most one per
 * [notifyMinMs], the last one guaranteed).
 */
@OptIn(FlowPreview::class)
class StandalonePeople(
    private val store: PeopleStore,
    ops: PeopleOps,
    private val wa: WaRepository,
    numbers: suspend () -> List<String>,
    private val scope: CoroutineScope,
    /** Deletes the cache files (`people.json`, `people-sync.json`) after an unlink. */
    private val clear: suspend () -> Unit = {},
    gatewayUrl: suspend () -> String = { "" },
    codec: AvatarCodec = BitmapAvatarCodec,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    random: () -> Int = { Random.nextInt(0, Int.MAX_VALUE) },
    lockFile: File? = null,
    notifyMinMs: Long = NOTIFY_MIN_MS,
) {
    /** The cache as the app's `PeopleIndex` reads it: the store's rows, pictures from its folder. */
    val source: GatewaySource =
        GatewaySource(
            fetch = { store.snapshot() },
            pictureFile = { row -> row.pictureFile?.let { File(store.dir, File(it).name) }?.takeIf { it.isFile } },
        )

    private val notifier = PartNotifier(scope, notifyMinMs) { source.changed() }

    /** The engine (shared with Contacts' code in `:people`). */
    val runner =
        PeopleSyncRunner(
            store = store,
            ops = ops,
            wa = wa,
            numbers = numbers,
            gatewayUrl = gatewayUrl,
            codec = codec,
            clock = clock,
            pause = pause,
            random = random,
            onPart = notifier::part,
            lockFile = lockFile,
        )

    private var started = false

    @Volatile private var later: Job? = null

    /** Follows the link and the gateway's `profile` events. Idempotent. */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            var syncedFor: String? = null
            var wasLinked = false
            wa.linkState.collect { link ->
                val connected = link as? WaLinkState.Connected
                val gone = link is WaLinkState.None || link is WaLinkState.LoggedOut || link is WaLinkState.Banned
                when {
                    connected != null -> {
                        wasLinked = true
                        // A new link, or the same one back after a disconnect: continue (or start) the run.
                        val key = connected.status.instanceId + connected.status.state.name
                        if (key != syncedFor) {
                            syncedFor = key
                            scope.launch { sync() }
                        }
                    }

                    gone && wa.settled.value -> {
                        syncedFor = null
                        if (wasLinked) forget()
                        wasLinked = false
                    }
                }
            }
        }
        scope.launch {
            wa.events
                .filterIsInstance<GatewayEvent.Profile>()
                .onEach { event ->
                    val profile = event.profile()
                    if (wa.linkState.value !is WaLinkState.Connected ||
                        profile.jid.endsWith(GROUP_SUFFIX)
                    ) {
                        return@onEach
                    }
                    val answer = store.upsert(listOf(PeopleSyncRunner.profileUpdate(profile)))
                    if (answer != null && (answer.changed > 0 || answer.dropFiles.isNotEmpty())) notifier.part()
                }.debounce(LIVE_DEBOUNCE_MS)
                .collect {
                    if (wa.linkState.value is WaLinkState.Connected) {
                        runner.run(SyncWire.START_LIVE_PICTURES, PeopleSyncRunner.LIVE_SLICE_MS)
                    }
                }
        }
    }

    /**
     * One slice: resumes a stored run or starts an incremental one (fresh answers are not asked again). A rate limit
     * schedules the next slice for its end while the process lives. False when nothing could start.
     */
    suspend fun sync(): Boolean {
        val result = runner.run(SyncWire.START_AUTO, null)
        RovstiLog.event(PeopleSyncRunner.TAG, "standalone", "result" to result.javaClass.simpleName)
        val until =
            when (result) {
                is SliceResult.Waiting -> result.untilMs
                is SliceResult.Refused -> result.untilMs
                else -> null
            }
        if (until != null) continueAt(until)
        return result == SliceResult.Done || result is SliceResult.Waiting
    }

    private fun continueAt(untilMs: Long) {
        later?.cancel()
        later =
            scope.launch {
                pause((untilMs - clock()).coerceAtLeast(PeopleSyncRunner.CONTINUE_DELAY_MS))
                if (wa.linkState.value is WaLinkState.Connected) sync()
            }
    }

    private suspend fun forget() {
        later?.cancel()
        runner.unlinked()
        store.delete(store.files().map { it.path })
        clear()
        RovstiLog.event(PeopleSyncRunner.TAG, "standalone_forgot")
        source.changed()
    }

    companion object {
        const val NOTIFY_MIN_MS = 3_000L
        private const val LIVE_DEBOUNCE_MS = 2_000L
        private const val GROUP_SUFFIX = "@g.us"
    }
}
