package dev.rovsti.catalog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.Test

/** The E1.11 screenshots: a lifted row with its panel, the same near the bottom, the preview card, dragged, RTL. */
class E111ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private val density get() = compose.activity.resources.displayMetrics.density

    private fun snap(
        name: String,
        dark: Boolean,
        rtl: Boolean = false,
    ) {
        compose.onRoot().captureRoboImage(
            filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
            roborazziOptions = options,
        )
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun holdOn(tag: String) {
        node(tag).performTouchInput { down(center) }
        advance(16)
        advance(1000)
    }

    private fun release(tag: String) {
        node(tag).performTouchInput { up() }
        advance(16)
        advance(1500)
    }

    /** The lowest row that is fully inside the list. */
    private fun lowestVisibleRow(): String {
        val list = node("ctx-list").fetchSemanticsNode().boundsInRoot
        for (i in 11 downTo 0) {
            val row = runCatching { node("ctx-row-$i").fetchSemanticsNode().boundsInRoot }.getOrNull() ?: continue
            if (row.bottom <= list.bottom + 0.5f && row.height >= 63f * density) return "ctx-row-$i"
        }
        error("no row visible")
    }

    @Test
    fun rowOpen() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.ContextMenu) }
            holdOn("ctx-row-2")
            snap("e1.11-context-row-open", dark)
            release("ctx-row-2")
        }
    }

    @Test
    fun rowOpenNearBottom() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.ContextMenu) }
            val tag = lowestVisibleRow()
            holdOn(tag)
            snap("e1.11-context-row-open-near-bottom", dark)
            release(tag)
        }
    }

    @Test
    fun previewCard() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.ContextMenu) }
            holdOn("ctx-tile-right")
            snap("e1.11-context-preview-card", dark)
            release("ctx-tile-right")
        }
    }

    @Test
    fun previewCardLeftAligned() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.ContextMenu) }
            holdOn("ctx-tile-left")
            snap("e1.11-context-preview-card-left", dark)
            release("ctx-tile-left")
        }
    }

    @Test
    fun previewDragged() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.ContextMenu) }
            holdOn("ctx-tile-left")
            node("ctx-tile-left").performTouchInput { moveBy(Offset(30f * density, 36f * density)) }
            advance(16)
            advance(400)
            snap("e1.11-context-preview-dragged", dark)
            release("ctx-tile-left")
        }
    }

    @Test
    fun openRtl() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark, rtl = true) { CatalogApp(initialPage = CatalogPage.ContextMenu) }
            holdOn("ctx-row-2")
            snap("e1.11-context-open-rtl", dark)
            release("ctx-row-2")
        }
    }
}
