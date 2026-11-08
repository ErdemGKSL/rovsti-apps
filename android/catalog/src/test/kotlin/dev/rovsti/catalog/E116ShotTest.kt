package dev.rovsti.catalog

import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertTrue
import org.junit.Test

/** The E1.16 screenshots: fields at rest, focused, with a selection, search with clear, and the composer. */
@OptIn(ExperimentalTestApi::class)
class E116ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))
    private val toolbar = RecordingToolbar()

    private fun snap(
        name: String,
        fontScale: Float = 1f,
        before: () -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark, fontScale = fontScale) {
                CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar) {
                    CatalogApp(initialPage = CatalogPage.TextFields)
                }
            }
            before()
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
            setSimulatedIme(0)
        }
    }

    private fun pointAt(
        tag: String,
        offset: Int,
    ): Offset {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val density = compose.activity.resources.displayMetrics.density
        return Offset(14 * density + results.first().getCursorRect(offset).left, node.size.height / 2f)
    }

    @Test
    fun fieldEmpty() = snap("e1.16-field-empty")

    @Test
    fun fieldFocused() =
        snap("e1.16-field-focused") {
            compose.onNodeWithTag("field-note").performClick()
            advance(600)
        }

    @Test
    fun fieldFocusedFontScale2() =
        snap("e1.16-field-focused-fs2", fontScale = 2f) {
            compose.onNodeWithTag("field-note").performClick()
            advance(600)
        }

    @Test
    fun fieldWithSelection() =
        snap("e1.16-field-with-selection") {
            val at = pointAt("field-note", 7)
            compose.onNodeWithTag("field-note").performTouchInput { longClick(at) }
            advance(800)
        }

    @Test
    fun fieldSelectionToolbar() =
        snap("e1.16-field-selection-toolbar") {
            val before = toolbar.shown
            val at = pointAt("field-note", 7)
            compose.onNodeWithTag("field-note").performTouchInput { longClick(at) }
            advance(800)
            // Robolectric draws no floating toolbar; the platform one is asserted by request instead.
            assertTrue("platform toolbar requested", toolbar.shown > before)
        }

    @Test
    fun searchWithClear() =
        snap("e1.16-search-with-clear") {
            compose.onNodeWithTag("field-search").performClick()
            advance(600)
        }

    @Test
    fun composerThreeLines() =
        snap("e1.16-composer-3-lines") {
            compose.onNodeWithTag("composer-field").performClick()
            compose
                .onNodeWithTag(
                    "composer-field",
                ).performTextInput("Running late,\ncan you start without me?\nBack in ten")
            advance(800)
        }

    @Test
    fun composerImeOpen() =
        snap("e1.16-composer-ime-open") {
            setSimulatedIme(300)
            compose.onNodeWithTag("composer-field").performClick()
            compose.onNodeWithTag("composer-field").performTextInput("Running late")
            advance(800)
        }
}
