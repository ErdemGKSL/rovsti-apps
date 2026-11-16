package dev.rosty.platform.media

import android.graphics.Bitmap
import android.media.ExifInterface
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The rules of the media probe (E6-WA R3), and the platform probe on a real picture under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaProbeTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun aQuarterTurnSwapsTheSides() {
        assertEquals(1080 to 1920, MediaRules.shown(1920, 1080, 90))
        assertEquals(1080 to 1920, MediaRules.shown(1920, 1080, 270))
        assertEquals(1920 to 1080, MediaRules.shown(1920, 1080, 180))
        assertEquals(1920 to 1080, MediaRules.shown(1920, 1080, -360))
        assertEquals(1080 to 1920, MediaRules.shown(1920, 1080, -90))
    }

    @Test fun thePreviewIsAHundredPixelsOnItsLongerSide() {
        assertEquals(100 to 56, MediaRules.preview(1920, 1080))
        assertEquals(56 to 100, MediaRules.preview(1080, 1920))
        assertEquals(100 to 100, MediaRules.preview(512, 512))
        // A small picture is not blown up; a thin one keeps at least a pixel.
        assertEquals(64 to 48, MediaRules.preview(64, 48))
        assertEquals(100 to 1, MediaRules.preview(10_000, 20))
        assertEquals(0 to 0, MediaRules.preview(0, 10))
    }

    @Test fun theDecodeStepKeepsTwiceThePreview() {
        assertEquals(16, MediaRules.sampleSize(4000, 3000))
        assertEquals(1, MediaRules.sampleSize(300, 200))
        assertEquals(4, MediaRules.sampleSize(800, 600))
    }

    @Test fun aVideoLengthIsRoundedToWholeSeconds() {
        assertEquals(42, MediaRules.seconds(42_400))
        assertEquals(43, MediaRules.seconds(42_500))
        assertEquals(1, MediaRules.seconds(200))
        assertNull(MediaRules.seconds(0))
        assertNull(MediaRules.seconds(null))
    }

    @Test fun theJpegTakesTheFirstQualityThatFits() {
        val tried = mutableListOf<Int>()
        val picked =
            MediaRules.jpeg { quality ->
                tried += quality
                ByteArray(if (quality > 50) MediaRules.MAX_THUMBNAIL_BYTES + 1 else 10)
            }
        assertEquals(listOf(70, 50), tried)
        assertEquals(10, picked?.size)
        assertNull(MediaRules.jpeg { ByteArray(MediaRules.MAX_THUMBNAIL_BYTES + 1) })
        assertNull(MediaRules.jpeg { null })
        assertTrue(MediaRules.MAX_THUMBNAIL_BYTES < 64 * 1024)
    }

    @Test fun exifOrientationsTurnThePicture() {
        assertEquals(90, MediaRules.exifDegrees(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(180, MediaRules.exifDegrees(ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(270, MediaRules.exifDegrees(ExifInterface.ORIENTATION_ROTATE_270))
        assertEquals(0, MediaRules.exifDegrees(ExifInterface.ORIENTATION_NORMAL))
        assertEquals(0, MediaRules.exifDegrees(ExifInterface.ORIENTATION_UNDEFINED))
    }

    @Test fun aPhotoIsMeasuredAndPreviewed() =
        runBlocking {
            val file = File(folder.root, "wide.png")
            val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val measured = AndroidMediaProbe(kotlinx.coroutines.Dispatchers.Unconfined).measure(file, "image/png")
            assertEquals(400, measured?.width)
            assertEquals(300, measured?.height)
            assertNull(measured?.seconds)
            val preview = measured?.thumbnail
            assertTrue(preview != null && preview.size <= MediaRules.MAX_THUMBNAIL_BYTES)
        }

    @Test fun aDocumentOrAnUnreadableFileMeasuresAsNothing() =
        runBlocking {
            val probe = AndroidMediaProbe(kotlinx.coroutines.Dispatchers.Unconfined)
            val text = File(folder.root, "notes.txt").apply { writeText("hello") }
            assertNull(probe.measure(text, "text/plain"))
            assertNull(probe.measure(text, "image/jpeg"))
        }
}
