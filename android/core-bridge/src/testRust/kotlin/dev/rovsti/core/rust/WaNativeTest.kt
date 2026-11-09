package dev.rovsti.core.rust

import dev.rovsti.core.MainThreadCheck
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.RustThreading
import dev.rovsti.core.model.phone.CallPhase
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.model.wa.MessageBody
import dev.rovsti.core.model.wa.SendText
import dev.rovsti.core.model.wa.WaStreamEvent
import dev.rovsti.core.ops.NativeWaOps
import dev.rovsti.core.ops.WaEvents
import dev.rovsti.core.ops.WaFeed
import dev.rovsti.core.ops.WaGateway
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** E2.14 through the real library: the gateway ops and the event stream against a loopback gateway. */
class WaNativeTest {
    private val key = "rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU"
    private val ops = NativeWaOps(TestRust.core)
    private lateinit var fake: FakeGateway
    private lateinit var cache: File

    @Before
    fun start() {
        TestRust.ensure()
        fake = FakeGateway()
        cache = Files.createTempDirectory("rovsti-wa").toFile()
    }

    @After
    fun stop() {
        fake.close()
        cache.deleteRecursively()
    }

    private fun gateway(withKey: Boolean = true): WaGateway =
        runBlocking { WaGateway.open(fake.url, if (withKey) key else null, cache.absolutePath, ops) }.getOrOk()

    private fun json(text: String) = FakeGateway.Reply(200, text)

    @Test
    fun statusChatsAndSendTextRoundTripThroughRust() =
        runBlocking {
            fake.route(
                "GET",
                "/v1/instance",
                json("""{"instance_id":"i1","state":"connected","own_jid":"1@s.whatsapp.net","push_name":"Ada"}"""),
            )
            fake.route(
                "GET",
                "/v1/chats",
                json("""[{"jid":"1@s.whatsapp.net","name":"Ada","last":null,"unread":3,"is_group":false}]"""),
            )
            fake.route("POST", "/v1/messages/text", json("""{"message_id":"m1","timestamp_ms":5}"""))
            gateway().use { wa ->
                val status = wa.status().getOrOk()
                assertEquals("Ada", status.pushName)
                assertEquals(
                    3,
                    wa
                        .chats()
                        .getOrOk()
                        .single()
                        .unread,
                )
                val sent = wa.sendText(SendText("1@s.whatsapp.net", "Merhaba")).getOrOk()
                assertEquals("m1", sent.messageId)
            }
            assertTrue(fake.seen.all { it.authorization == "Bearer $key" })
            val body =
                fake.seen
                    .last { it.method == "POST" }
                    .body
                    .decodeToString()
            assertEquals("""{"chat":"1@s.whatsapp.net","text":"Merhaba","quoted_id":null}""", body)
        }

    @Test
    fun gatewayFailuresMapToTheDocumentedErrors() =
        runBlocking {
            gateway().use { wa ->
                fake.route(
                    "GET",
                    "/v1/instance",
                    FakeGateway.Reply(401, """{"code":"unauthorized","message":"revoked"}"""),
                )
                assertTrue((wa.status() as Outcome.Err).error is RustError.Auth)
                fake.route(
                    "GET",
                    "/v1/instance",
                    FakeGateway.Reply(409, """{"code":"not_connected","message":"the phone is offline"}"""),
                )
                val api = (wa.status() as Outcome.Err).error
                assertTrue(api is RustError.Invalid)
                assertEquals("the phone is offline", api.message)
                fake.route("GET", "/v1/instance", json("not json"))
                assertTrue((wa.status() as Outcome.Err).error is RustError.Parse)
            }
            fake.close()
            val network = gateway().use { it.status() }
            assertTrue((network as Outcome.Err).error is RustError.Network)
        }

    @Test
    fun mediaAboveOneMegabyteIsAFileInTheCacheDirectory() =
        runBlocking {
            val big = ByteArray(1_200_000) { 7 }
            fake.route("GET", "/v1/messages/m9/media", FakeGateway.Reply(200, big, "video/mp4"))
            fake.route(
                "GET",
                "/v1/messages/m1/thumbnail",
                FakeGateway.Reply(200, "THUMB".encodeToByteArray(), "image/jpeg"),
            )
            gateway().use { wa ->
                val large = wa.media("m9").getOrOk()
                assertNull(large.dataB64)
                assertEquals(1_200_000L, large.size)
                val file = File(large.path!!)
                assertTrue(file.startsWith(cache))
                assertEquals(1_200_000L, file.length())
                val small = wa.thumbnail("m1").getOrOk()
                assertEquals(
                    "THUMB",
                    java.util.Base64
                        .getDecoder()
                        .decode(small.dataB64)
                        .decodeToString(),
                )
                assertNull(small.path)
            }
        }

