package dev.rosty.messages.inbox

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.messages.InboxFilter
import dev.rosty.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test
import org.robolectric.annotation.Config

/** E6.2 screenshots: the inbox on the real shell (tab bar included), its menu, swipe, select mode and empty state. */
@Config(sdk = [35])
class E62ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(
        name: String,
        state: InboxUiState = InboxSample.state,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        after: (InboxHarness) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val h = InboxHarness(state)
            composeFrame(dark = dark, fontScale = fontScale, rtl = rtl) { HostedInbox(h) }
            advance(500)
            after(h)
            advance(1_400)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun inbox() = snap("e6.2-inbox")

    @Test fun lastRowAboveTheBar() =
        snap("e6.2-inbox-end-above-bar", after = { h ->
            repeat(3) {
                compose.onNodeWithTag("scaffold-list").performTouchInput {
                    swipeUp(startY = bottom * 0.8f, endY = top, durationMillis = 120)
                }
                advance(900)
            }
            check(
                h.listState.layoutInfo.visibleItemsInfo
                    .last()
                    .key == "cem",
            ) { "not at the end" }
        })

    @Test fun unreadChip() {
        val unread = InboxSample.rows.filter { it.isUnread }.toImmutableList()
        snap("e6.2-inbox-chips-unread", InboxSample.state.copy(filter = InboxFilter.Unread, rows = unread))
    }

    @Test fun rowMenu() =
        snap("e6.2-inbox-row-menu", after = {
            compose.onNodeWithTag(InboxTags.row("mert")).performTouchInput { longClick(durationMillis = 700) }
            advance(900)
        })

    @Test fun swipe() =
        snap("e6.2-inbox-swipe", after = {
            // A slow drag of a quarter row: its projected end stays short of fullSwipeFraction, so the row rests
            // open on its actions instead of committing the last one.
            compose.onNodeWithTag(InboxTags.row("mert")).performTouchInput {
                down(Offset(right * 0.95f, centerY))
                repeat(STEPS) { step ->
                    moveTo(Offset(right * (0.95f - 0.25f * (step + 1) / STEPS), centerY), delayMillis = 40)
                }
                up()
            }
            advance(900)
        })

    @Test fun selectMode() =
        snap(
            "e6.2-inbox-select-mode",
            InboxSample.state.copy(
                select =
                    SelectUi(
                        persistentSetOf("ada", "mert"),
                        persistentListOf(BulkKind.MarkRead, BulkKind.Mute, BulkKind.Pin, BulkKind.Archive),
                        allSelected = false,
                    ),
            ),
        )

    @Test fun empty() = snap("e6.2-inbox-empty", InboxUiState(loaded = true, access = SmsAccess.Ready))

    @Test fun fontScale2() = snap("e6.2-inbox-fs2", fontScale = 2f)

    @Test fun rightToLeft() = snap("e6.2-inbox-rtl", rtl = true)
}

private const val STEPS = 24
