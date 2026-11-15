package dev.rosty.messages.conversation

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.insert
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.testing.RecompositionCounter
import dev.rosty.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * E6.14 AC1: typing in the composer recomposes no bubble, scrolling a 2 000-bubble conversation composes each entering
 * bubble at most once and recomposes none that stays, opening a bubble menu recomposes at most the pressed bubble.
 * Bubbles are counted through [ConversationProbe].
 */
@Config(sdk = [35])
class ConversationRecompositionTest : ShotTest() {
    private val counter = RecompositionCounter()
    private lateinit var list: LazyListState
    private lateinit var field: TextFieldState

    @After fun clearProbe() {
        ConversationProbe.onBubbleComposed = null
    }

    private fun state(): ConversationUi {
        val now = ConversationSample.NOW
        val bubbles =
            (0 until COUNT).map { i ->
                BubbleUi("sms:$i", i % 3 == 0, "Message number $i", "12:00", atMs = now - (COUNT - i) * 60_000L)
            }
        val labels = DayLabels(java.time.ZoneId.of("UTC"), java.util.Locale.US, true, "Today", "Yesterday")
        return ConversationUi(
            title = "Mert Kaya",
            items = withDays(bubbles, labels, now).toImmutableList(),
            loaded = true,
            composer = ComposerMode.Ready("Text message"),
        )
    }

    private fun show() {
        val ui = state()
        val menu = persistentListOf<MenuItem>(MenuItem.Action("Copy", RostyIcons.Copy) {})
        composeFrame {
            list = rememberLazyListState()
            field = rememberTextFieldState()
            OverlayHost(remember { OverlayController() }) {
                ConversationScreen(
                    state = ui,
                    composer = field,
                    listState = list,
                    actions =
                        remember {
                            ConversationActions(
                                onBack = {},
                                onSend = {},
                                onLoadOlder = {},
                                menuFor = { menu },
                            )
                        },
                    sendLabel = "Send",
                    olderLabel = "Load Earlier",
                )
            }
        }
        advance(300)
        ConversationProbe.onBubbleComposed = { counter.increment(it) }
        counter.reset()
    }

    private fun onScreen() =
        list.layoutInfo.visibleItemsInfo
            .map { it.key.toString() }
            .filter { it.startsWith("sms:") }
            .toSet()

    @Test fun typingRecomposesNoBubble() {
        show()
        repeat(10) {
            compose.runOnUiThread { field.edit { insert(length, "a") } }
            advance(16)
        }
        assertTrue("bubbles recomposed while typing: $counter", counter.tags.all { counter[it] <= 2 })
        assertEquals("no bubble should recompose for a keystroke: $counter", 0, counter.tags.sumOf { counter[it] })
    }

    @Test fun scrollingComposesEnteringBubblesOnceAndLeavesTheOthers() {
        show()
        repeat(3) {
            val staying = onScreen()
            counter.reset()
            // Reverse layout: a positive scroll moves to older messages (a third of a screen each time).
            compose.runOnUiThread { runBlocking { list.scrollBy(SCROLL_PX) } }
            advance(100)
            assertTrue("the scroll brought new bubbles in: $counter", counter.tags.isNotEmpty())
            assertTrue("each bubble at most once per scroll: $counter", counter.tags.all { counter[it] <= 1 })
            val kept = staying intersect onScreen()
            assertEquals(
                "bubbles that stayed recomposed: $counter",
                emptyList<String>(),
                counter.tags.filter {
                    it in
                        kept
                },
            )
        }
    }

    @Test fun openingAMenuRecomposesAtMostThePressedBubble() {
        show()
        val pressed = onScreen().first()
        compose
            .onNodeWithTag(
                ConversationTags.bubble(pressed),
            ).performTouchInput { longClick(center, durationMillis = 700) }
        repeat(10) { advance(100) }
        assertTrue(
            "the menu opened",
            compose.onAllNodesWithTag(ConversationTags.BubblePanel).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue("others recomposed: $counter", counter.tags.all { it == pressed })
    }

    private companion object {
        const val COUNT = 2_000
        const val SCROLL_PX = 600f
    }
}
