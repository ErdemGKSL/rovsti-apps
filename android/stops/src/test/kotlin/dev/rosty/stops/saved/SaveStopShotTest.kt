package dev.rosty.stops.saved

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.Ends
import dev.rosty.stops.AppFixture
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.SampleStops
import dev.rosty.stops.trip.TripFixtures
import dev.rosty.stops.trip.TripTags
import dev.rosty.testing.ShotTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Saving the pair on the map as a stop, with no trip: the plan sheet's "Save as stop" under Start and Clear, the
 * name sheet with the core's suggestion in the field, and the plan of a pair that is a stop already.
 */
@Config(sdk = [35])
class SaveStopShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = ShotTest.CHANGE_THRESHOLD))

    /** A and B on the map; [named] false makes them two dropped pins with no name of their own. */
    private fun saveShot(
        name: String,
        named: Boolean = true,
        saved: Boolean = false,
        themes: List<Boolean> = listOf(false, true),
        fontScale: Float = 1f,
        then: () -> Unit = {},
    ) {
        for (dark in themes) {
            compose.runOnUiThread { compose.activity.viewModelStore.clear() }
            val a = if (named) TripFixtures.a else Destination("", TripFixtures.a.coord)
            val b = if (named) TripFixtures.b else Destination("", TripFixtures.b.coord)
            val stops = if (saved) SampleStops.saved else onlySchool
            val fixture = AppFixture(FakeStopsCore(saved = stops, draft = Ends(a, b), real = HostCore.real()))
            composeFrame(dark = dark, fontScale = fontScale) { remember { fixture }.Content() }
            settleRust()
            repeat(2) {
                compose.onNodeWithTag("sheet-grabber").performSemanticsAction(SemanticsActions.OnClick)
                settleRust(800)
            }
            then()
            val suffix =
                if (themes.size == 1) {
                    ""
                } else if (dark) {
                    "-dark"
                } else {
                    "-light"
                }
            compose.onRoot().captureRoboImage("screenshots/$name$suffix.png", options)
        }
    }

    private fun askName() {
        compose.onNodeWithTag(TripTags.Save, useUnmergedTree = true).performClick()
        settleRust()
        compose.onNodeWithTag(SavedTags.RenameField, useUnmergedTree = true).assertExists()
    }

    @Test fun planOffersSave() =
        saveShot("r3-plan-save") {
            compose.onNodeWithTag(TripTags.Save, useUnmergedTree = true).assertExists()
        }

    @Test fun planOffersSaveLargeText() = saveShot("r3-plan-save-fs2", themes = listOf(false), fontScale = 2f)

    @Test fun planOffersSaveTurkish() {
        RuntimeEnvironment.setQualifiers("+tr")
        saveShot("r3-plan-save-tr", themes = listOf(false)) {
            compose.onNodeWithText("Durak olarak kaydet", useUnmergedTree = true).assertExists()
        }
    }

    @Test fun planOfASavedPair() =
        saveShot("r3-plan-saved", saved = true) {
            compose.onNodeWithTag(TripTags.Save, useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("Saved as Pier", useUnmergedTree = true).assertExists()
        }

    @Test fun nameSheet() = saveShot("r3-save-name") { askName() }

    @Test fun nameSheetOfUnnamedPins() = saveShot("r3-save-name-unnamed", named = false) { askName() }

    @Test fun nameSheetLargeText() =
        saveShot("r3-save-name-fs2", named = false, themes = listOf(false), fontScale = 2f) { askName() }

    @Test fun nameSheetTurkish() {
        RuntimeEnvironment.setQualifiers("+tr")
        saveShot("r3-save-name-tr", named = false, themes = listOf(false)) {
            askName()
            compose.onNodeWithText("Durak 2", useUnmergedTree = true).assertExists()
        }
    }

    private companion object {
        val onlySchool = SampleStops.saved.copy(stops = SampleStops.saved.stops.filter { it.name == "School" })
    }
}
