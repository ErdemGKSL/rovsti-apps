package dev.rovsti.phone

import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.phone.stats.StatsAction
import dev.rovsti.phone.stats.StatsSample
import dev.rovsti.phone.stats.StatsScreen
import dev.rovsti.testing.ShotTest
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.14 screenshots: Settings and Statistics at twice the font size and right-to-left, light and dark. */
@Config(sdk = [35])
class E514ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(
        name: String,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        content: @androidx.compose.runtime.Composable (PhoneHarness) -> Unit,
    ) {
        for (dark in listOf(false, true)) {
            val h = PhoneHarness()
            composeFrame(dark = dark, fontScale = fontScale, rtl = rtl) { content(h) }
            advance(2_000)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    private val settings: @androidx.compose.runtime.Composable (PhoneHarness) -> Unit =
        { h -> HostedPhone(h, PhoneSample.setup(), push = PhoneRoute.Settings) }

    private val stats: @androidx.compose.runtime.Composable (PhoneHarness) -> Unit =
        { h ->
            HostedPhone(h, PhoneSample.setup(), push = PhoneRoute.Stats, stats = {
                StatsScreen(StatsSample.week(), onAction = { _: StatsAction -> }, onBack = {})
            })
        }

    @Test fun settingsBigFont() = snap("e5.14-settings-fs2", fontScale = 2f, content = settings)

    @Test fun settingsRtl() = snap("e5.14-settings-rtl", rtl = true, content = settings)

    @Test fun statsRtl() = snap("e5.14-stats-rtl", rtl = true, content = stats)
}
