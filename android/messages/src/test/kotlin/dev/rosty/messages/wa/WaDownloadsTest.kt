package dev.rosty.messages.wa

import dev.rosty.core.FakeWaSyncOps
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.getOrThrow
import dev.rosty.core.model.wa.BytesAnswer
import dev.rosty.core.model.wa.MediaMeta
import dev.rosty.core.model.wa.WaSyncFetched
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMediaEnqueueRequest
import dev.rosty.core.model.wa.WaSyncMediaSettleRequest
import dev.rosty.core.model.wa.WaSyncMediaSettled
import dev.rosty.core.model.wa.WaSyncMediaState
import dev.rosty.core.model.wa.WaSyncNetwork
import dev.rosty.core.model.wa.WaSyncReconcileRequest
import dev.rosty.core.ops.WaSyncOps
import dev.rosty.core.ops.WaSyncStore
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.INSTANCE
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import dev.rosty.platform.system.DataNetwork
import dev.rosty.platform.system.FakeDeviceData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The download worker: the store's queue and rules decide what is fetched, this side writes `.part`, renames and
 * settles; rows and files are reconciled when the store opens and the cap deletes files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaDownloadsTest {
    @get:Rule val folder = TemporaryFolder()

    private val ops = FakeWaSyncOps(INSTANCE)
    private val remote = FakeWaRemote()
    private val device = FakeDeviceData()
    private val wakes = mutableListOf<Long>()
    private val changed = mutableListOf<Set<String>>()
    private var now = NOW
    private val dir get() = File(folder.root, "wa-media")
    private val hour = 3_600_000L

    /** What was on disk when the store heard of a finished download: the file under its name, a `.part` beside it. */
    private class AtSettle(
        private val inner: FakeWaSyncOps,
    ) : WaSyncOps by inner {
        val seen = mutableListOf<Pair<Boolean, Boolean>>()

        override suspend fun mediaSettle(req: WaSyncMediaSettleRequest): Outcome<WaSyncMediaSettled> {
            (req.outcome as? WaSyncFetched.Ready)?.let { ready ->
                seen += File(ready.path).isFile to File(ready.path + ".part").exists()
            }
            return inner.mediaSettle(req)
        }
    }

    private val checking = AtSettle(ops)

    private fun TestScope.downloads() =
        WaDownloads(backgroundScope, remote, dir, { now }, device, wake = { wakes += it }, io = Dispatchers.Unconfined)

    private suspend fun store() = WaSyncStore.open(INSTANCE, ops = checking, release = {}).getOrThrow()

    private fun seed(
        id: String,
        kind: WaSyncKind,
        mime: String,
        meta: MediaMeta = MediaMeta(),
        bytes: String = "SU1H",
    ) {
        ops.messages.getOrPut(ADA) { mutableListOf() } +=
            WaTestKit.media(ADA, id, NOW - 60_000, kind, mime, 2_048, meta)
        remote.files[id] = BytesAnswer(3, dataB64 = bytes)
    }

    private fun seedAllKinds() {
        seed("photo", WaSyncKind.Image, "image/jpeg")
        seed("sticker", WaSyncKind.Sticker, "image/webp")
        seed("voice", WaSyncKind.Audio, "audio/ogg", MediaMeta(voiceNote = true))
        seed("video", WaSyncKind.Video, "video/mp4")
        seed("doc", WaSyncKind.Document, "application/pdf", MediaMeta(fileName = "Ferry timetable.pdf"))
        seed("once", WaSyncKind.Image, "image/jpeg", MediaMeta(viewOnce = true))
    }

    private val all = listOf("photo", "sticker", "voice", "video", "doc", "once").map { WaMediaRef(ADA, it) }

    private suspend fun stateOf(
        store: WaSyncStore,
        id: String,
    ) = store.media(ADA, id).getOrThrow()

    private fun fileOf(
        id: String,
        name: String,
    ) = File(File(File(File(dir, INSTANCE), "files"), id), name)

    // ---- the rules are the store's

    @Test fun onWifiPhotosStickersAndVoiceNotesDownloadByThemselves() =
        runTest {
            seedAllKinds()
            val store = store()
            val downloads = downloads()

            assertTrue(downloads.auto(store, all))
            downloads.kick(store) { changed += it }.join()

            assertEquals(listOf("photo", "sticker", "voice"), remote.mediaCalls.sorted())
            // Every row was offered with the network the phone is on; none as a tap.
            val asked = ops.writes.filterIsInstance<WaSyncMediaEnqueueRequest>()
            assertEquals(all.map { it.id }, asked.map { it.id })
            assertTrue(asked.all { it.network == WaSyncNetwork.Wifi && !it.tap })
            assertEquals(WaSyncMediaState.Ready, stateOf(store, "photo").state)
            assertEquals(WaSyncMediaState.None, stateOf(store, "video").state)
            assertEquals(WaSyncMediaState.None, stateOf(store, "doc").state)
            assertEquals(WaSyncMediaState.None, stateOf(store, "once").state)
            assertTrue(changed.all { it == setOf(ADA) })
        }

    @Test fun onMobileDataOnlyPhotosAndVoiceNotes() =
        runTest {
            seedAllKinds()
            device.kind = DataNetwork.Mobile
            val store = store()
            val downloads = downloads()

            downloads.auto(store, all)
            downloads.kick(store) {}.join()

            assertEquals(listOf("photo", "voice"), remote.mediaCalls.sorted())
        }

    @Test fun nothingDownloadsByItselfOnRoamingWithDataSaverOrWithLittleRoom() =
        runTest {
            seedAllKinds()
            val store = store()
            val downloads = downloads()

            for (kind in listOf(DataNetwork.Roaming, DataNetwork.DataSaver, DataNetwork.None)) {
                device.kind = kind
                assertFalse("$kind", downloads.auto(store, all))
            }
            device.kind = DataNetwork.Wifi
            device.free = 100L * 1024 * 1024
            assertFalse(downloads.auto(store, all))
            downloads.kick(store) {}.join()

            assertTrue(remote.mediaCalls.isEmpty())
            // The free space the rule judged by was handed in.
            val last = ops.writes.filterIsInstance<WaSyncMediaEnqueueRequest>().last()
            assertEquals(100L * 1024 * 1024, last.freeBytes)
        }

    @Test fun aTapDownloadsWhateverTheNetworkAlsoViewOnceMediaThatWasNotOpened() =
        runTest {
            seedAllKinds()
            device.kind = DataNetwork.Roaming
            val store = store()
            val downloads = downloads()

            assertTrue(downloads.tap(store, WaMediaRef(ADA, "video")))
            assertTrue(downloads.tap(store, WaMediaRef(ADA, "doc")))
            // View once is never fetched by the rules; the tap that opens it fetches it (E6-WA R3).
            assertTrue(downloads.tap(store, WaMediaRef(ADA, "once")))
            downloads.kick(store) {}.join()

            assertEquals(listOf("doc", "once", "video"), remote.mediaCalls.sorted())
            // A document keeps the name it was sent under.
            assertEquals("IMG", fileOf("doc", "Ferry timetable.pdf").readText())
            assertEquals(fileOf("doc", "Ferry timetable.pdf").absolutePath, stateOf(store, "doc").path)
        }

    @Test fun anAutomaticDownloadIsHeldWhenTheNetworkGotWorse() =
        runTest {
            seedAllKinds()
            val store = store()
            val downloads = downloads()
            downloads.auto(store, all)
            // Queued on Wi-Fi, but the phone is roaming by the time the worker runs.
            device.kind = DataNetwork.Roaming

            downloads.kick(store) {}.join()
            assertTrue(remote.mediaCalls.isEmpty())

            device.kind = DataNetwork.Wifi
            downloads.kick(store) {}.join()
            assertEquals(3, remote.mediaCalls.size)
        }

    // ---- the file on disk

    @Test fun theBytesGoToAPartFileThatIsRenamedBeforeTheStoreHears() =
        runTest {
            seed("photo", WaSyncKind.Image, "image/jpeg")
            // The core may also hand the bytes over as a temp file.
            val temp = folder.newFile("download.tmp").also { it.writeText("VIDEO") }
            seed("video", WaSyncKind.Video, "video/mp4")
            remote.files["video"] = BytesAnswer(5, path = temp.absolutePath)
            val store = store()
            val downloads = downloads()
            downloads.auto(store, listOf(WaMediaRef(ADA, "photo")))
            downloads.tap(store, WaMediaRef(ADA, "video"))

            downloads.kick(store) {}.join()

            // When the store was told, the whole file was under its name and no `.part` was left beside it.
            assertEquals(listOf(true to false, true to false), checking.seen)
            assertEquals("IMG", fileOf("photo", "image.jpg").readText())
            assertEquals("VIDEO", fileOf("video", "video.mp4").readText())
            assertFalse(temp.exists())
            val ready =
                ops.writes
                    .filterIsInstance<WaSyncMediaSettleRequest>()
                    .map { it.outcome as WaSyncFetched.Ready }
            // Taps first; each with its size.
            assertEquals(listOf(5L, 3L), ready.map { it.bytes })
            assertTrue(dir.walkTopDown().none { it.name.endsWith(".part") })
            assertTrue(downloads.busy.value.isEmpty())
        }

    @Test fun aFileThatCannotBePutInPlaceIsSettledAsFailedAndLeavesNothingBehind() =
        runTest {
            seed("photo", WaSyncKind.Image, "image/jpeg")
            seed("broken", WaSyncKind.Image, "image/jpeg", bytes = "not base64 !!")
            // Something sits where the photo should go and cannot be replaced by a rename.
            val blocked = fileOf("photo", "image.jpg")
            File(blocked, "in the way").apply { parentFile?.mkdirs() }.writeText("x")
            val store = store()
            val downloads = downloads()
            downloads.auto(store, listOf(WaMediaRef(ADA, "photo"), WaMediaRef(ADA, "broken")))

            downloads.kick(store) {}.join()

            val outcomes = ops.writes.filterIsInstance<WaSyncMediaSettleRequest>().associate { it.id to it.outcome }
            assertTrue(outcomes["photo"] is WaSyncFetched.Failed)
            assertTrue(outcomes["broken"] is WaSyncFetched.Failed)
            assertTrue(dir.walkTopDown().none { it.name.endsWith(".part") })
            assertFalse(fileOf("broken", "image.jpg").exists())
            // Queued again for a later attempt, and the wake job is asked for that time.
            assertEquals(WaSyncMediaState.Queued, stateOf(store, "photo").state)
            assertEquals(1, stateOf(store, "photo").attempts)
            assertTrue(wakes.single() > 0)
        }

    @Test fun aGatewayErrorSettlesAsFailedAndAMissingFileAsGone() =
        runTest {
            seed("photo", WaSyncKind.Image, "image/jpeg")
            seed("old", WaSyncKind.Image, "image/jpeg")
            remote.files.remove("photo")
            remote.mediaErrors["old"] = RustError.NotFound("no such media")
            val store = store()
            val downloads = downloads()
            downloads.auto(store, listOf(WaMediaRef(ADA, "photo"), WaMediaRef(ADA, "old")))

            downloads.kick(store) {}.join()

            assertEquals(WaSyncMediaState.Queued, stateOf(store, "photo").state)
            assertEquals(WaSyncMediaState.Gone, stateOf(store, "old").state)
            // Gone is not tried again by the rules, only by a tap.
            assertFalse(downloads.auto(store, listOf(WaMediaRef(ADA, "old"))))
            assertTrue(downloads.tap(store, WaMediaRef(ADA, "old")))
        }

    @Test fun withoutANetworkNothingIsTriedAndTheWakeJobIsAsked() =
        runTest {
            seed("video", WaSyncKind.Video, "video/mp4")
            val store = store()
            val downloads = downloads()
            device.kind = DataNetwork.None
            assertTrue(downloads.tap(store, WaMediaRef(ADA, "video")))

            downloads.kick(store) {}.join()

            assertTrue(remote.mediaCalls.isEmpty())
            // No attempt was spent: the file is still queued, and the job runs once a network is there.
            assertEquals(0, stateOf(store, "video").attempts)
            assertEquals(WaSyncMediaState.Queued, stateOf(store, "video").state)
            assertEquals(listOf(0L), wakes)
        }

    // ---- open: reconcile

    @Test fun atOpenRowsAndFilesAreReconciledAndWhatTheStoreNamesIsDeleted() =
        runTest {
            seed("kept", WaSyncKind.Image, "image/jpeg")
            seed("lost", WaSyncKind.Image, "image/jpeg")
            val store = store()
            val downloads = downloads()
            downloads.auto(store, listOf(WaMediaRef(ADA, "kept"), WaMediaRef(ADA, "lost")))
            downloads.kick(store) {}.join()
            // The file of one row vanished; a cut-off download and a file nothing names are lying around.
            assertTrue(fileOf("lost", "image.jpg").delete())
            val part = File(fileOf("kept", "image.jpg").path + ".part").also { it.writeText("half") }
            val orphan = fileOf("nobody", "image.jpg").also { it.parentFile?.mkdirs() }
            orphan.writeText("orphan")
            val staged = File(File(File(File(dir, INSTANCE), "out"), "u1"), "never sent.pdf")
            staged.parentFile?.mkdirs()
            staged.writeText("pdf")
            val thumb = downloads.thumbFile(INSTANCE, "kept").also { it.parentFile?.mkdirs() }
            thumb.writeText("thumb")

            val touched = downloads.opened(store)

            val listed =
                ops.writes
                    .filterIsInstance<WaSyncReconcileRequest>()
                    .single()
                    .files
            // Absolute paths of the media and outbox directories; never the thumbnails.
            assertEquals(
                listOf(fileOf("kept", "image.jpg"), part, orphan, staged).map { it.absolutePath }.sorted(),
                listed?.sorted(),
            )
            assertEquals(setOf(ADA), touched)
            assertEquals(WaSyncMediaState.Evicted, stateOf(store, "lost").state)
            assertEquals(WaSyncMediaState.Ready, stateOf(store, "kept").state)
            assertTrue(fileOf("kept", "image.jpg").isFile)
            assertFalse(part.exists())
            assertFalse(orphan.exists())
            assertFalse(orphan.parentFile.exists())
            assertFalse(staged.exists())
            assertTrue(thumb.isFile)
        }

    @Test fun aDirectoryThatCannotBeListedIsHandedInAsUnknownNotAsEmpty() =
        runTest {
            seed("kept", WaSyncKind.Image, "image/jpeg")
            val store = store()
            val downloads = downloads()
            downloads.auto(store, listOf(WaMediaRef(ADA, "kept")))
            downloads.kick(store) {}.join()
            // `files` is there but is not a directory any more: its listing fails.
            val files = File(File(dir, INSTANCE), "files")
            val aside = File(folder.root, "aside")
            assertTrue(files.renameTo(aside))
            files.writeText("not a directory")

            downloads.opened(store)

            assertNull(
                ops.writes
                    .filterIsInstance<WaSyncReconcileRequest>()
                    .single()
                    .files,
            )
            // No row lost its file on the word of a failed listing.
            assertEquals(WaSyncMediaState.Ready, stateOf(store, "kept").state)
        }

    @Test fun aFirstRunWithoutADirectoryListsNothing() =
        runTest {
            val store = store()
            downloads().opened(store)
            assertEquals(
                emptyList<String>(),
                ops.writes
                    .filterIsInstance<WaSyncReconcileRequest>()
                    .single()
                    .files,
            )
        }

    // ---- the cap

    @Test fun overTheCapTheStoreLetsFilesGoAndTheyAreDeleted() =
        runTest {
            seed("first", WaSyncKind.Image, "image/jpeg")
            seed("second", WaSyncKind.Image, "image/jpeg")
            ops.capBytes = 4
            val store = store()
            val downloads = downloads()
            downloads.auto(store, listOf(WaMediaRef(ADA, "first")))
            downloads.kick(store) {}.join()
            assertTrue(fileOf("first", "image.jpg").isFile)

            // A day later (a file used in the last 24 hours is spared) the second one comes in: 6 bytes over 4.
            now += 25 * hour
            downloads.auto(store, listOf(WaMediaRef(ADA, "second")))
            changed.clear()
            downloads.kick(store) { changed += it }.join()

            assertFalse(fileOf("first", "image.jpg").exists())
            assertFalse(fileOf("first", "image.jpg").parentFile.exists())
            assertTrue(fileOf("second", "image.jpg").isFile)
            assertEquals(WaSyncMediaState.Evicted, stateOf(store, "first").state)
            // The evicted file is not asked for again by the rules of a later look: only a tap brings it back.
            assertEquals(listOf(setOf(ADA), setOf(ADA)), changed)
        }

    @Test fun aLowerCapEvictsAtOnce() =
        runTest {
            seed("first", WaSyncKind.Image, "image/jpeg")
            val store = store()
            val downloads = downloads()
            downloads.auto(store, listOf(WaMediaRef(ADA, "first")))
            downloads.kick(store) {}.join()
            now += 25 * hour
            store.setMediaCap(1).getOrThrow()

            assertEquals(setOf(ADA), downloads.evict(store))

            assertFalse(fileOf("first", "image.jpg").exists())
            assertEquals(1L, store.mediaCap().getOrThrow())
        }

    // ---- another account, an unlink

    @Test fun wipingAnInstanceDeletesItsWholeDirectoryAndOnlyThat() =
        runTest {
            seed("photo", WaSyncKind.Image, "image/jpeg")
            val store = store()
            val downloads = downloads()
            downloads.auto(store, listOf(WaMediaRef(ADA, "photo")))
            downloads.kick(store) {}.join()
            val other = File(File(dir, "inst2"), "files/x/image.jpg").also { it.parentFile?.mkdirs() }
            other.writeText("theirs")
            val outside = folder.newFile("outside.txt")

            downloads.wipe(INSTANCE, listOf(fileOf("photo", "image.jpg").absolutePath, outside.absolutePath))

            assertFalse(File(dir, INSTANCE).exists())
            assertTrue(other.isFile)
            // A path outside the media directory is never deleted, whoever names it.
            assertTrue(outside.isFile)
        }

    @Test fun mediaEventsAndRecentPageRowsAreOffered() {
        val old = NOW - WaDownloads.AUTO_RECENT_MS - 1
        val page =
            listOf(
                WaTestKit.rich(ADA, "text", NOW, "hi"),
                mediaRich("new", NOW - hour),
                mediaRich("old", old),
            )
        assertEquals(listOf(WaMediaRef(ADA, "new")), WaDownloads.refsOf(page, NOW))
    }

    private fun mediaRich(
        id: String,
        atMs: Long,
    ) = dev.rosty.core.model.wa.RichMessage(
        id,
        ADA,
        ADA,
        false,
        atMs,
        dev.rosty.core.model.wa.MessageBody
            .Media(dev.rosty.core.model.wa.MediaKind.Image, null, "image/jpeg", 3),
        emptyList(),
    )
}
