package dev.rosty.core.rust

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.wa.ChatMeta
import dev.rosty.core.model.wa.DELETED_BY_ME
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.GroupInfo
import dev.rosty.core.model.wa.HistoryStatus
import dev.rosty.core.model.wa.IdentityMapping
import dev.rosty.core.model.wa.IdentityMappingsAnswer
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.MessageMeta
import dev.rosty.core.model.wa.Participant
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.RichMessage
import dev.rosty.core.model.wa.WaIdKind
import dev.rosty.core.model.wa.WaIdParseRequest
import dev.rosty.core.model.wa.WaIdParsed
import dev.rosty.core.model.wa.WaMessage
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncErrorKind
import dev.rosty.core.model.wa.WaSyncEvent
import dev.rosty.core.model.wa.WaSyncFetch
import dev.rosty.core.model.wa.WaSyncHistoryState
import dev.rosty.core.model.wa.WaSyncOutcome
import dev.rosty.core.model.wa.WaSyncRekeyed
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
 * all, deleted and edited messages keep their content, the outbox survives a close and never doubles a send, and a
 * person named by a LID and by a number is one chat once the pair is learned.
 */
class WaSyncStoreNativeTest {
    private val ops = NativeWaSyncOps(TestRust.core)
    private val ali = "100000000001@s.whatsapp.net"
    private val group = "120000000000000001@g.us"

    @Before
    fun load() = TestRust.ensure()

