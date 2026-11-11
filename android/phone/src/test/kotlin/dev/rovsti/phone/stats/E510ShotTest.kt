package dev.rovsti.phone.stats

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.phone.HostedPhone
import dev.rovsti.phone.PhoneHarness
import dev.rovsti.phone.PhoneRoute
import dev.rovsti.phone.PhoneSample
import dev.rovsti.testing.ShotTest
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.10 screenshots: the Statistics page on the real shell, light and dark. */
@Config(sdk = [35])
class E510ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(
        name: String,
        ui: StatsUi,
        fontScale: Float = 1f,
        after: (PhoneHarness) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val h = PhoneHarness()
            composeFrame(dark = dark, fontScale = fontScale) {
                HostedPhone(h, PhoneSample.setup(), push = PhoneRoute.Stats, stats = {
                    StatsScreen(ui, onAction = { action ->
                        if (action is StatsAction.TapBar) {
                            presentBucketSheet(h.overlays, StatsSample.bucket(), onOpen = {}, onDismissed = {})
                        }
                    }, onBack = {})
                })
            }
            advance(2_000)
            after(h)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun week() = snap("e5.10-stats-week", StatsSample.week())

    @Test fun eightWeeks() = snap("e5.10-stats-8-weeks", StatsSample.weeks8())

    @Test fun dayTapped() =
        snap("e5.10-stats-day-tapped", StatsSample.week(selected = 6)) { _ ->
            compose.onNodeWithTag(StatsTags.bar(6)).performClick()
            advance(1_800)
        }

    @Test fun records() =
        snap("e5.10-records", StatsSample.week()) { _ ->
            scrollInto(StatsTags.Longest)
            advance(600)
        }

    @Test fun empty() = snap("e5.10-stats-empty", StatsSample.empty())

    @Test fun weekBigFont() = snap("e5.10-stats-week-fs2", StatsSample.week(), fontScale = 2f)
}
