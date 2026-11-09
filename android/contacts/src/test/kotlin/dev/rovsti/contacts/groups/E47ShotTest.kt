package dev.rovsti.contacts.groups

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.contacts.people.presentContactPicker
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.7 screenshots: the tab, a group's page, its menu open, the member picker. */
@Config(sdk = [35])
class E47ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun capture(
        name: String,
        dark: Boolean,
    ) {
        compose.onRoot().captureRoboImage(
            filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
            roborazziOptions = options,
        )
    }

    @Test fun groups() {
        for (dark in listOf(false, true)) {
            val h = GroupsHarness()
            composeFrame(dark = dark) { HostedGroups(h) }
            advance(1200)
            capture("e4.7-groups", dark)
        }
    }

    @Test fun groupPage() {
        for (dark in listOf(false, true)) {
            val h = GroupsHarness()
            composeFrame(dark = dark) { HostedGroups(h, openPage = true) }
            advance(1500)
            capture("e4.7-group-page", dark)
        }
    }

    @Test fun groupMenuOpen() {
        for (dark in listOf(false, true)) {
            val h = GroupsHarness()
            composeFrame(dark = dark) { HostedGroups(h, openPage = true) }
            advance(1500)
            compose.onNodeWithTag("toolbar-overflow").performClick()
            advance(1000)
            capture("e4.7-group-menu-open", dark)
        }
    }

    @Test fun memberPicker() {
        for (dark in listOf(false, true)) {
            val h = GroupsHarness()
            composeFrame(dark = dark) { HostedGroups(h, openPage = true) }
            advance(1500)
            compose.runOnUiThread {
                h.overlays.presentContactPicker(
                    "Add Members",
                    "Done",
                    GroupsUi.everyone.toImmutableList(),
                    h.group.memberIds,
                ) {}
            }
            advance(1200)
            capture("e4.7-member-picker", dark)
        }
    }
}
