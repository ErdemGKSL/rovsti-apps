package dev.rovsti.shell.settings

import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.core.model.phone.HapticsLevel
import dev.rovsti.core.model.stops.Appearance
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import org.junit.Test
import org.robolectric.annotation.Config

/** The E3.11 screenshots of the common settings pages, light and dark. */
@Config(sdk = [35])
class E311SettingsShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(
        name: String,
        fontScale: Float = 1f,
        modes: List<Boolean> = listOf(false, true),
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        for (dark in modes) {
            composeFrame(dark = dark, fontScale = fontScale) { HostedPage(content) }
            advance(900)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun appearance() =
        snap(
            "e3.11-settings-appearance",
        ) { AppearancePage(selected = Appearance.Auto, onSelect = {}) }

    @Test fun haptics() =
        snap("e3.11-settings-haptics") {
            HapticsPage(selected = HapticsLevel.Light, onSelect = {}, haptics = RecordingHaptics())
        }

    @Test fun licences() = snap("e3.11-licences") { LicencesPage(realLicenceData(), onOpen = {}) }

    @Test fun licencesLargeFont() =
        snap(
            "e3.11-licences-fs2",
            fontScale = 2f,
            modes = listOf(false),
        ) { LicencesPage(realLicenceData(), onOpen = {}) }

    @Test fun licenceDetail() = snap("e3.11-licence-detail") { LicenceDetailPage("glisse", realLicenceData()) }

    @Test fun about() = snap("e3.11-about") { AboutPage(SampleAbout, onOpenLicences = {}) }
}
