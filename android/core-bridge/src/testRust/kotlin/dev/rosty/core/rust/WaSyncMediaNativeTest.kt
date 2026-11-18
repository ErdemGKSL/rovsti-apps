package dev.rosty.core.rust

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.MediaKind
import dev.rosty.core.model.wa.MediaMeta
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.MessageMeta
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncEvent
import dev.rosty.core.model.wa.WaSyncFetched
import dev.rosty.core.model.wa.WaSyncHistoryReport
import dev.rosty.core.model.wa.WaSyncHistoryState
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMediaDecision
import dev.rosty.core.model.wa.WaSyncMediaState
import dev.rosty.core.model.wa.WaSyncNetwork
import dev.rosty.core.ops.NativeWaSyncOps
import dev.rosty.core.ops.WaSyncStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * WA-R2 against the real host library: the media queue under the auto-download rules, the cap and its exemptions,
 * the reconcile at open, the gateway epoch, the account wipe, the history progress and the unlink wipe.
 */
class WaSyncMediaNativeTest {
    private val ops = NativeWaSyncOps(TestRust.core)
    private val ali = "100000000001@s.whatsapp.net"
    private val own = "100000000009@s.whatsapp.net"
    private val other = "100000000008@s.whatsapp.net"
    private val day = 24L * 60 * 60 * 1000
    private val now = 10 * day

    @Before
    fun load() = TestRust.ensure()

    private fun <T> Outcome<T>.getOrOk(): T =
        when (this) {
            is Outcome.Ok -> value
            is Outcome.Err -> error("failed: $this")
        }

    private fun dir(): String =
        Files
            .createTempDirectory("rosty-wa-media")
            .toFile()
            .apply { deleteOnExit() }
            .path

    private suspend fun open(
        dir: String,
        instance: String = "inst1",
    ): WaSyncStore = WaSyncStore.open(instance, dir = dir, ops = ops).getOrOk()

    private fun media(
        id: String,
        kind: MediaKind,
        size: Long,
    ) = GatewayEvent.Message(
        id,
        ali,
        ali,
        false,
        1_000,
        MessageBody.Media(kind, null, "application/octet-stream", size),
        emptyList(),
    )

    private fun flags(
        id: String,
        meta: MediaMeta,
    ) = GatewayEvent.MessageMetaChanged(ali, id, null, MessageMeta(media = meta))

    /** A photo, a sticker, a voice note, a video and a view-once photo from Ali. */
    private suspend fun seeded(dir: String): WaSyncStore {
        val store = open(dir)
        val events =
            listOf(
                media("photo", MediaKind.Image, 1_000),
                media("sticker", MediaKind.Sticker, 100),
                media("voice", MediaKind.Audio, 500),
                flags("voice", MediaMeta(voiceNote = true)),
                media("video", MediaKind.Video, 9_000),
                media("once", MediaKind.Image, 800),
                flags("once", MediaMeta(viewOnce = true)),
            )
        store.applyEvents(events.mapIndexed { index, event -> WaSyncEvent(index + 1L, 1_000, event) }).getOrOk()
        return store
    }

    @Test
    fun aStoreOpensAtTheSchemaThisBuildWrites() =
        runBlocking {
            open(dir()).use { assertEquals(7, it.opened.schemaVersion) }
        }

