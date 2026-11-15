package dev.rosty.stops.tools

import android.Manifest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.stops.AppFixture
import dev.rosty.stops.home.HomeMapTag
import dev.rosty.stops.home.HomeTags
import dev.rosty.stops.pick.PickFixtures
import dev.rosty.stops.saved.settleRust
import dev.rosty.testing.ShotTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** E7.9 screenshots on the real core: map options, the measure card and its menu, my location, follow mode. */
@Config(sdk = [35])
class E79ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = ShotTest.CHANGE_THRESHOLD))

    private fun toolShot(
        name: String,
        fontScale: Float = 1f,
        then: (AppFixture) -> Unit,
    ) {
        for (dark in listOf(false, true)) {
            compose.runOnUiThread { compose.activity.viewModelStore.clear() }
            val fixture = PickFixtures.app()
            val permissions =
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            shadowOf(fixture.app).grantPermissions(*permissions)
            fixture.container.permissions.refreshNow { false }
            composeFrame(dark = dark, fontScale = fontScale) { fixture.Content() }
            settleRust()
            then(fixture)
            compose.onRoot().captureRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png", options)
        }
    }

    private fun press(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        settleRust(800)
    }

    private fun menu(text: String) {
        compose.onNode(hasText(text) and hasClickAction()).performClick()
        settleRust(800)
    }

    private fun measureThreePoints(item: String = "Measure distance") {
        press(HomeTags.Layers)
        menu(item)
        for ((fx, fy) in listOf(0.25f to 0.32f, 0.6f to 0.28f, 0.7f to 0.5f)) {
            compose.onNodeWithTag(HomeMapTag).performTouchInput { click(Offset(width * fx, height * fy)) }
            settleRust(800)
        }
    }

    @Test fun mapOptionsPopover() = toolShot("e7.9-map-options-popover") { press(HomeTags.Options) }

    @Test fun measureTwoLegs() = toolShot("e7.9-measure-two-legs") { measureThreePoints() }

    @Test fun measureTurkish() {
        RuntimeEnvironment.setQualifiers("+tr")
        toolShot("e7.9-measure-two-legs-tr") { measureThreePoints("Mesafe ölç") }
    }

    @Test fun measureLargeText() = toolShot("e7.9-measure-two-legs-fs2", fontScale = 2f) { measureThreePoints() }

    @Test fun myLocationLargeText() =
        toolShot("e7.9-my-location-sheet-fs2", fontScale = 2f) {
            compose
                .onNodeWithTag(HomeTags.Locate, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.OnLongClick)
            settleRust()
        }

    @Test fun measureMoreMenu() =
        toolShot("e7.9-measure-more-menu") {
            measureThreePoints()
            compose.onNodeWithTag(ToolTags.MeasureMore, useUnmergedTree = true).performClick()
            settleRust(800)
        }

    @Test fun myLocationSheet() =
        toolShot("e7.9-my-location-sheet") {
            compose
                .onNodeWithTag(HomeTags.Locate, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.OnLongClick)
            settleRust()
        }

    @Test fun followMode() =
        toolShot("e7.9-follow-mode") {
            press(HomeTags.Locate)
            press(HomeTags.Locate)
        }
}
