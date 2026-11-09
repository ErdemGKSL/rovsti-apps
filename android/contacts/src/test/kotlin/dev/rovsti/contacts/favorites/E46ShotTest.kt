package dev.rovsti.contacts.favorites

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.6 screenshots: the tab, a row in the air during a reorder, the See All page. */
@Config(sdk = [35])
class E46ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun capture(
        name: String,
        dark: Boolean,
    ) {
        compose.onRoot().captureRoboImage(
            filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
            roborazziOptions = options,
        )
    }

    @Test fun favorites() {
        for (dark in listOf(false, true)) {
            val h = FavHarness(FavUi.state())
            composeFrame(dark = dark) { HostedFavorites(h) }
            advance(1200)
            capture("e4.6-favorites", dark)
        }
    }

    @Test fun reorderDrag() {
        for (dark in listOf(false, true)) {
            val h = FavHarness(FavUi.state(editing = true))
            composeFrame(dark = dark) { HostedFavorites(h, RecordingHaptics()) }
            advance(1200)
            val pitch =
                compose
                    .onNodeWithTag(FavTags.row(3))
                    .fetchSemanticsNode()
                    .boundsInRoot.top -
                    compose
                        .onNodeWithTag(FavTags.row(1))
                        .fetchSemanticsNode()
                        .boundsInRoot.top
            compose.onNodeWithTag(FavTags.handle(1), useUnmergedTree = true).performTouchInput { down(center) }
            advance(100)
            repeat(6) {
                compose.onNodeWithTag(FavTags.handle(1), useUnmergedTree = true).performTouchInput {
                    moveBy(
                        Offset(
                            0f,
                            pitch * 1.3f / 6f,
                        ),
                    )
                }
                advance(80)
            }
            advance(500)
            capture("e4.6-favorites-reorder-drag", dark)
            compose.onNodeWithTag(FavTags.handle(1), useUnmergedTree = true).performTouchInput { up() }
            advance(500)
        }
    }

    @Test fun upcomingPage() {
        for (dark in listOf(false, true)) {
            val h = FavHarness(FavUi.state())
            composeFrame(dark = dark) { HostedUpcoming(h) }
            advance(1200)
            capture("e4.6-upcoming-page", dark)
        }
    }
}