    @Test
    fun theRulesDecideAndTheQueueHandsOutSettlesAndRetries() =
        runBlocking {
            seeded(dir()).use { store ->
                assertEquals(WaSyncMediaDecision.Auto, store.mediaPolicy(ali, "sticker", WaSyncNetwork.Wifi).getOrOk())
                assertEquals(
                    WaSyncMediaDecision.OnTap,
                    store.mediaPolicy(ali, "sticker", WaSyncNetwork.Mobile).getOrOk(),
                )
                assertEquals(WaSyncMediaDecision.Auto, store.mediaPolicy(ali, "voice", WaSyncNetwork.Mobile).getOrOk())
                assertEquals(WaSyncMediaDecision.OnTap, store.mediaPolicy(ali, "video", WaSyncNetwork.Wifi).getOrOk())
                assertEquals(WaSyncMediaDecision.Never, store.mediaPolicy(ali, "once", WaSyncNetwork.Wifi).getOrOk())
                assertEquals(
                    WaSyncMediaDecision.OnTap,
                    store.mediaPolicy(ali, "photo", WaSyncNetwork.DataSaver).getOrOk(),
                )
                assertEquals(
                    WaSyncMediaDecision.OnTap,
                    store.mediaPolicy(ali, "photo", WaSyncNetwork.Wifi, freeBytes = 100L * 1024 * 1024).getOrOk(),
                )

                assertTrue(store.enqueueMedia(ali, "photo", now, network = WaSyncNetwork.Wifi).getOrOk().queued)
                assertTrue(store.enqueueMedia(ali, "sticker", now + 1, network = WaSyncNetwork.Wifi).getOrOk().queued)
                assertFalse(store.enqueueMedia(ali, "video", now + 2, network = WaSyncNetwork.Wifi).getOrOk().queued)
                val tapped = store.enqueueMedia(ali, "video", now + 3, tap = true).getOrOk()
                assertTrue(tapped.queued && tapped.media.byTap)
                assertTrue((store.enqueueMedia(ali, "photo", now) as Outcome.Err).error is RustError.Invalid)

                // On mobile data the sticker waits; the tapped video goes first.
                val due = store.dueMedia(now + 4, network = WaSyncNetwork.Mobile).getOrOk()
                assertEquals(listOf("video", "photo"), due.items.map { it.id })
                assertEquals(1, due.held)
                assertEquals(WaSyncMediaState.Downloading, store.message(ali, "video").getOrOk()?.mediaState)

                val failed = store.settleMedia(ali, "photo", WaSyncFetched.Failed("timeout"), now + 5).getOrOk().media
                assertEquals(WaSyncMediaState.Queued, failed.state)
                assertEquals(1, failed.attempts)
                assertEquals(now + 5 + 2_000, failed.nextAttemptMs)
                assertEquals("timeout", store.media(ali, "photo").getOrOk().lastError)

                val ready =
                    store
                        .settleMedia(ali, "video", WaSyncFetched.Ready("/m/video.mp4", 9_100, sha256 = "ab12"), now + 6)
                        .getOrOk()
                assertEquals(WaSyncMediaState.Ready, ready.media.state)
                assertNull(ready.replaced)
                val row = store.message(ali, "video").getOrOk()!!
                assertEquals("/m/video.mp4", row.mediaPath)
                assertEquals(WaSyncMediaState.Ready, row.mediaState)
                assertEquals(WaSyncKind.Video, row.kind)
                val gone = store.settleMedia(ali, "sticker", WaSyncFetched.Gone("not kept"), now + 7).getOrOk().media
                assertEquals(WaSyncMediaState.Gone, gone.state)
                assertEquals(
                    WaSyncMediaState.None,
                    store
                        .settleMedia(ali, "photo", WaSyncFetched.Cancelled, now + 8)
                        .getOrOk()
                        .media.state,
                )
            }
        }

    @Test
    fun theCapEvictsLeastRecentlyUsedAndSparesWhatIsExempt() =
        runBlocking {
            val dir = dir()
            seeded(dir).use { store ->
                assertEquals(512L * 1024 * 1024, store.mediaCap().getOrOk())
                val old = now - 2 * day
                store.setMedia(ali, "photo", old + 1, path = "/m/photo.jpg", bytes = 1_000).getOrOk()
                store.setMedia(ali, "sticker", old + 2, path = "/m/sticker.webp", bytes = 100).getOrOk()
                store.setMedia(ali, "voice", old + 3, path = "/m/voice.ogg", bytes = 500).getOrOk()
                store.setMedia(ali, "video", old + 4, path = "/m/video.mp4", bytes = 9_000).getOrOk()
                // The photo is starred, the sticker saved, the voice note heard a minute ago.
                store.enqueue("s1", ali, WaSyncAction.Star("photo", true), now).getOrOk()
                assertEquals(
                    "content://m/1",
                    store.keepMedia(ali, "sticker", savedUri = "content://m/1").getOrOk().savedUri,
                )
                store.media(ali, "voice", usedMs = now - 60_000).getOrOk()

                val stats = store.mediaStats(now).getOrOk()
                assertEquals(4, stats.total.files)
                assertEquals(10_600L, stats.total.bytes)
                assertEquals(1_600L, stats.exempt.bytes)
                assertEquals(WaSyncKind.Video, stats.byKind.first().kind)

                val eviction = store.evictMedia(maxBytes = 0, nowMs = now).getOrOk()
                assertEquals(listOf("/m/video.mp4"), eviction.evicted.map { it.path })
                assertEquals(1_600L, eviction.bytesLeft)
                assertEquals(1_600L, eviction.exemptBytes)
                val evicted = store.message(ali, "video").getOrOk()!!
                assertNull(evicted.mediaPath)
                assertEquals(WaSyncMediaState.Evicted, evicted.mediaState)

                assertEquals(1_000L, store.setMediaCap(1_000).getOrOk())
                assertTrue(store.mediaStats(now).getOrOk().exemptOverCap)
                assertNull(store.setMediaCap(null).getOrOk())
            }
            // The cap is kept in the store.
            open(dir).use { assertNull(it.mediaCap().getOrOk()) }
        }

