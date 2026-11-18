package dev.rosty.phone.people

import dev.rosty.core.log.RostyLog
import dev.rosty.core.model.people.SyncWire
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.WaIds
import dev.rosty.core.ops.PeopleOps
import dev.rosty.people.GatewaySource
import dev.rosty.people.sync.PartNotifier
import dev.rosty.people.sync.PeoplePictureCodec
import dev.rosty.people.sync.PeopleStore
import dev.rosty.people.sync.PeopleSyncHost
import dev.rosty.people.sync.PeopleSyncRunner
import dev.rosty.people.sync.PeopleSyncTriggers
import dev.rosty.people.sync.SliceResult
import dev.rosty.wa.WaLinkState
import dev.rosty.wa.WaRepository
import dev.rosty.wa.avatars.AvatarCodec
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
 * When a run starts follows Contacts' rules ([PeopleSyncTriggers], people sync v3, section 6): a link this process has
 * not followed before (another instance) starts one; the link read at start and a reconnect start one only when the
 * last run is twelve hours old and the last attempt half an hour, and never over the user's Stop. In the app it goes to
 * [request], which enqueues Phone's foreground worker (`PhonePeopleSyncWorker`, people sync v2 S11: ongoing
 * notification for every run, the gateway's job followed live, a long rate limit continues through WorkManager), the
 * same worker as Contacts'. Without one (tests) the slice runs in [scope] and a rate limit's end is waited for while
 * the process lives. Live `profile`
 * events update a person at once and fetch a changed picture shortly after. On unlink the stored
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
    codec: AvatarCodec = PeoplePictureCodec,
    private val clock: () -> Long = System::currentTimeMillis,
    pause: suspend (Long) -> Unit = { delay(it) },
    random: () -> Int = { Random.nextInt(0, Int.MAX_VALUE) },
    lockFile: File? = null,
    notifyMinMs: Long = NOTIFY_MIN_MS,
    /** Starts a run (`SyncWire.START_*`) in the worker; null runs it in [scope]. */
    private val request: ((String) -> Unit)? = null,
    /** Where the gateway writes a bundle's pictures (`<cache_dir>/people-bundle`), emptied when a slice begins. */
    bundleDir: File? = null,
) : PeopleSyncHost {
    /** The cache as the app's `PeopleIndex` reads it: the store's rows, pictures from its folder. */
    val source: GatewaySource =
        GatewaySource(
            fetch = { store.snapshot() },
            pictureFile = { row -> row.pictureFile?.let { File(store.dir, File(it).name) }?.takeIf { it.isFile } },
        )

    private val notifier = PartNotifier(scope, notifyMinMs) { source.changed() }
    private val sleep = pause

    /** The engine (shared with Contacts' code in `:people`). */
    override val runner =
        PeopleSyncRunner(
            store = store,
            ops = ops,
            wa = wa,
            numbers = numbers,
            gatewayUrl = gatewayUrl,
            codec = codec,
            clock = clock,
            pause = sleep,
            random = random,
            onPart = notifier::part,
            lockFile = lockFile,
            bundleDir = bundleDir,
            background = scope,
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
            var instance: String? = null
            var state: String? = null
            var wasLinked = false
            wa.linkState.collect { link ->
                val connected = link as? WaLinkState.Connected
                val gone = link is WaLinkState.None || link is WaLinkState.LoggedOut || link is WaLinkState.Banned
                when {
                    connected != null -> {
                        wasLinked = true
                        val id = connected.status.instanceId
                        val now = connected.status.state.name
                        val kind =
                            when {
                                // Its WhatsApp session is down: the reconnect decides.
                                connected.status.state == InstanceState.Disconnected -> null

                                instance == null -> PeopleSyncTriggers.Kind.RECONNECT

                                instance != id -> PeopleSyncTriggers.Kind.NEW_LINK

                                now != state -> PeopleSyncTriggers.Kind.RECONNECT

                                else -> null
                            }
                        instance = id
                        state = now
                        if (kind != null) trigger(kind)
                    }

                    gone && wa.settled.value -> {
                        instance = null
                        state = null
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
                        WaIds.isGroup(profile.jid)
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
     * The foreground trigger: a run when the last one is older than the period. Phone calls it when its WhatsApp vault
     * opens, for a run the lock held back (`plans/wa-vault.md` 13.3).
     */
    fun syncIfStale() {
        scope.launch { trigger(PeopleSyncTriggers.Kind.FOREGROUND) }
    }

    /** A link event: starts a run when [PeopleSyncTriggers] says so for [kind]. */
    private suspend fun trigger(kind: PeopleSyncTriggers.Kind) {
        val stored = runner.refresh()
        val go =
            PeopleSyncTriggers.shouldStart(
                kind,
                clock(),
                lastFinishedMs = stored?.last?.atMs,
                lastAttemptMs = stored?.attemptMs,
                run = stored?.run,
                notBeforeMs = stored?.notBeforeMs,
            )
        RostyLog.event(PeopleSyncRunner.TAG, "standalone_trigger", "kind" to kind.name, "start" to go)
        if (!go) return
        val worker = request
        if (worker != null) worker(SyncWire.START_AUTO) else scope.launch { sync() }
    }

    override fun now(): Long = clock()

    override suspend fun pause(ms: Long) = sleep(ms)

    /**
     * One slice in this process (no worker): resumes a stored run or starts an incremental one (fresh answers are not
     * asked again). A rate limit schedules the next slice for its end while the process lives. False when nothing
     * could start.
     */
    suspend fun sync(): Boolean {
        val result = runner.run(SyncWire.START_AUTO, null)
        RostyLog.event(PeopleSyncRunner.TAG, "standalone", "result" to result.javaClass.simpleName)
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
                sleep((untilMs - clock()).coerceAtLeast(PeopleSyncRunner.CONTINUE_DELAY_MS))
                if (wa.linkState.value is WaLinkState.Connected) sync()
            }
    }

    private suspend fun forget() {
        later?.cancel()
        runner.unlinked()
        store.delete(store.files().map { it.path })
        clear()
        RostyLog.event(PeopleSyncRunner.TAG, "standalone_forgot")
        source.changed()
    }

    companion object {
        const val NOTIFY_MIN_MS = 3_000L
        private const val LIVE_DEBOUNCE_MS = 2_000L
    }
}
