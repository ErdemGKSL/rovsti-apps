package dev.rovsti.catalog

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.Test

/** The E1.18 screenshots: the Demo tab mid push, mid predictive back, mid shared-avatar flight, and mid tab switch. */
class E118ShotTest : ShotTest() {
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

    private fun openDemo(dark: Boolean) {
        composeFrame(dark = dark) { CatalogApp() }
        compose.onNodeWithTag("tab-demo").performSemanticsAction(SemanticsActions.OnClick)
        advance(800)
    }

    @Test
    fun pushMid() {
        for (dark in listOf(false, true)) {
            openDemo(dark)
            compose.onNodeWithTag("contact-row-3").performClick()
            advance(175)
            capture("e1.18-push-mid", dark)
        }
    }

    @Test
    fun predictiveBack50() {
        for (dark in listOf(false, true)) {
            openDemo(dark)
            compose.onNodeWithTag("contact-row-3").performClick()
            advance(800)
            val dispatcher = compose.activity.onBackPressedDispatcher
            compose.runOnUiThread {
                dispatcher.dispatchOnBackStarted(
                    androidx.activity.BackEventCompat(0.05f, 0.5f, 0f, androidx.activity.BackEventCompat.EDGE_LEFT),
                )
            }
            advance(40)
            compose.runOnUiThread {
                dispatcher.dispatchOnBackProgressed(
                    androidx.activity.BackEventCompat(0.05f, 0.5f, 0.5f, androidx.activity.BackEventCompat.EDGE_LEFT),
                )
            }
            advance(100)
            capture("e1.18-predictive-back-50pct", dark)
            compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
            advance(800)
        }
    }

    @Test
    fun sharedAvatarMid() {
        for (dark in listOf(false, true)) {
            openDemo(dark)
            compose.onNodeWithTag("contact-row-3").performClick()
            advance(250)
            capture("e1.18-shared-avatar-mid", dark)
        }
    }

    @Test
    fun tabSwitchMid() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { CatalogApp() }
            compose.onNodeWithTag("tab-demo").performSemanticsAction(SemanticsActions.OnClick)
            advance(100)
            capture("e1.18-tab-switch-mid", dark)
        }
    }
}
