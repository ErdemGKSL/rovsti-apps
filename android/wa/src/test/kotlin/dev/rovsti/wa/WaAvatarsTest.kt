package dev.rovsti.wa

import coil3.ImageLoader
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.model.wa.InstanceState
import dev.rovsti.core.model.wa.InstanceStatus
import dev.rovsti.core.model.wa.PictureAnswer
import dev.rovsti.core.model.wa.StoredLink
import dev.rovsti.core.model.wa.WaProfile
import dev.rovsti.platform.PlatformEvent
import dev.rovsti.wa.avatars.AvatarBook
import dev.rovsti.wa.avatars.AvatarCodec
import dev.rovsti.wa.avatars.WaAvatar
import dev.rovsti.wa.avatars.WaAvatarFetcher
import dev.rovsti.wa.avatars.WaAvatars
import dev.rovsti.wa.avatars.sampleSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaAvatarsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val link = StoredLink("https://wa.example.com", "inst1", "rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU")
    private val push = MutableSharedFlow<PlatformEvent>(extraBufferCapacity = 8)
    private val ada = "905551112233@s.whatsapp.net"
    private val bob = "905559998877@s.whatsapp.net"
    private val cem = "905550001122@s.whatsapp.net"

    /** Keeps bytes that start with "JPEG" as they are; anything else is not an image. */
    private val codec = AvatarCodec { bytes, _ -> bytes.takeIf { it.decodeToString().startsWith("JPEG") } }

    private fun image(
        id: String,
        body: String = "JPEG-$id",
    ) = Outcome.Ok(
        PictureAnswer(
            "image",
            id = id,
            size = body.length.toLong(),
            dataB64 = Base64.getEncoder().encodeToString(body.encodeToByteArray()),
        ),
    )

    private fun rateLimited() =
        Outcome.Err(RustError.Invalid("slow down", JsonObject(mapOf("code" to JsonPrimitive("rate_limited")))))

    private class Rig(
        val fake: FakeWaGateway,
        val avatars: WaAvatars,
        val dir: File,
        val invalidated: MutableList<String>,
    )

    private fun TestScope.rig(
        fake: FakeWaGateway =
            FakeWaGateway(link = link).apply {
                status =
                    Outcome.Ok(InstanceStatus("inst1", InstanceState.Connected, ownJid = "905550000000@s.whatsapp.net"))
            },
        dir: File = tmp.newFolder("wa-avatars"),
        start: Boolean = false,
    ): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultWaRepository(WaLinkStore(fake.ops), backgroundScope, fake.opener, fake.waEvents, push)
        repo.start()
        val invalidated = mutableListOf<String>()
        val avatars =
            WaAvatars(
                repo,
                fake.ops,
                dir,
                backgroundScope,
                io = dispatcher,
                codec = codec,
                clock = { testScheduler.currentTime },
                invalidate = { invalidated += it },
            )
        if (start) avatars.start()
        runCurrent()
        return Rig(fake, avatars, dir, invalidated)
    }

    @Test fun twoWantsForTheSameJidMakeOneFetch() =
        runTest {
            val fake =
                FakeWaGateway(link = link).apply {
                    status = Outcome.Ok(InstanceStatus("inst1", InstanceState.Connected))
                    picture = { image("p1") }
                }
            val r = rig(fake)
            r.avatars.want(ada, "p1")
            r.avatars.want(ada, "p1")
            r.avatars.want(ada)
            runCurrent()
            assertEquals(1, fake.count("wa.picture"))
            // Now stored and current: asking again fetches nothing.
            r.avatars.want(ada, "p1")
            runCurrent()
            assertEquals(1, fake.count("wa.picture"))
            assertEquals("JPEG-p1", r.avatars.file(ada)!!.readText())
            assertEquals(listOf(ada), r.invalidated)
        }

    @Test fun aRateLimitHoldsEveryFetchUntilTheBackoffPasses() =
        runTest {
            val fake =
                FakeWaGateway(link = link).apply {
                    status = Outcome.Ok(InstanceStatus("inst1", InstanceState.Connected))
                    picture = { rateLimited() }
                }
            val r = rig(fake)
            r.avatars.want(ada, "p1")
            runCurrent()
            assertEquals(1, fake.count("wa.picture"))
            val before = r.avatars.revision.value

            // 5 s of back-off: other jids do not fetch.
            fake.picture = { image("p2") }
            r.avatars.want(bob, "p2")
            advanceTimeBy(4_900)
            r.avatars.want(cem, "p3")
            runCurrent()
            assertEquals("no fetch during the back-off", 1, fake.count("wa.picture"))

            // The wake-up moves the revision once the back-off is over; rows ask again then.
            advanceTimeBy(300)
            runCurrent()
            assertTrue("revision moved at the end of the back-off", r.avatars.revision.value > before)
            r.avatars.want(bob, "p2")
            runCurrent()
            assertEquals(2, fake.count("wa.picture"))
        }

    @Test fun theBackoffDoublesOnRepeatedRateLimitsAndResetsAfterAnAnswer() {
        val book = AvatarBook()
        val job1 = book.request("a", null, 0)!!
        book.arrived(job1, AvatarBook.Found.RateLimited, 0)
        assertEquals(5_000L, book.backoffUntilMs)
        assertNull(book.request("b", null, 4_999))
        val job2 = book.request("b", null, 5_000)!!
        book.arrived(job2, AvatarBook.Found.RateLimited, 5_000)
        assertEquals(5_000L + 10_000L, book.backoffUntilMs)
        val job3 = book.request("c", null, 15_000)!!
        book.arrived(job3, AvatarBook.Found.Image("1", verified = true), 15_000)
        assertFalse(book.backoffActive(15_001))
        val job4 = book.request("d", null, 15_001)!!
        book.arrived(job4, AvatarBook.Found.RateLimited, 15_001)
        assertEquals("reset: back to the minimum", 15_001L + 5_000L, book.backoffUntilMs)
    }

    @Test fun atMostThreeFetchesRunAtOnce() {
        val book = AvatarBook()
        val jobs = listOf("a", "b", "c").map { book.request(it, null, 0) }
        assertTrue(jobs.all { it != null })
        assertNull(book.request("d", null, 0))
        book.arrived(jobs[0]!!, AvatarBook.Found.None, 0)
        assertNotNull(book.request("d", null, 0))
    }

    @Test fun aChangedPictureIdFetchesAgainAndDropsTheOldFile() =
        runTest {
            val fake =
                FakeWaGateway(link = link).apply {
                    status = Outcome.Ok(InstanceStatus("inst1", InstanceState.Connected))
                    picture = { image("p1") }
                }
            val r = rig(fake)
            r.avatars.want(ada, "p1")
            runCurrent()
            fake.picture = { image("p2") }
            r.avatars.want(ada, "p2")
            runCurrent()
            assertEquals(2, fake.count("wa.picture"))
            assertEquals(listOf(FakeAvatarsPlan.fileName(ada, "p2")), r.dir.list()!!.toList())
        }

    @Test fun thePictureOnDiskIsUsedWithoutAskingTheGatewayWhenTheIdMatches() =
        runTest {
            val dir = tmp.newFolder("wa-avatars")
            File(dir, FakeAvatarsPlan.fileName(ada, "p1")).writeText("JPEG-p1")
            val fake =
                FakeWaGateway(link = link).apply {
                    status =
                        Outcome.Ok(InstanceStatus("inst1", InstanceState.Connected))
                }
            val r = rig(fake, dir)
            r.avatars.want(ada, "p1")
            runCurrent()
            assertEquals(0, fake.count("wa.picture"))
        }

    @Test fun pruneDeletesTheFilesThePlanListsOldestFirst() =
        runTest {
            val dir = tmp.newFolder("wa-avatars")
            val old =
                File(dir, FakeAvatarsPlan.fileName(ada, "1")).apply {
                    writeBytes(ByteArray(5 * 1024 * 1024))
                    setLastModified(1_000)
                }
            val mid =
                File(dir, FakeAvatarsPlan.fileName(bob, "1")).apply {
                    writeBytes(ByteArray(3 * 1024 * 1024))
                    setLastModified(2_000)
                }
            val new =
                File(dir, FakeAvatarsPlan.fileName(cem, "1")).apply {
                    writeBytes(ByteArray(2 * 1024 * 1024))
                    setLastModified(3_000)
                }
            val r = rig(dir = dir)
            r.avatars.prune()
            assertFalse("the oldest goes first", old.exists())
            assertTrue(mid.exists())
            assertTrue(new.exists())
            assertEquals("one plan call for the whole directory", 1, r.fake.count("wa.avatars_plan"))
        }

    @Test fun theFetcherAnswersFromTheCache() =
        runTest {
            val fake =
                FakeWaGateway(link = link).apply {
                    status = Outcome.Ok(InstanceStatus("inst1", InstanceState.Connected))
                    picture = { image("p1") }
                }
            val r = rig(fake)
            r.avatars.want(ada, "p1")
            runCurrent()
            val context = RuntimeEnvironment.getApplication()
            val factory = WaAvatarFetcher.Factory(r.avatars)
            val loader = ImageLoader.Builder(context).build()
            val result = factory.create(WaAvatar(ada), Options(context), loader).fetch()
            assertTrue(result is SourceFetchResult)
            result as SourceFetchResult
            assertEquals("image/jpeg", result.mimeType)
            assertEquals("wa-avatar:$ada", WaAvatar(ada).key)
            assertNull("no stored picture, no result", factory.create(WaAvatar(bob), Options(context), loader).fetch())
        }

    @Test fun profilesAndNamesComeThroughTheGateway() =
        runTest {
            val fake =
                FakeWaGateway(link = link).apply {
                    status = Outcome.Ok(InstanceStatus("inst1", InstanceState.Connected))
                    profiles =
                        { jids ->
                            Outcome.Ok(
                                jids.map {
                                    WaProfile(
                                        it,
                                        pushName =
                                            "Ada L".takeIf { _ ->
                                                it == ada
                                            },
                                        pictureId = "p9",
                                        fetchedMs = 5,
                                    )
                                },
                            )
                        }
                }
            val r = rig(fake)
            r.avatars.wantProfile(ada)
            r.avatars.wantProfile(ada)
            r.avatars.wantProfile(bob)
            advanceTimeBy(100)
            runCurrent()
            assertEquals("batched into one call", 1, fake.count("wa.profiles"))
            assertEquals("Ada L", r.avatars.profile(ada)?.pushName)
            assertEquals("Ada L", r.avatars.name(ada))
            assertEquals("no WhatsApp name: the number", "905559998877", r.avatars.name(bob))
        }

    @Test fun aProfileEventMakesTheHeldPictureStale() =
        runTest {
            val fake =
                FakeWaGateway(link = link).apply {
                    status = Outcome.Ok(InstanceStatus("inst1", InstanceState.Connected))
                    picture = { image("p1") }
                }
            val r = rig(fake, start = true)
            r.avatars.want(ada, "p1")
            runCurrent()
            fake.picture = { image("p2") }
            fake.emitEvent("inst1", 1, GatewayEvent.Profile(ada, pictureId = "p2", fetchedMs = 9))
            runCurrent()
            r.avatars.want(ada)
            runCurrent()
            assertEquals(2, fake.count("wa.picture"))
            assertEquals(FakeAvatarsPlan.fileName(ada, "p2"), r.avatars.file(ada)!!.name)
        }

    @Test fun anUndecodableAnswerIsAFailureNotAFile() =
        runTest {
            val fake =
                FakeWaGateway(link = link).apply {
                    status = Outcome.Ok(InstanceStatus("inst1", InstanceState.Connected))
                    picture = { image("p1", body = "<html>") }
                }
            val r = rig(fake)
            r.avatars.want(ada, "p1")
            runCurrent()
            assertEquals(0, r.dir.list()!!.size)
            r.avatars.want(ada, "p1")
            runCurrent()
            assertEquals("a failure is retried only after a minute", 1, fake.count("wa.picture"))
            advanceTimeBy(61_000)
            r.avatars.want(ada, "p1")
            runCurrent()
            assertEquals(2, fake.count("wa.picture"))
        }

    @Test fun sampleSizeKeepsTheShortSideAtTheSlot() {
        assertEquals(1, sampleSize(100, 100, 128))
        assertEquals(1, sampleSize(255, 400, 128))
        assertEquals(2, sampleSize(256, 640, 128))
        assertEquals(8, sampleSize(1024, 2048, 128))
        assertEquals(1, sampleSize(0, 0, 128))
    }
}
