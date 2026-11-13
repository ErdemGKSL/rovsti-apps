package dev.rovsti.phone.recents

import android.content.Context
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import dev.rovsti.testing.RecompositionCounter
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * E5.14, E1.21 harness: scrolling Recents composes every row that enters once and recomposes no row whose data did
 * not change. Rows are counted through [RecentsProbe] into a [RecompositionCounter]; the counter is reset after the
 * first composition so only the scroll is measured.
 */
@Config(sdk = [35])
class RecentsScrollRecompositionTest : ShotTest() {
    private val counter = RecompositionCounter()

    @After fun clearProbe() {
        RecentsProbe.onRowComposed = null
    }

    private fun keysOnScreen(h: RecentsHarness) =
        h.listState.layoutInfo.visibleItemsInfo
            .map { it.key.toString() }
            .toSet()

    @Test fun scrollingThreeTimesNeverRecomposesARowThatStays() {
        val h = RecentsHarness()
        val strings = RecentsStrings(ApplicationProvider.getApplicationContext<Context>().resources)
        composeFrame { HostedRecents(h, strings, RecordingHaptics()) }
        advance(300)
        counter.reset()
        RecentsProbe.onRowComposed = { counter.increment(it) }
        repeat(3) {
            val staying = keysOnScreen(h)
            counter.reset()
            compose.onNodeWithTag("scaffold-list").performTouchInput {
                swipeUp(startY = bottom * 0.7f, endY = bottom * 0.7f - 90f, durationMillis = 200)
            }
            advance(100)
            assertTrue("each row at most once per scroll: $counter", counter.tags.all { counter[it] <= 1 })
            assertEquals(
                "rows that stayed recomposed: $counter",
                emptyList<String>(),
                counter.tags.filter {
                    it in
                        staying
                },
            )
        }
        counter.reset()
        compose.onNodeWithTag("scaffold-list").performTouchInput {
            swipeDown(startY = bottom * 0.3f, endY = bottom * 0.3f + 60f, durationMillis = 200)
        }
        advance(100)
        assertTrue("each row at most once when scrolling back: $counter", counter.tags.all { counter[it] <= 1 })
    }
}
