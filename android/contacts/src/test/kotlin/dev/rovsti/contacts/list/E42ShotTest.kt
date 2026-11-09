package dev.rovsti.contacts.list

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.contacts.DraftSeed
import dev.rovsti.core.model.contacts.Addition
import dev.rovsti.design.list.AlphabetRailTags
import dev.rovsti.testing.ShotTest
import dev.rovsti.testing.ShotVariant
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.2 screenshots: the list in the real shell (tab bar, backdrop, overlays), the 40-contact [SampleBook]. */
@Config(sdk = [35])
class E42ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(
        name: String,
        harness: () -> ListHarness,
        before: (ListHarness) -> Unit = {},
        after: (ListHarness) -> Unit = {},
        content: @androidx.compose.runtime.Composable (ListHarness) -> Unit = { HostedList(it) },
    ) {
        for (dark in listOf(false, true)) {
            val h = harness()
            composeFrame(dark = dark) { content(h) }
            advance(300)
            before(h)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
            after(h)
        }
    }

    private fun typed(
        text: String,
        state: ContactsListUiState,
    ): @androidx.compose.runtime.Composable (ListHarness) -> Unit =
        { h ->
            HostedList(h)
            LaunchedEffect(Unit) { h.searchState.setTextAndPlaceCursorAtEnd(text) }
        }

    @Test fun list() =
        shot("e4.2-list", variants = setOf(ShotVariant.Fs2, ShotVariant.Rtl)) {
            HostedList(ListHarness(SampleBook.state()))
        }

    @Test fun listEndAboveTheBar() =
        snap("e4.2-list-end-above-bar", { ListHarness(SampleBook.state()) }, before = { h ->
            compose.runOnUiThread {
                kotlinx.coroutines.runBlocking { h.listState.scrollToItem(h.listState.layoutInfo.totalItemsCount - 1) }
            }
            advance(400)
        })

    @Test fun searchTypingWithDetails() {
        val rows = SampleBook.items().filter { it.name.lowercase().contains("ze") }.map { it.copy(header = false) }
        val deep =
            listOf(
                SampleBook
                    .items()
                    .first {
                        it.id == 34L
                    }.copy(header = false, secondary = "Note · Zeki's cousin, met at ZeroOne"),
            )
        val state = SampleBook.state(rows, "ze", deep = deep)
        simulatedImeDp = 300
        try {
            snap(
                "e4.2-search-typing",
                { ListHarness(state, onQuery = { state }) },
                content = typed("ze", state),
            )
        } finally {
            simulatedImeDp = 0
        }
    }

    @Test fun searchNoResults() {
        val state =
            SampleBook.state(emptyList(), "0599 111 22 33").copy(
                offer = SearchOffer(DraftSeed(number = "05991112233"), Addition(listOf("05991112233"), emptyList())),
            )
        snap(
            "e4.2-search-no-results",
            { ListHarness(state, onQuery = { state }) },
            content = typed("0599 111 22 33", state),
        )
    }

    @Test fun railScrubbing() =
        snap("e4.2-rail-scrubbing", { ListHarness(SampleBook.state()) }, before = { h ->
            val letters = h.ui.scrubberLetters.toList()
            val rail = compose.onNodeWithTag(AlphabetRailTags.Rail)
            val size = rail.fetchSemanticsNode().size
            val y = (letters.indexOf('M') + 1.5f) / (letters.size + 1) * size.height
            rail.performTouchInput { down(Offset(size.width / 2f, y)) }
            advance(200)
        }, after = { compose.onNodeWithTag(AlphabetRailTags.Rail).performTouchInput { up() } })

    @Test fun rowContextMenu() =
        snap("e4.2-row-context-menu", { ListHarness(SampleBook.state()) }, before = { h ->
            val row = h.ui.rows.first { it.id == 11L }
            compose.onNodeWithTag(ListTags.row(row.id)).performTouchInput { longClick(durationMillis = 700) }
            advance(900)
        })

    @Test fun recentlyViewed() {
        val items = SampleBook.items()
        val viewed =
            listOf(11L, 22L, 6L).map { id ->
                items.first { it.id == id }.copy(header = false, secondary = null)
            }
        val state =
            SampleBook.state(items, viewed = viewed).copy(
                myCard = MyCardItem(1, "Ayşe Yılmaz", "My Card"),
            )
        snap("e4.2-recently-viewed", { ListHarness(state) })
    }
}