    @Test
    fun reconcileAtOpenPutsRowsAndFilesBackInStep() =
        runBlocking {
            val dir = dir()
            seeded(dir).use { store ->
                store.setMedia(ali, "photo", now, path = "/m/photo.jpg", bytes = 1_000).getOrOk()
                store.setMedia(ali, "sticker", now, path = "/m/sticker.webp", bytes = 100).getOrOk()
                store.enqueueMedia(ali, "video", now, tap = true).getOrOk()
                store.dueMedia(now).getOrOk()
            }
            open(dir).use { store ->
                assertEquals(WaSyncMediaState.Downloading, store.media(ali, "video").getOrOk().state)
                val found =
                    store.reconcileMedia(listOf("/m/photo.jpg", "/m/video.mp4.part", "/m/stray.bin")).getOrOk()
                assertEquals(1, found.requeued)
                assertEquals(listOf("sticker"), found.missing.map { it.id })
                assertEquals(listOf("/m/video.mp4.part", "/m/stray.bin"), found.delete)
                assertEquals(WaSyncMediaState.Queued, store.media(ali, "video").getOrOk().state)
                assertEquals(WaSyncMediaState.Evicted, store.media(ali, "sticker").getOrOk().state)
                assertEquals(WaSyncMediaState.Ready, store.media(ali, "photo").getOrOk().state)
            }
        }

    @Test
    fun anotherEpochIsAGapAndHistoryProgressSurvivesAReopen() =
        runBlocking {
            val dir = dir()
            open(dir).use { store ->
                assertFalse(store.noteStatus(now, epoch = "e1", accountEpoch = 1, ownPn = own).getOrOk().gap)
                store
                    .applyEvents(listOf(WaSyncEvent(1, 1_000, media("photo", MediaKind.Image, 1_000))))
                    .getOrOk()
                val report =
                    WaSyncHistoryReport(WaSyncHistoryState.Syncing, progress = 40, chats = 12, messages = 3_400)
                val noted = store.noteStatus(now + 1, epoch = "e1", history = report).getOrOk()
                assertFalse(noted.gap)
                assertEquals(40, noted.history?.progress)
            }
            open(dir).use { store ->
                val history = store.history().getOrOk()!!
                assertEquals(WaSyncHistoryState.Syncing, history.state)
                assertEquals(3_400L, history.messages)
                assertEquals(now + 1, history.updatedMs)
                assertEquals(1L, store.plan().getOrOk().afterSeq)

                val restarted = store.noteStatus(now + 2, epoch = "e2").getOrOk()
                assertTrue(restarted.gap && restarted.resync && !restarted.accountChanged)
                val plan = store.plan().getOrOk()
                assertNull(plan.afterSeq)
                assertTrue(plan.refreshChats)
                // Nothing stored is lost, and the new process's first events are applied, not skipped.
                assertEquals(
                    1,
                    store
                        .thread(ali, 10)
                        .getOrOk()
                        .items.size,
                )
                val applied =
                    store.applyEvents(listOf(WaSyncEvent(1, 2_000, media("second", MediaKind.Image, 1)))).getOrOk()
                assertEquals(1, applied.applied)
            }
        }

