package dev.rovsti.phone.favorites

import dev.rovsti.core.model.phone.PhoneAccountInfo
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.data.eventually
import dev.rovsti.phone.keypad.sim1
import dev.rovsti.phone.keypad.sim2
import dev.rovsti.platform.system.permissions.PermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * E5.5: the order of the favorites from `prefs.favorites_order`, the drop and the row menu saved through
 * `contacts.favorites_move` into the Phone preferences, the Frequent section behind the call-log permission and the
 * `show_frequent` setting, a tap that calls. The Rust core is the real one; the provider and call log are in memory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {
    private val host by lazy { HostRust("rovsti-favorites-vm") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun mainDispatcher() =
        dev.rovsti.phone.UnconfinedMain
            .install()

    @After fun stop() = scope.cancel()

    private suspend fun rig(
        permission: PermissionState = PermissionState.Granted,
        callPermission: PermissionState = PermissionState.Granted,
        accounts: List<PhoneAccountInfo> = listOf(sim1),
    ) = PeopleRig(host, scope, permission = permission, callPermission = callPermission, accounts = accounts)
        .also { it.ready() }

    private fun FavoritesViewModel.watch() = state.onEach { }.launchIn(scope)

    private fun FavoritesUi.ids() = favorites.map { it.contactId }

    @Test fun favoritesAreTheStarredPeopleInTheSavedOrder() =
        runBlocking<Unit> {
            val r = rig()
            val model = r.favorites().also { it.watch() }
            assertEquals("starred people only", setOf<Long?>(1, 3, 5, 7), model.ready().ids().toSet())
            r.store.updatePrefs { it.copy(favoritesOrder = listOf("k-7", "k-5", "k-1", "k-3")) }
            assertEquals(listOf<Long?>(7, 5, 1, 3), model.where { it.ids().firstOrNull() == 7L }.ids())
            assertTrue("the type of the number is the label", model.ready().favorites.all { !it.label.isNullOrBlank() })
        }

    @Test fun aDropIsSavedAndSurvivesARestart() =
        runBlocking<Unit> {
            val r = rig()
            val model = r.favorites().also { it.watch() }
            r.store.updatePrefs { it.copy(favoritesOrder = listOf("k-7", "k-5", "k-1", "k-3")) }
            model.where { it.ids() == listOf<Long?>(7, 5, 1, 3) }
            model.onAction(FavoritesAction.Move(from = 0, to = 2))
            model.where { it.ids() == listOf<Long?>(5, 1, 7, 3) }
            assertTrue(r.store.flush())
            val fresh = r.reloaded()
            fresh.load()
            assertEquals(listOf("k-5", "k-1", "k-7", "k-3"), fresh.prefs.value.favoritesOrder)
        }

    @Test fun moveEarlierAndLaterAreButtonMoves() =
        runBlocking<Unit> {
            val r = rig()
            val model = r.favorites().also { it.watch() }
            r.store.updatePrefs { it.copy(favoritesOrder = listOf("k-1", "k-3", "k-5", "k-7")) }
            model.where { it.ids() == listOf<Long?>(1, 3, 5, 7) }
            model.onAction(FavoritesAction.MoveStep(index = 2, up = true))
            model.where { it.ids() == listOf<Long?>(1, 5, 3, 7) }
            model.onAction(FavoritesAction.MoveStep(index = 1, up = false))
            model.where { it.ids() == listOf<Long?>(1, 3, 5, 7) }
        }

    @Test fun movingTheFirstRowEarlierChangesNothing() =
        runBlocking<Unit> {
            val r = rig()
            val model = r.favorites().also { it.watch() }
            r.store.updatePrefs { it.copy(favoritesOrder = listOf("k-1", "k-3", "k-5", "k-7")) }
            model.where { it.ids() == listOf<Long?>(1, 3, 5, 7) }
            model.onAction(FavoritesAction.MoveStep(index = 0, up = true))
            model.onAction(FavoritesAction.Move(from = 3, to = 3))
            kotlinx.coroutines.delay(300)
            assertEquals(listOf("k-1", "k-3", "k-5", "k-7"), r.store.prefs.value.favoritesOrder)
        }

    @Test fun frequentPeopleAreTheMostCalledWhoAreNotFavorites() =
        runBlocking<Unit> {
            val r = rig()
            val model = r.favorites().also { it.watch() }
            val ui = model.where { it.frequent.isNotEmpty() }
            // Mert (3 calls) before Can (2); Ayse is a favorite already and stays out.
            assertEquals(listOf<Long?>(2, 6), ui.frequent.map { it.contactId })
            assertEquals("a tap dials the number of the call log", "+90 555 999 88 77", ui.frequent.first().number)
        }

    @Test fun frequentNeedsTheCallLogAndTheSetting() =
        runBlocking<Unit> {
            val denied = rig(callPermission = PermissionState.DeniedForGood)
            val noLog = denied.favorites().also { it.watch() }
            assertTrue(noLog.ready().frequent.isEmpty())

            val r = rig()
            val model = r.favorites().also { it.watch() }
            model.where { it.frequent.isNotEmpty() }
            r.store.updatePrefs { it.copy(settings = it.settings.copy(showFrequent = false)) }
            model.where { it.frequent.isEmpty() && it.favorites.isNotEmpty() }
        }

    @Test fun editNeedsTwoFavoritesAndHidesTheFrequentSection() =
        runBlocking<Unit> {
            val r = rig()
            val model = r.favorites().also { it.watch() }
            model.where { it.frequent.isNotEmpty() }
            model.onAction(FavoritesAction.ToggleEdit)
            val editing = model.where { it.editing }
            assertTrue(editing.canEdit)
            assertTrue("no Frequent while reordering", editing.frequent.isEmpty())
            model.onAction(FavoritesAction.ToggleEdit)
            model.where { !it.editing && it.frequent.isNotEmpty() }
            // One favorite left: there is nothing to reorder, edit mode cannot stay on.
            listOf(3L, 5L, 7L).forEach { model.onAction(FavoritesAction.Remove(it)) }
            model.where { it.favorites.size == 1 }
            model.onAction(FavoritesAction.ToggleEdit)
            kotlinx.coroutines.delay(100)
            assertFalse(model.state.value.editing)
        }

    @Test fun removeUnstarsAndAddStars() =
        runBlocking<Unit> {
            val r = rig()
            val model = r.favorites().also { it.watch() }
            model.ready()
            model.onAction(FavoritesAction.Remove(3))
            assertEquals(FavoritesEffect.Removed, withTimeout(10_000) { model.effect.first() })
            val after = model.where { 3L !in it.ids() }
            assertTrue("a removed favorite can be added again", after.pickables.any { it.contactId == 3L })
            model.onAction(FavoritesAction.Add(3))
            assertEquals(FavoritesEffect.Added, withTimeout(10_000) { model.effect.first() })
            model.where { 3L in it.ids() }
        }

    @Test fun aTapCallsAndTwoSimsAskWhichOne() =
        runBlocking<Unit> {
            val one = rig()
            val model = one.favorites().also { it.watch() }
            model.ready()
            model.onAction(FavoritesAction.Call("+90 555 111 22 33"))
            eventually("call placed") { one.launcher.placed.isNotEmpty() }
            assertEquals(
                "+90 555 111 22 33".filter { it.isDigit() || it == '+' },
                one.launcher.placed.single().first.filter {
                    it.isDigit() ||
                        it == '+'
                },
            )

            val two = rig(accounts = listOf(sim1, sim2))
            val dual = two.favorites().also { it.watch() }
            dual.ready()
            dual.onAction(FavoritesAction.Call("+905551112233"))
            val effect = withTimeout(10_000) { dual.effect.first() }
            assertTrue(effect is FavoritesEffect.ChooseSim)
            assertEquals(listOf(sim1.key, sim2.key), (effect as FavoritesEffect.ChooseSim).choices.map { it.key })
            assertTrue("nothing is dialled before the choice", two.launcher.placed.isEmpty())
            dual.onAction(FavoritesAction.CallOn("+905551112233", sim2.key))
            eventually("call placed on the chosen SIM") { two.launcher.placed.isNotEmpty() }
            assertEquals(
                sim2.key,
                two.launcher.placed
                    .single()
                    .second,
            )
        }

    @Test fun withoutContactsPermissionTheTabAsksForIt() =
        runBlocking<Unit> {
            val r = rig(permission = PermissionState.DeniedForGood)
            val model = r.favorites().also { it.watch() }
            assertEquals(FavoritesPhase.NoPermission, model.where { it.phase != FavoritesPhase.Loading }.phase)
        }
}
