package dev.rosty.messages.wa

import android.content.Intent
import android.net.Uri
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMediaKeepRequest
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import dev.rosty.platform.files.GalleryTarget
import dev.rosty.platform.files.GalleryWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A MediaStore that remembers what was written; [deleted] copies are gone from the gallery. */
class FakeGalleryWriter : GalleryWriter {
    val written = mutableListOf<Triple<String, String, String>>()
    val deleted = mutableSetOf<String>()
    var fails = false

    override suspend fun write(
        path: String,
        name: String,
        mime: String,
    ): String? {
        if (fails) return null
        written += Triple(path, name, mime)
        return "content://media/external/file/${written.size}"
    }

    override suspend fun exists(uri: String): Boolean = uri !in deleted
}

/** Save and Share of a WhatsApp file (E6-WA R3): one MediaStore copy per message, recorded in the store. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaMediaSaverTest {
    @get:Rule val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val writer = FakeGalleryWriter()

    private fun keeps(kit: WaTestKit) = kit.ops.writes.filterIsInstance<WaSyncMediaKeepRequest>()

    @Test fun aSaveWritesOneCopyRecordsItAndASecondSaveIsANoOp() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope, io = dispatcher)
            kit.message(WaTestKit.media(ADA, "p1", NOW, WaSyncKind.Image, "image/jpeg", 3))
            kit.offline.start()
            settle()
            val saver =
                WaMediaSaver({
                    kit.offline.session.value
                        ?.store
                }, writer)
            val file = File(folder.root, "harbour.jpg").apply { writeText("IMG") }

            val first = saver.save(ADA, "p1", file, "harbour.jpg", "image/jpeg")
            assertEquals(WaSaved.Saved("content://media/external/file/1", GalleryTarget.Pictures), first)
            assertEquals(listOf(Triple(file.absolutePath, "harbour.jpg", "image/jpeg")), writer.written)
            // The copy is recorded on the message: it keeps the cached file out of eviction.
            assertEquals("content://media/external/file/1", keeps(kit).single().savedUri)
            assertEquals("content://media/external/file/1", saver.savedUri(ADA, "p1"))

            // Saved twice: nothing is written again.
            assertEquals(
                WaSaved.Already("content://media/external/file/1"),
                saver.save(ADA, "p1", file, "harbour.jpg", "image/jpeg"),
            )
            assertEquals(1, writer.written.size)
            assertEquals(1, keeps(kit).size)

            // The user deleted the copy in the gallery: Save writes a new one.
            writer.deleted += "content://media/external/file/1"
            val again = saver.save(ADA, "p1", file, "harbour.jpg", "image/jpeg")
            assertEquals(WaSaved.Saved("content://media/external/file/2", GalleryTarget.Pictures), again)
            assertEquals("content://media/external/file/2", keeps(kit).last().savedUri)
        }

    @Test fun eachKindGoesToItsCollectionAndAViewOnceMessageIsNeverSaved() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope, io = dispatcher)
            kit.message(WaTestKit.media(ADA, "v1", NOW, WaSyncKind.Video, "video/mp4", 3))
            kit.message(WaTestKit.media(ADA, "d1", NOW, WaSyncKind.Document, "application/pdf", 3))
            kit.message(WaTestKit.media(ADA, "o1", NOW, WaSyncKind.Image, "image/jpeg", 3))
            kit.offline.start()
            settle()
            val saver =
                WaMediaSaver({
                    kit.offline.session.value
                        ?.store
                }, writer)
            val file = File(folder.root, "f.bin").apply { writeText("X") }

            assertEquals(
                GalleryTarget.Movies,
                (saver.save(ADA, "v1", file, "v.mp4", "video/mp4") as WaSaved.Saved).target,
            )
            assertEquals(
                GalleryTarget.Downloads,
                (saver.save(ADA, "d1", file, "Ferry timetable.pdf", "application/pdf") as WaSaved.Saved).target,
            )
            assertEquals(WaSaved.NotAllowed, saver.save(ADA, "o1", file, "o.jpg", "image/jpeg", viewOnce = true))
            assertEquals(2, writer.written.size)

            // A copy that could not be written is not recorded.
            writer.fails = true
            kit.message(WaTestKit.media(ADA, "p9", NOW, WaSyncKind.Image, "image/jpeg", 3))
            assertEquals(WaSaved.Failed, saver.save(ADA, "p9", file, "p.jpg", "image/jpeg"))
            assertTrue(keeps(kit).none { it.id == "p9" })
        }

    @Test fun theShareSheetCarriesTheStreamItsTypeAndTheReadGrant() {
        val uri = Uri.parse("content://dev.rosty.messages.rostyshare/1790000000000-harbour.jpg")
        val chooser = waShareIntent(uri, "image/jpeg", "harbour.jpg")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/jpeg", send.type)
        @Suppress("DEPRECATION")
        assertEquals(uri, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("harbour.jpg", send.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        // No type known: any file.
        @Suppress("DEPRECATION")
        val untyped = waShareIntent(uri, "", "x").getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("*/*", untyped.type)
    }
}
