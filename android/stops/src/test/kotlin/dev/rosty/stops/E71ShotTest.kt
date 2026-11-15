package dev.rosty.stops

import android.view.ViewGroup
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runAndroidComposeUiTest
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.design.theme.RostyTheme
import dev.rosty.stops.saved.settleRust
import dev.rosty.testing.ShotTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * E7.1 screenshots: the map home on the fixed test map (no tiles), its floating chrome and the sheet at peek and
 * medium, in light and dark, at font scale 2, in landscape and in Turkish. The map sheet slides in when the home
 * opens, so every frame is captured once it has settled (the plain `shot()` captures after three frames).
 */
@Config(sdk = [35])
class E71ShotTest : ShotTest() {
    /** A fresh install: no trip, no saved stop, no history, no transit feed. */
    private val empty get() = FakeStopsCore(saved = null, history = null, transitImportedMs = null)

    /** The GPUI user's files: two saved stops, one past trip, a feed, and a trip in progress. */
    private val withTrip get() = FakeStopsCore(trip = SampleStops.pierW to SampleStops.pierE)

    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun run(ms: Long) = repeat((ms / 100).toInt()) { advance(100) }

    /** Light and dark (or the [suffix] variant) of [content], each captured after the sheet has settled. */
    private fun settledShot(
        name: String,
        fontScale: Float = 1f,
        before: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark, fontScale = fontScale, content = content)
            // Real waits between the steps: the tiles load on their own threads (a busy run caught them unloaded).
            settleRust(1500)
            before()
            compose.onRoot().captureRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png", options)
        }
    }

    @Test fun idle() {
        settledShot("e7.1-home-idle") { remember { AppFixture(empty) }.Content() }
    }

    @Test fun idleTurkish() {
        RuntimeEnvironment.setQualifiers("+tr")
        settledShot("e7.1-home-idle-tr") { remember { AppFixture(empty) }.Content() }
    }

    @Test fun sheetPeek() {
        settledShot("e7.1-home-sheet-peek") { remember { AppFixture(withTrip) }.Content() }
    }

    /** The grabber's accessibility action steps the sheet from peek to medium. */
    @Test fun sheetMedium() {
        settledShot("e7.1-home-sheet-medium", before = {
            compose.onNodeWithTag("sheet-grabber").performSemanticsAction(SemanticsActions.OnClick)
            run(1500)
        }) { remember { AppFixture(withTrip) }.Content() }
    }

    @Test fun fontScale2() {
        settledShot("e7.1-home-fs2", fontScale = 2f) { remember { AppFixture(withTrip) }.Content() }
    }

    /** An 891 x 411 dp window has its own activity (as `ShotTest`'s landscape capture), with the same simulated bars. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun landscape() {
        RuntimeEnvironment.setQualifiers("+w891dp-h411dp-land")
        for (dark in listOf(false, true)) {
            runAndroidComposeUiTest<ComponentActivity> {
                mainClock.autoAdvance = false
                // The real core behind the scripted trip: the pick controller draws the trip's pins and circle with it.
                val core = FakeStopsCore(trip = SampleStops.pierW to SampleStops.pierE, real = HostCore.real())
                setContent { RostyTheme(darkTheme = dark) { remember { AppFixture(core) }.Content() } }
                repeat(3) {
                    mainClock.advanceTimeByFrame()
                    waitForIdle()
                    simulateBars(activity!!)
                }
                // The core answers on its own threads, through the main looper (and the tiles load on theirs: a busy
                // full run needed longer than three seconds).
                repeat(60) {
                    mainClock.advanceTimeBy(100)
                    Thread.sleep(15)
                }
                waitForIdle()
                onRoot().captureRoboImage(
                    "screenshots/e7.1-home-landscape-${if (dark) "dark" else "light"}.png",
                    options,
                )
            }
        }
    }

    /** `ShotTest`'s simulated status bar (28 dp) and gesture bar (24 dp). */
    private fun simulateBars(activity: ComponentActivity) {
        val density = activity.resources.displayMetrics.density
        val insets =
            WindowInsets
                .Builder()
                .setInsets(WindowInsets.Type.statusBars(), android.graphics.Insets.of(0, (28 * density).toInt(), 0, 0))
                .setInsets(
                    WindowInsets.Type.navigationBars(),
                    android.graphics.Insets.of(0, 0, 0, (24 * density).toInt()),
                ).build()
        activity.runOnUiThread {
            activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)?.dispatchApplyWindowInsets(insets)
        }
    }
}
