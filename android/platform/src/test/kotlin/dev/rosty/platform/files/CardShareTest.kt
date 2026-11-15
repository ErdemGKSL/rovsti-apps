package dev.rosty.platform.files

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import dev.rosty.bridge.RostyShareProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** X.2 step 6: the share intent, the gallery save and the cache of finished cards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardShareTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val work = File(context.cacheDir, "work").apply { mkdirs() }

    @Before fun clean() {
        MediaShare.cardsDir(context).listFiles()?.forEach { it.delete() }
        work.listFiles()?.forEach { it.delete() }
    }

    private fun png(
        name: String,
        alpha: Boolean,
    ): File =
        File(work, name).also { file ->
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.argb(if (alpha) 0 else 255, 200, 100, 50))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

    private fun gif(name: String): File =
        File(work, name).apply { writeBytes("GIF89a".toByteArray() + ByteArray(20) { it.toByte() }) }

    @Test fun anOpaqueStillLeavesAsJpegInTheCardsFolder() {
        val staged = CardShare.stage(context, png("a.png", alpha = false).path)!!

        assertEquals(CardFormat.Jpeg, staged.format)
        assertEquals("image/jpeg", staged.mime)
        assertEquals(MediaShare.cardsDir(context), staged.file.parentFile)
        assertTrue(staged.file.name.matches(Regex("[0-9a-f]{16}\\.jpg")))
        assertEquals(CardFormat.Jpeg, CardShare.sniff(staged.file.readBytes().copyOf(16)))
    }

    @Test fun aTransparentStillStaysPng() {
        val staged = CardShare.stage(context, png("t.png", alpha = true).path)!!

        assertEquals(CardFormat.Png, staged.format)
        assertEquals("image/png", staged.mime)
        assertTrue(staged.file.name.endsWith(".png"))
    }

    @Test fun anAnimatedCardKeepsItsGifBytes() {
        val source = gif("c.gif")
        val animated = CardShare.stage(context, source.path)!!
        assertEquals(CardFormat.Gif, animated.format)
        assertEquals("image/gif", animated.mime)
        assertTrue(animated.file.readBytes().contentEquals(source.readBytes()))
    }

    @Test fun webpIsRecognisedAndOnlyKnownChatAppsAreAskedForIt() {
        val head = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray()
        assertEquals(CardFormat.WebP, CardShare.sniff(head))
        assertEquals("image/webp", CardFormat.WebP.mime)
        assertTrue(CardShare.prefersWebP("com.whatsapp"))
        assertFalse(CardShare.prefersWebP("com.example.mail"))
        assertFalse(CardShare.prefersWebP(null))
        assertNull(CardShare.sniff(ByteArray(16)))
    }

    @Test fun aMissingOrUnknownFileStagesNothing() {
        assertNull(CardShare.stage(context, File(work, "none.png").path))
        assertNull(CardShare.stage(context, File(work, "junk.bin").apply { writeBytes(ByteArray(32)) }.path))
    }

    @Test fun theShareIntentCarriesTheMimeTheGrantAndAProviderUri() {
        val staged = CardShare.stage(context, png("i.png", alpha = false).path)!!

        val chooser = CardShare.shareIntent(staged.uri, staged.mime, "Ayse-card.jpg")

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/jpeg", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val stream = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals(MediaShare.authority(context), stream.authority)
        assertEquals(listOf("cards", staged.file.name), stream.pathSegments)
        assertEquals(stream, send.clipData!!.getItemAt(0).uri)
    }

    @Test fun theProviderServesACardAndRefusesOtherDepths() {
        val staged = CardShare.stage(context, png("p.png", alpha = false).path)!!
        val provider =
            Robolectric.buildContentProvider(RostyShareProvider::class.java).create(MediaShare.authority(context)).get()

        assertEquals("image/jpeg", provider.getType(staged.uri))
        val fd = provider.openFile(staged.uri, "r")
        assertNotNull(fd)
        fd!!.close()
        assertNull(provider.openFile(staged.uri, "w"))
        val deep =
            Uri.parse("content://${MediaShare.authority(context)}/cards/x/${staged.file.name}")
        assertNull(provider.openFile(deep, "r"))
        val elsewhere = Uri.parse("content://${MediaShare.authority(context)}/other/${staged.file.name}")
        assertNull(provider.openFile(elsewhere, "r"))
    }

    @Test fun theCacheHoldsAtMostTwentyFilesAndTheOldestGoFirst() {
        val dir = MediaShare.cardsDir(context)
        repeat(25) { i ->
            File(dir, "old$i.jpg").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(1_000L + i)
            }
        }

        assertEquals(5, CardShare.prune(dir))

        val left = dir.listFiles()!!.map { it.name }.toSet()
        assertEquals(20, left.size)
        assertFalse("old0.jpg" in left)
        assertTrue("old24.jpg" in left)
        assertEquals(0, CardShare.prune(dir))
    }

    @Test fun stagingPrunesTheFolderToTwentyFiles() {
        val dir = MediaShare.cardsDir(context)
        repeat(25) { i ->
            File(dir, "old$i.jpg").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(1_000L + i)
            }
        }

        val staged = CardShare.stage(context, png("n.png", alpha = false).path)!!

        assertEquals(20, dir.listFiles()!!.size)
        assertTrue(staged.file.isFile)
    }

    /** Records what the gallery insert does; stands in for the MediaStore provider. */
    class FakeMedia : ContentProvider() {
        val inserted = mutableListOf<ContentValues>()
        val updated = mutableListOf<ContentValues>()
        var deleted = 0
        var fail = false

        override fun onCreate() = true

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? {
            inserted += ContentValues(values)
            return Uri.parse("content://media/external/images/media/7")
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            s: String?,
            a: Array<out String>?,
        ): Int {
            updated += ContentValues(values)
            return 1
        }

        override fun delete(
            uri: Uri,
            s: String?,
            a: Array<out String>?,
        ): Int {
            deleted++
            return 1
        }

        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor? {
            if (fail) return null
            val out = File.createTempFile("gallery", ".bin")
            return ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_WRITE)
        }

        override fun query(
            uri: Uri,
            p: Array<out String>?,
            s: String?,
            a: Array<out String>?,
            o: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null
    }

    private fun media(): FakeMedia =
        Robolectric.buildContentProvider(FakeMedia::class.java).create(MediaStore.AUTHORITY).get()

    @Test fun theGalleryInsertGoesToPicturesRostyPendingAndThenClearsPending() {
        val media = media()
        val card = CardShare.stage(context, png("g.png", alpha = true).path)!!

        assertTrue(MediaShare.saveToGallery(context, card.file.path, "Ayse-card.png", card.mime))

        val row = media.inserted.single()
        assertEquals("Pictures/Rosty", row.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertEquals("Ayse-card.png", row.getAsString(MediaStore.Images.Media.DISPLAY_NAME))
        assertEquals("image/png", row.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals(1, row.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertEquals(0, media.updated.single().getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertEquals(0, media.deleted)
    }

    @Test fun aFailedCopyDeletesTheRowAndAnswersFalse() {
        val media = media().also { it.fail = true }
        val card = CardShare.stage(context, png("h.png", alpha = true).path)!!

        assertFalse(MediaShare.saveToGallery(context, card.file.path, "x.png", card.mime))

        assertEquals(1, media.deleted)
        assertTrue(media.updated.isEmpty())
    }
}
