package dev.rosty.messages.conversation

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.design.theme.RostyTheme
import dev.rosty.messages.inbox.RowAvatar
import dev.rosty.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The thread scroll rules on the real [ConversationScreen] (plan `thread-scroll.md`, 8.2): a fake clock through `now`,
 * a hand-made lifecycle owner, rows as a `mutableStateOf`. "At the newest" = the newest row is first visible with
 * offset 0.
 */
@Config(sdk = [35])
class ThreadScrollScreenTest : ShotTest() {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    private val owner = Owner()
    private val ui = mutableStateOf(ConversationUi())
    private val tools = mutableStateOf(ToolsUi())
    private val height = mutableStateOf<Dp?>(null)
    private val reopen = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var clockMs = NOW
    private lateinit var list: LazyListState

    private fun bubble(
        n: Int,
        outgoing: Boolean = false,
        state: BubbleState = if (outgoing) BubbleState.Delivered else BubbleState.None,
        text: String = "Message number $n",
        reactions: String? = null,
    ): ConversationItem =
        ConversationItem.Bubble(
            BubbleUi("m$n", outgoing, text, "12:00", state, NOW + n * 1_000L, reactions = reactions),
        )

    /** Bubbles [from] down to [to], newest first; every third one mine. */
    private fun rows(
        from: Int,
        to: Int = 1,
    ): List<ConversationItem> = (from downTo to).map { bubble(it, outgoing = it % 3 == 0) }

    private fun thread(items: List<ConversationItem>) =
        ConversationUi(
            title = "Mert Kaya",
            avatar = RowAvatar(colorKey = "mert"),
            items = items.toImmutableList(),
            loaded = true,
            composer = ComposerMode.Ready("Text message"),
        )

