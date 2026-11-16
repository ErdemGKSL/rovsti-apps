package dev.rosty.contacts.settings

import androidx.compose.runtime.Composable
import dev.rosty.contacts.ContactsRoute
import dev.rosty.contacts.R
import dev.rosty.design.text.UiText
import dev.rosty.shell.settings.SettingsPage
import dev.rosty.testing.ShotTest
import dev.rosty.testing.ShotVariant
import org.junit.Test
import org.robolectric.annotation.Config

/** X.1b and people sync v2 and v3 screenshots:the "WhatsApp Names and Photos" group of the Contacts settings, per state. */
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

    // ---- people sync v3: the gateway prepares, the phone downloads once

    private val server =
        PeopleSyncUi(
            UiText.Res(R.string.settings_people_server_lookup, listOf(30, 120)),
            running = true,
            progress = 0.25f,
        )

    private val serverLimit =
        PeopleSyncUi(
            UiText.Res(
                R.string.settings_people_server_limit,
                listOf(UiText.Plural(R.plurals.settings_people_minutes, 12)),
            ),
            running = true,
            progress = 0.5f,
        )

    private val serverQuiet = PeopleSyncUi(UiText.Res(R.string.settings_people_server_quiet), running = true)

    private val download =
        PeopleSyncUi(
            UiText.Res(R.string.settings_people_downloading, listOf(3, 12)),
            running = true,
            progress = 0.25f,
        )

    private val unchanged = PeopleSyncUi(UiText.Res(R.string.settings_people_synced_unchanged, listOf("5 min. ago")))

    @Test fun peopleSyncRowServer() {
        shot("x1-people-sync-row-server", content = group(server))
    }

    @Test fun peopleSyncRowServerLimit() {
        shot("x1-people-sync-row-server-limit", content = group(serverLimit))
    }

    @Test fun peopleSyncRowServerQuiet() {
        shot("x1-people-sync-row-server-quiet", content = group(serverQuiet))
    }

    @Test fun peopleSyncRowDownload() {
        shot("x1-people-sync-row-download", content = group(download))
    }

    @Test fun peopleSyncRowUnchanged() {
        shot("x1-people-sync-row-unchanged", content = group(unchanged))
    }

    @Config(qualifiers = "+tr")
    @Test
    fun peopleSyncRowServerTurkish() {
        shot("x1-people-sync-row-server-tr", content = group(serverLimit))
    }
}
