package dev.rosty.core.rust

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.wa.ChatMeta
import dev.rosty.core.model.wa.DELETED_BY_ME
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.MessageMeta
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.RichMessage
import dev.rosty.core.model.wa.WaMessage
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncErrorKind
import dev.rosty.core.model.wa.WaSyncEvent
import dev.rosty.core.model.wa.WaSyncFetch
import dev.rosty.core.model.wa.WaSyncOutcome
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.core.model.wa.WaSyncSettled
import dev.rosty.core.ops.NativeWaSyncOps
import dev.rosty.core.ops.WaSyncStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * The WhatsApp offline store against the real host library (E6.4s): what was synced is read back with no gateway at
 * all, deleted and edited messages keep their content, the outbox survives a close and never doubles a send.
 */
class WaSyncStoreNativeTest {
    private val ops = NativeWaSyncOps(TestRust.core)
    private val ali = "100000000001@s.whatsapp.net"
    private val group = "120000000000000001@g.us"

    @Before
    fun load() = TestRust.ensure()

    private fun <T> Outcome<T>.getOrOk(): T = (this as? Outcome.Ok)?.value ?: error("failed: $this")

    private fun dir(): String =
        Files
            .createTempDirectory("rosty-wa-sync")
            .toFile()
            .apply { deleteOnExit() }
            .path

    private suspend fun open(
        dir: String,
        instance: String = "inst1",
    ): WaSyncStore = WaSyncStore.open(instance, dir = dir, ops = ops).getOrOk()

    private fun text(
        chat: String,
        id: String,
        at: Long,
        body: String,
        fromMe: Boolean = false,
    ) = GatewayEvent.Message(id, chat, if (fromMe) null else chat, fromMe, at, MessageBody.Text(body), emptyList())

    private fun WaSyncStore.ids(
        chat: String,
        limit: Int = 50,
    ): List<String> = runBlocking { thread(chat, limit).getOrOk().items.map { it.id } }

    @Test
    fun theInboxAndAThreadReadFromTheStoreWithNoGateway() =
        runBlocking {
            val dir = dir()
            open(dir).use { store ->
                assertTrue(store.opened.needsResync)
                assertNull(store.plan().getOrOk().afterSeq)
                val applied =
                    store
                        .applyEvents(
                            listOf(
                                WaSyncEvent(1, 1_000, text(ali, "A1", 1_000, "merhaba")),
                                WaSyncEvent(2, 2_000, text(ali, "A2", 2_000, "selam", fromMe = true)),
                                WaSyncEvent(3, 3_000, text(group, "G1", 3_000, "toplantı 5'te")),
                                WaSyncEvent(
                                    4,
                                    4_000,
                                    GatewayEvent.MessageMetaChanged(
                                        ali,
                                        "A2",
                                        MessageBody.Text("selam!"),
                                        MessageMeta(editedMs = 3_500),
                                    ),
                                ),
                                WaSyncEvent(5, 5_000, GatewayEvent.MessageDeleted(ali, "A1", deletedAt = 4_500)),
                            ),
                        ).getOrOk()
                assertEquals(5L, applied.lastSeq)
                assertEquals(listOf(ali, group), applied.chats)
            }

            // Later, offline: no gateway handle exists in this test at all. Everything comes from the file.
            open(dir).use { store ->
                assertEquals(5L, store.plan().getOrOk().afterSeq)
                val chats = store.chats().getOrOk()
                assertEquals(listOf(group, ali), chats.map { it.jid })
                assertEquals("toplantı 5'te", chats[0].last?.text)
                val rows = store.thread(ali, 50).getOrOk().items
                assertEquals(listOf("A2", "A1"), rows.map { it.id })
                assertEquals("selam!", rows[0].text)
                assertEquals(listOf("selam"), rows[0].editHistory.map { it.text })
                // Deleted, shown as deleted, and what it said is still there for "show content".
                assertTrue(rows[1].deleted)
                assertEquals("merhaba", rows[1].text)
                assertEquals(4_500L, rows[1].deletedAt)
                assertEquals(DELETED_BY_ME, rows[1].deletedBy)
            }
        }

