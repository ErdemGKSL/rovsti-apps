package dev.rosty.messages.wa

import dev.rosty.core.FakeWaSyncOps
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.getOrThrow
import dev.rosty.core.model.wa.BytesAnswer
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.HistoryStatus
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.model.wa.MediaKind
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.WaSyncApplied
import dev.rosty.core.model.wa.WaSyncEventsRequest
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncReconcileRequest
import dev.rosty.core.model.wa.WaSyncWipeRequest
import dev.rosty.core.ops.WaSyncOps
import dev.rosty.core.ops.WaSyncStore
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.ADA_LID
import dev.rosty.messages.wa.WaTestKit.Companion.INSTANCE
import dev.rosty.messages.wa.WaTestKit.Companion.MERT
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import dev.rosty.platform.system.DataNetwork
import dev.rosty.wa.FakeWaRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The offline hub and what belongs to a link (E6-WA R2): the media files follow the store, another account and an
 * unlink drop everything kept for the link and nothing else does, the history progress is the store's, and a closed
 * socket applies nothing late.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaOfflineLinkTest {
    @get:Rule val folder = TemporaryFolder()

    private val dir get() = File(folder.root, "wa-media")
    private val other = "905550000009@s.whatsapp.net"
    private val cursor = MemoryRekeyCursor()

    private fun TestScope.kit(
        ops: FakeWaSyncOps = FakeWaSyncOps(INSTANCE),
        storeOps: WaSyncOps = ops,
    ) = WaTestKit(backgroundScope, ops, storeOps, mediaDir = dir, rekeys = WaRekeys(cursor))

    private fun status(
        own: String = MERT,
        accountEpoch: Int? = 1,
        history: HistoryStatus? = null,
    ) = InstanceStatus(
        INSTANCE,
        InstanceState.Connected,
        own,
        "Me",
        epoch = "e1",
        accountEpoch = accountEpoch,
        history = history,
    )

    private fun picture(
        kit: WaTestKit,
        id: String,
    ) {
        kit.message(WaTestKit.media(ADA, id, NOW - 60_000, WaSyncKind.Image, "image/jpeg", 2_048))
        kit.remote.files[id] = BytesAnswer(3, dataB64 = "SU1H")
    }

    private fun fileOf(id: String) = File(File(File(File(dir, INSTANCE), "files"), id), "image.jpg")

    /** Something of every kind the app keeps on disk for the instance. */
    private fun fillDirectory(): List<File> {
        val files =
            listOf(
                File(File(dir, INSTANCE), "thumbs/t1.jpg"),
                File(File(dir, INSTANCE), "out/u1/staged.pdf"),
                File(File(dir, INSTANCE), "files/stray/unknown.bin"),
            )
        files.forEach {
            it.parentFile?.mkdirs()
            it.writeText("x")
        }
        return files
    }

    // ---- open

    @Test fun openingTheStoreReconcilesItsFilesAndFetchesWhatIsQueued() =
        runTest {
            val kit = kit()
            picture(kit, "p1")
            val part = File(fileOf("p1").path + ".part").also { it.parentFile?.mkdirs() }
            part.writeText("half")
            // A tap of an earlier run that the process did not live to serve.
            val earlier = WaSyncStore.open(INSTANCE, ops = kit.ops, release = {}).getOrThrow()
            earlier.enqueueMedia(ADA, "p1", NOW, tap = true).getOrThrow()

            kit.offline.start()
            settle()

            assertEquals(1, kit.ops.writes.count { it is WaSyncReconcileRequest })
            assertFalse(part.exists())
            assertEquals("IMG", fileOf("p1").readText())
            assertEquals(listOf("p1"), kit.remote.mediaCalls)
        }

    /** The fake stores a media event as a row of unknown kind; the real store keeps what the gateway sent. */
    private class KeepsMedia(
        private val fake: FakeWaSyncOps,
    ) : WaSyncOps by fake {
        override suspend fun applyEvents(req: WaSyncEventsRequest): Outcome<WaSyncApplied> {
            val applied = fake.applyEvents(req)
            for (event in req.events) {
                val message = event.event as? GatewayEvent.Message ?: continue
                val body = message.body as? MessageBody.Media ?: continue
                val rows = fake.messages.getOrPut(message.chat) { mutableListOf() }
                rows.removeAll { it.id == message.id }
                rows +=
                    WaTestKit.media(
                        message.chat,
                        message.id,
                        message.timestampMs,
                        WaSyncKind.Image,
                        body.mime,
                        body.size,
                    )
            }
            return applied
        }
    }

    @Test fun aPictureThatArrivesOnTheStreamIsFetchedUnderTheRulesOfTheNetwork() =
        runTest {
            val ops = FakeWaSyncOps(INSTANCE)
            val kit = kit(ops, KeepsMedia(ops))
            kit.remote.files["p1"] = BytesAnswer(3, dataB64 = "SU1H")
            kit.remote.files["p2"] = BytesAnswer(3, dataB64 = "SU1H")
            val body = MessageBody.Media(MediaKind.Image, null, "image/jpeg", 3)
            kit.offline.start()
            settle()
            kit.connect()

            kit.device.kind = DataNetwork.Roaming
            kit.remote.emit(1, GatewayEvent.Message("p1", ADA, ADA, false, NOW, body, emptyList()))
            settle()
            assertTrue(kit.remote.mediaCalls.isEmpty())

            kit.device.kind = DataNetwork.Mobile
            kit.remote.emit(2, GatewayEvent.Message("p2", ADA, ADA, false, NOW, body, emptyList()))
            settle()
            assertEquals(listOf("p2"), kit.remote.mediaCalls)
            assertTrue(fileOf("p2").isFile)
        }

    // ---- another account

    @Test fun anotherAccountInTheStatusDropsTheMediaTheStateAndTheCursorThenRefreshes() =
        runTest {
            val kit = kit()
            kit.remote.ownStatus = status()
            kit.chat(ADA, name = "Ada")
            picture(kit, "p1")
            kit.offline.start()
            settle()
            kit.connect()
            settle()
            val store =
                kit.offline.session.value!!
                    .store
            kit.offline.wantMedia(listOf(WaMediaRef(ADA, "p1")))
            settle()
            assertTrue(fileOf("p1").isFile)
            val kept = fillDirectory()
            cursor.write(INSTANCE, 7)
            assertTrue(kit.forgotten.isEmpty())
            val chatCalls = kit.remote.chatCalls

            // The same link, another own number: the gateway says so at the next catch-up.
            kit.remote.ownStatus = status(own = other, accountEpoch = 2)
            kit.remote.emit(WaRemoteEvent.Disconnected)
            kit.connect()
            settle()

            assertEquals(listOf(INSTANCE), kit.forgotten)
            assertFalse(File(dir, INSTANCE).exists())
            assertTrue(kept.none { it.exists() })
            assertEquals(0L, cursor.read(INSTANCE))
            assertTrue(store.chats().getOrThrow().isEmpty())
            // The same store goes on (it emptied itself) and the chat list is fetched again.
            assertTrue(kit.closed.isEmpty())
            assertTrue(kit.wiped.isEmpty())
            assertEquals(chatCalls + 1, kit.remote.chatCalls)
        }

    @Test fun anAccountChangedEventOnTheSocketDoesTheSame() =
        runTest {
            val kit = kit()
            kit.remote.ownStatus = status()
            kit.offline.start()
            settle()
            kit.connect()
            settle()
            val kept = fillDirectory()
            val chatCalls = kit.remote.chatCalls

            kit.remote.emit(1, GatewayEvent.AccountChanged(MERT, other))
            settle()

            assertEquals(listOf(INSTANCE), kit.forgotten)
            assertTrue(kept.none { it.exists() })
            assertEquals(chatCalls + 1, kit.remote.chatCalls)
        }

    @Test fun aResyncAGapAndAMergeNeverDropWhatIsKeptForTheLink() =
        runTest {
            val ops = FakeWaSyncOps(INSTANCE)
            val kit = kit(ops)
            kit.remote.ownStatus = status()
            kit.offline.start()
            settle()
            kit.connect()
            settle()
            val kept = fillDirectory()
            cursor.write(INSTANCE, 0)

            // A resync, a restarted gateway (another epoch, the same account) and two chats becoming one.
            kit.remote.emit(1, GatewayEvent.Resync())
            kit.remote.ownStatus = status().copy(epoch = "e2")
            kit.remote.emit(WaRemoteEvent.Disconnected)
            kit.connect()
            kit.remote.emit(2, GatewayEvent.Hello("e2"))
            kit.remote.emit(3, GatewayEvent.IdentityMapped(ADA_LID, ADA, "usync", NOW))
            // The link itself reopening its socket (another key) is not an unlink either.
            kit.link.value = WaStoreLink(INSTANCE, socketKey = "new key")
            settle()

            assertTrue(kit.forgotten.isEmpty())
            assertTrue(kit.wiped.isEmpty())
            assertTrue(kept.all { it.isFile })
        }

    // ---- unlink

    @Test fun unlinkWipesTheStoreItsMediaAndTheKeptState() =
        runTest {
            val kit = kit()
            picture(kit, "p1")
            kit.offline.start()
            settle()
            kit.connect()
            kit.offline.wantMedia(listOf(WaMediaRef(ADA, "p1")))
            settle()
            assertTrue(fileOf("p1").isFile)
            val kept = fillDirectory()
            val elsewhere = File(dir, "inst2/files/x/image.jpg").also { it.parentFile?.mkdirs() }
            elsewhere.writeText("theirs")
            cursor.write(INSTANCE, 7)

            val removed =
                kit.offline.unlink {
                    // The repository forgets the link; the hub hears of it like of any link change.
                    kit.link.value = null
                    Outcome.Ok(Unit)
                }
            settle()

            assertTrue(removed is Outcome.Ok)
            assertNull(kit.offline.session.value)
            assertEquals(listOf(INSTANCE), kit.wiped)
            assertEquals(
                INSTANCE,
                kit.ops.writes
                    .filterIsInstance<WaSyncWipeRequest>()
                    .single()
                    .instanceId,
            )
            assertEquals(listOf(INSTANCE), kit.forgotten)
            assertFalse(File(dir, INSTANCE).exists())
            assertTrue(kept.none { it.exists() })
            assertEquals(0L, cursor.read(INSTANCE))
            // Another instance's files are not this link's.
            assertTrue(elsewhere.isFile)
            assertEquals(1, kit.closed.size)
        }

    @Test fun unlinkInTheSettingsGoesThroughTheHub() =
        runTest {
            val kit = kit()
            kit.offline.start()
            settle()
            val kept = fillDirectory()
            val real = FakeWaRepository()
            val repo = UnlinkingWaRepository(real) { kit.offline }

            // The settings' "Unlink" calls the repository's `forget`; nothing else of it is changed.
            assertTrue(repo.forget() is Outcome.Ok)
            settle()

            assertEquals(listOf("forget"), real.log)
            assertEquals(listOf(INSTANCE), kit.wiped)
            assertEquals(listOf(INSTANCE), kit.forgotten)
            assertTrue(kept.none { it.exists() })
            assertNull(kit.offline.session.value)
        }

    @Test fun anUnlinkThatFailedDeletesNothing() =
        runTest {
            val kit = kit()
            kit.offline.start()
            settle()
            val kept = fillDirectory()

            val removed = kit.offline.unlink { Outcome.Err(RustError.Io("the link file is read only")) }
            settle()

            assertTrue(removed is Outcome.Err)
            assertNotNull(kit.offline.session.value)
            assertTrue(kit.wiped.isEmpty())
            assertTrue(kit.forgotten.isEmpty())
            assertTrue(kept.all { it.isFile })
        }

    @Test fun aLinkThatGoesAwayWithoutAnUnlinkKeepsItsStoreAndFiles() =
        runTest {
            val kit = kit()
            kit.offline.start()
            settle()
            val kept = fillDirectory()

            // The link could not be read, or another instance was linked: the store is closed, not deleted.
            kit.link.value = null
            settle()

            assertNull(kit.offline.session.value)
            assertTrue(kit.wiped.isEmpty())
            assertTrue(kit.forgotten.isEmpty())
            assertTrue(kept.all { it.isFile })
        }

    // ---- history

    @Test fun theHistoryProgressComesFromTheStoreAndSurvivesARestart() =
        runTest {
            val ops = FakeWaSyncOps(INSTANCE)
            val first = kit(ops)
            first.remote.ownStatus =
                status(history = HistoryStatus(HistoryStatus.SYNCING, progress = 40, chats = 12, messages = 3_400))
            first.offline.start()
            settle()
            first.connect()
            settle()
            assertEquals(WaHistoryUi(40, 12, 3_400), WaHistoryUi.of(first.offline.history.value))

            // A chunk arrives on the socket: the row moves without another status.
            first.remote.emit(1, GatewayEvent.HistorySynced(3, progress = 55, messages = 600))
            settle()
            assertEquals(WaHistoryUi(55, 12, 4_000), WaHistoryUi.of(first.offline.history.value))

            // The process dies; the next one has no gateway yet and still shows where the sync was.
            val second = WaTestKit(backgroundScope, ops, mediaDir = dir)
            second.remote.down = true
            second.offline.start()
            settle()
            assertEquals(WaHistoryUi(55, 12, 4_000), WaHistoryUi.of(second.offline.history.value))

            // Done: the row goes away.
            second.remote.down = false
            second.remote.ownStatus =
                status(history = HistoryStatus(HistoryStatus.DONE, progress = 100, chats = 20, messages = 9_000))
            second.connect()
            settle()
            assertNull(WaHistoryUi.of(second.offline.history.value))
            assertEquals(
                9_000L,
                second.offline.history.value
                    ?.messages,
            )
        }

    // ---- a closed socket

    /** A store whose first `apply_events` waits: the socket's next events pile up behind it. */
    private class Gated(
        private val fake: FakeWaSyncOps,
    ) : WaSyncOps by fake {
        val gate = CompletableDeferred<Unit>()
        var entered = 0

        override suspend fun applyEvents(req: WaSyncEventsRequest): Outcome<WaSyncApplied> {
            if (entered++ == 0) gate.await()
            return fake.applyEvents(req)
        }
    }

    @Test fun whatAClosedSocketStillHeldIsDroppedNotAppliedLate() =
        runTest {
            val ops = FakeWaSyncOps(INSTANCE)
            val gated = Gated(ops)
            val kit = kit(ops, gated)
            kit.offline.start()
            settle()
            kit.connect()
            settle()

            // One batch is being applied and two more events wait in the socket when the link reopens it.
            kit.remote.emit(1, WaTestKit.incoming(ADA, "first", 1_000, "being applied"))
            settle()
            assertEquals(1, gated.entered)
            kit.remote.emit(2, WaTestKit.incoming(ADA, "late-1", 2_000, "held by the old socket"))
            kit.remote.emit(3, WaTestKit.incoming(ADA, "late-2", 3_000, "held by the old socket"))
            kit.link.value = WaStoreLink(INSTANCE, socketKey = "new key")
            settle()
            gated.gate.complete(Unit)
            settle()

            // Nothing of the closed socket reached the store, not even after the store answered again.
            assertTrue(ops.writes.none { it is WaSyncEventsRequest })
            assertEquals(2, kit.remote.socketsAfter.size)
            // The new socket replays from the store's `seq` and its events are applied, once.
            kit.remote.emit(1, WaTestKit.incoming(ADA, "first", 1_000, "replayed"))
            settle()
            assertEquals(
                listOf(1L),
                ops.writes
                    .filterIsInstance<WaSyncEventsRequest>()
                    .flatMap { it.events }
                    .map { it.seq },
            )
        }
}