    @Composable
    private fun Screen(openSeq: Int = 0) {
        val labels =
            ToolLabels(
                "More",
                "Search in Chat",
                "Older",
                "Newer",
                "Done",
                "Jump to the latest message",
                { a, b -> "$a of $b" },
                "No matches",
            )
        CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            val h = height.value
            Box(if (h == null) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(h)) {
                OverlayHost(remember { OverlayController() }) {
                    ConversationScreen(
                        state = ui.value,
                        composer = rememberTextFieldState(),
                        listState = list,
                        actions =
                            remember {
                                ConversationActions(
                                    onBack = {},
                                    onSend = {},
                                    onLoadOlder = {},
                                    menuFor = { persistentListOf<MenuItem>() },
                                    onScrolled = { tools.value = tools.value.copy(scrollTo = null) },
                                )
                            },
                        sendLabel = "Send",
                        olderLabel = "Load Earlier",
                        tools = tools.value,
                        labels = labels,
                        openSeq = openSeq,
                        reopen = reopen,
                        now = { clockMs },
                    )
                }
            }
        }
    }

    private fun show(initial: ConversationUi) {
        ui.value = initial
        composeFrame {
            list = rememberLazyListState()
            Screen()
        }
        advance(300)
    }

    private fun set(items: List<ConversationItem>) {
        compose.runOnUiThread { ui.value = ui.value.copy(items = items.toImmutableList()) }
        advance(100)
    }

    private fun settle() = repeat(15) { advance(100) }

    private fun firstKey(): Any? =
        list.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == list.firstVisibleItemIndex }
            ?.key

    private fun spot() = Triple(firstKey(), list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)

    private fun assertAtNewest() {
        assertEquals(
            "the newest row is first visible",
            ui.value.items
                .first()
                .key,
            firstKey(),
        )
        assertEquals(0, list.firstVisibleItemIndex)
        assertEquals(0, list.firstVisibleItemScrollOffset)
    }

    private fun scrollUp(dp: Int = 600) {
        val px = with(compose.density) { dp.dp.toPx() }
        compose.runOnUiThread { runBlocking { list.scrollBy(px) } }
        advance(300)
    }

    private fun jumpShown() = compose.onAllNodesWithTag(ConversationTags.JumpLatest).fetchSemanticsNodes().isNotEmpty()

    private fun assertBadge(count: Int) {
        compose
            .onNode(
                hasText(count.toString()) and hasAnyAncestor(hasTestTag(ConversationTags.JumpBadge)),
                useUnmergedTree = true,
            ).assertExists()
    }

    private fun stop(away: Long) {
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        advance(100)
        clockMs += away
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        advance(300)
    }

    @Test fun t01_pinnedShowsNewRows() {
        show(thread(rows(30)))
        assertAtNewest()
        clockMs += 5_000
        set(rows(31))
        settle()
        assertAtNewest()
        compose.onNodeWithTag(ConversationTags.bubble("m31")).assertIsDisplayed()
        // Five at once: no animation, in place at the next frame.
        set(rows(36))
        assertAtNewest()
        compose.onNodeWithTag(ConversationTags.bubble("m36")).assertIsDisplayed()
        assertFalse(jumpShown())
    }

    @Test fun t02_scrolledUpKeepsThePlaceAndCounts() {
        show(thread(rows(40)))
        scrollUp()
        val before = spot()
        assertTrue(jumpShown())
        // m41, m43 incoming, m42 mine.
        set(rows(43))
        settle()
        assertEquals(before.first, firstKey())
        assertEquals(before.third, list.firstVisibleItemScrollOffset)
        assertTrue(jumpShown())
        assertBadge(2)
    }

    @Test fun t03_myNewSendPullsToTheNewest() {
        show(thread(rows(40)))
        scrollUp()
        set(listOf(bubble(41, outgoing = true, state = BubbleState.Sending)) + rows(40))
        settle()
        assertAtNewest()
        assertFalse(jumpShown())
    }

    @Test fun t03b_aScheduledOrOtherDeviceSendKeepsThePlace() {
        // A scheduled WhatsApp text goes out from the gateway: it arrives as a sent message, not a pending one.
        show(thread(rows(40)))
        scrollUp()
        val before = spot()
        set(listOf(bubble(41, outgoing = true, state = BubbleState.Sent)) + rows(40))
        settle()
        assertEquals(before.first, firstKey())
        assertEquals(before.third, list.firstVisibleItemScrollOffset)
        assertTrue(jumpShown())
    }

    @Test fun t04_pagingKeepsThePlace() {
        show(thread(rows(100, 51)))
        scrollUp()
        val before = spot()
        set(rows(100, 1))
        settle()
        assertEquals(before.first, firstKey())
        assertEquals(before.third, list.firstVisibleItemScrollOffset)
    }

    @Test fun t05_backFromTheBackgroundAtTheNewest() {
        show(thread(rows(30)))
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        advance(100)
        set(rows(33))
        clockMs += 60_000
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        settle()
        assertAtNewest()
        compose.onNodeWithTag(ConversationTags.bubble("m33")).assertIsDisplayed()
    }

    @Test fun t06_scrolledUpAndAway() {
        show(thread(rows(40)))
        scrollUp()
        val before = spot()
        stop(5 * 60_000L)
        assertEquals(before, spot())
        assertTrue(jumpShown())
        stop(THREAD_STALE_MS)
        assertAtNewest()
        assertFalse(jumpShown())
    }

    @Test fun t07_aShorterAndTallerListAreaStaysAtTheNewest() {
        show(thread(rows(40)))
        compose.runOnUiThread { height.value = 400.dp }
        settle()
        assertAtNewest()
        compose.onNodeWithTag(ConversationTags.bubble("m40")).assertIsDisplayed()
        compose.runOnUiThread { height.value = null }
        settle()
        assertAtNewest()
        compose.onNodeWithTag(ConversationTags.bubble("m40")).assertIsDisplayed()
    }

    @Test fun t08_editsAndHeightChangesDoNotMoveTheList() {
        show(thread(rows(40)))
        scrollUp()
        val before = spot()
        val anchor = list.firstVisibleItemIndex
        val changed =
            rows(40).mapIndexed { i, item ->
                val n = 40 - i
                when (i) {
                    anchor + 1 -> bubble(n, text = "A picture arrived\nand its size\nis known now\nfour lines")
                    anchor + 2 -> bubble(n, text = "Edited text")
                    anchor + 3 -> bubble(n, reactions = "👍")
                    else -> item
                }
            }
        set(changed)
        settle()
        assertEquals(before, spot())
        // Pinned: the same changes on the newest rows keep the newest in place.
        compose.runOnUiThread { runBlocking { list.scrollToItem(0) } }
        settle()
        assertAtNewest()
        set(listOf(bubble(40, text = "Edited\nover\nthree lines"), bubble(39, reactions = "❤️")) + rows(38))
        settle()
        assertAtNewest()
    }

    @Test fun t09_aJumpToAMessageEndsThere() {
        show(thread(rows(60)))
        compose.runOnUiThread { tools.value = ToolsUi(scrollTo = "m20") }
        settle()
        assertEquals("m20", firstKey())
        assertTrue(jumpShown())
        set(rows(61))
        settle()
        assertEquals("m20", firstKey())
    }

    @Test fun t10_restoredWithThePage() {
        val tester = StateRestorationTester(compose)
        ui.value = thread(rows(40))
        tester.setContent {
            list = rememberLazyListState()
            RostyTheme { Screen() }
        }
        advance(300)
        scrollUp()
        val before = spot()
        // Rows arrive while the page is away (a page pushed on top).
        set(rows(42))
        tester.emulateSavedInstanceStateRestore()
        settle()
        assertEquals(before.first, firstKey())
        assertEquals(before.third, list.firstVisibleItemScrollOffset)
        // Pinned restores at the newest, with rows that arrived meanwhile. (A missing anchor restores at the newest:
        // ThreadScrollTest 26; a live page re-anchors before it is saved, so it cannot be staged here.)
        compose.runOnUiThread { runBlocking { list.scrollToItem(0) } }
        settle()
        assertAtNewest()
        tester.emulateSavedInstanceStateRestore()
        set(rows(44))
        settle()
        assertAtNewest()
    }

    private fun unreadThread(unread: Int): List<ConversationItem> {
        val read = (20 downTo 1).map { bubble(it, outgoing = it % 2 == 0) }
        val fresh = (20 + unread downTo 21).map { bubble(it) }
        return fresh + ConversationItem.Divider("unread", "$unread unread messages") + read
    }

    private fun open(items: List<ConversationItem>) {
        // The rows arrive after the page is up, as from a view model.
        show(ConversationUi(title = "Mert Kaya", composer = ComposerMode.Ready("Text message")))
        compose.runOnUiThread { ui.value = thread(items) }
        settle()
    }

    @Test fun t11_opensAtTheFirstUnreadMessage() {
        open(unreadThread(40))
        val divider = compose.onNodeWithText("40 unread messages").fetchSemanticsNode().boundsInRoot
        val area = compose.onNodeWithTag(ConversationTags.List).fetchSemanticsNode().boundsInRoot
        assertTrue("the divider is in the top part: $divider in $area", divider.top < area.top + area.height / 4)
        assertTrue(divider.top >= area.top)
        assertTrue(jumpShown())
        // The bottom-most row on screen is an unread one; those below it are counted.
        val below = list.firstVisibleItemIndex
        assertTrue(below in 1 until 40)
        assertBadge(below)
        compose.runOnUiThread { runBlocking { list.scrollToItem(0) } }
        settle()
        assertFalse(jumpShown())
    }

    @Test fun t12_aShortUnreadRunOpensAtTheBottom() {
        open(unreadThread(2))
        assertAtNewest()
        assertFalse(jumpShown())
    }

    @Test fun t13_selectModeHoldsNewRows() {
        show(thread(rows(40)))
        compose.runOnUiThread { tools.value = ToolsUi(selected = persistentListOf<String>()) }
        advance(300)
        set(rows(41))
        settle()
        assertEquals("m40", firstKey())
        compose.runOnUiThread { tools.value = ToolsUi() }
        settle()
        assertAtNewest()
    }

    @Test fun t14_newestRowsRemovedWhileOneRowUp() {
        show(thread(rows(40)))
        compose.runOnUiThread { runBlocking { list.scrollToItem(1) } }
        settle()
        assertTrue(jumpShown())
        set(rows(39))
        settle()
        assertFalse(jumpShown())
        assertAtNewest()
    }

    @Test fun t15_aNotificationTapForThisThreadGoesToTheNewest() {
        show(thread(rows(40)))
        scrollUp()
        set(rows(42))
        compose.runOnUiThread { reopen.tryEmit(Unit) }
        settle()
        assertAtNewest()
        assertFalse(jumpShown())
    }

    @Test fun threadOpenAtUnread() {
        val labels = DayLabels(java.time.ZoneId.of("UTC"), java.util.Locale.US, true, "Today", "Yesterday")
        val texts =
            listOf(
                "Did you see the match last night?",
                "That last goal!",
                "I still can't believe it",
                "Are you coming on Saturday?",
                "Bring the blue scarf",
            )
        val bubbles =
            (1..44).map { n ->
                val mine = n <= 20 && n % 3 == 0
                BubbleUi(
                    "m$n",
                    mine,
                    texts[n % texts.size],
                    "%02d:%02d".format(9 + n / 6, n % 60),
                    if (mine) BubbleState.Read else BubbleState.None,
                    NOW - (45 - n) * 600_000L,
                )
            }
        val items = withDays(bubbles, labels, NOW, divider = "m21" to "24 unread messages")
        val options =
            RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))
        for (dark in listOf(false, true)) {
            ui.value = ConversationUi(title = "Mert Kaya", composer = ComposerMode.Ready("Text message"))
            composeFrame(dark = dark) {
                list = rememberLazyListState()
                Screen()
            }
            advance(300)
            compose.runOnUiThread {
                ui.value = thread(items)
            }
            settle()
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/thread-open-at-unread-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    private companion object {
        const val NOW = 1_790_000_000_000L
    }
}
