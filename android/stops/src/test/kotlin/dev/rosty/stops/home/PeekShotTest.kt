package dev.rosty.stops.home

import android.view.ViewGroup
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runAndroidComposeUiTest
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.stops.Ends
import dev.rosty.design.theme.RostyTheme
import dev.rosty.stops.AppFixture
import dev.rosty.stops.Background
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.SampleStops
import dev.rosty.stops.saved.settleRust
import dev.rosty.stops.trip.TripFixtures
import dev.rosty.testing.ShotTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The map sheet at peek, measured down to its buttons (the owner's request): with no plan (title and hint), with A
 * and B (Start, Clear, Save as stop) and during a trip (the live card's key line and its buttons). Light and dark,
 * then Turkish, font scale 2 and landscape in light.
 */
@Config(sdk = [35])
class PeekShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private enum class State(
        val id: String,
    ) {
        Empty("empty"),
        Plan("plan"),
        Trip("trip"),
    }

    private fun core(state: State): FakeStopsCore =
        when (state) {
            State.Empty -> {
                FakeStopsCore(saved = null, history = null, transitImportedMs = null)
            }

            State.Plan -> {
                FakeStopsCore(saved = onlySchool, draft = Ends(TripFixtures.a, TripFixtures.b), real = HostCore.real())
            }

            State.Trip -> {
                FakeStopsCore(trip = SampleStops.pierW to SampleStops.pierE, real = HostCore.real())
            }
        }

    private fun peekShot(
        state: State,
        suffixes: List<Pair<String, Boolean>> = listOf("-light" to false, "-dark" to true),
        fontScale: Float = 1f,
    ) {
        for ((suffix, dark) in suffixes) {
            compose.runOnUiThread { compose.activity.viewModelStore.clear() }
            val fixture = AppFixture(core(state))
            composeFrame(dark = dark, fontScale = fontScale) { remember { fixture }.Content() }
            settleRust(2000)
            compose.onRoot().captureRoboImage("screenshots/r3-peek-${state.id}$suffix.png", options)
        }
    }

    @Test fun empty() = peekShot(State.Empty)

    @Test fun plan() = peekShot(State.Plan)

    @Test fun trip() = peekShot(State.Trip)

    @Test fun turkish() {
        RuntimeEnvironment.setQualifiers("+tr")
        for (state in State.entries) peekShot(state, listOf("-tr" to false))
    }

    @Test fun largeText() {
        for (state in State.entries) peekShot(state, listOf("-fs2" to false), fontScale = 2f)
    }

    /** An 891 x 411 dp window has its own activity (as in `E71ShotTest`), with the same simulated bars. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun landscape() {
        RuntimeEnvironment.setQualifiers("+w891dp-h411dp-land")
        for (state in State.entries) {
            runAndroidComposeUiTest<ComponentActivity> {
                mainClock.autoAdvance = false
                val core = core(state)
                setContent { RostyTheme(darkTheme = false) { remember { AppFixture(core) }.Content() } }
                repeat(3) {
                    mainClock.advanceTimeByFrame()
                    waitForIdle()
                    simulateBars(activity!!)
                }
                repeat(60) {
                    Background.await { waitForIdle() }
                    mainClock.advanceTimeBy(100)
                    Thread.sleep(15)
                }
                Background.await { waitForIdle() }
                mainClock.advanceTimeByFrame()
                waitForIdle()
                onRoot().captureRoboImage("screenshots/r3-peek-${state.id}-landscape.png", options)
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

    private companion object {
        val onlySchool = SampleStops.saved.copy(stops = SampleStops.saved.stops.filter { it.name == "School" })
    }
}
