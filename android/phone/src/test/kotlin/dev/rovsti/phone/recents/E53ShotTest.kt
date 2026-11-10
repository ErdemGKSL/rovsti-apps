package dev.rovsti.phone.recents

import android.content.Context
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.core.model.phone.RecentsFilter
import dev.rovsti.design.overlay.confirmDestructive
import dev.rovsti.phone.FavoritesTab
import dev.rovsti.testing.ShotTest
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.3 screenshots: Recents on the real shell (tab bar included), its menu, select mode and the block confirmation. */
@Config(sdk = [35])
class E53ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private val strings get() = RecentsStrings(ApplicationProvider.getApplicationContext<Context>().resources)

    private fun snap(
        name: String,
        fontScale: Float = 1f,
        harness: () -> RecentsHarness = { RecentsHarness() },
        tab: dev.rovsti.design.transition.Tab = dev.rovsti.phone.RecentsTab,
        query: String = "",
        after: (RecentsHarness) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val h = harness()
            composeFrame(
                dark = dark,
                fontScale = fontScale,
            ) { HostedRecents(h, strings, tab = tab, initialQuery = query) }
            advance(500)
            after(h)
            advance(1_400)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun recents() = snap("e5.3-recents")

    @Test fun lastRowAboveTheBar() =
        snap("e5.3-recents-end-above-bar", after = { h ->
            compose.runOnUiThread { }
            compose.onNodeWithTag("scaffold-list").performTouchInput {
                swipeUp(startY = bottom * 0.8f, endY = top, durationMillis = 120)
            }
            advance(900)
            compose.runOnUiThread { }
            compose.onNodeWithTag("scaffold-list").performTouchInput {
                swipeUp(startY = bottom * 0.8f, endY = top, durationMillis = 120)
            }
            advance(900)
            check(
                h.listState.layoutInfo.visibleItemsInfo
                    .last()
                    .key
                    .toString()
                    .endsWith("14"),
            ) { "not at the end" }
        })

    @Test fun missedFilter() {
        val missed =
            listOf(
                RecentsItem.Header("Today", 0),
                RecentsItem.Row(
                    RecentsSample.row(
                        "1",
                        "Ayse Yilmaz",
                        "missed",
                        "12:41",
                        dev.rovsti.core.model.phone.Direction.Missed,
                        "(2)",
                        missed = true,
                    ),
                ),
                RecentsItem.Header("Yesterday", 1),
                RecentsItem.Row(
                    RecentsSample.row(
                        "6",
                        "Can Demir",
                        "mobile",
                        "Yesterday",
                        dev.rovsti.core.model.phone.Direction.Missed,
                        missed = true,
                    ),
                ),
                RecentsItem.Header("Monday", 2),
                RecentsItem.Row(
                    RecentsSample.row(
                        "12",
                        "Naz Polat",
                        "mobile",
                        "Monday",
                        dev.rovsti.core.model.phone.Direction.Missed,
                        missed = true,
                    ),
                ),
            )
        snap("e5.3-recents-missed-filter", harness = {
            RecentsHarness(RecentsSample.ui(missed, filter = RecentsFilter.Missed))
        })
    }

    @Test fun chipsScrolled() =
        snap("e5.3-recents-chips-scrolled", fontScale = 1.5f, after = {
            compose.onNodeWithTag(RecentsTags.Chips).performTouchInput { swipeLeft() }
            advance(600)
        })

    @Test fun rowMenu() =
        snap("e5.3-recents-row-menu", after = {
            compose.onNodeWithTag(RecentsTags.row("2")).performTouchInput { longClick(durationMillis = 700) }
            advance(900)
        })

    @Test fun selectMode() =
        snap("e5.3-recents-select-mode", harness = {
            RecentsHarness(RecentsSample.ui(select = RecentsSample.selected("2", "4")))
        })

    @Test fun search() {
        val found =
            listOf(
                RecentsItem.Header("Today", 0),
                RecentsItem.Row(
                    RecentsSample.row(
                        "2",
                        "Mert Kaya",
                        "mobile",
                        "11:05",
                        dev.rovsti.core.model.phone.Direction.Outgoing,
                    ),
                ),
                RecentsItem.Header("Monday", 2),
                RecentsItem.Row(
                    RecentsSample.row(
                        "11",
                        "Kerem Aydin",
                        "work",
                        "Monday",
                        dev.rovsti.core.model.phone.Direction.Outgoing,
                    ),
                ),
            )
        snap(
            "e5.3-recents-search",
            harness = { RecentsHarness(RecentsSample.ui(found, query = "er")) },
            query = "er",
        )
    }

    @Test fun whatsAppBadge() {
        val wa =
            listOf(
                RecentsItem.Header("Today", 0),
                RecentsItem.Row(
                    RecentsSample.row("4", "Deniz Aksoy", "WhatsApp", "08:30", whatsapp = true, deletable = false),
                ),
                RecentsItem.Header("Yesterday", 1),
                RecentsItem.Row(
                    RecentsSample.row(
                        "8",
                        "Burak Ozturk",
                        "WhatsApp",
                        "Yesterday",
                        dev.rovsti.core.model.phone.Direction.Outgoing,
                        whatsapp = true,
                        deletable = false,
                    ),
                ),
                RecentsItem.Row(
                    RecentsSample.row(
                        "15",
                        "Ece Yildiz",
                        "WhatsApp",
                        "Yesterday",
                        dev.rovsti.core.model.phone.Direction.Missed,
                        "(2)",
                        missed = true,
                        whatsapp = true,
                        deletable = false,
                    ),
                ),
            )
        snap("e5.3-recents-whatsapp-badge", harness = {
            RecentsHarness(RecentsSample.ui(wa, filter = RecentsFilter.WhatsApp))
        })
    }

    @Test fun dualSimChip() =
        snap("e5.3-recents-dual-sim-chip", harness = {
            RecentsHarness(RecentsSample.ui(RecentsSample.rows(sim = true), dualSim = true))
        })

    @Test fun tabBadge() =
        snap(
            "e5.3-recents-tab-badge",
            tab = FavoritesTab,
            harness = { RecentsHarness(missedBadge = 3) },
        )

    @Test fun blockConfirm() =
        snap("e5.3-block-confirm", after = { h ->
            compose.runOnUiThread {
                h.overlays.confirmDestructive(
                    destructiveLabel = strings.blockAction,
                    title = strings.blockTitle("Ayse Yilmaz", 1),
                    message = strings.blockMessage,
                    cancelLabel = strings.cancel,
                    onConfirm = {},
                )
            }
        })
}
