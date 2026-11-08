// Rovsti Project (C) 2026, GPL-3.0-or-later. Original code.
package dev.rovsti.catalog

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.Test

/**
 * E1.22 AC1: one golden per overlay state of [OverlayStatesPage], light and dark. Each state is opened with its own
 * button on the manual clock (settled, not mid-flight) so the goldens are stable. The accessibility checks run on every
 * frame through [screenTest].
 */
@OptIn(ExperimentalTestApi::class)
class E122ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun settleOverlay() = repeat(12) { advance(100) }

    private fun capture(
        state: String,
        dark: Boolean,
    ) {
        compose.onRoot().captureRoboImage(
            filePath = "screenshots/e1.22-states-$state-${if (dark) "dark" else "light"}.png",
            roborazziOptions = options,
        )
    }

    /** Opens the page fresh, runs [open] on it and records [state]. */
    private fun state(
        state: String,
        scrollToBottom: Boolean = false,
        open: () -> Unit,
    ) {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.OverlayStates) }
            settleOverlay()
            if (scrollToBottom) {
                repeat(3) {
                    compose.onRoot().performTouchInput {
                        swipeUp(startY = bottom * 0.9f, endY = top, durationMillis = 120)
                    }
                    advance(100)
                }
                settleOverlay()
            }
            open()
            capture(state, dark)
            if (held) {
                compose.onRoot().performTouchInput { up() }
                held = false
                settleOverlay()
            }
        }
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        settleOverlay()
    }

    private var held = false

    private fun hold(tag: String) {
        held = true
        compose.onNodeWithTag(tag).performTouchInput { down(center) }
        advance(16)
        advance(700)
        settleOverlay()
    }

    @Test
    fun closed() =
        screenTest {
            state("page") {}
        }

    @Test
    fun menuBelow() =
        screenTest {
            state("menu-below") { hold("states-menu-below") }
        }

    @Test
    fun menuAbove() =
        screenTest {
            state("menu-above", scrollToBottom = true) { hold("states-menu-above") }
        }

    @Test
    fun popover() =
        screenTest {
            state("popover") { click("states-popover-below") }
        }

    @Test
    fun contextPreview() =
        screenTest {
            state("context-preview") { hold("states-context-preview") }
        }

    @Test
    fun sheetMedium() =
        screenTest {
            state("sheet-medium") { click("states-sheet-medium") }
        }

    @Test
    fun sheetLarge() =
        screenTest {
            state("sheet-large") { click("states-sheet-large") }
        }

    @Test
    fun alert() =
        screenTest {
            state("alert") { click("states-alert") }
        }

    @Test
    fun actionSheet() =
        screenTest {
            state("action-sheet") { click("states-action-sheet") }
        }

    @Test
    fun toast() =
        screenTest {
            state("toast", scrollToBottom = true) { click("states-toast") }
        }

    @Test
    fun undo() =
        screenTest {
            state("undo", scrollToBottom = true) { click("states-undo") }
        }

    @Test
    fun photoViewer() =
        screenTest {
            state("photo-viewer", scrollToBottom = true) { click("states-photo-viewer") }
        }
}
