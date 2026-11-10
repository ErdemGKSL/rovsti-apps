package dev.rovsti.phone.setup

import androidx.lifecycle.viewModelScope
import dev.rovsti.core.model.phone.ItemState
import dev.rovsti.core.model.phone.Reading
import dev.rovsti.core.model.phone.SetupFacts
import dev.rovsti.core.model.phone.SetupItem
import dev.rovsti.phone.HostRust
import dev.rovsti.platform.system.permissions.PermissionGroup
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The facts the test controls; everything else is read from the real core's answers. */
private class FakeFacts(
    var facts: SetupFacts = SetupFacts(),
    override var xiaomi: Boolean = false,
    override val appVersion: String = "1.0.0",
) : SetupFactsSource {
    override fun facts(): SetupFacts = facts
}

/**
 * E5.1 Setup sheet on the REAL Rust core (`setup.steps`, `setup.checklist`, `setup.xiaomi`, `setup.reopened`): the rows
 * and their states are the core's, the buttons follow the state, a resume re-reads the facts and names a row that was
 * lost, and the Xiaomi rows can be confirmed by hand only after their page was opened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupSheetViewModelTest {
    private val host by lazy { HostRust("rovsti-setup") }
    private val allGranted =
        SetupFacts(
            roleHeld = true,
            phoneAndCallLogGranted = true,
            contactsGranted = true,
            notificationsGranted = true,
            fullScreenAllowed = true,
            batteryExempt = true,
        )
    private val created = mutableListOf<SetupSheetViewModel>()
    private var permissions = mutableMapOf<PermissionGroup, PermissionState>()

    @Before fun mainDispatcher() =
        dev.rovsti.phone.UnconfinedMain
            .install()

    @After fun resetMainDispatcher() {
        // A refresh may still be on its way back to Main from the Rust call; let it finish before Main is released.
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                created.forEach { vm ->
                    vm.viewModelScope.coroutineContext.job.children
                        .forEach { it.join() }
                }
            }
        }
    }

    private fun viewModel(
        facts: FakeFacts,
        memory: SetupMemory = InMemorySetupMemory(),
    ) = SetupSheetViewModel(host.ops, facts, memory) { permissions[it] ?: PermissionState.NeverAsked }.also {
        created +=
            it
    }

    private fun <T> wait(block: suspend () -> T): T = runBlocking { withTimeout(TIMEOUT_MS) { block() } }

    private fun SetupSheetViewModel.ready() = wait { state.first { it.loaded } }

    private fun SetupSheetViewModel.refresh(until: (SetupUiState) -> Boolean): SetupUiState {
        onAction(SetupAction.Refresh)
        return wait { state.first(until) }
    }

    private fun SetupUiState.row(item: SetupItem) = rows.single { it.item == item }

    private fun SetupRowUi.acts() = buttons.map { it.act }

    @Test fun theRowsAndTheirStatesComeFromTheCore() {
        val vm = viewModel(FakeFacts(allGranted.copy(roleHeld = false, contactsGranted = false)))
        val state = vm.ready()
        assertEquals(
            listOf(
                SetupItem.DefaultPhoneApp,
                SetupItem.PhoneAndCallLog,
                SetupItem.Contacts,
                SetupItem.Notifications,
                SetupItem.FullScreenNotifications,
            ),
            state.rows.map { it.item },
        )
        assertEquals("Default phone app", state.row(SetupItem.DefaultPhoneApp).title)
        assertEquals(ItemState.NotDone, state.row(SetupItem.DefaultPhoneApp).state)
        assertEquals(ItemState.Done, state.row(SetupItem.PhoneAndCallLog).state)
        assertEquals("3 of 5", state.progressLabel)
        assertFalse(state.complete)
        assertFalse(state.roleHeld)
        assertEquals(0.6f, state.fraction, 0.001f)
    }

    @Test fun doneRowsHaveNoButtonsAndTheOpenOnesHaveTheirs() {
        val vm =
            viewModel(FakeFacts(allGranted.copy(roleHeld = false, contactsGranted = false, fullScreenAllowed = false)))
        val state = vm.ready()
        assertEquals(listOf(SetupAct.AppInfo, SetupAct.RequestRole), state.row(SetupItem.DefaultPhoneApp).acts())
        assertEquals(listOf(SetupAct.Allow), state.row(SetupItem.Contacts).acts())
        assertEquals(
            PermissionGroup.CONTACTS,
            state
                .row(SetupItem.Contacts)
                .buttons
                .single()
                .group,
        )
        assertEquals(listOf(SetupAct.FullScreen), state.row(SetupItem.FullScreenNotifications).acts())
        assertTrue(state.row(SetupItem.Notifications).buttons.isEmpty())
        assertEquals(SetupSteps.Restricted, state.row(SetupItem.DefaultPhoneApp).steps)
        assertNull("only the role row has help text here", state.row(SetupItem.Contacts).steps)
    }

    @Test fun theRoleButtonAsksForTheRoleAndARefusalOffersDefaultApps() {
        val facts = FakeFacts(allGranted.copy(roleHeld = false))
        val vm = viewModel(facts)
        val before = vm.ready()
        val role = before.row(SetupItem.DefaultPhoneApp).buttons.single { it.act == SetupAct.RequestRole }
        vm.onAction(SetupAction.Press(SetupItem.DefaultPhoneApp, role))
        assertEquals(SetupEffect.RequestRole, wait { vm.effects.first() })

        vm.onAction(SetupAction.RoleAnswered(held = false))
        val declined = wait { vm.state.first { SetupAct.DefaultApps in it.row(SetupItem.DefaultPhoneApp).acts() } }
        assertEquals(
            listOf(SetupAct.AppInfo, SetupAct.RequestRole, SetupAct.DefaultApps),
            declined.row(SetupItem.DefaultPhoneApp).acts(),
        )
        val defaults = declined.row(SetupItem.DefaultPhoneApp).buttons.single { it.act == SetupAct.DefaultApps }
        vm.onAction(SetupAction.Press(SetupItem.DefaultPhoneApp, defaults))
        assertEquals(
            SetupEffect.OpenSettings(SetupItem.DefaultPhoneApp, SettingsKind.DefaultApps),
            wait { vm.effects.first() },
        )

        // The role is granted: the row is done, the sheet is complete and the notice line has nothing to say.
        facts.facts = allGranted
        vm.onAction(SetupAction.RoleAnswered(held = true))
        val done = wait { vm.state.first { it.complete } }
        assertTrue(done.roleHeld)
        assertTrue(done.row(SetupItem.DefaultPhoneApp).buttons.isEmpty())
        assertEquals("5 of 5", done.progressLabel)
    }

    @Test fun aPermissionDeniedForGoodOnlyOpensSettings() {
        permissions[PermissionGroup.CONTACTS] = PermissionState.DeniedForGood
        permissions[PermissionGroup.PHONE] = PermissionState.DeniedOnce
        val vm = viewModel(FakeFacts(allGranted.copy(contactsGranted = false, phoneAndCallLogGranted = false)))
        val state = vm.ready()
        assertEquals(listOf(SetupAct.OpenSettings), state.row(SetupItem.Contacts).acts())
        assertEquals(listOf(SetupAct.Allow), state.row(SetupItem.PhoneAndCallLog).acts())

        vm.onAction(SetupAction.Press(SetupItem.Contacts, state.row(SetupItem.Contacts).buttons.single()))
        assertEquals(SetupEffect.OpenSettings(SetupItem.Contacts, SettingsKind.AppDetails), wait { vm.effects.first() })
        vm.onAction(SetupAction.Press(SetupItem.PhoneAndCallLog, state.row(SetupItem.PhoneAndCallLog).buttons.single()))
        assertEquals(SetupEffect.RequestPermission(PermissionGroup.PHONE), wait { vm.effects.first() })
    }

    @Test fun aResumeThatFindsAGrantTakenBackNamesTheRow() {
        val facts = FakeFacts(allGranted)
        val vm = viewModel(facts)
        val first = vm.ready()
        assertTrue(first.complete)
        assertNull(first.changedTitle)

        facts.facts = allGranted.copy(notificationsGranted = false)
        val lost = vm.refresh { !it.complete }
        assertEquals("Something changed: Notifications", lost.changedTitle)
        assertEquals(ItemState.NotDone, lost.row(SetupItem.Notifications).state)

        // Still lost on the next resume: the title stays until the row is done again.
        val still = vm.refresh { !it.complete }
        assertEquals("Something changed: Notifications", still.changedTitle)

        facts.facts = allGranted
        val back = vm.refresh { it.complete }
        assertNull(back.changedTitle)
    }

    @Test fun twoLostRowsAreCountedInTheTitle() {
        val facts = FakeFacts(allGranted)
        val vm = viewModel(facts)
        vm.ready()
        facts.facts = allGranted.copy(notificationsGranted = false, contactsGranted = false)
        val lost = vm.refresh { !it.complete }
        assertTrue(lost.changedTitle.orEmpty().startsWith("Something changed: "))
        assertTrue(lost.changedTitle.orEmpty().endsWith("and 1 more"))
    }

    @Test fun anOpenedSettingsPageThatCannotBeStartedSaysSo() {
        val vm = viewModel(FakeFacts(allGranted.copy(fullScreenAllowed = false)))
        vm.ready()
        vm.onAction(SetupAction.SettingsOpened(SetupItem.FullScreenNotifications, opened = false, fellBack = false))
        val state = wait { vm.state.first { it.note != null } }
        assertEquals(SetupNote.CouldNotOpen, state.note)
        vm.onAction(SetupAction.SettingsOpened(SetupItem.FullScreenNotifications, opened = true, fellBack = false))
        assertNull(wait { vm.state.first { it.note == null } }.note)
    }

    // ---- Xiaomi ---------------------------------------------------------------------------------------------------

    private val xiaomiFacts =
        allGranted.copy(
            autostart = Reading.Denied,
            lockScreen = Reading.Unknown,
            backgroundPopups = Reading.Unknown,
        )

    @Test fun theXiaomiRowsAppearOnlyOnAXiaomiPhone() {
        assertEquals(5, viewModel(FakeFacts(allGranted)).ready().rows.size)
        val state = viewModel(FakeFacts(xiaomiFacts, xiaomi = true)).ready()
        assertEquals(9, state.rows.size)
        assertEquals(ItemState.NotDone, state.row(SetupItem.Autostart).state)
        assertEquals(ItemState.Unverifiable, state.row(SetupItem.ShowOnLockScreen).state)
        assertEquals(SetupSteps.Autostart, state.row(SetupItem.Autostart).steps)
        // Nothing to confirm before the page was opened, and the help says so.
        assertFalse(SetupAct.Confirm in state.row(SetupItem.ShowOnLockScreen).acts())
        assertEquals(StepsTail.OpenFirst, state.row(SetupItem.ShowOnLockScreen).tail)
    }

    @Test fun aXiaomiRowIsConfirmedByHandOnlyAfterItsPageWasOpenedAndCanBeTakenBack() {
        val facts = FakeFacts(xiaomiFacts, xiaomi = true)
        val memory = InMemorySetupMemory()
        val vm = viewModel(facts, memory)
        val state = vm.ready()
        val open = state.row(SetupItem.ShowOnLockScreen).buttons.single { it.act == SetupAct.XiaomiOther }
        vm.onAction(SetupAction.Press(SetupItem.ShowOnLockScreen, open))
        assertEquals(
            SetupEffect.OpenSettings(SetupItem.ShowOnLockScreen, SettingsKind.XiaomiPermissions),
            wait {
                vm.effects.first()
            },
        )

        vm.onAction(SetupAction.SettingsOpened(SetupItem.ShowOnLockScreen, opened = true, fellBack = false))
        val opened = wait { vm.state.first { SetupAct.Confirm in it.row(SetupItem.ShowOnLockScreen).acts() } }
        val confirm = opened.row(SetupItem.ShowOnLockScreen).buttons.single { it.act == SetupAct.Confirm }
        vm.onAction(SetupAction.Press(SetupItem.ShowOnLockScreen, confirm))
        val done = wait { vm.state.first { it.row(SetupItem.ShowOnLockScreen).state == ItemState.Done } }
        assertEquals(listOf(SetupAct.Unconfirm), done.row(SetupItem.ShowOnLockScreen).acts())
        assertEquals("1.0.0", wait { memory.confirmations() }.appVersion)

        val undo = done.row(SetupItem.ShowOnLockScreen).buttons.single()
        vm.onAction(SetupAction.Press(SetupItem.ShowOnLockScreen, undo))
        wait { vm.state.first { it.row(SetupItem.ShowOnLockScreen).state == ItemState.Unverifiable } }
    }

    @Test fun aXiaomiPageThatFellBackToAppInfoSaysSo() {
        // Battery saver reads as done when the app is exempt; the help matters while it is not.
        val vm = viewModel(FakeFacts(xiaomiFacts.copy(batteryExempt = false), xiaomi = true))
        vm.ready()
        vm.onAction(SetupAction.SettingsOpened(SetupItem.BatterySaver, opened = true, fellBack = true))
        val state = wait { vm.state.first { it.row(SetupItem.BatterySaver).tail == StepsTail.FellBack } }
        assertEquals(SetupSteps.Battery, state.row(SetupItem.BatterySaver).steps)
    }

    @Test fun aConfirmationForAnOlderVersionIsDroppedOnTheNextLook() {
        val memory = InMemorySetupMemory()
        val facts = FakeFacts(xiaomiFacts, xiaomi = true)
        val vm = viewModel(facts, memory)
        vm.ready()
        vm.onAction(SetupAction.SettingsOpened(SetupItem.ShowOnLockScreen, opened = true, fellBack = false))
        // Pretend the row was opened and confirmed by an older version.
        val old =
            dev.rovsti.core.model.phone.XiaomiConfirmations(
                appVersion = "0.9.0",
                confirmed =
                    1 shl SetupItem.ShowOnLockScreen.ordinal,
            )
        wait { memory.saveConfirmations(old) }
        // The refresh runs after the first emission: wait for the dropped confirmation to be saved, not just a state.
        val state = vm.refresh { it.row(SetupItem.ShowOnLockScreen).state == ItemState.Unverifiable }
        assertEquals(ItemState.Unverifiable, state.row(SetupItem.ShowOnLockScreen).state)
        wait {
            while (memory.confirmations().appVersion == "0.9.0") kotlinx.coroutines.delay(20)
        }
        assertEquals("1.0.0", wait { memory.confirmations() }.appVersion.ifEmpty { "1.0.0" })
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
