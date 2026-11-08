package dev.rovsti.catalog

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.Test

/** The E1.27 screenshots: the ported menus over a list page, light and dark. */
class E127ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(
        name: String,
        dark: Boolean,
    ) {
        compose.onRoot().captureRoboImage(
            filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
            roborazziOptions = options,
        )
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    @Test
    fun menuOpen() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.ButtonMenu) }
            compose.onNodeWithTag("menu-trigger-top").performClick()
            advance(16)
            advance(1200)
            snap("e1.27-menu-open", dark)
        }
    }

    @Test
    fun contextMenuPreview() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.ContextMenu) }
            node("ctx-tile-left").performTouchInput { down(center) }
            advance(16)
            advance(1000)
            snap("e1.27-context-menu-preview", dark)
            node("ctx-tile-left").performTouchInput { up() }
            advance(16)
            advance(1500)
        }
    }

    @Test
    fun contextMenuLiftedRow() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.ContextMenu) }
            node("ctx-row-2").performTouchInput { down(center) }
            advance(16)
            advance(1000)
            snap("e1.27-context-menu-row", dark)
            node("ctx-row-2").performTouchInput { up() }
            advance(16)
            advance(1500)
        }
    }

    /** Quick actions on top and a picker section with a header and a check column. */
    @Test
    fun contextMenuSubmenu() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.ContextMenu) }
            node("ctx-sort").performTouchInput { down(center) }
            advance(16)
            advance(1000)
            snap("e1.27-context-menu-submenu", dark)
            // The lifted copy carries the tag too: the first node is the real chip the finger is on.
            compose.onAllNodesWithTag("ctx-sort", useUnmergedTree = true)[0].performTouchInput { up() }
            advance(16)
            advance(1500)
        }
    }
}
