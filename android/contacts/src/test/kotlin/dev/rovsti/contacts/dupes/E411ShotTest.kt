package dev.rovsti.contacts.dupes

import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.contacts.cleanup.CleanupHarness
import dev.rovsti.contacts.cleanup.HostedCleanup
import dev.rovsti.contacts.cleanup.cleanupState
import dev.rovsti.testing.ShotTest
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.11 screenshots: the duplicates page, the merge review sheet and the Clean up chips (also at twice the font). */
@Config(sdk = [35])
class E411ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(
        name: String,
        dark: Boolean,
        fontScale: Float = 1f,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        composeFrame(dark = dark, fontScale = fontScale, content = content)
        advance(1_400)
        compose.onRoot().captureRoboImage(
            filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
            roborazziOptions = options,
        )
    }

    @Test fun duplicates() {
        for (dark in listOf(false, true)) {
            val h = DupesHarness(SampleDupes)
            snap("e4.11-duplicates", dark) { HostedDupes(h) }
        }
    }

    @Test fun mergePreview() {
        for (dark in listOf(false, true)) {
            val h = DupesHarness(SampleDupes.copy(review = SampleReview))
            snap("e4.11-merge-preview", dark) { HostedDupes(h) }
        }
    }

    @Test fun cleanupChips() {
        for (dark in listOf(false, true)) {
            val h = CleanupHarness(cleanupState())
            snap("e4.11-cleanup-chips", dark) { HostedCleanup(h) }
        }
    }

    @Test fun cleanupChipsAtTwiceTheFont() {
        for (dark in listOf(false, true)) {
            val h = CleanupHarness(cleanupState())
            snap("e4.11-cleanup-chips-fs2", dark, fontScale = 2f) { HostedCleanup(h) }
        }
    }

    @Test fun cleanupSelecting() {
        for (dark in listOf(false, true)) {
            val h = CleanupHarness(cleanupState(picked = setOf(1L, 3L, 4L), filter = "NoPhoto"))
            snap("e4.11-cleanup-select", dark) { HostedCleanup(h) }
        }
    }
}
