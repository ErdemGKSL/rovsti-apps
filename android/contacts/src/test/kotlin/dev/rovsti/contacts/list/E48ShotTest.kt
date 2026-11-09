package dev.rovsti.contacts.list

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.8 screenshots: the list in select mode on the real shell (folded tab bar, toolbar beside it) and its "..." menu. */
@Config(sdk = [35])
class E48ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun selecting(vararg ids: Long): ListHarness {
        val h = ListHarness(SampleBook.state()).also { it.simulateSelect = true }
        h.ui = h.ui.copy(selectMode = h.selectState(ids.toSet()))
        return h
    }

    private fun snap(
        name: String,
        before: (ListHarness) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val h = selecting(2, 3, 11)
            composeFrame(dark = dark) { HostedList(h) }
            advance(500)
            before(h)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun selectMode() = snap("e4.8-select-mode")

    @Test fun selectActionsMenu() =
        snap("e4.8-select-actions-menu", before = {
            compose.onNodeWithTag(SelectTags.More).performTouchInput {
                down(center)
                up()
            }
            advance(900)
        })
}
