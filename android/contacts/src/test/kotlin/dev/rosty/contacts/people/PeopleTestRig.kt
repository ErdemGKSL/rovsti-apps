package dev.rosty.contacts.people

import android.app.Application
import android.content.Context
import dev.rosty.contacts.ContactsContainer
import dev.rosty.core.FakeRustCore
import dev.rosty.core.Outcome
import dev.rosty.core.RustCore
import dev.rosty.core.RustError
import dev.rosty.core.model.people.PeopleLookupAnswer
import dev.rosty.core.model.people.PeopleLookupRequest
import dev.rosty.core.model.people.PeoplePlanAnswer
import dev.rosty.core.model.people.PeoplePlanRequest
import dev.rosty.core.model.people.PeopleSnapshotAnswer
import dev.rosty.core.model.people.PeopleSnapshotRequest
import dev.rosty.core.model.people.PeopleUpsertAnswer
import dev.rosty.core.model.people.PeopleUpsertRequest
import dev.rosty.core.model.people.PersonRow
import dev.rosty.core.model.people.PictureRefresh
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.model.wa.PictureAnswer
import dev.rosty.core.model.wa.PictureBody
import dev.rosty.core.model.wa.WaProfile
import dev.rosty.core.model.wa.WaUser
import dev.rosty.core.ops.WaGateway
import dev.rosty.people.sync.PeopleStore
import dev.rosty.shell.HasContainer
import dev.rosty.wa.FakeWaGateway
import dev.rosty.wa.FakeWaRepository
import dev.rosty.wa.WaLinkState
import dev.rosty.wa.WaRepository
import dev.rosty.wa.avatars.AvatarCodec
import kotlinx.coroutines.awaitCancellation
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * The `people.*` ops over an in-memory book with the rules of `rosty_app_core::people` that the sync depends on: a new
 * picture id marks the picture stale, a stored file clears it, "removed" drops the file, `plan` asks for stale or
 * missing pictures and lists files nobody holds. Not the real core: `PeopleStore`'s own test runs that on the host
 * library (the real-Rust half is in `PeopleRustTest`).
 */
class FakePeopleRust(
    val core: FakeRustCore = FakeRustCore(),
) {
    class Rec(
        val jid: String,
        val numbers: MutableList<String> = mutableListOf(),
        var name: String? = null,
        var onWhatsapp: Boolean = false,
        var pictureId: String? = null,
        var pictureFile: String? = null,
        var stale: Boolean = false,
        var fetched: Long = 0,
    ) {
        fun row() = PersonRow(jid, numbers.toList(), null, name, onWhatsapp, pictureId, pictureFile, stale)
    }

    val book = LinkedHashMap<String, Rec>()

    init {
        core.onTyped("people.upsert", PeopleUpsertRequest.serializer(), PeopleUpsertAnswer.serializer()) { req ->
            var changed = 0
            val stale = mutableListOf<String>()
            val drop = mutableListOf<String>()
            for (u in req.people) {
                val before = book[u.jid]?.row()
                val p = book.getOrPut(u.jid) { Rec(u.jid) }
                u.number?.let { if (it !in p.numbers) p.numbers += it }
                (u.verifiedName ?: u.pushName)?.let { p.name = it }
                u.onWhatsapp?.let { p.onWhatsapp = it }
                if (u.pictureRemoved == true) {
                    p.pictureFile?.let(drop::add)
                    p.pictureFile = null
                    p.pictureId = null
                    p.stale = false
                    p.fetched = req.nowMs
                } else if (u.pictureId != null) {
                    p.onWhatsapp = true
                    val moved = p.pictureId != u.pictureId
                    p.pictureId = u.pictureId
                    if (u.pictureFile != null) {
                        p.pictureFile?.takeIf { it != u.pictureFile }?.let(drop::add)
                        p.pictureFile = u.pictureFile
                        p.stale = false
                        p.fetched = req.nowMs
                    } else if (moved) {
                        p.stale = true
                    }
                }
                if (before != p.row()) changed++
                if (p.stale) stale += p.jid
            }
            Outcome.Ok(PeopleUpsertAnswer(changed, stale, drop))
        }
        core.onTyped(
            "wa.avatars_plan",
            dev.rosty.core.model.wa.AvatarsPlanRequest
                .serializer(),
            dev.rosty.core.model.wa.AvatarsPlanAnswer
                .serializer(),
        ) {
            Outcome.Ok(
                dev.rosty.wa.FakeAvatarsPlan
                    .answer(it),
            )
        }
        core.onTyped("people.snapshot", PeopleSnapshotRequest.serializer(), PeopleSnapshotAnswer.serializer()) {
            Outcome.Ok(PeopleSnapshotAnswer(book.values.map(Rec::row).sortedBy { it.name ?: "￿" }))
        }
        core.onTyped("people.lookup", PeopleLookupRequest.serializer(), PeopleLookupAnswer.serializer()) { req ->
            Outcome.Ok(PeopleLookupAnswer(book.values.firstOrNull { it.jid == req.key }?.row()))
        }
        core.onTyped("people.plan", PeoplePlanRequest.serializer(), PeoplePlanAnswer.serializer()) { req ->
            val onDisk = req.files.map { File(it.path).name }.toSet()
            val refresh =
                book.values
                    .filter { it.onWhatsapp }
                    .filter {
                        it.stale || (it.pictureId != null && it.pictureFile !in onDisk) ||
                            (it.pictureId == null && it.fetched == 0L)
                    }.map { PictureRefresh(it.jid, it.pictureId) }
            val held = book.values.mapNotNull { it.pictureFile }.toSet()
            Outcome.Ok(PeoplePlanAnswer(refresh, req.files.filter { File(it.path).name !in held }.map { it.path }))
        }
    }
}

