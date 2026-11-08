package dev.rovsti.testing

import android.os.Looper
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runAndroidComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.design.glass.GlassConditions
import dev.rovsti.design.glass.LocalGlassConditions
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.time.LocalRovstiClock
import dev.rovsti.design.time.RovstiClock
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.TimeZone

/**
 * Base class of every screenshot and Compose UI test.
 *
 * Pins everything that could make a golden differ between runs: native graphics, sdk 35,
 * a 411 x 891 dp xxhdpi window, en-US, UTC, a manual Compose clock and a [FixedClock].
 * (No randomness exists in the design system yet; avatar colours are a pure hash of the name.
 * TODO(E1.20): install a fake image fetcher here when Coil arrives.)
 *
 * [shot] writes `screenshots/<name>-light.png` and `-dark.png` (plus `-fs2-*`, `-rtl-*` and
 * `-landscape-*` for the requested [ShotVariant]s) into the test module's directory. Record with
 * `recordRoborazziDebug`, compare with `verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = ShotTest.PORTRAIT)
abstract class ShotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The clock composables see through [LocalRovstiClock]. */
    protected open val clock: RovstiClock = FixedClock()

    private class Frame(
        val id: Int,
        val dark: Boolean,
        val fontScale: Float,
        val rtl: Boolean,
        val content: @Composable () -> Unit,
    )

    private var frame by mutableStateOf<Frame?>(null)
    private var frameCounter = 0
    private var contentSet = false

    @Before
    fun pinEnvironment() {
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        compose.mainClock.autoAdvance = false
    }

    /** Renders [content] without capturing; for UI tests that assert on semantics. */
    fun composeFrame(
        dark: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        val next = Frame(frameCounter++, dark, fontScale, rtl, content)
        if (!contentSet) {
            contentSet = true
            frame = next
            compose.setContent { frame?.let { Host(it) } }
        } else {
            frame = next
        }
        settle()
        // Compose re-requests the real (empty) insets when a composition attaches its listener, which lands
        // after the first dispatch; applying twice with frames in between makes the simulated bars stick.
        applySimulatedInsets()
        settle()
        applySimulatedInsets()
        settle()
    }

    /** Renders [content] in light and dark (and each requested variant) and captures every one. */
    fun shot(
        name: String,
        variants: Set<ShotVariant> = emptySet(),
        content: @Composable () -> Unit,
    ) {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            capture("$name-$theme", composeDark = dark, content = content)
        }
        for (variant in ShotVariant.entries.filter { it in variants }) {
            for (dark in listOf(false, true)) {
                val file = "$name-${variant.suffix}-${if (dark) "dark" else "light"}"
                when (variant) {
                    ShotVariant.Fs2 -> {
                        capture(file, composeDark = dark, fontScale = 2f, content = content)
                    }

                    ShotVariant.Rtl -> {
                        capture(file, composeDark = dark, rtl = true, content = content)
                    }

                    ShotVariant.Landscape -> {
                        captureLandscape(file, dark, content)
                    }
                }
            }
        }
    }

    /**
     * Changing the Robolectric qualifiers recreates the rule's activity and drops its content, so the
     * landscape window gets its own activity. Put landscape in the last `shot()` call of a test.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun captureLandscape(
        file: String,
        dark: Boolean,
        content: @Composable () -> Unit,
    ) {
        RuntimeEnvironment.setQualifiers(LANDSCAPE)
        try {
            runAndroidComposeUiTest<ComponentActivity> {
                mainClock.autoAdvance = false
                val next = Frame(frameCounter++, dark, 1f, false, content)
                setContent { Host(next) }
                repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }
                waitForIdle()
                dispatchSimulatedInsets(activity!!)
                repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }
                dispatchSimulatedInsets(activity!!)
                repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }
                waitForIdle()
                onRoot().captureRoboImage(filePath = "screenshots/$file.png", roborazziOptions = shotOptions)
            }
        } finally {
            RuntimeEnvironment.setQualifiers(PORTRAIT_DELTA)
        }
    }

    private fun capture(
        file: String,
        composeDark: Boolean,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        composeFrame(dark = composeDark, fontScale = fontScale, rtl = rtl, content = content)
        compose.onRoot().captureRoboImage(filePath = "screenshots/$file.png", roborazziOptions = shotOptions)
    }

    private val shotOptions =
        RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD),
        )

    @Composable
    private fun Host(current: Frame) {
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(base.density, current.fontScale),
            LocalLayoutDirection provides if (current.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            LocalRovstiClock provides clock,
            // Glass is Flat in every test: JVM renderers do not run RenderEffect or AGSL like a GPU.
            LocalGlassConditions provides GlassConditions.Default.copy(forTests = true),
        ) {
            RovstiTheme(darkTheme = current.dark) {
                key(current.id) {
                    Box(Modifier.fillMaxSize()) { current.content() }
                }
            }
        }
    }

    /** Advances the manual clock and the main looper, then re-dispatches the simulated bars (UI tests that interact). */
    fun advance(millis: Long = 500) {
        compose.mainClock.advanceTimeBy(millis)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        applySimulatedInsets()
        compose.mainClock.advanceTimeByFrame()
    }

    private fun settle() {
        repeat(SETTLE_FRAMES) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
    }

    /**
     * Robolectric reports no system bars. Dispatch a status bar and a gesture-navigation bar so
     * screenshots show whether content is padded with the insets (edge-to-edge like production).
     */
    private fun applySimulatedInsets() {
        compose.runOnUiThread { dispatchSimulatedInsets(compose.activity) }
    }

    private fun dispatchSimulatedInsets(activity: ComponentActivity) {
        val density = activity.resources.displayMetrics.density
        val insets =
            WindowInsets
                .Builder()
                .setInsets(
                    WindowInsets.Type.statusBars(),
                    android.graphics.Insets.of(0, (STATUS_BAR_DP * density).toInt(), 0, 0),
                ).setInsets(
                    WindowInsets.Type.navigationBars(),
                    android.graphics.Insets.of(0, 0, 0, (NAV_BAR_DP * density).toInt()),
                ).build()
        activity.runOnUiThread {
            activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)?.dispatchApplyWindowInsets(insets)
        }
    }

    companion object {
        const val PORTRAIT = "en-rUS-w411dp-h891dp-xxhdpi"
        private const val LANDSCAPE = "+w891dp-h411dp-land"
        private const val PORTRAIT_DELTA = "+w411dp-h891dp-port"
        const val CHANGE_THRESHOLD = 0.005f
        private const val SETTLE_FRAMES = 3
        private const val STATUS_BAR_DP = 28
        private const val NAV_BAR_DP = 24
    }
}
