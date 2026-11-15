package dev.rosty.core

import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.MediaKind
import dev.rosty.core.model.wa.MediaMeta
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncEvent
import dev.rosty.core.model.wa.WaSyncFetched
import dev.rosty.core.model.wa.WaSyncHistoryReport
import dev.rosty.core.model.wa.WaSyncHistoryState
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMediaDecision
import dev.rosty.core.model.wa.WaSyncMediaState
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.core.model.wa.WaSyncNetwork
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.core.ops.WaSyncStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media queue, the cap, the gateway epoch and the account wipe through [WaSyncStore] over [FakeWaSyncOps]: the
 * fake follows the store's rules, so a screen test sees what the real store would answer. The rules themselves are
 * tested in Rust; `WaSyncMediaNativeTest` runs the same calls against the real library.
 */
class WaSyncMediaFakeTest {
    private val chat = "100000000001@s.whatsapp.net"
    private val own = "100000000009@s.whatsapp.net"
    private val other = "100000000008@s.whatsapp.net"
    private val day = 24L * 60 * 60 * 1000
    private val now = 10 * day

    private fun media(
        id: String,
        kind: WaSyncKind,
        size: Long,
        meta: MediaMeta? = null,
        starred: Boolean = false,
    ) = WaSyncMessage(
        id = id,
        chat = chat,
        sender = chat,
        fromMe = false,
        timestampMs = 1_000,
        kind = kind,
        mime = "application/octet-stream",
        size = size,
        reactions = emptyList(),
        state = WaSyncRowState.Received,
        media = meta,
        starred = starred,
    )

    private suspend fun store(
        fake: FakeWaSyncOps,
        vararg rows: WaSyncMessage,
    ): WaSyncStore {
        fake.messages[chat] = rows.toMutableList()
        return WaSyncStore.open("inst1", ops = fake, release = {}).getOrThrow()
    }

    @Test
    fun theRulesQueueOnWifiWhatTheyHoldBackOnMobileData() =
        runTest {
            val store =
                store(
                    FakeWaSyncOps(),
                    media("photo", WaSyncKind.Image, 1_000),
                    media("sticker", WaSyncKind.Sticker, 100),
                    media("voice", WaSyncKind.Audio, 500, MediaMeta(voiceNote = true)),
                    media("song", WaSyncKind.Audio, 500),
                    media("video", WaSyncKind.Video, 9_000),
                    media("once", WaSyncKind.Image, 800, MediaMeta(viewOnce = true)),
                )
            val wifi =
                listOf("photo", "sticker", "voice", "song", "video", "once").associateWith {
                    store.mediaPolicy(chat, it, WaSyncNetwork.Wifi).getOrThrow()
                }
            assertEquals(
                mapOf(
                    "photo" to WaSyncMediaDecision.Auto,
                    "sticker" to WaSyncMediaDecision.Auto,
                    "voice" to WaSyncMediaDecision.Auto,
                    "song" to WaSyncMediaDecision.OnTap,
                    "video" to WaSyncMediaDecision.OnTap,
                    "once" to WaSyncMediaDecision.Never,
                ),
                wifi,
            )
            assertEquals(
                WaSyncMediaDecision.OnTap,
                store.mediaPolicy(chat, "sticker", WaSyncNetwork.Mobile).getOrThrow(),
            )
            assertEquals(
                WaSyncMediaDecision.OnTap,
                store.mediaPolicy(chat, "photo", WaSyncNetwork.Roaming).getOrThrow(),
            )
            assertEquals(
                WaSyncMediaDecision.OnTap,
                store.mediaPolicy(chat, "photo", WaSyncNetwork.Wifi, freeBytes = 1_000_000).getOrThrow(),
            )

            assertTrue(store.enqueueMedia(chat, "photo", now, network = WaSyncNetwork.Mobile).getOrThrow().queued)
            assertFalse(store.enqueueMedia(chat, "sticker", now, network = WaSyncNetwork.Mobile).getOrThrow().queued)
            assertFalse(store.enqueueMedia(chat, "video", now, network = WaSyncNetwork.Wifi).getOrThrow().queued)
            assertTrue(store.enqueueMedia(chat, "video", now + 1, tap = true).getOrThrow().queued)
            assertFalse(store.enqueueMedia(chat, "once", now, tap = true).getOrThrow().queued)
            assertTrue((store.enqueueMedia(chat, "photo", now) as Outcome.Err).error is RustError.Invalid)
            assertTrue((store.enqueueMedia(chat, "nope", now, tap = true) as Outcome.Err).error is RustError.NotFound)

            // Taps first; what is out is not handed out twice.
            val due = store.dueMedia(now + 2, network = WaSyncNetwork.Mobile).getOrThrow()
            assertEquals(listOf("video", "photo"), due.items.map { it.id })
            assertTrue(
                store
                    .dueMedia(now + 3)
                    .getOrThrow()
                    .items
                    .isEmpty(),
            )
            assertEquals(
                WaSyncMediaState.Downloading,
                store.message(chat, "video").getOrThrow()?.mediaState,
            )
        }

