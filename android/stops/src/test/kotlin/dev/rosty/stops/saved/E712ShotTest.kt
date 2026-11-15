package dev.rosty.stops.saved

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.Outcome
import dev.rosty.design.overlay.OverlayController
import dev.rosty.stops.AppFixture
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.data.SavedStore
import dev.rosty.testing.ShotTest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * E7.12: the import summary over the map, for a GPX with two new stops and one already saved (the system document
 * picker cannot run here, so the sheet is presented with what the picked file's plan says).
 */
@Config(sdk = [35])
class E712ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = ShotTest.CHANGE_THRESHOLD))

    private fun importShot(
        name: String,
        fontScale: Float = 1f,
    ) {
        for (dark in listOf(false, true)) {
            compose.runOnUiThread { compose.activity.viewModelStore.clear() }
            val fixture = AppFixture(FakeStopsCore(scriptTransit = false, real = HostCore.real()))
            composeFrame(dark = dark, fontScale = fontScale) { fixture.Content() }
            settleRust()
            present(fixture.overlays, fixture)
            settleRust()
            compose.onNodeWithTag(ImportTags.Sheet, useUnmergedTree = true).assertExists()
            compose.onRoot().captureRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png", options)
        }
    }

    private fun present(
        overlays: OverlayController,
        fixture: AppFixture,
    ) {
        val ops = fixture.container.ops
        val transfer = PlacesTransfer(ops, SavedStore(ops, nowMs = { NOW }), { NOW }, { "2026-09-21" })
        val plan = runBlocking { (transfer.plan(GPX) as Outcome.Ok).value.plan }
        val resources = RuntimeEnvironment.getApplication().resources
        compose.runOnUiThread {
            overlays.presentImportSheet(
                resources.getString(dev.rosty.stops.R.string.places_import_title),
                importSummary(resources, plan),
                plan.add.map { it.name },
            ) {}
        }
    }

    @Test fun importSummary() {
        importShot("e7.12-import-summary")
        compose.onNodeWithText("Add 2 stops. 1 already saved.", useUnmergedTree = true).assertExists()
    }

    @Test fun importSummaryLargeText() = importShot("e7.12-import-summary-fs2", fontScale = 2f)

    @Test fun importSummaryTurkish() {
        RuntimeEnvironment.setQualifiers("+tr")
        importShot("e7.12-import-summary-tr")
    }

    private companion object {
        const val NOW = 1_790_066_400_000L
        val pier =
            dev.rosty.stops.SampleStops.saved.stops
                .first()
        val GPX =
            "<?xml version=\"1.0\"?><gpx version=\"1.1\">" +
                "<rte><name>Bridge</name><rtept lat=\"41.1\" lon=\"29.0\"/><rtept lat=\"41.1\" lon=\"29.01\"/></rte>" +
                "<rte><name>Market Hall</name><rtept lat=\"41.12\" lon=\"29.02\"/>" +
                "<rtept lat=\"41.121\" lon=\"29.03\"/></rte>" +
                "<rte><name>Pier</name><rtept lat=\"${pier.a.coord.lat}\" lon=\"${pier.a.coord.lon}\"/>" +
                "<rtept lat=\"${pier.b.coord.lat}\" lon=\"${pier.b.coord.lon}\"/></rte></gpx>"
    }
}
