package dev.rovsti.phone.details.photo

import android.graphics.Bitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.design.media.PhotoViewerTags
import dev.rovsti.design.overlay.Detent
import dev.rovsti.phone.details.CallHarness
import dev.rovsti.phone.details.DetailsSample
import dev.rovsti.phone.details.presentCall
import dev.rovsti.phone.details.presentPerson
import dev.rovsti.phone.recents.HostedRecents
import dev.rovsti.phone.recents.RecentsHarness
import dev.rovsti.phone.recents.RecentsStrings
import dev.rovsti.testing.ShotTest
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.13 screenshots: the person sheet with its photo, the viewer, its Edit menu and the zoomed photo, light and dark. */
@OptIn(ExperimentalTestApi::class)
@Config(sdk = [35])
class E513ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    override val imageProducer: (Any, Int, Int) -> Bitmap? = { _, w, h -> portrait(w, h) }

    private val recentsStrings get() = RecentsStrings(compose.activity.resources)

    private fun snap(
        name: String,
        after: (RecentsHarness, TestPhotoOpener) -> Unit,
    ) {
        for (dark in listOf(false, true)) {
            val h = RecentsHarness()
            val opener = TestPhotoOpener(h.overlays)
            composeFrame(dark = dark) { WithOpener(opener) { HostedRecents(h, recentsStrings) } }
            advance(500)
            after(h, opener)
            advance(1_600)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    private fun openViewer(h: RecentsHarness) {
        compose.runOnUiThread {
            h.overlays.presentPerson(
                DetailsSample.person(photo = SamplePhoto),
                detent = Detent.Medium,
            )
        }
        advance(1_600)
        compose.onNodeWithTag(PERSON_AVATAR_TAG).performClick()
        advance(1_200)
    }

    @Test fun personSheetWithPhoto() =
        snap("e5.13-person-sheet") { h, _ ->
            compose.runOnUiThread { h.overlays.presentPerson(DetailsSample.person(photo = SamplePhoto)) }
        }

    @Test fun callDetailsWithPhoto() =
        snap("e5.13-call-details") { h, _ ->
            compose.runOnUiThread {
                h.overlays.presentCall(CallHarness().also { it.photo = SamplePhoto }, detent = Detent.Medium)
            }
        }

    @Test fun viewer() = snap("e5.13-viewer") { h, _ -> openViewer(h) }

    @Test fun editMenu() =
        snap("e5.13-photo-edit-menu") { h, _ ->
            openViewer(h)
            compose.onNodeWithTag(PhotoViewerTags.action("edit")).performClick()
            advance(900)
        }

    @Test fun zoomed() =
        snap("e5.13-viewer-zoomed") { h, _ ->
            openViewer(h)
            compose.onNodeWithTag(PhotoViewerTags.Root).performTouchInput { doubleClick(center) }
            advance(900)
        }
}
