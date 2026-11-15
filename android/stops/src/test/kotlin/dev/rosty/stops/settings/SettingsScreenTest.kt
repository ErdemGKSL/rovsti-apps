package dev.rosty.stops.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.stops.AppFixture
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.StopsRoute
import dev.rosty.stops.saved.settleRust
import dev.rosty.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E7.10 on the real app root: rows lead to their pages, confirmations guard the destructive and long actions. */
@Config(sdk = [35])
class SettingsScreenTest : ShotTest() {
    private var fixture: AppFixture? = null

    @Composable
    private fun App(route: StopsRoute) {
        val made =
            remember {
                AppFixture(FakeStopsCore(prefs = StopsPrefs(snoozeMin = 15), real = HostCore.real())).also { f ->
                    val tiles = FakeTilesOps()
                    f.container.tileOpener = { tiles.openHandle(it) }
                    f.container.mapView.value = FakeTilesOps.AREA
                    f.navigator.push(StopsRoute.Settings)
                    if (route != StopsRoute.Settings) f.navigator.push(route)
                }
            }
        fixture = made
        made.Content()
    }

    private fun click(tag: String) {
        compose
            .onNode(hasClickAction() and hasAnyDescendant(hasTestTag(tag)), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        settleRust()
    }

    @Test fun theAlarmRowOpensTheAlarmPageAndResetAsksFirst() {
        composeFrame { App(StopsRoute.Settings) }
        settleRust()
        compose.onNodeWithTag(SettingsTags.Alarm, useUnmergedTree = true).assertExists()
        fixture!!.navigator.push(StopsRoute.AlarmSettings)
        settleRust()
        compose.onNodeWithTag(AlarmTags.Page).assertExists()
        // Snooze 15 is not the default: the reset row shows (the page is one scrolling column, all composed) and asks first.
        click(AlarmTags.Reset)
        compose.onNodeWithText("Reset alarm settings?").assertIsDisplayed()
        compose.onNodeWithText("Reset").performSemanticsAction(SemanticsActions.OnClick)
        settleRust()
        assertEquals(
            5,
            fixture!!
                .container.store.prefs.value.snoozeMin,
        )
    }

    @Test fun theTilesPageEstimatesTheMapsViewAndDownloadsItAfterAsking() {
        composeFrame { App(StopsRoute.MapTiles) }
        settleRust()
        compose.onNode(hasText("up to zoom", substring = true), useUnmergedTree = true).assertExists()
        click(TilesTags.Download)
        compose
            .onNode(
                hasText("Download", substring = true) and hasText("tiles?", substring = true),
            ).assertIsDisplayed()
        compose.onNodeWithText("Download").performSemanticsAction(SemanticsActions.OnClick)
        settleRust()
        assertEquals(listOf(FakeTilesOps.AREA), fixture!!.container.scheduledAreas)
        assertTrue(
            fixture!!
                .container.offline.state.value.active,
        )
    }

    /** S10: leaving the map for Settings hands its view over as the area to download. */
    @Test fun theMapsViewBecomesTheOfflineAreaWhenSettingsOpens() {
        composeFrame {
            val made = remember { AppFixture(FakeStopsCore()) }
            fixture = made
            made.Content()
        }
        settleRust()
        val container = fixture!!.container
        assertEquals(null, container.mapView.value)
        compose.runOnUiThread { fixture!!.navigator.push(StopsRoute.Settings) }
        settleRust()
        val area = container.mapView.value
        assertTrue("$area", area != null && area.south < area.north && area.west < area.east)
    }
}
