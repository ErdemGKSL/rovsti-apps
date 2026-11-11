package dev.rovsti.phone.setup

import androidx.lifecycle.viewModelScope
import dev.rovsti.core.model.phone.ItemState
import dev.rovsti.core.model.phone.SetupItem
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.UnconfinedMain
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.platform.system.permissions.SettingsKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * E5.12 setup flows on the REAL core: the Xiaomi/HyperOS rows exist only on a Xiaomi phone, the full-screen-intent row
 * turns open when Android 14 revokes the grant, the exact-alarm row (reminders) appears while that grant is missing, and
 * a return from a system page reads Android again. The same state feeds the sheet and the Setup section of Settings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupFlowViewModelTest {
    private val host by lazy { HostRust("rovsti-setup-flow") }
    private val created = mutableListOf<SetupSheetViewModel>()

    @Before fun mainDispatcher() = UnconfinedMain.install()

    @After fun finishWork() {
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                created.forEach { vm ->
                    vm.viewModelScope.coroutineContext.job.children
                        .forEach { it.join() }
                }
            }
        }
    }

    private fun viewModel(facts: FlowFacts) =
        SetupSheetViewModel(
            host.ops,
            facts,
            InMemorySetupMemory(),
        ) { PermissionState.NeverAsked }.also { created += it }

    private fun SetupSheetViewModel.ready() = runBlocking { withTimeout(TIMEOUT_MS) { state.first { it.loaded } } }

    private fun SetupUiState.row(item: SetupItem) = rows.single { it.item == item }

    @Test fun xiaomiRowsAppearOnlyOnAXiaomiPhone() {
        val stock = viewModel(FlowFacts()).ready()
        assertTrue(stock.rows.none { it.item in XIAOMI_ROWS })
        val xiaomi = viewModel(FlowFacts(SetupFlowRig.xiaomiFacts, xiaomi = true)).ready()
        assertEquals(
            XIAOMI_ROWS,
            xiaomi.rows
                .map { it.item }
                .filter { it in XIAOMI_ROWS }
                .toSet(),
        )
        // Autostart is readable: denied is a real "not done" with a button that opens the Xiaomi page.
        assertEquals(ItemState.NotDone, xiaomi.row(SetupItem.Autostart).state)
        assertEquals(
            listOf(SetupAct.XiaomiAutostart),
            xiaomi.row(SetupItem.Autostart).buttons.map { it.act },
        )
        // Battery saver is the Xiaomi page first, the plain Android dialog second.
        assertEquals(
            listOf(SetupAct.XiaomiBattery, SetupAct.BatteryDialog),
            xiaomi.row(SetupItem.BatterySaver).buttons.map { it.act },
        )
    }

    @Test fun theFullScreenRowIsOpenWhenAndroidRevokedTheGrant() {
        val revoked = viewModel(FlowFacts(SetupFlowRig.allGranted.copy(fullScreenAllowed = false))).ready()
        val row = revoked.row(SetupItem.FullScreenNotifications)
        assertEquals(ItemState.NotDone, row.state)
        assertEquals(listOf(SetupAct.FullScreen), row.buttons.map { it.act })
        assertFalse(revoked.complete)

        val granted = viewModel(FlowFacts()).ready()
        assertEquals(ItemState.Done, granted.row(SetupItem.FullScreenNotifications).state)
        assertTrue(granted.row(SetupItem.FullScreenNotifications).buttons.isEmpty())
        assertTrue(granted.complete)
    }

    @Test fun pressingTheFullScreenRowOpensItsSystemPage() {
        val vm = viewModel(FlowFacts(SetupFlowRig.allGranted.copy(fullScreenAllowed = false)))
        val state = vm.ready()
        val button = state.row(SetupItem.FullScreenNotifications).buttons.single()
        vm.onAction(SetupAction.Press(SetupItem.FullScreenNotifications, button))
        val effect = runBlocking { withTimeout(TIMEOUT_MS) { vm.effects.first() } }
        assertEquals(SetupEffect.OpenSettings(SetupItem.FullScreenNotifications, SettingsKind.FullScreenIntent), effect)
    }

    @Test fun theExactAlarmRowAppearsOnlyWhileTheGrantIsMissingAndOpensItsPage() {
        assertFalse(viewModel(FlowFacts()).ready().exactAlarmMissing)
        val vm = viewModel(FlowFacts(exact = false))
        assertTrue(vm.ready().exactAlarmMissing)
        vm.onAction(SetupAction.OpenExactAlarms)
        assertEquals(SetupEffect.OpenExactAlarms, runBlocking { withTimeout(TIMEOUT_MS) { vm.effects.first() } })
    }

    @Test fun returningFromASystemPageReadsAndroidAgain() {
        val facts = FlowFacts(SetupFlowRig.allGranted.copy(fullScreenAllowed = false), exact = false)
        val vm = viewModel(facts)
        assertEquals(ItemState.NotDone, vm.ready().row(SetupItem.FullScreenNotifications).state)

        facts.facts = SetupFlowRig.allGranted
        facts.exact = true
        vm.onAction(SetupAction.Refresh)
        val after =
            runBlocking {
                withTimeout(TIMEOUT_MS) { vm.state.first { !it.exactAlarmMissing && it.complete } }
            }
        assertEquals(ItemState.Done, after.row(SetupItem.FullScreenNotifications).state)
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        val XIAOMI_ROWS =
            setOf(
                SetupItem.Autostart,
                SetupItem.ShowOnLockScreen,
                SetupItem.BackgroundPopups,
                SetupItem.BatterySaver,
            )
    }
}