    @Test
    fun aDownloadIsSettledRetriedAndShownOnItsRow() =
        runTest {
            val store = store(FakeWaSyncOps(), media("photo", WaSyncKind.Image, 1_000))
            store.enqueueMedia(chat, "photo", now, network = WaSyncNetwork.Wifi).getOrThrow()
            var at = now
            for (attempt in 1 until FakeWaSyncOps.MAX_ATTEMPTS) {
                assertEquals(
                    1,
                    store
                        .dueMedia(at)
                        .getOrThrow()
                        .items.size,
                )
                val failed = store.settleMedia(chat, "photo", WaSyncFetched.Failed("timeout"), at).getOrThrow().media
                assertEquals(WaSyncMediaState.Queued, failed.state)
                assertEquals(attempt, failed.attempts)
                assertEquals(at + FakeWaSyncOps.backoffMs(attempt), failed.nextAttemptMs)
                assertTrue(
                    store
                        .dueMedia(at + 1)
                        .getOrThrow()
                        .items
                        .isEmpty(),
                )
                at = failed.nextAttemptMs!!
            }
            store.dueMedia(at).getOrThrow()
            val last = store.settleMedia(chat, "photo", WaSyncFetched.Failed("timeout"), at).getOrThrow().media
            assertEquals(WaSyncMediaState.Failed, last.state)
            // The rules leave a failed file alone; a tap starts over.
            assertFalse(store.enqueueMedia(chat, "photo", at, network = WaSyncNetwork.Wifi).getOrThrow().queued)
            assertTrue(store.enqueueMedia(chat, "photo", at, tap = true).getOrThrow().queued)
            store.dueMedia(at).getOrThrow()
            val ready =
                store.settleMedia(chat, "photo", WaSyncFetched.Ready("/m/photo.jpg", 1_024), at).getOrThrow().media
            assertEquals(WaSyncMediaState.Ready, ready.state)
            val row = store.message(chat, "photo").getOrThrow()!!
            assertEquals("/m/photo.jpg", row.mediaPath)
            assertEquals(WaSyncMediaState.Ready, row.mediaState)
            val read = store.media(chat, "photo").getOrThrow()
            assertEquals(WaSyncMediaState.Ready, read.state)
            assertEquals(1_024L, read.bytes)
            assertEquals(1_000L, read.sizeExpected)
        }

