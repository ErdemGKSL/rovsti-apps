package dev.rosty.phone.people

import dev.rosty.core.Envelope
import dev.rosty.core.Outcome
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.model.wa.JidsBody
import dev.rosty.core.model.wa.PictureAnswer
import dev.rosty.core.model.wa.WaProfile
import dev.rosty.core.model.wa.WaUser
import dev.rosty.core.ops.NativePeopleOps
import dev.rosty.core.ops.NativeWaOps
import dev.rosty.core.ops.WaGateway
import dev.rosty.people.PeopleIdentitySource
import dev.rosty.people.PeopleIndex
import dev.rosty.people.sync.PeopleStore
import dev.rosty.phone.HostRust
import dev.rosty.wa.FakeWaGateway
import dev.rosty.wa.FakeWaRepository
import dev.rosty.wa.WaLinkState
import dev.rosty.wa.avatars.AvatarCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * X.1d and people sync v2 S10: Phone without Contacts keeps its own people cache on disk, filled by the shared engine
 * (the real planner on the host library), draws initials until it has answers, and deletes everything on unlink.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StandaloneSourceTest {
    private val gw = FakeWaGateway()
    private val onWhatsapp = setOf("905321112233@s.whatsapp.net", "4915123456789@s.whatsapp.net")
    private val asked = mutableListOf<List<String>>()
    private val repo = FakeWaRepository()
    private val pictures: File = Files.createTempDirectory("standalone-pictures").toFile().apply { deleteOnExit() }
    private val data: File = Files.createTempDirectory("standalone-data").toFile().apply { deleteOnExit() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_000_000_000L
    private var cleared = 0

    init {
        gw.core.on("wa.lookup_users") { request ->
            val jids =
                Envelope.json
                    .decodeFromJsonElement(
                        JidsBody.serializer(),
                        JsonObject((request as JsonObject).filterKeys { it != "handle" }),
                    ).jids
            asked += jids
            Outcome.Ok(
                Envelope.json.encodeToJsonElement(
                    ListSerializer(WaUser.serializer()),
                    jids.map { WaUser(it, it in onWhatsapp) },
                ),
            )
        }
        gw.profiles = { jids ->
            Outcome.Ok(
                jids.map {
                    WaProfile(it, pushName = "Push ${it.take(4)}", pictureId = "pic${it.take(4)}", fetchedMs = 1)
                },
            )
        }
        gw.picture = { body ->
            val bytes = Base64.getEncoder().encodeToString("img-${body.jid}".encodeToByteArray())
            Outcome.Ok(PictureAnswer("image", id = "pic${body.jid.take(4)}", dataB64 = bytes))
        }
        repo.gatewayOpener = {
            (WaGateway.open("https://gateway.example", "key", null, gw.ops, gw.audio, release = {}) as? Outcome.Ok)
                ?.value
        }
    }

    @After fun stop() = scope.cancel()

    private object Identity : AvatarCodec {
        override fun downscale(
            bytes: ByteArray,
            maxPx: Int,
        ): ByteArray? = bytes
    }

    private fun people(request: ((String) -> Unit)? = null): StandalonePeople {
        val host = HostRust("standalone")
        val ops = NativePeopleOps(host.core)
        val store = PeopleStore(ops, NativeWaOps(host.core), pictures, clock = { now }, dataDir = data.absolutePath)
        return StandalonePeople(
            store = store,
            ops = ops,
            wa = repo,
            numbers = { listOf("+905321112233", "+4915123456789", "+4930111222", "not a number") },
            scope = scope,
            clear = {
                cleared++
                File(data, "people.json").delete()
                File(data, "people-sync.json").delete()
            },
            codec = Identity,
            clock = { now },
            pause = { now += it },
            random = { 20 },
            notifyMinMs = 0,
            request = request,
        )
    }

    private fun connect() = repo.setState(WaLinkState.Connected(InstanceStatus("inst", InstanceState.Connected)))

    private fun waitFor(
        what: String,
        ok: suspend () -> Boolean,
    ) = runBlocking {
        val deadline = System.currentTimeMillis() + 10_000
        while (!ok() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(what, ok())
    }

    @Test fun nothingIsSyncedAndNothingIsKnownWhileWhatsAppIsNotLinked() =
        runBlocking {
            val p = people()
            p.start()
            assertTrue(p.source.load().isEmpty())
            assertTrue(asked.isEmpty())
            assertEquals(false, p.sync())
        }

    /** People sync v2 S11: in the app a link hands its run to the foreground worker, not to the process. */
    @Test fun withAWorkerALinkRequestsARunAndAsksNothingItself() {
        val starts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val p = people(request = { starts += it })
        p.start()
        connect()
        waitFor("the worker was asked") { starts.isNotEmpty() }
        assertEquals(listOf(dev.rosty.core.model.people.SyncWire.START_AUTO), starts.toList())
        assertTrue("nothing asked in the process", asked.isEmpty())
        // The worker's slice is the host's: it fills the same cache.
        val result = runBlocking { p.runSlice(dev.rosty.core.model.people.SyncWire.START_AUTO, null) }
        assertEquals(dev.rosty.people.sync.SliceResult.Done, result)
        assertEquals(onWhatsapp, runBlocking { p.source.load() }.map { it.jid }.toSet())
    }

    /**
     * People sync v3, section 6: a reconnect of the same link starts a run only when the last one is twelve hours old
     * (and the last attempt half an hour); another link starts one at once.
     */
    @Test fun aReconnectSyncsOnlyAfterTwelveHoursButAnotherLinkSyncsAtOnce() {
        val starts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val p = people(request = { starts += it })
        p.start()
        connect()
        waitFor("the first link starts a run") { starts.size == 1 }
        val result = runBlocking { p.runSlice(dev.rosty.core.model.people.SyncWire.START_AUTO, null) }
        assertEquals(dev.rosty.people.sync.SliceResult.Done, result)

        repo.setState(WaLinkState.Connected(InstanceStatus("inst", InstanceState.Disconnected)))
        // The link state is a StateFlow: give the follower time to see the disconnect.
        Thread.sleep(300)
        connect()
        Thread.sleep(300)
        assertEquals("a reconnect right after a run asks nothing", 1, starts.size)

        now += 13 * 3_600_000L
        repo.setState(WaLinkState.Connected(InstanceStatus("inst", InstanceState.Disconnected)))
        // The link state is a StateFlow: give the follower time to see the disconnect.
        Thread.sleep(300)
        connect()
        waitFor("a reconnect twelve hours later starts one") { starts.size == 2 }

        repo.setState(WaLinkState.Connected(InstanceStatus("inst-2", InstanceState.Connected)))
        waitFor("another link starts one at once") { starts.size == 3 }
        assertTrue(starts.all { it == dev.rosty.core.model.people.SyncWire.START_AUTO })
    }

    @Test fun linkingSyncsWhoIsOnWhatsAppIntoItsOwnCacheWithThePictures() {
        val p = people()
        p.start()
        connect()
        waitFor("synced") { p.source.load().count { it.pictureFile != null } == 2 }
        val rows = runBlocking { p.source.load() }
        assertEquals(onWhatsapp, rows.map { it.jid }.toSet())
        // Only plausible numbers go to the gateway, as phone JIDs, in one small batch.
        assertEquals(
            setOf("905321112233@s.whatsapp.net", "4915123456789@s.whatsapp.net", "4930111222@s.whatsapp.net"),
            asked.flatten().toSet(),
        )
        assertTrue(rows.all { it.onWhatsapp && it.name!!.startsWith("Push") })
        assertTrue("the cache is on disk", File(data, "people.json").isFile)
        rows.forEach { assertNotNull(p.source.pictureUri(it)) }
    }

    @Test fun theIndexAndTheIdentitySourceAnswerAfterTheSyncAndNotBefore() {
        val index = PeopleIndex()
        val ids = PeopleIdentitySource(index)
        val p = people()
        val job = index.attach(scope, p.source)
        p.start()
        assertTrue("initials until it arrives", runBlocking { ids.lookup(listOf("+905321112233")) }.isEmpty())
        connect()
        waitFor("the index has the picture") {
            ids.lookup(listOf("+905321112233"))["+905321112233"]?.waPhotoUri != null
        }
        val who = runBlocking { ids.lookup(listOf("+905321112233", "+4930111222")) }
        assertEquals(setOf("+905321112233"), who.keys)
        assertEquals("Push 9053", who.getValue("+905321112233").waPushName)
        job.cancel()
    }

    @Test fun aSecondStartAsksNothingAgain() {
        val p = people()
        p.start()
        connect()
        waitFor("synced") { p.source.load().count { it.pictureFile != null } == 2 }
        val lookups = asked.size
        now += 60_000
        runBlocking { p.sync() }
        assertEquals("fresh answers are not asked again", lookups, asked.size)
    }

    @Test fun aProfileEventUpdatesThePersonAtOnce() {
        val p = people()
        p.start()
        connect()
        // After the whole first run, so its profile batch cannot answer after the event.
        waitFor("synced") { p.source.load().count { it.pictureFile != null } == 2 }
        repo.emit(
            GatewayEvent.Profile(
                jid = "905321112233@s.whatsapp.net",
                pushName = "Ayse",
                pictureId = "pic9053",
                fetchedMs = 5,
            ),
        )
        waitFor("the name changed") { p.source.load().any { it.name == "Ayse" } }
    }

    @Test fun unlinkingDeletesTheCache() {
        val p = people()
        p.start()
        connect()
        waitFor("synced") { p.source.load().count { it.pictureFile != null } == 2 }
        repo.setState(WaLinkState.None)
        waitFor("forgotten") { p.source.load().isEmpty() && cleared == 1 }
        assertFalse(File(data, "people.json").exists())
        assertTrue("no picture is left", pictures.listFiles().orEmpty().none { it.isFile })
    }
}