/**
 * The gateway's 429: an `invalid` error whose detail says `rate_limited` (what `rateLimit()` reads), with the S1 hint
 * fields when given.
 */
fun <T> rateLimited(
    retryAfterMs: Long? = null,
    source: String? = null,
): Outcome<T> =
    Outcome.Err(
        RustError.Invalid(
            "too many requests",
            kotlinx.serialization.json.JsonObject(
                buildMap {
                    put("code", kotlinx.serialization.json.JsonPrimitive("rate_limited"))
                    retryAfterMs?.let { put("retry_after_ms", kotlinx.serialization.json.JsonPrimitive(it)) }
                    source?.let { put("source", kotlinx.serialization.json.JsonPrimitive(it)) }
                },
            ),
        ),
    )

/** The gateway's per-instance token bucket (`TokenBucket` in `services/wa_gateway`), all or nothing, on [clock]. */
class FakeBucket(
    private val burst: Int,
    private val perSec: Double,
    private val clock: () -> Long,
    start: Int = burst,
) {
    private var tokens = start.toDouble()
    private var last = clock()

    /** The requests it turned away. */
    var refused = 0

    fun tryTake(n: Int): Boolean {
        val now = clock()
        tokens = (tokens + (now - last) * perSec / 1_000).coerceAtMost(burst.toDouble())
        last = now
        if (n > tokens) {
            refused++
            return false
        }
        tokens -= n
        return true
    }
}

/** The gateway of the sync tests: who is on WhatsApp, their profiles and pictures, and a log of what was asked. */
class FakeGatewayData(
    val gateway: FakeWaGateway = FakeWaGateway(),
) {
    val onWhatsapp = mutableSetOf<String>()
    val profiles = mutableMapOf<String, WaProfile>()
    val pictures = mutableMapOf<String, PictureAnswer>()

    /** Lookups the gateway answered (a refused one is not here). */
    val lookups = mutableListOf<List<String>>()
    val pictureAsks = mutableListOf<PictureBody>()

    /** The lookup bucket; none: no rate limit. A refused lookup answers 429. */
    var lookupBucket: FakeBucket? = null

    /** The profile and picture bucket (one token per profile or picture); none: no rate limit. */
    var profileBucket: FakeBucket? = null

    /** A picture answer ahead of [pictures], e.g. a 429 for a jid; null: none. */
    var pictureOverride: (PictureBody) -> Outcome<PictureAnswer>? = { null }

    init {
        // A gateway op: the request carries `handle` next to the body fields.
        gateway.core.on("wa.lookup_users") { request ->
            val jids =
                dev.rosty.core.Envelope.json
                    .decodeFromJsonElement(
                        dev.rosty.core.model.wa.JidsBody
                            .serializer(),
                        kotlinx.serialization.json.JsonObject(
                            (request as kotlinx.serialization.json.JsonObject).filterKeys {
                                it !=
                                    "handle"
                            },
                        ),
                    ).jids
            if (lookupBucket?.tryTake(jids.size) == false) return@on rateLimited()
            lookups += jids
            Outcome.Ok(
                dev.rosty.core.Envelope.json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(WaUser.serializer()),
                    jids.map { WaUser(it, it in onWhatsapp) },
                ),
            )
        }
        gateway.profiles = { jids ->
            if (profileBucket?.tryTake(jids.size) == false) {
                rateLimited()
            } else {
                Outcome.Ok(jids.mapNotNull(profiles::get))
            }
        }
        gateway.picture = { body ->
            pictureAsks += body
            pictureOverride(body)
                ?: if (profileBucket?.tryTake(1) == false) {
                    rateLimited()
                } else {
                    Outcome.Ok(pictures[body.jid] ?: PictureAnswer("none"))
                }
        }
    }

    suspend fun open(): WaGateway? =
        (
            WaGateway.open(
                "https://gateway.example",
                "key",
                null,
                gateway.ops,
                gateway.audio,
                release = {},
            ) as? Outcome.Ok
        )?.value
}

