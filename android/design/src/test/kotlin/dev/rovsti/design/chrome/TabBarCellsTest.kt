package dev.rovsti.design.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.transition.ChromeSpec
import dev.rovsti.design.transition.RovstiNavHost
import dev.rovsti.design.transition.Tab
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F21: the tab bar must draw every cell in every frame. The second frame of a test (the theme switch the pushed-page
 * goldens rely on) used to leave only the selected cell with visible ink. The check is on pixels: each cell's label band
 * must contain ink that contrasts with the band's own backdrop, in the light frame and in the dark one.
 */
class TabBarCellsTest : ShotTest() {
    private val tabs =
        persistentListOf(
            TabItem("favorites", "Favorites", RovstiIcons.Star),
            TabItem("recents", "Recents", RovstiIcons.Clock),
            TabItem("contacts", "Contacts", RovstiIcons.People),
            TabItem("keypad", "Keypad", RovstiIcons.Phone),
        )

    @Serializable
    private sealed interface R {
        @Serializable data object Recents : R

        @Serializable data object Settings : R
    }

    private val tab = Tab("recents")
    private val overlays = OverlayController()

    /** The real nav host with the Recents tab and the Settings page pushed on it (pushed pages keep the full bar). */
    @Composable
    private fun PushedPage() {
        val navigator =
            remember {
                TabNavigator<R>(
                    listOf(Tab("favorites"), tab, Tab("contacts"), Tab("keypad")),
                    mapOf<Tab, R>(
                        Tab("favorites") to R.Recents,
                        tab to R.Recents,
                        Tab("contacts") to R.Recents,
                        Tab("keypad") to R.Recents,
                    ),
                ).also {
                    it.select(tab)
                    it.push(R.Settings)
                }
            }
        RovstiNavHost(
            navigator = navigator,
            tabs = tabs,
            chromeFor = { ChromeSpec(largeTitle = "Settings") },
            overlays = overlays,
            modifier = Modifier.fillMaxSize(),
        ) {
            entry<R.Recents> { Box(Modifier.fillMaxSize()) }
            entry<R.Settings> { ScreenPage(title = "Settings") { Box(Modifier.height(300.dp)) } }
        }
    }

    /** Largest luminance difference between any two pixels of the lower label band of the cell. */
    private fun inkContrast(tag: String): Float {
        val image: ImageBitmap = compose.onNodeWithTag(tag).captureToImage()
        val w = image.width
        val h = image.height
        val px = IntArray(w * h)
        image.readPixels(px, 0, 0, w, h, 0, w)
        var lo = 1f
        var hi = 0f
        for (y in (h * 0.62f).toInt() until (h * 0.95f).toInt()) {
            for (x in (w * 0.1f).toInt() until (w * 0.9f).toInt()) {
                val c = px[y * w + x]
                val l =
                    (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
                if (l < lo) lo = l
                if (l > hi) hi = l
            }
        }
        return hi - lo
    }

    @Test
    fun everyCellDrawsInLightAndInTheSecondDarkFrame() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { PushedPage() }
            advance(500)
            for (tab in tabs) {
                val contrast = inkContrast("tab-${tab.id}")
                assertTrue(
                    "tab ${tab.id} has no visible label in the ${if (dark) "dark" else "light"} frame (contrast $contrast)",
                    contrast > 0.25f,
                )
            }
        }
    }
}
