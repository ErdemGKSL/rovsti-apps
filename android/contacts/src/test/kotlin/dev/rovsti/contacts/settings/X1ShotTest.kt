package dev.rovsti.contacts.settings

import androidx.compose.runtime.Composable
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.design.text.UiText
import dev.rovsti.shell.settings.SettingsPage
import dev.rovsti.testing.ShotTest
import dev.rovsti.testing.ShotVariant
import org.junit.Test
import org.robolectric.annotation.Config

/** X.1b screenshots: the "WhatsApp Names and Photos" group of the Contacts settings. */
@Config(sdk = [35])
class X1ShotTest : ShotTest() {
    private fun group(sync: PeopleSyncUi) =
        @Composable {
            HostedSettings(SettingsHarness(), ContactsRoute.Settings) {
                SettingsPage(title = "Settings") { PeopleSyncGroup(sync) {} }
            }
        }

    @Test fun peopleSyncRow() {
        shot(
            "x1-people-sync-row",
            variants = setOf(ShotVariant.Fs2),
            content = group(PeopleSyncUi(UiText.Literal("5 min. ago"))),
        )
    }

    @Test fun peopleSyncRowSyncing() {
        shot("x1-people-sync-row-syncing", content = group(PeopleSyncUi(UiText.Literal("5 min. ago"), running = true)))
    }

    @Test fun peopleSyncRowNeverAndFailed() {
        shot(
            "x1-people-sync-row-never",
            content = group(PeopleSyncUi(UiText.Res(dev.rovsti.contacts.R.string.settings_people_never))),
        )
    }

    @Config(qualifiers = "+tr")
    @Test
    fun peopleSyncRowTurkish() {
        shot("x1-people-sync-row-tr", content = group(PeopleSyncUi(UiText.Literal("5 dk. önce"))))
    }
}
