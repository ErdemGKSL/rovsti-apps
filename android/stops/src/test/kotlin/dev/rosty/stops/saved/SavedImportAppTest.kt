package dev.rosty.stops.saved

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import dev.rosty.stops.AppFixture
import dev.rosty.stops.SampleStops
import dev.rosty.stops.home.HomeSheet
import dev.rosty.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** S5 end to end in the real app root: More, "Import stops…", the picked file, the summary, Import, Undo. */
@Config(sdk = [35])
class SavedImportAppTest : ShotTest() {
    /** The system's document picker: answers every launch with [uri] at once. */
    private class PickedFile(
        private val uri: Uri,
    ) : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
        }

        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry: ActivityResultRegistry get() = this@PickedFile
            }
    }

    private fun start(text: String): AppFixture {
        val fixture = AppFixture(SavedFixtures.core())
        val uri = Uri.parse("content://dev.rosty.stops.test/stops.gpx")
        shadowOf(fixture.app.contentResolver).registerInputStreamSupplier(uri) { text.byteInputStream() }
        val picker = PickedFile(uri)
        composeFrame {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker.owner) { fixture.Content() }
        }
        settleRust()
        compose.runOnUiThread { fixture.container.pendingSheet.value = HomeSheet.Saved }
        settleRust()
        compose.onNodeWithTag(SavedTags.More, useUnmergedTree = true).performClick()
        settleRust(800)
        compose.onNode(hasText("Import stops…") and hasClickAction()).performClick()
        settleRust()
        return fixture
    }

    private fun count(fixture: AppFixture): Int =
        fixture.container.fake.saved!!
            .stops.size

    @Test fun aPickedFileAddsItsNewStopsAfterTheSummaryAndUndoTakesThemAway() {
        val fixture = start(GPX)
        compose.onNodeWithTag(ImportTags.Sheet, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Add 2 stops. 1 already saved.", useUnmergedTree = true).assertExists()
        assertEquals(2, count(fixture))
        compose.onNodeWithTag(ImportTags.Confirm, useUnmergedTree = true).performClick()
        settleRust()
        assertEquals(4, count(fixture))
        compose.onNodeWithText("Imported 2 stops").assertExists()
        compose.onNodeWithText("Undo").performClick()
        settleRust()
        assertEquals(2, count(fixture))
    }

    @Test fun aFileThatHoldsNoStopsSaysSo() {
        val fixture = start("not a list of stops")
        compose.onNodeWithTag(ImportTags.Sheet, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("No stops could be read from this file").assertExists()
        assertEquals(2, count(fixture))
    }

    private companion object {
        val pier = SampleStops.saved.stops.first()
        val GPX =
            "<?xml version=\"1.0\"?><gpx version=\"1.1\">" +
                "<rte><name>Bridge</name><rtept lat=\"41.1\" lon=\"29.0\"/><rtept lat=\"41.1\" lon=\"29.01\"/></rte>" +
                "<rte><name>Market Hall</name><rtept lat=\"41.12\" lon=\"29.02\"/>" +
                "<rtept lat=\"41.121\" lon=\"29.03\"/></rte>" +
                "<rte><name>Pier</name><rtept lat=\"${pier.a.coord.lat}\" lon=\"${pier.a.coord.lon}\"/>" +
                "<rtept lat=\"${pier.b.coord.lat}\" lon=\"${pier.b.coord.lon}\"/></rte></gpx>"
    }
}
