package dev.rovsti.catalog

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.design.media.PhotoViewerTags
import dev.rovsti.testing.ShotTest
import org.junit.Test

/** The E1.20 screenshots: the photos grid and the viewer at fit, zoomed, mid dismiss drag and mid page swipe. */
@OptIn(ExperimentalTestApi::class)
class E120ShotTest : ShotTest() {
    override val imageProducer: (Any, Int, Int) -> Bitmap? = ::catalogPhoto

    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun root() = compose.onNodeWithTag(PhotoViewerTags.Root)

    private fun openViewer(dark: Boolean) {
        openPhotosPage(dark)
        compose.onNodeWithTag("photo-cell-3").performClick()
        repeat(3) { advance(500) }
    }

    private fun shotOf(
        name: String,
        dark: Boolean,
    ) {
        compose.onRoot().captureRoboImage(
            "screenshots/e1.20-$name-${if (dark) "dark" else "light"}.png",
            roborazziOptions = options,
        )
    }

    @Test
    fun photosGrid() {
        for (dark in listOf(false, true)) {
            openPhotosPage(dark)
            shotOf("photos-grid", dark)
        }
    }

    @Test
    fun viewerFit() {
        for (dark in listOf(false, true)) {
            openViewer(dark)
            shotOf("viewer-fit", dark)
        }
    }

    @Test
    fun viewerZoomed() {
        for (dark in listOf(false, true)) {
            openViewer(dark)
            root().performTouchInput { doubleClick(Offset(centerX + 80f, centerY - 120f)) }
            repeat(3) { advance(400) }
            shotOf("viewer-zoomed", dark)
        }
    }

    @Test
    fun viewerDismissDrag() {
        for (dark in listOf(false, true)) {
            openViewer(dark)
            val dp = compose.activity.resources.displayMetrics.density
            root().performTouchInput {
                down(center)
                moveBy(Offset(0f, 40f * dp), 60)
                moveBy(Offset(0f, 40f * dp), 60)
                moveBy(Offset(0f, 40f * dp), 60)
            }
            advance(100)
            shotOf("viewer-dismiss-drag", dark)
            root().performTouchInput {
                advanceEventTime(400)
                up()
            }
            repeat(4) { advance(500) }
        }
    }

    @Test
    fun viewerPaging() {
        for (dark in listOf(false, true)) {
            openViewer(dark)
            root().performTouchInput {
                down(Offset(width * 0.7f, centerY))
                moveBy(Offset(-width * 0.15f, 0f), 60)
                moveBy(Offset(-width * 0.15f, 0f), 60)
                moveBy(Offset(-width * 0.15f, 0f), 60)
            }
            advance(100)
            shotOf("viewer-paging", dark)
            root().performTouchInput {
                advanceEventTime(400)
                up()
            }
            repeat(3) { advance(500) }
        }
    }
}
