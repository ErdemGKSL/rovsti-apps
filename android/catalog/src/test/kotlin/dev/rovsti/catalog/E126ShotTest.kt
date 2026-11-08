package dev.rovsti.catalog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.design.chrome.RovstiBackdropRoot
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.overlay.ButtonMenuEntry
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.PopoverEntry
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

/** E1.26: a menu and a popover over the striped glass page: the stripes must show blurred through both panels. */
class E126ShotTest : ShotTest() {
    @Test
    fun overlaysOverStripes() {
        for (dark in listOf(false, true)) {
            val controller = OverlayController()
            composeFrame(dark = dark) {
                RovstiBackdropRoot(controller) { GlassPage() }
            }
            advance(400)
            val density = compose.activity.resources.displayMetrics.density
            compose.runOnIdle {
                controller.present(
                    ButtonMenuEntry(
                        Rect(300f * density, 360f * density, 344f * density, 404f * density),
                        persistentListOf(
                            MenuItem.Action("Select", RovstiIcons.Checkmark, onClick = {}),
                            MenuItem.Action("Sort by", onClick = {}),
                            MenuItem.Separator,
                            MenuItem.Action("Delete all", RovstiIcons.Trash, destructive = true, onClick = {}),
                        ),
                    ),
                )
            }
            advance(1200)
            compose.runOnIdle {
                controller.present(
                    PopoverEntry(Rect(40f * density, 620f * density, 130f * density, 664f * density), title = "Date") {
                        Column(Modifier.padding(16.dp)) {
                            RovstiText("Popover over stripes", style = RovstiTheme.type.headline)
                            RovstiText(
                                "Blurred, saturated stripes behind the panel.",
                                style = RovstiTheme.type.subheadline,
                            )
                        }
                    },
                )
            }
            advance(1200)
            snapOverlay("e1.26-overlays-over-stripes", dark)
        }
    }

    private fun snapOverlay(
        name: String,
        dark: Boolean,
    ) {
        compose.onRoot().captureRoboImage(
            filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
            roborazziOptions =
                RoborazziOptions(
                    compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD),
                ),
        )
    }
}