    /** The value of an answer, which may itself be null ("not a JID"). */
    private fun <T> Outcome<T>.getOrOk(): T =
        when (this) {
            is Outcome.Ok -> value
            is Outcome.Err -> error("failed: $this")
        }

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
                // Deleted for me (E6-WA R4): the thread leaves it out, as WhatsApp does.
                assertEquals(listOf("A2"), rows.map { it.id })
                assertEquals("selam!", rows[0].text)
                assertEquals(listOf("selam"), rows[0].editHistory.map { it.text })
                // Still stored, marked, with what it said.
                val deleted = store.message(ali, "A1").getOrOk()
                assertTrue(deleted!!.deleted)
                assertEquals("merhaba", deleted.text)
                assertEquals(4_500L, deleted.deletedAt)
                assertEquals(DELETED_BY_ME, deleted.deletedBy)
            }
        }

    @Test
    fun aStarWrittenOfflineShowsAtOnceIsListedAndIsUndoneWhenRefused() =
        runBlocking {
            val dir = dir()
            open(dir).use { store ->
                store
                    .applyEvents(
                        listOf(
                            WaSyncEvent(1, 1_000, text(ali, "A1", 1_000, "merhaba")),
                            WaSyncEvent(2, 2_000, text(group, "G1", 2_000, "toplantı 5'te")),
                            WaSyncEvent(3, 3_000, text(ali, "A2", 3_000, "selam", fromMe = true)),
                        ),
                    ).getOrOk()
                store.enqueue("s1", ali, WaSyncAction.Star("A1", true), nowMs = 4_000).getOrOk()
                store.enqueue("s2", group, WaSyncAction.Star("G1", true), nowMs = 4_001).getOrOk()
                store.enqueue("s3", ali, WaSyncAction.Star("A2", true), nowMs = 4_002).getOrOk()
                // Starred before anything went out, in every chat, newest message first, in pages.
                assertTrue(store.message(ali, "A1").getOrOk()!!.starred)
                val first = store.starred(limit = 2).getOrOk()
                assertEquals(listOf("A2", "G1"), first.items.map { it.id })
                val rest = store.starred(limit = 2, before = first.next).getOrOk()
                assertEquals(listOf("A1"), rest.items.map { it.id })
                assertNull(rest.next)
                // A star and its unstar written offline are one call.
                store.enqueue("s4", ali, WaSyncAction.Star("A2", false), nowMs = 4_003).getOrOk()
                assertEquals(listOf("s1", "s2", "s4"), store.outbox().getOrOk().map { it.clientId })
            }

            // The queue survives the close; the gateway takes one star and refuses the other.
            open(dir).use { store ->
                assertEquals(
                    listOf("G1", "A1"),
                    store
                        .starred(limit = 50)
                        .getOrOk()
                        .items
                        .map { it.id },
                )
                val due = store.due(nowMs = 5_000).getOrOk().items
                assertEquals(listOf("s1", "s2"), due.map { it.clientId })
                assertEquals(WaSyncAction.Star("A1", true), due[0].action)
                assertEquals(
                    WaSyncSettled.DONE,
                    store.settle("s2", WaSyncOutcome.Sent(), nowMs = 5_100).getOrOk().state,
                )
                val refused = WaSyncOutcome.Error(WaSyncErrorKind.Rejected, "no such message")
                assertEquals(WaSyncSettled.FAILED, store.settle("s1", refused, nowMs = 5_101).getOrOk().state)
                assertFalse(store.message(ali, "A1").getOrOk()!!.starred)
                assertEquals(
                    listOf("G1"),
                    store
                        .starred(limit = 50)
                        .getOrOk()
                        .items
                        .map { it.id },
                )
                assertEquals(listOf("s4"), store.outbox().getOrOk().map { it.clientId })
            }
        }

    @Test
    fun aGapIsRepairedByPagingDownToTheStoredHistory() =
        runBlocking {
            open(dir()).use { store ->
                store.applyEvents(listOf(WaSyncEvent(7, 1_000, text(ali, "A1", 1_000, "eski")))).getOrOk()
                store.storeChats(emptyList(), nowMs = 1_500).getOrOk()
                assertTrue(store.applyEvents(listOf(WaSyncEvent(2, 9_000, GatewayEvent.Resync()))).getOrOk().resync)
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
    fun aLidChatAndANumberChatBecomeOneWhenThePairIsLearned() =
        runBlocking {
            val lid = "777000000000001@lid"
            val dir = dir()
            open(dir).use { store ->
                store
                    .applyEvents(
                        listOf(
                            WaSyncEvent(1, 1_000, text(lid, "L1", 1_000, "lid'den")),
                            WaSyncEvent(2, 2_000, text(ali, "A1", 2_000, "numaradan")),
                        ),
                    ).getOrOk()
                // Two chats until the gateway says whose LID it is.
                assertEquals(listOf(ali, lid), store.chats().getOrOk().map { it.jid })
                assertEquals(lid, store.resolve(lid).getOrOk()?.key)

                val mapped = GatewayEvent.IdentityMapped(lid, ali, "peer_message", learnedMs = 2_500)
                val applied = store.applyEvents(listOf(WaSyncEvent(3, 3_000, mapped))).getOrOk()
                assertEquals(listOf(WaSyncRekeyed(1, fromKey = lid, intoKey = ali)), applied.rekeyed)
                assertTrue(applied.resync)
                assertEquals(listOf(ali), applied.chats)
                assertTrue(applied.rejected.isEmpty())

                // One chat, and a screen that still holds the LID reads it.
                val chats = store.chats().getOrOk()
                assertEquals(listOf(ali), chats.map { it.jid })
                assertEquals(lid, chats[0].wireJid)
                assertEquals(2, chats[0].unread)
                val rows = store.thread(lid, 50).getOrOk().items
                assertEquals(listOf("A1", "L1"), rows.map { it.id })
                assertTrue(rows.all { it.chat == ali && it.sender == ali })
                assertEquals(listOf(null, lid), rows.map { it.wireSender })
                assertEquals("lid'den", store.message(lid, "L1").getOrOk()?.text)
                assertEquals(ali, store.older(lid).getOrOk()?.chat)

                // An id that arrives outside the store (typing, a notification) is resolved to the same chat.
                val resolved = store.resolve("777000000000001:4@lid").getOrOk()
                assertEquals(ali, resolved?.key)
                assertEquals(WaIdKind.Lid, resolved?.kind)
                assertEquals(lid, resolved?.wire)
                assertEquals(WaIdKind.Group, store.resolve(group).getOrOk()?.kind)
                assertNull(store.resolve("merhaba").getOrOk())

                // A device contact is looked up by the person's number, whichever id a screen holds.
                assertEquals(listOf("+100000000001"), store.numbers("777000000000001:4@lid").getOrOk())
                assertEquals(listOf("+100000000001"), store.numbers(ali).getOrOk())
                assertTrue(store.numbers("777000000000005@lid").getOrOk().isEmpty())
                assertTrue(store.numbers(group).getOrOk().isEmpty())

                // A later message under the LID lands in the one chat.
                store.applyEvents(listOf(WaSyncEvent(4, 4_000, text(lid, "L2", 4_000, "yine lid")))).getOrOk()
                assertEquals(listOf("L2", "A1", "L1"), store.ids(ali))
            }

            open(dir).use { store ->
                // The person moves to another number: the new one first, then the one a contact may still hold.
                val moved = "100000000002@s.whatsapp.net"
                val changed = GatewayEvent.IdentityNumberChanged(oldPn = ali, newPn = moved, newLid = lid)
                store.applyEvents(listOf(WaSyncEvent(5, 5_000, changed))).getOrOk()
                for (id in listOf(lid, ali, moved)) {
                    assertEquals(id, listOf("+100000000002", "+100000000001"), store.numbers(id).getOrOk())
                }
            }
            open(dir).use { store ->
                assertEquals(listOf("+100000000002", "+100000000001"), store.numbers(lid).getOrOk())
            }
        }

    @Test
    fun aStepLoggedBeforeACloseIsFoundFromTheCursorAfterIt() =
        runBlocking {
            val lid = "777000000000001@lid"
            val dir = dir()
            open(dir).use { store ->
                store
                    .applyEvents(
                        listOf(
                            WaSyncEvent(1, 1_000, text(lid, "L1", 1_000, "lid'den")),
                            WaSyncEvent(2, 2_000, text(ali, "A1", 2_000, "numaradan")),
                            WaSyncEvent(3, 3_000, GatewayEvent.IdentityMapped(lid, ali, "peer_message", 2_500)),
                            WaSyncEvent(4, 4_000, text(lid, "L2", 4_000, "yine lid")),
                        ),
                    ).getOrOk()
            }
            // A layer that was not running finds the step from its own cursor.
            open(dir).use { store ->
                assertEquals(listOf(WaSyncRekeyed(1, lid, ali)), store.rekeyedSince().getOrOk())
                assertTrue(store.rekeyedSince(seq = 1).getOrOk().isEmpty())
                assertEquals(listOf("L2", "A1", "L1"), store.ids(lid))
            }
        }

    @Test
    fun theGatewaysPairsAreCaughtUpPageByPageFromTheStoredCursor() =
        runBlocking {
            val lid = "777000000000001@lid"
            val dir = dir()
            val asked = mutableListOf<String?>()
            val pages =
                mapOf(
                    null to IdentityMappingsAnswer(true, listOf(IdentityMapping(lid, ali, "usync", 500)), "41"),
                    "41" to IdentityMappingsAnswer(true, emptyList(), null),
                )
            val fetch: suspend (String?) -> Outcome<IdentityMappingsAnswer> = { since ->
                asked += since
                Outcome.Ok(pages.getValue(since))
            }
            open(dir).use { store ->
                store
                    .applyEvents(
                        listOf(
                            WaSyncEvent(1, 1_000, text(lid, "L1", 1_000, "lid'den")),
                            WaSyncEvent(2, 2_000, text(ali, "A1", 2_000, "numaradan")),
                        ),
                    ).getOrOk()
                val caught = store.identityCatchUp(fetch).getOrOk()
                assertTrue(caught.supported && caught.complete && caught.resync)
                assertEquals(1, caught.pairs)
                assertEquals(listOf(WaSyncRekeyed(1, lid, ali)), caught.rekeyed)
                assertEquals(listOf(null, "41"), asked)
                assertEquals(listOf("A1", "L1"), store.ids(ali))
            }
            // After a restart the catch-up asks only for what is newer.
            open(dir).use { store ->
                assertEquals("41", store.plan().getOrOk().identitySince)
                asked.clear()
                val again = store.identityCatchUp(fetch).getOrOk()
                assertTrue(again.complete && again.rekeyed.isEmpty())
                assertEquals(listOf<String?>("41"), asked)
            }
        }

    @Test
    fun whatTheGatewayAnswersElsewhereTeachesTheStoreToo() =
        runBlocking {
            val lid = "777000000000001@lid"
            open(dir()).use { store ->
                store.applyEvents(listOf(WaSyncEvent(1, 1_000, text(lid, "L1", 1_000, "lid'den")))).getOrOk()
                val team =
                    GroupInfo(
                        group,
                        "Aile",
                        listOf(Participant(lid, admin = false, lid = lid, pn = ali)),
                        addressingMode = "lid",
                    )
                val own = InstanceStatus("inst1", InstanceState.Connected, "100000000009@s.whatsapp.net", "Ben")
                val applied =
                    store
                        .observe(
                            nowMs = 2_000,
                            status = own.copy(ownLid = "777000000000009@lid"),
                            groups = listOf(team),
                        ).getOrOk()
                assertEquals(listOf(WaSyncRekeyed(1, lid, ali)), applied.rekeyed)
                assertFalse(applied.accountChanged)
                assertEquals(listOf("L1"), store.ids(ali))
                // My LID is me: its chat is the chat of my number.
                assertEquals("100000000009@s.whatsapp.net", store.resolve("777000000000009@lid").getOrOk()?.key)
            }
        }

    /** The epoch, account and history frames go through the real library as Kotlin encodes them. */
    @Test
    fun theGatewaysEpochAccountAndHistoryReachTheStore() =
        runBlocking {
            val own = "100000000009@s.whatsapp.net"
            val other = "100000000008@s.whatsapp.net"
            open(dir()).use { store ->
                // A whole status answer: the epoch, the account and a history report with zero counts.
                val status =
                    InstanceStatus(
                        "inst1",
                        InstanceState.Connected,
                        own,
                        "Ben",
                        epoch = "e1",
                        accountEpoch = 1,
                        history = HistoryStatus(HistoryStatus.IDLE),
                    )
                val first = store.observe(nowMs = 1_000, status = status).getOrOk()
                assertFalse(first.resync || first.accountChanged)
                assertEquals(WaSyncHistoryState.Idle, store.history().getOrOk()?.state)

                store
                    .applyEvents(
                        listOf(
                            WaSyncEvent(1, 2_000, GatewayEvent.Hello("e1")),
                            WaSyncEvent(2, 2_000, text(ali, "A1", 1_000, "eski")),
                        ),
                    ).getOrOk()
                store.storeChats(emptyList(), nowMs = 2_500).getOrOk()
                assertEquals(2L, store.plan().getOrOk().afterSeq)

                // A socket of a restarted gateway: the hello is the gap, the low numbers after it are applied.
                val restarted =
                    store
                        .applyEvents(
                            listOf(
                                WaSyncEvent(0, 3_000, GatewayEvent.Hello("e2")),
                                WaSyncEvent(
                                    1,
                                    3_000,
                                    GatewayEvent.State(
                                        "inst1",
                                        InstanceState.Connected,
                                        own,
                                        epoch = "e2",
                                        accountEpoch = 1,
                                        history = HistoryStatus(HistoryStatus.SYNCING, 55, 120, 48_000, 500),
                                    ),
                                ),
                                WaSyncEvent(2, 3_100, GatewayEvent.HistorySynced(2, 60, "full", 500, 400)),
                                WaSyncEvent(3, 3_200, GatewayEvent.Resync("e2")),
                            ),
                        ).getOrOk()
                assertTrue(restarted.resync && !restarted.accountChanged)
                assertEquals(3L, restarted.lastSeq)
                val history = store.history().getOrOk()
                assertEquals(listOf(60, 120), listOf(history?.progress, history?.chats))
                assertEquals(48_500L, history?.messages)
                assertEquals(400L, history?.oldestMs)
                assertEquals("full", history?.syncType)
                assertEquals(listOf("A1"), store.ids(ali))

                // The announcement of another account, in the batch.
                val changed =
                    store
                        .applyEvents(
                            listOf(
                                WaSyncEvent(4, 4_000, GatewayEvent.AccountChanged(own, other)),
                                WaSyncEvent(5, 4_100, text(ali, "B1", 4_100, "yeni")),
                            ),
                        ).getOrOk()
                assertTrue(changed.accountChanged && changed.resync)
                assertEquals(listOf("B1"), store.ids(ali))
                assertNull(store.history().getOrOk())
                // The status of the new account, with its raised epoch, wipes nothing more.
                val after =
                    store
                        .observe(nowMs = 5_000, status = status.copy(ownJid = other, epoch = "e2", accountEpoch = 2))
                        .getOrOk()
                assertFalse(after.accountChanged)
                assertEquals(listOf("B1"), store.ids(ali))
            }
        }

    @Test
    fun aJidIsTakenApartWithoutAStore() =
        runBlocking {
            val pn = ops.parseId(WaIdParseRequest("100000000001:12@s.whatsapp.net")).getOrOk()
            assertEquals(WaIdParsed(WaIdKind.Pn, "100000000001", ali, "+100000000001", 12), pn)
            val lid = ops.parseId(WaIdParseRequest("100000000001@lid")).getOrOk()
            // The digits of a LID are not a phone number.
            assertEquals(WaIdParsed(WaIdKind.Lid, "100000000001", "100000000001@lid"), lid)
            assertEquals(WaIdKind.Group, ops.parseId(WaIdParseRequest(group)).getOrOk()?.kind)
            assertNull(ops.parseId(WaIdParseRequest("+90 555 000 00 01")).getOrOk())
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
