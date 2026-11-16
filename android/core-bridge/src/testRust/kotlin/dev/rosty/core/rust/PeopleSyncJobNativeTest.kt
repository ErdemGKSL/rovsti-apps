package dev.rosty.core.rust

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.people.PeopleEntry
import dev.rosty.core.model.people.PeopleJobWire
import dev.rosty.core.ops.NativeWaOps
import dev.rosty.core.ops.WaGateway
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** People sync v3 through the real library: the `wa.people_sync_*` ops against a loopback gateway. */
class PeopleSyncJobNativeTest {
    private val key = "rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU"
    private val ops = NativeWaOps(TestRust.core)
    private lateinit var fake: FakeGateway
    private lateinit var cache: File

    @Before
    fun start() {
        TestRust.ensure()
        fake = FakeGateway()
        cache = Files.createTempDirectory("rosty-people").toFile()
    }

    @After
    fun stop() {
        fake.close()
        cache.deleteRecursively()
    }

    private fun gateway(): WaGateway = runBlocking { WaGateway.open(fake.url, key, cache.absolutePath, ops) }.getOrOk()

    private val job = "5f0c9a1b22d4e7a3"

    private fun status(state: String) =
        """{"job_id":"$job","rev":2,"state":"$state","phase":"lookup","done":1,"total":2,"numbers":2,""" +
            """"account_epoch":0,"created_ms":1,"updated_ms":2,"expires_ms":3}"""

    @Test
    fun aJobStartsAndIsWatched() =
        runBlocking {
            fake.route("POST", "/v1/people/sync", FakeGateway.Reply(200, status("running")))
            fake.route("GET", "/v1/people/sync/$job?rev=2&wait_ms=25000", FakeGateway.Reply(200, status("ready")))
            gateway().use { wa ->
                val started =
                    wa
                        .peopleSyncStart(
                            PeopleJobWire.MODE_AUTO,
                            listOf(PeopleEntry("+100000000001", h = "aa")),
                        ).getOrOk()
                assertTrue(started.supported)
                assertEquals(PeopleJobWire.RUNNING, started.status!!.state)
                val watched = wa.peopleSyncStatus(job, 2, 25_000).getOrOk()
                assertEquals(PeopleJobWire.READY, watched.status!!.state)
                assertFalse(watched.gone)
            }
            val body =
                fake.seen
                    .first { it.method == "POST" }
                    .body
                    .decodeToString()
            assertEquals("""{"mode":"auto","entries":[{"n":"+100000000001","h":"aa"}]}""", body)
        }

    @Test
    fun anOldGatewayIsUnsupportedAndALostJobIsGone() =
        runBlocking {
            // No route: the stub's 404 for an unknown path, like an old gateway's router.
            fake.route("POST", "/v1/people/sync", FakeGateway.Reply(404, ByteArray(0), "text/plain"))
            fake.route(
                "GET",
                "/v1/people/sync/$job",
                FakeGateway.Reply(404, """{"code":"not_found","message":"no such people job"}"""),
            )
            fake.route("DELETE", "/v1/people/sync/$job", FakeGateway.Reply(204, ByteArray(0)))
            gateway().use { wa ->
                val start = wa.peopleSyncStart(PeopleJobWire.MODE_FULL, emptyList()).getOrOk()
                assertFalse(start.supported)
                assertNull(start.status)
                assertTrue(wa.peopleSyncStatus(job).getOrOk().gone)
                assertTrue(wa.peopleSyncCancel(job) is Outcome.Ok)
            }
        }

    @Test
    fun aBundlePartWritesItsPicturesAndACutOneIsANetworkError() =
        runBlocking {
            val picture = byteArrayOf(-1, -40, -1, -32, 10, 74, 80, 71)
            val head =
                """{"v":1,"job_id":"$job","after":0}""" + "\n" +
                    """{"seq":1,"number":"+100000000001","hash":"9a41c0de5b7e1f02","on_whatsapp":true,""" +
                    """"jid":"100000000001@s.whatsapp.net","picture_id":"17","picture_bytes":${picture.size}}""" + "\n"
            val tail =
                "\n" + """{"seq":2,"number":"+100000000002","hash":"0c11d2e3f4a5b6c7","on_whatsapp":false}""" + "\n"
            val end = """{"end":true,"last_seq":2,"more":false}""" + "\n"
            val full = head.encodeToByteArray() + picture + (tail + end).encodeToByteArray()
            val type = "application/x-rosty-people-bundle"
            fake.route("GET", "/v1/people/sync/$job/bundle?after=0", FakeGateway.Reply(200, full, type))
            fake.route(
                "GET",
                "/v1/people/sync/$job/bundle?after=1",
                FakeGateway.Reply(200, head.replace("\"after\":0", "\"after\":1").encodeToByteArray(), type),
            )
            gateway().use { wa ->
                val part = wa.peopleSyncBundle(job, 0).getOrOk()
                assertEquals(2, part.lastSeq)
                assertFalse(part.more)
                assertEquals(2, part.records.size)
                val path = part.records[0].picturePath!!
                assertTrue(path.startsWith(cache.absolutePath))
                assertTrue(File(path).readBytes().contentEquals(picture))
                assertNull(part.records[1].picturePath)
                assertFalse(part.records[1].onWhatsapp)
                // The end line is missing: a transport problem, and nothing is left behind.
                val cut = wa.peopleSyncBundle(job, 1)
                assertTrue((cut as Outcome.Err).error is RustError.Network)
            }
        }

    private fun <T> Outcome<T>.getOrOk(): T = (this as? Outcome.Ok)?.value ?: error("failed: $this")
}
