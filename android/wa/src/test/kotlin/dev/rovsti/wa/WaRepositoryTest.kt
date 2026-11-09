package dev.rovsti.wa

import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.model.wa.InstanceState
import dev.rovsti.core.model.wa.InstanceStatus
import dev.rovsti.core.model.wa.MessageBody
import dev.rovsti.core.model.wa.RichChat
import dev.rovsti.core.model.wa.RichMessage
import dev.rovsti.core.model.wa.StoredLink
import dev.rovsti.core.model.wa.TypingState
import dev.rovsti.core.model.wa.WaStreamEvent
import dev.rovsti.platform.PlatformEvent
import dev.rovsti.platform.wa.WaPush
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WaRepositoryTest {
    private val link = StoredLink("https://wa.example.com", "inst1", "rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU")
    private val push = MutableSharedFlow<PlatformEvent>(extraBufferCapacity = 8)

    private fun status(state: InstanceState = InstanceState.Connected) =
        InstanceStatus("inst1", state, ownJid = "905551112233@s.whatsapp.net", pushName = "Ada")

    private fun chat(jid: String) = RichChat(jid = jid, name = jid, unread = 0, isGroup = false)

    private fun message(id: String) =
        RichMessage(
            id,
            "c1@s.whatsapp.net",
            null,
            fromMe = false,
            timestampMs = 1,
            body = MessageBody.Text(id),
            reactions = emptyList(),
        )

    private fun TestScope.repo(fake: FakeWaGateway) =
        DefaultWaRepository(WaLinkStore(fake.ops), backgroundScope, fake.opener, fake.waEvents, push)

    private val typing = GatewayEvent.Typing("c1@s.whatsapp.net", "c1@s.whatsapp.net", TypingState.Typing)

    @Test fun noLinkGivesNone() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeWaGateway()
            val repo = repo(fake)
            repo.start()
            assertEquals(WaLinkState.None, repo.linkState.value)
            assertNull(repo.gateway())
            assertEquals(0, fake.count("wa.open"))
        }

    @Test fun storedLinkAndStatusOkGiveConnected() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeWaGateway(link = link).apply { status = Outcome.Ok(status()) }
            val repo = repo(fake)
            repo.start()
            assertEquals(WaLinkState.Connected(status()), repo.linkState.value)
            assertEquals(1, fake.count("wa.events_start"))
            // The stream starts for this instance, from the beginning.
            assertTrue(
                fake.core.calls
                    .single { it.op == "wa.events_start" }
                    .request
                    .toString()
                    .contains("inst1"),
            )
        }

    @Test fun anUnreachableGatewayIsAnError() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeWaGateway(link = link).apply { openError = RustError.Network("no route") }
            val repo = repo(fake)
            repo.start()
            assertEquals(WaLinkState.Error("no route"), repo.linkState.value)
        }

    @Test fun linkingStatusAndQrEventsGiveLinking() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeWaGateway(link = link).apply { status = Outcome.Ok(status(InstanceState.Linking)) }
            val repo = repo(fake)
            repo.start()
            assertEquals(WaLinkState.Linking(), repo.linkState.value)
            fake.emitEvent("inst1", 1, GatewayEvent.Qr("2@abc", 20_000))
            assertEquals(WaLinkState.Linking(qr = "2@abc"), repo.linkState.value)
            fake.emitEvent("inst1", 2, GatewayEvent.PairCode("ABCD-1234", 20_000))
            assertEquals(WaLinkState.Linking(code = "ABCD-1234"), repo.linkState.value)
        }

    @Test fun loggedOutAndBannedEventsMoveTheState() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeWaGateway(link = link).apply { status = Outcome.Ok(status()) }
            val repo = repo(fake)
            repo.start()
            fake.emitEvent("inst1", 1, GatewayEvent.State("inst1", InstanceState.LoggedOut))
            assertEquals(WaLinkState.LoggedOut, repo.linkState.value)
            fake.emitEvent(
                "inst1",
                2,
                GatewayEvent.State("inst1", InstanceState.Connected, "905551112233@s.whatsapp.net"),
            )
            assertTrue(repo.linkState.value is WaLinkState.Connected)
            fake.emitEvent("inst1", 3, GatewayEvent.State("inst1", InstanceState.Banned))
            assertEquals(WaLinkState.Banned, repo.linkState.value)
        }

    @Test fun aRevokedKeyIsAnError() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeWaGateway(link = link).apply { status = Outcome.Ok(status()) }
            val repo = repo(fake)
            repo.start()
            fake.emitEvent("inst1", 1, GatewayEvent.KeyRevoked)
            assertTrue(repo.linkState.value is WaLinkState.Error)
        }

    @Test fun eventsOfOtherInstancesAreIgnored() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeWaGateway(link = link).apply { status = Outcome.Ok(status()) }
            val repo = repo(fake)
            repo.start()
            val seen = mutableListOf<GatewayEvent>()
            backgroundScope.launch { repo.events.toList(seen) }
            fake.emitEvent("other", 1, GatewayEvent.State("other", InstanceState.Banned))
            fake.emitEvent("inst1", 1, typing)
            assertEquals(WaLinkState.Connected(status()), repo.linkState.value)
            assertEquals(listOf<GatewayEvent>(typing), seen)
        }

    @Test fun waPushCallsReconnectExactlyOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeWaGateway(link = link).apply { status = Outcome.Ok(status()) }
            val repo = repo(fake)
            repo.start()
            push.emit(PlatformEvent("call_log_changed", "{}"))
            assertEquals(0, fake.count("wa.events_reconnect_now"))
            push.emit(PlatformEvent(WaPush.EVENT, "{}"))
            assertEquals(1, fake.count("wa.events_reconnect_now"))
            // It went to the stream handle, not the gateway handle.
            assertTrue(
                fake.core.calls
                    .single {
                        it.op == "wa.events_reconnect_now"
                    }.request
                    .toString()
                    .contains("${FakeWaGateway.STREAM}"),
            )
        }

    @Test fun wakeWithoutAStreamIsNotFound() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = repo(FakeWaGateway())
            repo.start()
            assertTrue((repo.wake() as Outcome.Err).error is RustError.NotFound)
        }

    @Test fun aGapRefetchesTheChatsOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val fake =
                FakeWaGateway(link = link).apply {
                    status = Outcome.Ok(status())
                    chats = listOf(chat("a"))
                }
            val repo = repo(fake)
            repo.start()
            val lists = mutableListOf<List<RichChat>>()
            backgroundScope.launch { repo.chats().toList(lists) }
            assertEquals(1, fake.count("wa.chats_with_meta"))
            fake.emitEvent("inst1", 1, typing)
            fake.emitEvent("inst1", 2, typing)
            assertEquals("no gap, no refetch", 1, fake.count("wa.chats_with_meta"))
            fake.chats = listOf(chat("a"), chat("b"))
            fake.emitEvent("inst1", 5, typing)
            assertEquals(2, fake.count("wa.chats_with_meta"))
            assertEquals(listOf(listOf(chat("a")), listOf(chat("a"), chat("b"))), lists)
            // The next event follows the gap without one of its own.
            fake.emitEvent("inst1", 6, typing)
            assertEquals(2, fake.count("wa.chats_with_meta"))
        }

    @Test fun aResyncEventRefetchesOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeWaGateway(link = link).apply { status = Outcome.Ok(status()) }
            val repo = repo(fake)
            repo.start()
            backgroundScope.launch { repo.chats().toList(mutableListOf()) }
            val before = fake.count("wa.chats_with_meta")
            fake.emitEvent("inst1", 1, GatewayEvent.Resync)
            assertEquals(before + 1, fake.count("wa.chats_with_meta"))
        }

    @Test fun aGapThatCarriesAResyncStillRefetchesOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeWaGateway(link = link).apply { status = Outcome.Ok(status()) }
            val repo = repo(fake)
            repo.start()
            backgroundScope.launch { repo.chats().toList(mutableListOf()) }
            fake.emitEvent("inst1", 1, typing)
            val before = fake.count("wa.chats_with_meta")
            fake.emitEvent("inst1", 9, GatewayEvent.Resync)
            assertEquals(before + 1, fake.count("wa.chats_with_meta"))
        }

    @Test fun messagesReloadOnlyForTheirChat() =
        runTest(UnconfinedTestDispatcher()) {
            val fake =
                FakeWaGateway(link = link).apply {
                    status = Outcome.Ok(status())
                    messages = mapOf("c1@s.whatsapp.net" to listOf(message("m1")))
                }
            val repo = repo(fake)
            repo.start()
            val lists = mutableListOf<List<RichMessage>>()
            backgroundScope.launch { repo.messages("c1@s.whatsapp.net").toList(lists) }
            assertEquals(listOf(listOf(message("m1"))), lists)
            val other = GatewayEvent.MessageDeleted("other@s.whatsapp.net", "x")
            fake.emitEvent("inst1", 1, other)
            assertEquals(1, fake.count("wa.messages_with_meta"))
            fake.emitEvent("inst1", 2, GatewayEvent.MessageDeleted("c1@s.whatsapp.net", "m1"))
            assertEquals(2, fake.count("wa.messages_with_meta"))
            fake.emitEvent("inst1", 4, GatewayEvent.Resync)
            assertEquals(3, fake.count("wa.messages_with_meta"))
        }

    @Test fun chatsWithoutALinkAreEmpty() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = repo(FakeWaGateway())
            val lists = mutableListOf<List<RichChat>>()
            backgroundScope.launch { repo.chats().toList(lists) }
            assertEquals(listOf(emptyList<RichChat>()), lists)
        }

    @Test fun forgetClosesTheGatewayAndTheStream() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeWaGateway(link = link).apply { status = Outcome.Ok(status()) }
            val repo = repo(fake)
            repo.start()
            val gateway = checkNotNull(repo.gateway())
            assertEquals(Outcome.Ok(status()), gateway.status())
            assertEquals(Outcome.Ok(Unit), repo.forget())
            assertEquals(setOf(FakeWaGateway.GATEWAY, FakeWaGateway.STREAM), fake.released.toSet())
            assertTrue(gateway.released)
            assertTrue((gateway.status() as Outcome.Err).error is RustError.NotFound)
            assertEquals(WaLinkState.None, repo.linkState.value)
            assertNull(fake.link)
            assertNull(repo.gateway())
            // Events of the closed stream no longer move anything.
            fake.emitEvent("inst1", 1, GatewayEvent.State("inst1", InstanceState.Banned))
            assertEquals(WaLinkState.None, repo.linkState.value)
        }

    @Test fun relinkToStoresTheLinkAndReconnects() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeWaGateway().apply { status = Outcome.Ok(status()) }
            val repo = repo(fake)
            repo.start()
            assertEquals(WaLinkState.None, repo.linkState.value)
            assertEquals(Outcome.Ok(Unit), repo.relinkTo(link))
            assertEquals(link, fake.link)
            assertEquals(WaLinkState.Connected(status()), repo.linkState.value)
            // A second link replaces the first: the old handles are released.
            assertEquals(Outcome.Ok(Unit), repo.relinkTo(link.copy(instanceId = "inst2")))
            assertEquals(1, fake.released.count { it == FakeWaGateway.GATEWAY })
            assertEquals(2, fake.count("wa.open"))
        }

    @Test fun startIsIdempotent() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeWaGateway(link = link).apply { status = Outcome.Ok(status()) }
            val repo = repo(fake)
            repo.start()
            repo.start()
            push.emit(PlatformEvent(WaPush.EVENT, "{}"))
            assertEquals(1, fake.count("wa.open"))
            assertEquals(1, fake.count("wa.events_reconnect_now"))
        }
}
