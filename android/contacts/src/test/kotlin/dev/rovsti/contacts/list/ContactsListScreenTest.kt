package dev.rovsti.contacts.list

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.list.AlphabetRailTags
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.2 on the real shell with the 40-contact [SampleBook]; the clock is manual (`autoAdvance = false`). */
@Config(sdk = [35])
class ContactsListScreenTest : ShotTest() {
    private val haptics = RecordingHaptics()

    @After fun clearProbe() {
        ListProbe.onRowComposed = null
    }

    private fun harness(
        state: ContactsListUiState = SampleBook.state(),
        onQuery: ((String) -> ContactsListUiState)? = null,
    ) = ListHarness(state, onQuery)

    private fun show(h: ListHarness) {
        composeFrame { HostedList(h, haptics) }
        advance(100)
    }

    @Test fun draggingTheRailOverMJumpsToItsHeaderWithoutAnimation() {
        val h = harness()
        show(h)
        val letters = h.ui.scrubberLetters.toList()
        // The rail has an extra slot above the letters (the jump-to-top arrow).
        val slot = letters.indexOf('M') + 1
        val rail = compose.onNodeWithTag(AlphabetRailTags.Rail)
        val size = rail.fetchSemanticsNode().size
        val y = (slot + 0.5f) / (letters.size + 1) * size.height
        rail.performTouchInput { down(Offset(size.width / 2f, y)) }
        // One frame is enough: scrollToItem is not animated.
        compose.mainClock.advanceTimeByFrame()
        // The jump lands the header just below the frosted bar: it is the first item whose top is on screen.
        val header =
            h.listState.layoutInfo.visibleItemsInfo
                .first { it.key == "h-M" }
        assertTrue("M header is placed below the status inset, not hidden above: ${header.offset}", header.offset >= 0)
        assertTrue("and near the top: ${header.offset}", header.offset < 400)
        assertTrue("a haptic tick per letter: ${haptics.events}", haptics.events.isNotEmpty())
        rail.performTouchInput { up() }
        advance(100)
    }

    @Test fun draggingAcrossLettersTicksOncePerLetter() {
        val h = harness()
        show(h)
        val letters = h.ui.scrubberLetters.toList()
        val rail = compose.onNodeWithTag(AlphabetRailTags.Rail)
        val size = rail.fetchSemanticsNode().size

        fun y(letter: Char) = (letters.indexOf(letter) + 1.5f) / (letters.size + 1) * size.height
        haptics.events.clear()
        rail.performTouchInput {
            down(Offset(size.width / 2f, y('B')))
            moveTo(Offset(size.width / 2f, y('B') + 1f))
            moveTo(Offset(size.width / 2f, y('E')))
            moveTo(Offset(size.width / 2f, y('M')))
            up()
        }
        advance(100)
        assertEquals(listOf(HapticEvent.Tick, HapticEvent.Tick, HapticEvent.Tick), haptics.events)
    }

    @Test fun theRailIsGoneWhileSearching() {
        val query = "a"
        val h = harness(SampleBook.state(SampleBook.items().filter { 'a' in it.name.lowercase() }, query))
        show(h)
        compose.onAllNodesWithTag(AlphabetRailTags.Rail).assertCountEquals(0)
    }

    @Test fun aLongPressOpensTheMenuAndLeavesTheRowWhereItWas() {
        val h = harness()
        show(h)
        val row = h.ui.rows[2]

        // While the menu is open the real row is hidden from the merged tree (its lifted copy stands in): look at both.
        fun bounds(id: Long) = compose.onNodeWithTag(ListTags.row(id), useUnmergedTree = true).getBoundsInRoot()
        val before = bounds(row.id)
        val next = bounds(h.ui.rows[3].id)
        compose.onNodeWithTag(ListTags.row(row.id)).performTouchInput { longClick(durationMillis = 700) }
        advance(900)
        compose.onNodeWithTag("context-panel").assertIsDisplayed()
        compose.onNodeWithText("Call").assertIsDisplayed()
        compose.onNodeWithText("Select").assertIsDisplayed()
        assertEquals(before, bounds(row.id))
        assertEquals(next, bounds(h.ui.rows[3].id))
        assertTrue("the menu opened with a long-press haptic", haptics.events.contains(HapticEvent.LongPress))
    }

    @Test fun theMenuActionsGoBackAsActions() {
        val h = harness()
        show(h)
        val row = h.ui.rows[2]
        compose.onNodeWithTag(ListTags.row(row.id)).performTouchInput { longClick(durationMillis = 700) }
        advance(900)
        compose.onNodeWithText("Call").performClick()
        advance(900)
        assertEquals(
            listOf<ContactsListAction>(ContactsListAction.Quick(row.id, "call")),
            h.actions.filterIsInstance<ContactsListAction.Quick>(),
        )
    }

    @Test fun aTapOpensTheCard() {
        val h = harness()
        show(h)
        val row = h.ui.rows[1]
        compose.onNodeWithTag(ListTags.row(row.id)).performClick()
        advance(100)
        assertTrue(h.actions.contains(ContactsListAction.Open(row.id)))
    }

