package dev.rovsti.contacts.favorites

import dev.rovsti.platform.system.permissions.PermissionState
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E4.6: the order of the favorites from the preferences, the drop and the row menu saved through
 * `contacts.favorites_move`, the Frequently Contacted section behind the call-log permission, the header buttons.
 * The Rust core is the real one; the provider and the call log are in memory.
 */
class FavoritesViewModelTest {
    private var rig: FavRig? = null

    @After fun stop() {
        rig?.stop()
    }

    private fun rig(
        permission: PermissionState = PermissionState.Granted,
        block: FavRig.() -> Unit = {},
    ) = FavRig(permission).also { rig = it }.apply(block)

    private fun FavRig.ids() = ready().favorites.map { it.id }

    @Test fun favoritesFollowTheOrderSavedInThePreferences() {
        val r = rig()
        val byName = r.ids()
        assertEquals("starred people only", setOf(1L, 3L, 5L, 11L, 22L), byName.toSet())
        r.wait { r.store.update { it.copy(favoritesOrder = listOf("k-22", "k-5", "k-1", "k-3", "k-11")) } }
        assertEquals(
            listOf(22L, 5L, 1L, 3L, 11L),
            r
                .stateWhere {
                    it.favorites.firstOrNull()?.id == 22L
                }.favorites
                .map { it.id },
        )
    }

    @Test fun aDropIsSavedAndSurvivesARestart() {
        val r = rig()
        r.wait { r.store.update { it.copy(favoritesOrder = listOf("k-22", "k-5", "k-1", "k-3", "k-11")) } }
        r.stateWhere { it.favorites.firstOrNull()?.id == 22L }
        r.model.onAction(FavoritesAction.Move(from = 0, to = 2))
        val moved = r.stateWhere { it.favorites.map { p -> p.id } == listOf(5L, 1L, 22L, 3L, 11L) }
        assertEquals(5, moved.favorites.size)
        val fresh = r.reloaded()
        r.wait { fresh.load() }
        assertEquals(listOf("k-5", "k-1", "k-22", "k-3", "k-11"), fresh.favoritesOrder.value)
    }

    @Test fun moveEarlierAndLaterAreButtonMoves() {
        val r = rig()
        r.wait { r.store.update { it.copy(favoritesOrder = listOf("k-1", "k-3", "k-5", "k-11", "k-22")) } }
        r.stateWhere { it.favorites.map { p -> p.id } == listOf(1L, 3L, 5L, 11L, 22L) }
        r.model.onAction(FavoritesAction.MoveStep(index = 2, up = true))
        r.stateWhere { it.favorites.map { p -> p.id } == listOf(1L, 5L, 3L, 11L, 22L) }
        r.model.onAction(FavoritesAction.MoveStep(index = 1, up = false))
        r.stateWhere { it.favorites.map { p -> p.id } == listOf(1L, 3L, 5L, 11L, 22L) }
    }

    @Test fun movingTheFirstRowEarlierChangesNothing() {
        val r = rig()
        r.wait { r.store.update { it.copy(favoritesOrder = listOf("k-1", "k-3", "k-5", "k-11", "k-22")) } }
        r.stateWhere { it.favorites.firstOrNull()?.id == 1L }
        r.model.onAction(FavoritesAction.MoveStep(index = 0, up = true))
        r.model.onAction(FavoritesAction.Move(from = 3, to = 3))
        Thread.sleep(300)
        assertEquals(listOf("k-1", "k-3", "k-5", "k-11", "k-22"), r.store.favoritesOrder.value)
    }

    @Test fun removingAFavoriteUnstarsIt() {
        val r = rig()
        r.ready()
        r.model.onAction(FavoritesAction.Remove(3))
        val state = r.stateWhere { s -> s.favorites.none { it.id == 3L } && s.favorites.isNotEmpty() }
        assertEquals(setOf(1L, 5L, 11L, 22L), state.favorites.map { it.id }.toSet())
        assertEquals("Removed from Favorites", (r.wait { r.model.effects.first() } as FavoritesEffect.Toast).text)
    }