    @Test
    fun aGapIsRepairedByPagingDownToTheStoredHistory() =
        runBlocking {
            open(dir()).use { store ->
                store.applyEvents(listOf(WaSyncEvent(7, 1_000, text(ali, "A1", 1_000, "eski")))).getOrOk()
                store.storeChats(emptyList(), nowMs = 1_500).getOrOk()
                assertTrue(store.applyEvents(listOf(WaSyncEvent(2, 9_000, GatewayEvent.Resync))).getOrOk().resync)
                val plan = store.plan(limit = 2).getOrOk()
                assertEquals(2L, plan.afterSeq)
                assertTrue(plan.refreshChats)

                val gateway =
                    (1..5).map {
                        WaMessage(
                            "A$it",
                            ali,
                            ali,
                            false,
                            it * 1_000L,
                            MessageBody.Text("m$it"),
                            emptyList(),
                        )
                    }
                val newest = gateway.last()
                val listed = RichChat(ali, "Ali", newest, unread = 4, isGroup = false, meta = ChatMeta(pinned = true))
                var fetch: WaSyncFetch? =
                    store
                        .storeChats(
                            listOf(listed),
                            nowMs = 9_500,
                            limit = 2,
                        ).getOrOk()
                        .fetch
                        .single()
                val asked = mutableListOf<Long?>()
                while (fetch != null) {
                    val page = fetch
                    asked += page.before
                    val messages =
                        gateway
                            .filter { m -> page.before?.let { m.timestampMs < it } ?: true }
                            .sortedByDescending { it.timestampMs }
                            .take(page.limit)
                            .map {
                                RichMessage(
                                    it.id,
                                    it.chat,
                                    it.sender,
                                    it.fromMe,
                                    it.timestampMs,
                                    it.body,
                                    it.reactions,
                                )
                            }
                    fetch = store.storePage(page, messages, nowMs = 9_600).getOrOk().next
                }
                assertEquals(listOf(null, 4_001L, 3_001L, 2_001L), asked)
                assertEquals(listOf("A5", "A4", "A3", "A2", "A1"), store.ids(ali))
                val chat = store.chats().getOrOk().single()
                assertEquals(4, chat.unread)
                assertTrue(chat.pinned)
                assertTrue(
                    store
                        .plan()
                        .getOrOk()
                        .fetch
                        .isEmpty(),
                )
            }
        }

    @Test
    fun aMessageWrittenOfflineWaitsSurvivesACloseAndGoesOutOnce() =
        runBlocking {
            val dir = dir()
            open(dir).use { store ->
                store.storeChats(emptyList(), nowMs = 500).getOrOk()
                store.enqueue("c1", ali, WaSyncAction.SendText("yoldayım"), nowMs = 1_000).getOrOk()
                store.enqueue("c2", ali, WaSyncAction.SendText("5 dk"), nowMs = 1_001).getOrOk()
                val rows = store.thread(ali, 50).getOrOk().items
                assertEquals(listOf("c2", "c1"), rows.map { it.id })
                assertTrue(rows.all { it.state == WaSyncRowState.Waiting })
            }

            // The app was killed; the connection comes back in a new process.
            open(dir).use { store ->
                val due = store.due(nowMs = 60_000, networkBack = true).getOrOk()
                assertEquals(listOf("c1"), due.items.map { it.clientId })
                assertEquals(WaSyncAction.SendText("yoldayım"), due.items.single().action)

                // The connection drops before the answer: unknown whether it went out, so it waits.
                val lost =
                    store
                        .settle(
                            "c1",
                            WaSyncOutcome.Error(WaSyncErrorKind.Network, "timed out"),
                            nowMs = 61_000,
                        ).getOrOk()
                assertEquals(WaSyncSettled.WAITING, lost.state)
                // It did go out: its echo arrives with the missed events and confirms it. Nothing is sent twice.
                val echo =
                    store
                        .applyEvents(
                            listOf(WaSyncEvent(1, 62_000, text(ali, "S1", 60_500, "yoldayım", fromMe = true))),
                        ).getOrOk()
                assertEquals(listOf("c1"), echo.reconciled)

                val next = store.due(nowMs = 63_000, networkBack = true).getOrOk()
                assertEquals(listOf("c2"), next.items.map { it.clientId })
                val sent = store.settle("c2", WaSyncOutcome.Sent("S2", 63_100), nowMs = 63_200).getOrOk()
                assertEquals(WaSyncSettled.DONE, sent.state)
                // The echo of the second one comes after its answer: still one row.
                store
                    .applyEvents(
                        listOf(WaSyncEvent(2, 63_300, text(ali, "S2", 63_100, "5 dk", fromMe = true))),
                    ).getOrOk()

                val rows = store.thread(ali, 50).getOrOk().items
                assertEquals(listOf("S2", "S1"), rows.map { it.id })
                assertEquals(listOf("c2", "c1"), rows.map { it.clientId })
                assertTrue(store.outbox().getOrOk().isEmpty())
                assertTrue(
                    store
                        .due(nowMs = 999_999, networkBack = true)
                        .getOrOk()
                        .items
                        .isEmpty(),
                )
            }
        }

