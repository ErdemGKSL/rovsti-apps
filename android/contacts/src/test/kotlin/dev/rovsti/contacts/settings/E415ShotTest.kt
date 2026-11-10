package dev.rovsti.contacts.settings

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.core.model.search.NameFormat
import dev.rovsti.testing.ShotTest
import dev.rovsti.testing.ShotVariant
import dev.rovsti.wa.settings.WhatsAppSettingsScreen
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.15 screenshots: the Settings root (top and end), the choice page, the accounts summary and WhatsApp. */
@Config(sdk = [35])
class E415ShotTest : ShotTest() {
    private fun root(h: SettingsHarness) =
        @androidx.compose.runtime.Composable {
            HostedSettings(h) { ContactsSettingsPage(h.ui, h.common, h.links(), h::on) }
        }

    @Test fun settingsRoot() {
        val h = SettingsHarness(SettingsSample.state(reminders = true, accounts = true))
        shot("e4.15-settings", variants = setOf(ShotVariant.Fs2), content = root(h))
    }

    @Test fun settingsEnd() {
        val h = SettingsHarness(SettingsSample.state(accounts = true))
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { root(h)() }
            advance(700)
            repeat(3) {
                compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
                advance(600)
            }
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/e4.15-settings-end-${if (dark) "dark" else "light"}.png",
                roborazziOptions =
                    RoborazziOptions(
                        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD),
                    ),
            )
        }
    }

    @Test fun nameFormat() {
        val h = SettingsHarness()
        shot("e4.15-name-format", variants = setOf(ShotVariant.Rtl)) {
            HostedSettings(h, ContactsRoute.SettingsNameFormat) {
                ChoicePage(
                    title = "Name Format",
                    options = nameFormatOptions { if (it == NameFormat.FirstLast) "First Last" else "Last, First" },
                    selected = h.ui.nameFormat,
                    onSelect = {},
                    footer = "How names are written in the list and on a contact's page.",
                )
            }
        }
    }

    @Test fun accountsSummary() {
        val h = SettingsHarness()
        shot("e4.15-accounts-summary", variants = setOf(ShotVariant.Fs2)) {
            HostedSettings(h, ContactsRoute.SettingsSummary) { SummaryPage(h.ui.summary) }
        }
    }

    @Test fun whatsApp() {
        val h = SettingsHarness()
        val view = fixtureView("")
        shot("e4.15-whatsapp") {
            HostedSettings(h, ContactsRoute.WhatsAppSettings) {
                WhatsAppSettingsScreen(stateOf(view), {})
            }
        }
    }
}
