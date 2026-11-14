package dev.rovsti.shell.settings.dev

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.core.log.DevMode
import dev.rovsti.core.log.LogEntry
import dev.rovsti.core.log.LogLevel
import dev.rovsti.core.log.LogSource
import dev.rovsti.core.model.phone.HapticsLevel
import dev.rovsti.core.model.stops.Appearance
import dev.rovsti.shell.settings.CommonSettingsGroup
import dev.rovsti.shell.settings.CommonSettingsPage
import dev.rovsti.shell.settings.FakeCommonSettings
import dev.rovsti.shell.settings.HostedPage
import dev.rovsti.shell.settings.SettingsPage
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The developer-mode UI (people sync v2, S3): the Logs page and the Developer group, light and dark, en and tr. */
@Config(sdk = [35])
class DevLogsShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f))

    private val lines =
        listOf(
            Triple(LogLevel.Info, "sync", "run_begin mode=v1 wa_state=Connected"),
            Triple(LogLevel.Info, "sync", "batch stage=lookup n=15 found=6 ms=820"),
            Triple(LogLevel.Debug, "core", "people.upsert changed=6 jid=90***01@s.whatsapp.net"),
            Triple(
                LogLevel.Warn,
                "sync",
                "rate_limited stage=profiles source=whatsapp retry_after_ms=60000 attempt=0 n=15",
            ),
            Triple(LogLevel.Error, "sync", "error stage=pictures kind=Network message=connection reset"),
        ).mapIndexed { i, (level, tag, text) ->
            LogEntry(i + 1L, 1_760_000_000_000 + i * 1_000L, level, tag, LogSource.Kotlin, text)
        }

    private fun ui(filter: LogsFilter = LogsFilter()) =
        LogsUi(
            lines = LogsViewModel.matching(lines, filter).toImmutableList(),
            tags = listOf("core", "sync").toImmutableList(),
            filter = filter,
            total = lines.size,
        )

    private fun snap(
        name: String,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { HostedPage(content) }
            advance(900)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun logsPage() = snap("dev-logs") { LogsScreen(ui(), {}, {}, {}, {}) }

    @Test fun logsPageFiltered() =
        snap("dev-logs-warn-sync") {
            LogsScreen(ui(LogsFilter(minLevel = LogLevel.Warn, tag = "sync")), {}, {}, {}, {})
        }

    @Test
    @Config(sdk = [35], qualifiers = "+tr")
    fun logsPageTurkish() = snap("dev-logs-tr") { LogsScreen(ui(), {}, {}, {}, {}) }

    @Test fun anEmptyLogSaysSo() {
        composeFrame { HostedPage { LogsScreen(LogsUi(), {}, {}, {}, {}) } }
        advance(600)
        compose.onNodeWithTag(LogsTags.Empty, useUnmergedTree = true).assertExists()
    }

    @Test fun theDeveloperGroupShowsOnlyWhileOnAndItsSwitchTurnsItOff() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences(DevMode.PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val mode = DevMode(prefs)
        val opened = mutableListOf<CommonSettingsPage>()
        val settings = FakeCommonSettings(appearance = Appearance.Auto, haptics = HapticsLevel.Light)
        val page = @androidx.compose.runtime.Composable {
            CompositionLocalProvider(LocalDevMode provides mode) {
                SettingsPage(title = "Settings") { CommonSettingsGroup(settings, onOpen = { opened += it }) }
            }
        }
        composeFrame { HostedPage(page) }
        advance(600)
        compose.onNodeWithTag(DevTags.Group, useUnmergedTree = true).assertDoesNotExist()
        mode.set(true)
        advance(600)
        snap("dev-group", page)
        compose.onNodeWithTag(DevTags.Logs, useUnmergedTree = true).performClick()
        advance(300)
        assertEquals(listOf(CommonSettingsPage.Logs), opened)
        compose.onNodeWithTag(DevTags.Switch, useUnmergedTree = true).performClick()
        advance(600)
        assertFalse(mode.enabled.value)
        compose.onNodeWithTag(DevTags.Group, useUnmergedTree = true).assertDoesNotExist()
    }
}