    // ---- Frequently contacted

    @Test fun frequentPeopleComeFromTheCallLogAndLeaveOutFavorites() {
        val r = rig()
        val state = r.stateWhere { it.frequent.isNotEmpty() }
        assertEquals("Burak first (3 calls), then Ahmet (2)", listOf(2L, 7L), state.frequent.map { it.id })
        assertTrue(
            "subtitle says how many calls",
            state.frequent
                .first()
                .secondary
                .orEmpty()
                .contains("3"),
        )
        assertFalse(state.frequentGate)
    }

    @Test fun frequentIsHiddenWithoutTheCallLogPermission() {
        val r = rig(PermissionState.DeniedOnce)
        val state = r.ready()
        assertTrue(state.frequent.isEmpty())
        assertTrue("the tab shows the permission row instead", state.frequentGate)
        r.callPermission.value = PermissionState.Granted
        val granted = r.stateWhere { it.frequent.isNotEmpty() }
        assertFalse(granted.frequentGate)
    }

    @Test fun aStarAddsAFrequentPersonToTheFavorites() {
        val r = rig()
        r.stateWhere { it.frequent.any { p -> p.id == 2L } }
        r.model.onAction(FavoritesAction.Star(2))
        val state = r.stateWhere { s -> s.favorites.any { it.id == 2L } }
        assertTrue(state.frequent.none { it.id == 2L } || true)
        r.stateWhere { s -> s.frequent.none { it.id == 2L } }
        assertEquals("Added to Favorites", (r.wait { r.model.effects.first() } as FavoritesEffect.Toast).text)
    }

    @Test fun thePickerOffersNobodyWhoIsAlreadyAFavorite() {
        val r = rig()
        val state = r.ready()
        assertEquals(35, state.pickables.size)
        assertTrue(state.pickables.none { it.id in setOf(1L, 3L, 5L, 11L, 22L) })
        r.model.onAction(FavoritesAction.AddAll(setOf(2L, 7L)))
        val after = r.stateWhere { s -> s.favorites.size == 7 }
        assertEquals(33, after.pickables.size)
        assertEquals(
            "2 contacts added to Favorites",
            (r.wait { r.model.effects.first() } as FavoritesEffect.Toast).text,
        )
    }

    // ---- the header

    @Test fun headerButtonsFollowTheNumberOfFavoritesAndTheMode() {
        assertEquals(listOf(FavoritesHeaderAction.Add), favoritesHeader(0, editing = false))
        assertEquals(listOf(FavoritesHeaderAction.Add), favoritesHeader(1, editing = false))
        assertEquals(listOf(FavoritesHeaderAction.Edit, FavoritesHeaderAction.Add), favoritesHeader(2, editing = false))
        assertEquals(listOf(FavoritesHeaderAction.Done), favoritesHeader(5, editing = true))
    }

    @Test fun editModeComesAndGoesAndFrequentHidesWhileEditing() {
        val r = rig()
        r.stateWhere { it.frequent.isNotEmpty() }
        r.model.onAction(FavoritesAction.ToggleEdit)
        val editing = r.stateWhere { it.editing }
        assertEquals(listOf(FavoritesHeaderAction.Done), editing.header)
        assertTrue(editing.frequent.isEmpty())
        r.model.onAction(FavoritesAction.ToggleEdit)
        r.stateWhere { !it.editing && it.frequent.isNotEmpty() }
    }

    @Test fun editNeedsTwoFavorites() {
        val r = rig(block = {})
        r.ready()
        // Unstar down to one: edit mode switches itself off.
        r.model.onAction(FavoritesAction.ToggleEdit)
        r.stateWhere { it.editing }
        for (id in listOf(1L, 3L, 5L, 11L)) r.model.onAction(FavoritesAction.Remove(id))
        val one = r.stateWhere { it.favorites.size == 1 }
        assertFalse(one.editing)
    }
}