    @Test
    fun theCapSparesStarredSavedSendingAndRecentlyUsedFiles() =
        runTest {
            val fake = FakeWaSyncOps()
            val rows = (1..6).map { media("P$it", WaSyncKind.Image, 1_000, starred = it == 1) }
            val store = store(fake, *rows.toTypedArray())
            for (n in 1..6) {
                store.setMedia(chat, "P$n", now - 2 * day + n, path = "/m/P$n.jpg", bytes = 1_000).getOrThrow()
            }
            store.keepMedia(chat, "P2", savedUri = "content://media/42").getOrThrow()
            store
                .enqueue("c1", chat, WaSyncAction.SendMedia("/m/P3.jpg", MediaKind.Image, "image/jpeg", 1_000), now)
                .getOrThrow()
            store.media(chat, "P4", usedMs = now - 1_000).getOrThrow()

            val eviction = store.evictMedia(maxBytes = 0, nowMs = now).getOrThrow()
            assertEquals(listOf("/m/P5.jpg", "/m/P6.jpg"), eviction.evicted.map { it.path })
            assertEquals(4_000L, eviction.bytesLeft)
            assertEquals(4_000L, eviction.exemptBytes)
            val evicted = store.message(chat, "P5").getOrThrow()!!
            assertNull(evicted.mediaPath)
            assertEquals(WaSyncMediaState.Evicted, evicted.mediaState)
            // An evicted file is fetched again like one never seen.
            assertTrue(store.enqueueMedia(chat, "P5", now, network = WaSyncNetwork.Wifi).getOrThrow().queued)

            val stats = store.mediaStats(now).getOrThrow()
            assertEquals(4, stats.total.files)
            assertEquals(4, stats.exempt.files)
            assertEquals(1, stats.queued)
            assertEquals(FakeWaSyncOps.DEFAULT_CAP_BYTES, stats.capBytes)
            assertEquals(listOf(WaSyncKind.Image), stats.byKind.map { it.kind })

            // The stored cap is used when none is named; no limit evicts nothing.
            assertEquals(1_500L, store.setMediaCap(1_500).getOrThrow())
            store.keepMedia(chat, "P2", savedUri = "").getOrThrow()
            assertEquals(
                listOf("/m/P2.jpg"),
                store
                    .evictMedia(nowMs = now)
                    .getOrThrow()
                    .evicted
                    .map { it.path },
            )
            assertNull(store.setMediaCap(null).getOrThrow())
            assertNull(store.mediaCap().getOrThrow())
            assertTrue(
                store
                    .evictMedia()
                    .getOrThrow()
                    .evicted
                    .isEmpty(),
            )
        }

    @Test
    fun reconcileRequeuesCutDownloadsAndNamesFilesToDelete() =
        runTest {
            val store =
                store(FakeWaSyncOps(), media("P1", WaSyncKind.Image, 1_000), media("P2", WaSyncKind.Image, 1_000))
            store.setMedia(chat, "P1", now, path = "/m/P1.jpg", bytes = 1_000).getOrThrow()
            store.enqueueMedia(chat, "P2", now, tap = true).getOrThrow()
            store.dueMedia(now).getOrThrow()
            val bare = store.reconcileMedia(null).getOrThrow()
            assertEquals(1, bare.requeued)
            assertTrue(bare.missing.isEmpty() && bare.delete.isEmpty())
            val found = store.reconcileMedia(listOf("/m/P2.jpg.part", "/m/stray.bin")).getOrThrow()
            assertEquals(listOf("P1"), found.missing.map { it.id })
            assertEquals(listOf("/m/P2.jpg.part", "/m/stray.bin"), found.delete)
            assertEquals(WaSyncMediaState.Evicted, store.media(chat, "P1").getOrThrow().state)
        }

