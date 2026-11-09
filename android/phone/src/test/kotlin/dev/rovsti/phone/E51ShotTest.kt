package dev.rovsti.phone

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.phone.setup.SetupSheetScreen
import dev.rovsti.phone.setup.SetupUiState
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.1 screenshots: the Setup sheet over Recents, Diagnostics, the notice line and the tab bar, light and dark. */
@Config(sdk = [35])
class E51ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(
        name: String,
        setup: SetupUiState,
        harness: () -> PhoneHarness = { PhoneHarness() },
        before: (PhoneHarness) -> Unit = {},
        content: @androidx.compose.runtime.Composable (PhoneHarness) -> Unit,
    ) {
        for (dark in listOf(false, true)) {
            val h = harness()
            composeFrame(dark = dark) { content(h) }
            advance(500)
            before(h)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun setupSheet() {
        val setup = PhoneSample.setup()
        snap("e5.1-setup-sheet", setup, content = { h -> HostedPhone(h, setup) }, before = { h ->
            compose.runOnUiThread {
                h.overlays.present(
                    SheetEntry(
                        "Setup",
                        persistentListOf(Detent.Medium, Detent.Large),
                        initialDetent = Detent.Large,
                    ) { _ ->
                        SetupSheetScreen(setup, onAction = {})
                    },
                )
            }
            advance(1_400)
        })
    }

    @Test fun setupSheetAtLargeFont() {
        val setup = PhoneSample.setup(changedTitle = "Something changed: Notifications")
        for (dark in listOf(false, true)) {
            val h = PhoneHarness()
            composeFrame(dark = dark, fontScale = 2f) { HostedPhone(h, setup) }
            advance(500)
            compose.runOnUiThread {
                h.overlays.present(
                    SheetEntry(
                        "Setup",
                        persistentListOf(Detent.Medium, Detent.Large),
                        initialDetent = Detent.Large,
                    ) { _ ->
                        SetupSheetScreen(setup, onAction = {})
                    },
                )
            }
            advance(1_400)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/e5.1-setup-sheet-fs2-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun diagnostics() {
        val setup = PhoneSample.setup()
        snap("e5.1-diagnostics", setup, content = { h -> HostedPhone(h, setup, push = PhoneRoute.Diagnostics) })
    }

    @Test fun noticeLine() {
        val setup = PhoneSample.setup()
        snap("e5.1-notice-line", setup, content = { h -> HostedPhone(h, setup) })
    }

    @Test fun tabs() {
        val setup = PhoneSample.setup(roleHeld = true)
        snap("e5.1-tabs", setup, content = { h -> HostedPhone(h, setup, tab = KeypadTab) })
    }

    @Test fun settings() {
        val setup = PhoneSample.setup()
        snap("e5.1-settings", setup, content = { h -> HostedPhone(h, setup, push = PhoneRoute.Settings) })
    }
}
