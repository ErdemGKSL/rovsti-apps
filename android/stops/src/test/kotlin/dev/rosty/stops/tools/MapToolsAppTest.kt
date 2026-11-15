package dev.rosty.stops.tools

import android.Manifest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import dev.rosty.core.model.stops.Appearance
import dev.rosty.platform.location.Fix
import dev.rosty.stops.AppFixture
import dev.rosty.stops.SampleStops
import dev.rosty.stops.home.HomeMapTag
import dev.rosty.stops.home.HomeTags
import dev.rosty.stops.pick.PickFixtures
import dev.rosty.stops.saved.settleRust
import dev.rosty.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The map tools (E7.9) in the real app root, on the real core. */
@Config(sdk = [35])
class MapToolsAppTest : ShotTest() {
    private fun start(): AppFixture {
        val fixture = PickFixtures.app()
        val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(fixture.app).grantPermissions(*permissions)
        fixture.container.permissions.refreshNow { false }
        composeFrame { fixture.Content() }
        settleRust()
        return fixture
    }

    private fun press(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        settleRust(800)
    }

    /** A real tap: a capsule's test tag sits on its outer box, the click on the button inside. */
    private fun tap(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        settleRust(800)
    }

    private fun menu(text: String) {
        compose.onNode(hasText(text) and hasClickAction()).performClick()
        settleRust(800)
    }

    private fun tapMap(
        fx: Float,
        fy: Float,
    ) {
        compose.onNodeWithTag(HomeMapTag).performTouchInput { click(Offset(width * fx, height * fy)) }
        settleRust(800)
    }

    @Test fun theMeButtonCentresThenFollowsThenLetsGo() {
        val fixture = start()
        press(HomeTags.Locate)
        compose.onNodeWithText("Following you").assertDoesNotExist()
        press(HomeTags.Locate)
        compose.onNodeWithText("Following you").assertExists()
        // A new fix moves the view while following.
        val moved = Fix(SampleStops.here.lat + 0.01, SampleStops.here.lon, 10.0, 0L)
        compose.runOnUiThread { fixture.container.fakeLocation.push(moved) }
        settleRust(800)
        press(HomeTags.Locate)
        compose.onNodeWithText("Stopped following").assertExists()
    }

    @Test fun theMeasureToolAddsTappedPointsAndOffersSaveAsStop() {
        start()
        press(HomeTags.Layers)
        menu("Measure distance")
        compose.onNodeWithTag(ToolTags.MeasureCard, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Tap the map to add points", useUnmergedTree = true).assertExists()
        tapMap(0.3f, 0.3f)
        compose.onNodeWithText("Tap a second point", useUnmergedTree = true).assertExists()
        tapMap(0.7f, 0.35f)
        compose.onNodeWithText("1 leg", substring = true, useUnmergedTree = true).assertExists()
        tap(ToolTags.MeasureMore)
        compose.onNode(hasText("Save as stop") and hasClickAction()).assertExists()
        menu("Copy result")
        compose.onNodeWithText("Measurement copied").assertExists()
        tap(ToolTags.MeasureDone)
        compose.onNodeWithTag(ToolTags.MeasureCard, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun clearOffersUndo() {
        start()
        press(HomeTags.Layers)
        menu("Measure distance")
        tapMap(0.3f, 0.3f)
        tapMap(0.7f, 0.35f)
        tap(ToolTags.MeasureClear)
        compose.onNodeWithText("Tap the map to add points", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Undo").performClick()
        settleRust(800)
        compose.onNodeWithText("1 leg", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test fun holdingMeOpensMyLocation() {
        start()
        compose
            .onNodeWithTag(
                HomeTags.Locate,
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.OnLongClick)
        settleRust(800)
        compose.onNodeWithTag(ToolTags.MyLocation, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Accurate to 25 m", useUnmergedTree = true).assertExists()
    }

    @Test fun mapOptionsSetTheCommonAppearanceAndLayersHideSavedStops() {
        val fixture = start()
        press(HomeTags.Options)
        menu("Dark")
        assertEquals(Appearance.Dark, fixture.container.fake.prefs.appearance)
        press(HomeTags.Layers)
        menu("Saved stops on map")
        assertFalse(fixture.container.fake.prefs.showSavedOnMap)
        compose.onNodeWithText("Saved stops hidden").assertExists()
    }

    @Test fun copyMapLinkPutsAnOsmLinkOnTheClipboard() {
        val fixture = start()
        press(HomeTags.Options)
        menu("Copy map link")
        val copied =
            fixture.container.clipboard
                .primaryText()
                .orEmpty()
        assertTrue(copied, copied.startsWith("https://www.openstreetmap.org/#map="))
    }
}