    @Test
    fun anotherGatewayEpochIsAGapAndAnotherAccountWipesTheStore() =
        runTest {
            val fake = FakeWaSyncOps()
            val store = store(fake, media("P1", WaSyncKind.Image, 1_000))
            store.setMedia(chat, "P1", now, path = "/m/P1.jpg", bytes = 1_000).getOrThrow()
            val first = store.noteStatus(now, epoch = "e1", accountEpoch = 1, ownPn = own).getOrThrow()
            assertFalse(first.gap || first.accountChanged)
            store.applyEvents(listOf(WaSyncEvent(7, now, GatewayEvent.Resync))).getOrThrow()
            fake.owed = fake.owed.copy(afterSeq = 7, refreshChats = false)

            // Unknown values and the same account change nothing.
            for (same in listOf(
                store.noteStatus(now),
                store.noteStatus(now, epoch = "e1", accountEpoch = 1, ownPn = "100000000009:3@s.whatsapp.net"),
                store.noteStatus(now, epoch = "e1"),
            )) {
                assertFalse(same.getOrThrow().gap || same.getOrThrow().accountChanged)
            }
            assertEquals(
                listOf("P1"),
                store
                    .thread(chat, 10)
                    .getOrThrow()
                    .items
                    .map { it.id },
            )

            val restarted = store.noteStatus(now, epoch = "e2", accountEpoch = 1, ownPn = own).getOrThrow()
            assertTrue(restarted.gap && restarted.resync && !restarted.accountChanged)
            val plan = store.plan().getOrThrow()
            assertNull(plan.afterSeq)
            assertTrue(plan.refreshChats)

            val history = WaSyncHistoryReport(WaSyncHistoryState.Syncing, progress = 40, chats = 3, messages = 90)
            assertEquals(
                40,
                store
                    .noteStatus(now, history = history)
                    .getOrThrow()
                    .history
                    ?.progress,
            )
            assertEquals(WaSyncHistoryState.Syncing, store.history().getOrThrow()?.state)

            val changed = store.noteStatus(now, epoch = "e2", accountEpoch = 2, ownPn = other).getOrThrow()
            assertTrue(changed.accountChanged && changed.resync)
            assertEquals(listOf("/m/P1.jpg"), changed.deleteFiles)
            assertTrue(
                store
                    .thread(chat, 10)
                    .getOrThrow()
                    .items
                    .isEmpty(),
            )
            assertNull(store.history().getOrThrow())
            assertFalse(
                store.noteStatus(now, epoch = "e2", accountEpoch = 2, ownPn = other).getOrThrow().accountChanged,
            )
        }

    @Test
    fun aStateEventOfAnotherAccountAndTheAnnouncementWipeToo() =
        runTest {
            val fake = FakeWaSyncOps()
            val store = store(fake, media("P1", WaSyncKind.Image, 1_000))
            store.noteStatus(now, epoch = "e1", ownPn = own).getOrThrow()
            val applied =
                store
                    .applyEvents(
                        listOf(
                            WaSyncEvent(1, now, GatewayEvent.State("inst1", InstanceState.Connected, ownJid = other)),
                        ),
                    ).getOrThrow()
            assertTrue(applied.accountChanged)
            assertTrue(
                store
                    .thread(chat, 10)
                    .getOrThrow()
                    .items
                    .isEmpty(),
            )

            fake.put(chat, "A1", 1_000, "of the second account")
            val announced = store.accountChanged(oldPn = other, newPn = own, nowMs = now).getOrThrow()
            assertTrue(announced.accountChanged)
            assertTrue(
                store
                    .thread(chat, 10)
                    .getOrThrow()
                    .items
                    .isEmpty(),
            )
            assertFalse(store.accountChanged(oldPn = other, newPn = own, nowMs = now).getOrThrow().accountChanged)
        }

    @Test
    fun wipeEmptiesTheStoreAndClosesIt() =
        runTest {
            val fake = FakeWaSyncOps()
            val released = mutableListOf<Long>()
            fake.messages[chat] = mutableListOf(media("P1", WaSyncKind.Image, 1_000))
            val store = WaSyncStore.open("inst1", ops = fake, release = { released += it }).getOrThrow()
            store.setMedia(chat, "P1", now, path = "/m/P1.jpg", bytes = 1_000).getOrThrow()
            val wiped = store.wipe().getOrThrow()
            assertEquals(listOf("/m/P1.jpg"), wiped.mediaFiles)
            assertTrue(store.released)
            assertEquals(listOf(FakeWaSyncOps.HANDLE), released)
            assertTrue((store.chats() as Outcome.Err).error is RustError.NotFound)
            assertTrue((store.wipe() as Outcome.Err).error is RustError.NotFound)
            assertTrue(fake.messages.isEmpty())
            // Without a store at hand: by instance.
            assertTrue(
                WaSyncStore
                    .wipe("inst1", ops = fake)
                    .getOrThrow()
                    .mediaFiles
                    .isEmpty(),
            )
        }
}
