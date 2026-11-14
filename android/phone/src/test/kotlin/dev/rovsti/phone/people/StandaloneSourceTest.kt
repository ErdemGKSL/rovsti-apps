package dev.rovsti.phone.people

import dev.rovsti.core.Envelope
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.wa.InstanceState
import dev.rovsti.core.model.wa.InstanceStatus
import dev.rovsti.core.model.wa.JidsBody
import dev.rovsti.core.model.wa.WaProfile
import dev.rovsti.core.model.wa.WaUser
import dev.rovsti.core.ops.WaGateway
import dev.rovsti.people.PeopleIdentitySource
import dev.rovsti.people.PeopleIndex
import dev.rovsti.wa.FakeWaGateway
import dev.rovsti.wa.FakeWaRepository
import dev.rovsti.wa.WaLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * X.1d: Phone without Contacts. [StandalonePeople] syncs the gateway into memory when WhatsApp is linked (and at
 * start, once the link is read), draws initials until then, forgets everything on unlink, and writes nothing to disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StandaloneSourceTest {
    private val gw = FakeWaGateway()
    private val onWhatsapp = setOf("905321112233@s.whatsapp.net", "4915123456789@s.whatsapp.net")
    private val asked = mutableListOf<List<String>>()
    private val wanted = mutableListOf<Pair<String, String?>>()
    private val pictures = mutableMapOf<String, File>()
    private val pictureChanges = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    private val repo = FakeWaRepository()
    private val dir: File = Files.createTempDirectory("standalone").toFile().apply { deleteOnExit() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

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
            Outcome.Ok(jids.map { WaProfile(it, pushName = "Push ${it.take(4)}", pictureId = "pic-${it.take(4)}") })
        }
        repo.gatewayOpener = {
            (WaGateway.open("https://gateway.example", "key", null, gw.ops, gw.audio, release = {}) as? Outcome.Ok)
                ?.value
        }
    }

    @After fun stop() = scope.cancel()

    private fun people() =
        StandalonePeople(
            wa = repo,
            numbers = { listOf("+905321112233", "+4915123456789", "+4930111222", "not a number") },
            wantPicture = { jid, id -> wanted += jid to id },
            pictureFile = { jid -> pictures[jid] },
            pictureChanges = pictureChanges,
            scope = scope,
        )

    private fun connect() = repo.setState(WaLinkState.Connected(InstanceStatus("inst", InstanceState.Connected)))

    private fun filesBefore() = dir.walkTopDown().count()

    @Test fun nothingIsSyncedAndNothingIsKnownWhileWhatsAppIsNotLinked() =
        runBlocking {
            val p = people()
            p.start()
            assertTrue(p.source.load().isEmpty())
            assertTrue(asked.isEmpty())
            assertEquals(false, p.sync())
        }

    @Test fun linkingSyncsWhoIsOnWhatsAppIntoMemoryAndAsksThePicturesFromTheAvatarCache() =
        runBlocking {
            val p = people()
            p.start()
            connect()
            val rows = p.source.load()
            assertEquals(
                setOf("905321112233@s.whatsapp.net", "4915123456789@s.whatsapp.net"),
                rows.map { it.jid }.toSet(),
            )
            // Only plausible numbers go to the gateway, as phone JIDs.
            assertEquals(
                setOf("905321112233@s.whatsapp.net", "4915123456789@s.whatsapp.net", "4930111222@s.whatsapp.net"),
                asked.flatten().toSet(),
            )
            assertEquals(2, wanted.size)
            assertTrue(rows.all { it.onWhatsapp && it.name!!.startsWith("Push") })
        }

    @Test fun theIndexAndTheIdentitySourceAnswerFromMemoryAfterTheSyncAndNotBefore() =
        runBlocking {
            val index = PeopleIndex()
            val ids = PeopleIdentitySource(index)
            val p = people()
            val job = index.attach(scope, p.source)
            p.start()
            assertTrue("initials until it arrives", ids.lookup(listOf("+905321112233")).isEmpty())
            val file = File(dir, "pic.jpg").apply { writeText("x") }
            pictures["905321112233@s.whatsapp.net"] = file
            connect()
            val who = ids.lookup(listOf("+905321112233", "+4930111222"))
            assertEquals(setOf("+905321112233"), who.keys)
            assertEquals("Push 9053", who.getValue("+905321112233").waPushName)
            assertEquals(
                android.net.Uri
                    .fromFile(file)
                    .toString(),
                who.getValue("+905321112233").waPhotoUri,
            )
            job.cancel()
        }

    @Test fun aPictureThatArrivesLaterIsPickedUpWithoutAskingTheGatewayAgain() =
        runBlocking {
            val p = people()
            p.start()
            connect()
            val lookups = asked.size
            val file = File(dir, "late.jpg").apply { writeText("x") }
            pictures["4915123456789@s.whatsapp.net"] = file
            pictureChanges.emit(1)
            assertEquals(lookups, asked.size)
            assertNotNull(p.source.pictureUri(p.source.load().first { it.jid.startsWith("49151") }))
        }

    @Test fun unlinkingForgetsEverything() =
        runBlocking {
            val p = people()
            p.start()
            connect()
            assertEquals(2, p.source.load().size)
            repo.setState(WaLinkState.None)
            assertTrue(p.source.load().isEmpty())
        }

    @Test fun noDiskCacheIsWritten() =
        runBlocking {
            val before = filesBefore()
            val p = people()
            p.start()
            connect()
            p.sync()
            assertEquals("the source keeps nothing on disk", before, filesBefore())
            assertNull(File(dir, "people").takeIf { it.exists() })
        }
}
