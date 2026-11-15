package dev.rosty.stops.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.AlarmSound
import dev.rosty.core.model.stops.GtfsSummaryAnswer
import dev.rosty.core.model.stops.GtfsSummaryRequest
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.stops.AppFixture
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.StopsRoute
import dev.rosty.stops.saved.settleRust
import dev.rosty.testing.ShotTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * E7.10 screenshots (the WhatsApp parts, `e7.10-arriving-soon` and `e7.10-template-field-selection`, belong to that
 * work): the Settings page pushed over the map with the full bar, the Alarm page, the Map tiles page, a running
 * offline download in Settings, the Settings page at font scale 2 and in Turkish. Light and dark, on the real core
 * (prefs_choices, prefs_edit) behind the fake's files; the transit summary and the tile cache are scripted.
 */
@Config(sdk = [35])
class E710ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = ShotTest.CHANGE_THRESHOLD))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    /** A user who chose a sound and a louder alarm, imported a feed and moved to another tile server once. */
    private fun core(): FakeStopsCore {
        val core =
            FakeStopsCore(
                prefs = StopsPrefs(volumePct = 90, alarmSound = AlarmSound("content://media/12", "Morning Birds")),
                real = HostCore.real(),
            )
        core.core.onTyped("stops.gtfs_summary", GtfsSummaryRequest.serializer(), GtfsSummaryAnswer.serializer()) {
            Outcome.Ok(GtfsSummaryAnswer(true, "48 stops, 6 lines", "Imported 12 Nov", 1_789_200_000_000L, 48, 6, 9))
        }
        return core
    }

    private var fixture: AppFixture? = null
    private var tiles: FakeTilesOps? = null

    @Composable
    private fun App(route: StopsRoute) {
        val made =
            remember {
                AppFixture(core()).also { f ->
                    val fakeTiles = FakeTilesOps()
                    tiles = fakeTiles
                    f.container.tileOpener = { fakeTiles.openHandle(it) }
                    f.container.mapView.value = FakeTilesOps.AREA
                    f.navigator.push(StopsRoute.Settings)
                    if (route != StopsRoute.Settings) f.navigator.push(route)
                }
            }
        fixture = made
        made.Content()
    }

    private fun settingsShot(
        name: String,
        route: StopsRoute = StopsRoute.Settings,
        fontScale: Float = 1f,
        before: () -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            // The view models belong to the activity: each pass starts as a new launch.
            compose.runOnUiThread { compose.activity.viewModelStore.clear() }
            composeFrame(dark = dark, fontScale = fontScale) { App(route) }
            settleRust()
            before()
            settleRust()
            compose.onRoot().captureRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png", options)
        }
    }

    @Test fun settings() = settingsShot("e7.10-settings")

    @Test fun settingsTurkish() {
        RuntimeEnvironment.setQualifiers("+tr")
        settingsShot("e7.10-settings-tr")
    }

    @Test fun settingsFontScale2() = settingsShot("e7.10-settings-fs2", fontScale = 2f)

    @Test fun alarmSettings() = settingsShot("e7.10-alarm-settings", StopsRoute.AlarmSettings)

    @Test fun tilesSettings() = settingsShot("e7.10-tiles-settings", StopsRoute.MapTiles)

    /** A download of 1 200 tiles at 480: the progress row with its bar and Cancel in the Map group. */
    @Test fun offlineDownloadProgress() =
        settingsShot("e7.10-offline-download-progress") {
            val f = fixture!!
            val t = tiles!!
            t.total = 1_200
            t.progress = listOf(480)
            t.hold = true
            scope.launch { f.container.offline.run(FakeTilesOps.AREA) }
        }
}