    @Test
    fun aClosedGatewayAnswersNotFoundWithoutANativeCall() =
        runBlocking {
            val wa = gateway()
            wa.close()
            assertTrue((wa.status() as Outcome.Err).error is RustError.NotFound)
            assertTrue((wa.startEvents("i1") as Outcome.Err).error is RustError.NotFound)
            assertTrue((wa.openCallAudio("c1") as Outcome.Err).error is RustError.NotFound)
        }

    @Test
    fun theLinkFileRoundTripsAndRefusesABadKey() =
        runBlocking {
            val dir = cache.absolutePath
            val link =
                dev.rovsti.core.model.wa
                    .StoredLink("https://wa.example.com", "i1", key)
            assertNull(
                ops
                    .linkRead(
                        dev.rovsti.core.model.wa
                            .LinkReadRequest(dir),
                    ).getOrOk()
                    .link,
            )
            ops
                .linkWrite(
                    dev.rovsti.core.model.wa
                        .LinkWriteRequest(dir, link),
                ).getOrOk()
            assertEquals(
                link,
                ops
                    .linkRead(
                        dev.rovsti.core.model.wa
                            .LinkReadRequest(dir),
                    ).getOrOk()
                    .link,
            )
            val bad =
                ops.linkWrite(
                    dev.rovsti.core.model.wa
                        .LinkWriteRequest(dir, link.copy(key = "rwk_short")),
                )
            assertTrue((bad as Outcome.Err).error is RustError.Invalid)
            ops
                .linkWrite(
                    dev.rovsti.core.model.wa
                        .LinkWriteRequest(dir, null),
                ).getOrOk()
            assertNull(
                ops
                    .linkRead(
                        dev.rovsti.core.model.wa
                            .LinkReadRequest(dir),
                    ).getOrOk()
                    .link,
            )
        }

    @Test
    fun theEventStreamDeliversEventsInOrderAndReleaseStopsIt() =
        runBlocking {
            fake.wsScript =
                listOf(
                    """{"seq":1,"at_ms":10,"event":{"type":"message","id":"m1","chat":"1@s.whatsapp.net","sender":null,"from_me":false,"timestamp_ms":9,"body":{"text":"selam"},"reactions":[]}}""",
                    """{"seq":2,"at_ms":11,"event":{"type":"call","call_id":"c1","peer":"1@s.whatsapp.net","peer_name":null,"direction":"in","phase":"ringing","is_video":false}}""",
                    """{"seq":3,"at_ms":12,"event":{"type":"key_revoked"}}""",
                )
            val feed =
                async(start = CoroutineStart.UNDISPATCHED) { WaEvents(TestRust.events).feed("i1").take(4).toList() }
            gateway().use { wa ->
                val stream = wa.startEvents("i1", after = 0).getOrOk()
                val got = withTimeout(10_000) { feed.await() }
                assertEquals(WaStreamEvent.Connected, (got[0] as WaFeed.Stream).event)
                val events = got.drop(1).map { ((it as WaFeed.Stream).event as WaStreamEvent.Event) }
                assertEquals(listOf(1L, 2L, 3L), events.map { it.seq })
                val message = events[0].event as GatewayEvent.Message
                assertEquals(MessageBody.Text("selam"), message.body)
                assertEquals(CallPhase.Ringing, (events[1].event as GatewayEvent.Call).phase)
                assertEquals(GatewayEvent.KeyRevoked, events[2].event)
                assertEquals("/v1/events?after=0", fake.seen.first { it.path.startsWith("/v1/events") }.path)
                assertTrue(stream.reconnectNow() is Outcome.Ok)
                stream.close()
                assertTrue((stream.reconnectNow() as Outcome.Err).error is RustError.NotFound)
            }
        }

    @Test
    fun everyHandleTypeRefusesTheMainThread() {
        fake.route("GET", "/v1/instance", json("""{"instance_id":"i1","state":"connected"}"""))
        val wa = gateway()
        try {
            RustThreading.enable(MainThreadCheck { true })
            try {
                runBlocking { wa.status() }
                fail("a native call on the main thread must throw")
            } catch (e: IllegalStateException) {
                assertNotNull(e.message)
                assertTrue(e.message.orEmpty().contains("main thread"))
            }
        } finally {
            RustThreading.enable(MainThreadCheck { false })
            wa.close()
        }
    }

    private fun <T> Outcome<T>.getOrOk(): T = (this as? Outcome.Ok)?.value ?: error("failed: $this")
}