/** Stores what it is given; the real codec needs Android's bitmap decoder. */
object IdentityCodec : AvatarCodec {
    override fun downscale(
        bytes: ByteArray,
        maxPx: Int,
    ): ByteArray? = bytes.takeIf { it.isNotEmpty() }
}

fun b64(text: String): String = Base64.getEncoder().encodeToString(text.encodeToByteArray())

/**
 * The real host library for `people.*` (the planner and the book, people sync v2) once [real] is set, the fake for
 * everything else (the gateway's `wa.*` ops, `wa.avatars_plan`).
 */
class RoutingCore(
    private val fake: RustCore,
) : RustCore {
    @Volatile var real: RustCore? = null

    override suspend fun version() = fake.version()

    override suspend fun call(
        op: String,
        request: kotlinx.serialization.json.JsonElement,
    ): Outcome<kotlinx.serialization.json.JsonElement> {
        val host = real
        return if (host != null && op.startsWith("people.")) host.call(op, request) else fake.call(op, request)
    }

    override suspend fun headless(
        kind: String,
        json: String,
    ) = fake.headless(kind, json)
}

/** Contacts' container on fakes: no native library, a temp picture directory, a scripted gateway. */
class PeopleTestContainer(
    context: Context,
) : ContactsContainer(context) {
    val people = FakePeopleRust()
    val routing = RoutingCore(people.core)
    val gw = FakeGatewayData()
    val link = FakeWaRepository()
    val dir: File = Files.createTempDirectory("people").toFile().apply { deleteOnExit() }

    /** The `people.*` data directory of this container (the real library writes `people.json` here). */
    val peopleData: File = Files.createTempDirectory("people-data").toFile().apply { deleteOnExit() }
    var numbers: List<String> = emptyList()
    var now = 1_000_000_000L
    var ready: Boolean? = true
    var changes = 0

    /**
     * Routes `people.*` to the real host library (the sync tests: the planner is not faked); skips the test when the
     * library is not built. Call before the first use of the store.
     */
    fun useRealPeople() {
        dev.rosty.contacts.HostRust("people-sync")
        routing.real =
            dev.rosty.core.Rust
                .core()
    }

    /** The settings rows' status once it satisfies [ok] (it follows the runner on the scope, a moment later). */
    fun awaitStatus(ok: (PeopleSyncStatus) -> Boolean): PeopleSyncStatus {
        val deadline = System.currentTimeMillis() + 5_000
        while (!ok(peopleSync.status.value) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        return peopleSync.status.value
    }

    /** The real book, as the provider reads it. */
    fun book(): Map<String, PersonRow> =
        kotlinx.coroutines.runBlocking { peopleStore.snapshot().associateBy { it.jid } }

    /** The waits the sync asked for; each one moves [now] on, so the fake buckets refill. */
    val pauses = mutableListOf<Long>()

    /** Runs inside every wait before the clock moves (a test can hold the sync there). */
    @Volatile var pauseHook: (suspend (Long) -> Unit)? = null

    init {
        link.gatewayOpener = { gw.open() }
    }

    fun connect() = link.setState(WaLinkState.Connected(InstanceStatus("inst", InstanceState.Connected)))

    override fun createRust(): RustCore = routing

    override suspend fun rustReady(): Boolean =
        when (ready) {
            true -> true
            false -> false
            null -> awaitCancellation()
        }

    override fun createWa(): WaRepository = link

    override fun createPeopleStore() =
        PeopleStore(
            peopleOps,
            waOps,
            dir,
            kotlinx.coroutines.Dispatchers.IO,
            clock = { now },
            dataDir = peopleData.absolutePath,
        )

    override fun createPeopleSync() =
        PeopleSync(
            store = peopleStore,
            ops = peopleOps,
            wa = wa,
            numbers = { numbers },
            scope = scope,
            legacy = context.getSharedPreferences("people-sync-test", Context.MODE_PRIVATE),
            codec = IdentityCodec,
            clock = { now },
            pause = {
                pauses += it
                pauseHook?.invoke(it)
                now += it
            },
            random = { 20 },
            notifyMinMs = 0,
            onChanged = {
                changes++
                peopleStore.notifyChanged(context.contentResolver)
            },
        )
}

class PeopleTestApp :
    Application(),
    HasContainer<PeopleTestContainer> {
    override val container: PeopleTestContainer by lazy { PeopleTestContainer(this) }
}