    @Test fun theWhatsAppGlyphAppearsOncePerMarkedRowAndNowhereElse() {
        val h = harness()
        show(h)
        val rows = h.ui.rows
        for (marked in rows.filter { it.whatsapp }) {
            val index = rows.indexOf(marked)
            compose.runOnUiThread {
                kotlinx.coroutines.runBlocking {
                    h.listState.scrollToItem(2 + index + rows.take(index).count { it.header })
                }
            }
            advance(100)
            val glyphs = compose.onAllNodesWithTag(ListTags.WaGlyph, useUnmergedTree = true).fetchSemanticsNodes().size
            val visibleMarked =
                rows.count { r ->
                    r.whatsapp &&
                        compose.onAllNodesWithTag(ListTags.row(r.id)).fetchSemanticsNodes().isNotEmpty()
                }
            assertEquals("one glyph per visible marked row near ${marked.name}", visibleMarked, glyphs)
            compose
                .onNode(hasTestTag(ListTags.row(marked.id)))
                .assertExists()
            assertEquals(
                "exactly one in ${marked.name}'s row",
                1,
                compose
                    .onAllNodesWithTag(ListTags.WaGlyph, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .count { node ->
                        val bounds = node.boundsInRoot
                        val rowBounds = compose.onNodeWithTag(ListTags.row(marked.id)).getBoundsInRoot()
                        bounds.center.y / compose.density.density in rowBounds.top.value..rowBounds.bottom.value
                    },
            )
        }
    }

    @Test fun anUnmarkedRowHasNoGlyphAndAStarredRowHasTheStar() {
        val h = harness()
        show(h)
        val plain = h.ui.rows.first { !it.whatsapp && !it.starred }
        assertEquals(
            0,
            compose
                .onAllNodes(
                    hasTestTag(ListTags.row(plain.id)) and hasAnyDescendant(hasTestTag(ListTags.WaGlyph)),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .size,
        )
        val starred = h.ui.rows.first { it.starred && !it.whatsapp }
        compose
            .onAllNodes(
                hasTestTag(ListTags.row(starred.id)) and hasAnyDescendant(hasTestTag(ListTags.Star)),
                useUnmergedTree = true,
            ).assertCountEquals(1)
    }

    @Test fun theLastRowEndsAboveTheTabBar() {
        val h = harness()
        show(h)
        compose.runOnUiThread {
            kotlinx.coroutines.runBlocking {
                h.listState.scrollToItem(h.listState.layoutInfo.totalItemsCount - 1)
            }
        }
        advance(300)
        val last = h.ui.rows.last()
        val row = compose.onNodeWithTag(ListTags.row(last.id)).getBoundsInRoot()
        val bar = compose.onNodeWithTag("tab-surface").getBoundsInRoot()
        assertTrue("last row bottom ${row.bottom} is above the bar top ${bar.top}", row.bottom <= bar.top)
    }

    @Test fun noResultsOffersCreateAndAdd() {
        val state =
            SampleBook.state(emptyList(), "0599 111 22 33").copy(
                offer =
                    SearchOffer(
                        create = dev.rovsti.contacts.DraftSeed(number = "05991112233"),
                        addition =
                            dev.rovsti.core.model.contacts
                                .Addition(listOf("05991112233"), emptyList()),
                    ),
            )
        val h = harness(state)
        show(h)
        compose.onNodeWithText("No Results").assertIsDisplayed()
        compose.onNodeWithText("No contact matches “0599 111 22 33”.").assertIsDisplayed()
        compose.onNodeWithTag(ListTags.CreateFromSearch).performClick()
        advance(100)
        assertTrue(h.actions.contains(ContactsListAction.CreateFromQuery))
        compose.onNodeWithTag(ListTags.AddFromSearch).assertIsDisplayed()
    }

    @Test fun theToolbarHasSelectAndNew() {
        val h = harness()
        show(h)
        compose.onNodeWithTag("toolbar-select").performClick()
        compose.onNodeWithTag("toolbar-add").performClick()
        advance(900)
        // "+" opens a menu over the page (E4.10): New Contact, Paste Contact, Scan QR Code.
        compose.onNodeWithText("Paste Contact").assertExists()
        compose.onNodeWithText("Scan QR Code").assertExists()
        compose.onNodeWithText("New Contact").performClick()
        advance(600)
        assertTrue(h.actions.contains(ContactsListAction.EnterSelect(null)))
        assertTrue(h.actions.contains(ContactsListAction.NewContact))
    }

    @Test fun typingReportsTheQueryAndOnlyTheChangedRowsRecompose() {
        val all = SampleBook.items()
        val flat = all.filter { 'a' in it.name.lowercase() }.map { it.copy(header = false) }
        val counts = HashMap<Long, Int>()
        // "a" gives the flat result list; "ab" keeps its first eight rows (all on screen already).
        val h =
            harness(
                onQuery = {
                    when (it.length) {
                        0 -> SampleBook.state()
                        1 -> SampleBook.state(flat, it)
                        else -> SampleBook.state(flat.take(8), it)
                    }
                },
            )
        show(h)
        ListProbe.onRowComposed = { counts.merge(it, 1, Int::plus) }
        compose.onNodeWithTag(ListTags.Search).performTextInput("a")
        advance(100)
        assertEquals(ContactsListAction.QueryChanged("a"), h.actions.last())
        // The first letter turns the sectioned list into a flat result: every row is drawn once, none twice.
        assertTrue("each row at most once: $counts", counts.values.all { it <= 1 })
        counts.clear()
        compose.onNodeWithTag(ListTags.Search).performTextInput("b")
        advance(100)
        assertEquals(ContactsListAction.QueryChanged("ab"), h.actions.last())
        // The second letter narrows the list: the rows that stay are not drawn again.
        assertEquals("rows that were already there do not recompose: $counts", emptyMap<Long, Int>(), counts)
    }
}
