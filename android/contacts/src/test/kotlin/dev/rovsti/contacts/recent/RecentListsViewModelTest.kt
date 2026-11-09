package dev.rovsti.contacts.recent

import dev.rovsti.contacts.data.Book
import dev.rovsti.contacts.favorites.NOW
import dev.rovsti.contacts.me.DAY_MS
import dev.rovsti.contacts.me.PeopleRig
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * E4.14 Recently Added / Edited (C15, C45) on the real Rust core: [dev.rovsti.contacts.data.RecentTracker] folds each
 * pull of the book into the marks (`contacts.recent_note_pull`), the page shows `contacts.recent_lists` with the op's
 * "when" texts, and the segment is a state of the model.
 */
class RecentListsViewModelTest {
    private var rig: PeopleRig? = null

    @Before fun pinZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun stop() {
        rig?.stop()
    }

    private fun rig() = PeopleRig().also { rig = it }

    private fun PeopleRig.pull() = wait { tracker.pull(waitBook() as Book.Ready) }

    private fun PeopleRig.stateWhere(predicate: (RecentUiState) -> Boolean) =
        wait { recent.state.first { predicate(it) } }

    @Test fun theFirstPullOnlySeedsTheHighestIdAndMarksNothing() {
        val r = rig()
        r.pull()
        assertEquals(40L, r.store.prefs.value.knownMaxId)
        assertTrue(
            r.store.prefs.value.added
                .isEmpty(),
        )
        assertTrue(r.stateWhere { it.phase == RecentPhase.Ready }.added.isEmpty())
    }

    @Test fun aContactThatAppearsLaterIsMarkedAddedAndSurvivesARestart() {
        val r = rig()
        r.pull()
        val id = r.add("Nora", "Numara", "0580 111 22 33")
        r.wait { r.book.book.first { b -> (b as? Book.Ready)?.contacts?.any { it.id == id } == true } }
        r.pull()
        val state = r.stateWhere { it.added.isNotEmpty() }
        assertEquals(listOf(id), state.added.map { it.id })
        assertEquals("Nora Numara", state.added.single().name)
        assertEquals("Today", state.added.single().secondary)
        val fresh = r.reloaded()
        r.wait { fresh.load() }
        assertEquals(
            id,
            fresh.prefs.value.added
                .single()
                .id,
        )
        assertEquals(id, fresh.prefs.value.knownMaxId)
    }

    @Test fun anAddedMarkAgesIntoYesterdayAndOutOfTheWindow() {
        val r = rig()
        r.pull()
        val id = r.add("Nora", "Numara", "0580 111 22 33")
        r.wait { r.book.book.first { b -> (b as? Book.Ready)?.contacts?.any { it.id == id } == true } }
        r.pull()
        r.stateWhere { it.added.isNotEmpty() }
        r.now = NOW + DAY_MS
        // The model reads the clock when it builds: touch the preferences so it rebuilds.
        r.wait { r.store.update { it.copy(viewed = listOf("k-1")) } }
        assertEquals(
            "Yesterday",
            r
                .stateWhere { it.added.firstOrNull()?.secondary == "Yesterday" }
                .added
                .single()
                .secondary,
        )
        r.now = NOW + 31 * DAY_MS
        r.wait { r.store.update { it.copy(viewed = emptyList()) } }
        r.pull()
        assertTrue(r.stateWhere { it.added.isEmpty() && it.phase == RecentPhase.Ready }.added.isEmpty())
    }

    @Test fun editedListsTheLastThirtyDaysNewestFirstWithTheOpsWhenTexts() {
        val r = rig()
        val state = r.stateWhere { it.edited.isNotEmpty() }
        assertEquals(listOf(2L, 3L, 8L), state.edited.map { it.id })
        assertEquals(listOf("Today", "2 days ago", "7 Sep"), state.edited.map { it.secondary })
        assertTrue("35 days ago is outside the window", state.edited.none { it.id == 7L })
        assertEquals(30, state.windowDays)
    }

    @Test fun theSegmentsAndTheirEmptyTextsComeFromTheOp() {
        val r = rig()
        val state = r.stateWhere { it.tabs.isNotEmpty() }
        assertEquals(listOf("Added", "Edited"), state.tabs.map { it.label })
        assertEquals(listOf("added", "edited"), state.tabs.map { it.id })
        assertEquals("Nothing New", state.tabs[0].emptyTitle)
        assertEquals("No contacts added in the last 30 days", state.tabs[0].emptyBody)
        assertEquals("Nothing Edited", state.tabs[1].emptyTitle)
    }

    @Test fun switchingTheSegmentChangesTheRowsAndNothingElse() {
        val r = rig()
        r.stateWhere { it.edited.isNotEmpty() }
        assertEquals(RecentUiState.ADDED, r.recent.state.value.selected)
        assertTrue(
            r.recent.state.value.rows
                .isEmpty(),
        )
        r.recent.onAction(RecentAction.SelectTab(RecentUiState.EDITED))
        val edited = r.stateWhere { it.selected == RecentUiState.EDITED }
        assertEquals(1, edited.selectedIndex)
        assertEquals(listOf(2L, 3L, 8L), edited.rows.map { it.id })
        r.recent.onAction(RecentAction.SelectTab(RecentUiState.ADDED))
        assertTrue(r.stateWhere { it.selected == RecentUiState.ADDED }.rows.isEmpty())
    }

    @Test fun anEmptyFirstPullKeepsTheSeedUnset() {
        val r = rig()
        r.wait {
            r.tracker.pull(
                Book.Ready(
                    kotlinx.collections.immutable.persistentListOf(),
                    kotlinx.collections.immutable.persistentListOf(),
                ),
            )
        }
        assertNull(r.store.prefs.value.knownMaxId)
    }

    @Test fun openingARowAsksForItsCard() {
        val r = rig()
        r.stateWhere { it.edited.isNotEmpty() }
        r.recent.onAction(RecentAction.Open(3))
        assertEquals(RecentEffect.OpenCard(3), r.wait { r.recent.effects.first() })
    }
}
