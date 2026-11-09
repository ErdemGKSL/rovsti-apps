package dev.rovsti.contacts.card

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import dev.rovsti.core.model.qr.QrMatrix
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.OverlayHost
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.platform.files.FakeFilesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files

/** E4.9: the QR sheet's buttons call the files seam once each and the sheet leaves by drag. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class QrSheetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val files = FakeFilesRepository(staged = Uri.parse("content://x/qr.png"))
    private val staging = Files.createTempDirectory("qr-sheet").toFile().apply { deleteOnExit() }
    private val controller = OverlayController()
    private val shared = mutableListOf<Uri>()

    private fun model(): QrSheetModel {
        val side = 25
        // A checkerboard: a valid-enough module grid for drawing and encoding to a PNG.
        val bits = ByteArray((side * side + 7) / 8) { 0x55 }
        val b64 =
            java.util.Base64
                .getEncoder()
                .encodeToString(bits)
        return QrSheetModel("Ayşe Yılmaz", QrMatrix.of(side, b64), "ayse-qr.png", files, staging)
    }

    private var entry: dev.rovsti.design.overlay.SheetEntry? = null

    private fun show() {
        compose.setContent {
            RovstiTheme(darkTheme = false) { Host() }
        }
        compose.runOnUiThread { entry = controller.presentQrSheet(model()) { shared += it } }
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
    }

    @Composable
    private fun Host() {
        OverlayHost(controller) { Box(Modifier.fillMaxSize()) }
    }

    @Test fun saveCallsTheGalleryOnceAndShowsTheToast() {
        show()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(QrSheetTags.Save).performClick()
        compose.waitUntil(WAIT_MS) { files.gallery.isNotEmpty() }
        repeat(6) { compose.mainClock.advanceTimeBy(100) }
        assertEquals(1, files.gallery.size)
        val (path, name, mime) = files.gallery.single()
        assertEquals("ayse-qr.png", name)
        assertEquals("image/png", mime)
        val png = java.io.File(path).readBytes()
        assertEquals("PNG magic", 0x89.toByte(), png[0])
        compose.onNodeWithText("Saved to Photos").assertIsDisplayed()
    }

    @Test fun shareStagesThePngAndHandsTheUriOver() {
        show()
        compose.onNodeWithTag(QrSheetTags.Share).performClick()
        compose.waitUntil(WAIT_MS) { shared.isNotEmpty() }
        assertEquals(listOf(files.staged!!), shared)
        assertEquals("ayse-qr.png", files.stagedPaths.single().second)
    }

    @Test fun theSheetLeavesWhenDraggedDown() {
        show()
        compose.onNodeWithTag(QrSheetTags.Code).assertIsDisplayed()
        compose.onNodeWithTag(QrSheetTags.Sheet).performTouchInput { swipeDown(durationMillis = 120) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertTrue("the sheet is gone", entry!!.sheet!!.isGone)
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