    @Test
    fun aWaitingMessageCanBeEditedAndCancelled() =
        runBlocking {
            open(dir()).use { store ->
                store.enqueue("c1", ali, WaSyncAction.SendText("taslak"), nowMs = 1_000).getOrOk()
                assertEquals(WaSyncAction.SendText("son hali"), store.editQueued("c1", "son hali").getOrOk().action)
                assertTrue(store.cancel("c1").getOrOk())
                assertFalse(store.cancel("c1").getOrOk())
                assertTrue(
                    store
                        .thread(ali, 50)
                        .getOrOk()
                        .items
                        .isEmpty(),
                )
                assertTrue(store.editQueued("c1", "x") is Outcome.Err)
            }
        }

    @Test
    fun anotherInstanceNeverSeesTheFirstOnesData() =
        runBlocking {
            val dir = dir()
            open(
                dir,
                "inst1",
            ).use { it.applyEvents(listOf(WaSyncEvent(1, 1_000, text(ali, "A1", 1_000, "ilk hesap")))).getOrOk() }
            open(dir, "inst2").use { second ->
                assertTrue(second.chats().getOrOk().isEmpty())
                assertNull(second.opened.lastSeq)
            }
            open(dir, "inst1").use { first -> assertEquals(listOf("A1"), first.ids(ali)) }
        }

    @Test
    fun evictingMediaKeepsTheMessageAndItsThumbnail() =
        runBlocking {
            open(dir()).use { store ->
                store.applyEvents(listOf(WaSyncEvent(1, 1_000, text(group, "G1", 1_000, "foto")))).getOrOk()
                store
                    .setMedia(
                        group,
                        "G1",
                        nowMs = 2_000,
                        thumbnailB64 = "dGh1bWI=",
                        path = "/cache/G1.jpg",
                        bytes = 4_096,
                    ).getOrOk()
                assertEquals("/cache/G1.jpg", store.media(group, "G1", usedMs = 3_000).getOrOk().path)
                val eviction = store.evictMedia(maxBytes = 1_024).getOrOk()
                assertEquals(listOf("/cache/G1.jpg"), eviction.evicted.map { it.path })
                val left = store.media(group, "G1").getOrOk()
                assertNull(left.path)
                assertEquals("dGh1bWI=", left.thumbnailB64)
                val row = store.message(group, "G1").getOrOk()
                assertEquals("foto", row?.text)
                assertTrue(row?.hasThumbnail == true)
            }
        }

    @Test
    fun aClosedStoreIsNotFoundOnBothSides() =
        runBlocking {
            val store = open(dir())
            val id = store.id
            store.close()
            assertTrue((store.chats() as Outcome.Err).error is RustError.NotFound)
            val raw =
                ops.chats(
                    dev.rosty.core.model.wa
                        .WaSyncChatsRequest(id),
                )
            assertTrue((raw as Outcome.Err).error is RustError.NotFound)
        }
}
