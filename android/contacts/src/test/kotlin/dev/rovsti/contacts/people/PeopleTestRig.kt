package dev.rovsti.contacts.people

import android.app.Application
import android.content.Context
import dev.rovsti.contacts.ContactsContainer
import dev.rovsti.core.FakeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustCore
import dev.rovsti.core.RustError
import dev.rovsti.core.model.people.PeopleLookupAnswer
import dev.rovsti.core.model.people.PeopleLookupRequest
import dev.rovsti.core.model.people.PeoplePlanAnswer
import dev.rovsti.core.model.people.PeoplePlanRequest
import dev.rovsti.core.model.people.PeopleSnapshotAnswer
import dev.rovsti.core.model.people.PeopleSnapshotRequest
import dev.rovsti.core.model.people.PeopleUpsertAnswer
import dev.rovsti.core.model.people.PeopleUpsertRequest
import dev.rovsti.core.model.people.PersonRow
import dev.rovsti.core.model.people.PictureRefresh
import dev.rovsti.core.model.wa.InstanceState
import dev.rovsti.core.model.wa.InstanceStatus
import dev.rovsti.core.model.wa.PictureAnswer
import dev.rovsti.core.model.wa.PictureBody
import dev.rovsti.core.model.wa.WaProfile
import dev.rovsti.core.model.wa.WaUser
import dev.rovsti.core.ops.WaGateway
import dev.rovsti.shell.HasContainer
import dev.rovsti.wa.FakeWaGateway
import dev.rovsti.wa.FakeWaRepository
import dev.rovsti.wa.WaLinkState
import dev.rovsti.wa.WaRepository
import dev.rovsti.wa.avatars.AvatarCodec
import kotlinx.coroutines.awaitCancellation
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * The `people.*` ops over an in-memory book with the rules of `rovsti_app_core::people` that the sync depends on: a new
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
            dev.rovsti.core.model.wa.AvatarsPlanRequest
                .serializer(),
            dev.rovsti.core.model.wa.AvatarsPlanAnswer
                .serializer(),
        ) {
            Outcome.Ok(
                dev.rovsti.wa.FakeAvatarsPlan
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

/** The gateway's 429: an `invalid` error whose detail says `rate_limited` (what `isRateLimited` reads). */
fun <T> rateLimited(): Outcome<T> =
    Outcome.Err(
        RustError.Invalid(
            "too many requests",
            kotlinx.serialization.json.JsonObject(
                mapOf("code" to kotlinx.serialization.json.JsonPrimitive("rate_limited")),
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
                dev.rovsti.core.Envelope.json
                    .decodeFromJsonElement(
                        dev.rovsti.core.model.wa.JidsBody
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
                dev.rovsti.core.Envelope.json.encodeToJsonElement(
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

/** Contacts' container on fakes: no native library, a temp picture directory, a scripted gateway. */
class PeopleTestContainer(
    context: Context,
) : ContactsContainer(context) {
    val people = FakePeopleRust()
    val gw = FakeGatewayData()
    val link = FakeWaRepository()
    val dir: File = Files.createTempDirectory("people").toFile().apply { deleteOnExit() }
    var numbers: List<String> = emptyList()
    var now = 1_000_000_000L
    var ready: Boolean? = true
    var changes = 0

    /** The waits the sync asked for; each one moves [now] on, so the fake buckets refill. */
    val pauses = mutableListOf<Long>()

    init {
        link.gatewayOpener = { gw.open() }
    }

    fun connect() = link.setState(WaLinkState.Connected(InstanceStatus("inst", InstanceState.Connected)))

    override fun createRust(): RustCore = people.core

    override suspend fun rustReady(): Boolean =
        when (ready) {
            true -> true
            false -> false
            null -> awaitCancellation()
        }

    override fun createWa(): WaRepository = link

    override fun createPeopleStore() =
        PeopleStore(peopleOps, waOps, dir, kotlinx.coroutines.Dispatchers.IO, clock = { now })

    override fun createPeopleSync() =
        PeopleSync(
            store = peopleStore,
            wa = wa,
            numbers = { numbers },
            log = PeopleSyncLog(context.getSharedPreferences("people-sync-test", Context.MODE_PRIVATE)),
            scope = scope,
            codec = IdentityCodec,
            clock = { now },
            onChanged = {
                changes++
                peopleStore.notifyChanged(context.contentResolver)
            },
            pause = {
                pauses += it
                now += it
            },
        )
}

class PeopleTestApp :
    Application(),
    HasContainer<PeopleTestContainer> {
    override val container: PeopleTestContainer by lazy { PeopleTestContainer(this) }
}
