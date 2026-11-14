package dev.rovsti.contacts.settings

import androidx.compose.runtime.Composable
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.contacts.R
import dev.rovsti.design.text.UiText
import dev.rovsti.shell.settings.SettingsPage
import dev.rovsti.testing.ShotTest
import dev.rovsti.testing.ShotVariant
import org.junit.Test
import org.robolectric.annotation.Config

/** X.1b and people sync v2 screenshots: the "WhatsApp Names and Photos" group of the Contacts settings, per state. */
@Config(sdk = [35])
class X1ShotTest : ShotTest() {
    private fun group(sync: PeopleSyncUi) =
        @Composable {
            HostedSettings(SettingsHarness(), ContactsRoute.Settings) {
                SettingsPage(title = "Settings") { PeopleSyncGroup(sync, {}) }
            }
        }

    private val running =
        PeopleSyncUi(
            UiText.Res(R.string.settings_people_running_lookup, listOf(30, 120)),
            running = true,
            progress = 0.25f,
        )

    private val limited =
        PeopleSyncUi(
            UiText.Res(R.string.settings_people_limited, listOf(UiText.Plural(R.plurals.settings_people_minutes, 12))),
            canContinue = true,
            continueEnabled = false,
            syncEnabled = false,
            confirmRestart = true,
        )

    private val stopped =
        PeopleSyncUi(
            UiText.Res(R.string.settings_people_stopped, listOf(30, 120)),
            canContinue = true,
            confirmRestart = true,
        )

    @Test fun peopleSyncRow() {
        shot(
            "x1-people-sync-row",
            variants = setOf(ShotVariant.Fs2),
            content = group(PeopleSyncUi(UiText.Res(R.string.settings_people_synced, listOf("5 min. ago")))),
        )
    }

    @Test fun peopleSyncRowSyncing() {
        shot("x1-people-sync-row-syncing", variants = setOf(ShotVariant.Fs2), content = group(running))
    }

    @Test fun peopleSyncRowNever() {
        shot("x1-people-sync-row-never", content = group(PeopleSyncUi(UiText.Res(R.string.settings_people_never))))
    }

    @Test fun peopleSyncRowRateLimited() {
        shot("x1-people-sync-row-limited", variants = setOf(ShotVariant.Fs2), content = group(limited))
    }

    @Test fun peopleSyncRowStopped() {
        shot("x1-people-sync-row-stopped", content = group(stopped))
    }

    @Config(qualifiers = "+tr")
    @Test
    fun peopleSyncRowTurkish() {
        shot("x1-people-sync-row-tr", content = group(limited))
    }
}