    @Test
    fun anotherAccountWipesTheStoreAndTheSameAccountKeepsIt() =
        runBlocking {
            seeded(dir()).use { store ->
                store.noteStatus(now, epoch = "e1", accountEpoch = 1, ownPn = own).getOrOk()
                store.setMedia(ali, "photo", now, path = "/m/photo.jpg", bytes = 1_000).getOrOk()
                store
                    .enqueue(
                        "c1",
                        ali,
                        WaSyncAction.SendMedia("/m/out/send.jpg", MediaKind.Image, "image/jpeg", 300),
                        now,
                    ).getOrOk()

                // The same account again, by status and by event, and a status that names no account.
                for (same in listOf(
                    store.noteStatus(now, epoch = "e1", accountEpoch = 1, ownPn = "100000000009:5@s.whatsapp.net"),
                    store.noteStatus(now, epoch = "e1"),
                    store.noteStatus(now),
                )) {
                    assertFalse(same.getOrOk().accountChanged)
                }
                val state = GatewayEvent.State("inst1", InstanceState.Connected, ownJid = own)
                assertFalse(store.applyEvents(listOf(WaSyncEvent(20, now, state))).getOrOk().accountChanged)
                assertEquals(
                    6,
                    store
                        .thread(ali, 50)
                        .getOrOk()
                        .items.size,
                )

                val changed = store.noteStatus(now + 1, epoch = "e1", accountEpoch = 2, ownPn = other).getOrOk()
                assertTrue(changed.accountChanged && changed.resync && !changed.gap)
                assertEquals(listOf("/m/photo.jpg", "/m/out/send.jpg"), changed.deleteFiles)
                assertTrue(store.chats().getOrOk().isEmpty())
                assertTrue(
                    store
                        .thread(ali, 50)
                        .getOrOk()
                        .items
                        .isEmpty(),
                )
                assertTrue(store.outbox().getOrOk().isEmpty())
                assertEquals(
                    0,
                    store
                        .mediaStats()
                        .getOrOk()
                        .total.files,
                )
                assertTrue(store.plan().getOrOk().refreshChats)

                // Events of the old account that were still in the gateway's ring arrive, then the announcement.
                store.applyEvents(listOf(WaSyncEvent(21, now, media("stale", MediaKind.Image, 5)))).getOrOk()
                assertEquals(
                    1,
                    store
                        .thread(ali, 50)
                        .getOrOk()
                        .items.size,
                )
                val swept = store.accountChanged(oldPn = own, newPn = other, nowMs = now + 2).getOrOk()
                assertTrue(swept.accountChanged)
                assertTrue(
                    store
                        .thread(ali, 50)
                        .getOrOk()
                        .items
                        .isEmpty(),
                )
                assertFalse(store.accountChanged(oldPn = own, newPn = other, nowMs = now + 3).getOrOk().accountChanged)
            }
        }

    @Test
    fun aStateEventOfAnotherAccountWipesThroughApplyEvents() =
        runBlocking {
            seeded(dir()).use { store ->
                store.noteStatus(now, ownPn = own).getOrOk()
                store.setMedia(ali, "photo", now, path = "/m/photo.jpg", bytes = 1_000).getOrOk()
                val state = GatewayEvent.State("inst1", InstanceState.Connected, ownJid = other)
                val applied =
                    store
                        .applyEvents(
                            listOf(WaSyncEvent(20, now, state), WaSyncEvent(21, now, media("new", MediaKind.Image, 7))),
                        ).getOrOk()
                assertTrue(applied.accountChanged)
                assertEquals(listOf("/m/photo.jpg"), applied.deleteFiles)
                assertEquals(
                    listOf("new"),
                    store
                        .thread(ali, 50)
                        .getOrOk()
                        .items
                        .map { it.id },
                )
            }
        }

    @Test
    fun wipeDeletesTheStoreFilesAndClosesEveryStoreOfTheInstance() =
        runBlocking {
            val dir = dir()
            val store = seeded(dir)
            val second = open(dir)
            val keep = open(dir, "inst2")
            store.setMedia(ali, "photo", now, path = "/m/photo.jpg", bytes = 1_000).getOrOk()
            assertTrue(File(dir, "wa-sync-inst1.db").isFile)

            val wiped = store.wipe().getOrOk()
            assertEquals(listOf("/m/photo.jpg"), wiped.mediaFiles)
            assertTrue(wiped.deleted.any { it.endsWith("wa-sync-inst1.db") })
            assertTrue(store.released)
            assertTrue(File(dir).list().orEmpty().none { it.startsWith("wa-sync-inst1") })
            assertTrue((second.chats() as Outcome.Err).error is RustError.NotFound)
            second.close()
            assertTrue(keep.chats().getOrOk().isEmpty())

            // Linking again starts empty; unlinking by instance works on an open and on a closed store.
            open(dir).use { again ->
                assertTrue(again.chats().getOrOk().isEmpty())
                assertNull(again.opened.lastSeq)
                assertTrue(
                    WaSyncStore
                        .wipe("inst1", dir = dir, ops = ops)
                        .getOrOk()
                        .deleted
                        .isNotEmpty(),
                )
                assertTrue((again.chats() as Outcome.Err).error is RustError.NotFound)
            }
            keep.close()
            assertTrue(
                WaSyncStore
                    .wipe("inst2", dir = dir, ops = ops)
                    .getOrOk()
                    .deleted
                    .isNotEmpty(),
            )
            assertTrue(File(dir).list().orEmpty().isEmpty())
            val nothing = WaSyncStore.wipe("inst2", dir = dir, ops = ops).getOrOk()
            assertTrue(nothing.deleted.isEmpty() && nothing.mediaFiles.isEmpty())
        }
}
