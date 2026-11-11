package dev.rovsti.phone.setup

import androidx.lifecycle.viewModelScope
import dev.rovsti.core.model.phone.Reading
import dev.rovsti.core.model.phone.SetupFacts
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.UnconfinedMain
import dev.rovsti.platform.system.permissions.PermissionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The facts a setup test controls; the rows, their words and states are the real core's. */
internal class FlowFacts(
    var facts: SetupFacts = SetupFlowRig.allGranted,
    override var xiaomi: Boolean = false,
    var exact: Boolean = true,
    override val appVersion: String = "1.0.0",
) : SetupFactsSource {
    override fun facts(): SetupFacts = facts

    override fun exactAlarmsAllowed(): Boolean = exact
}

internal object SetupFlowRig {
    val allGranted =
        SetupFacts(
            roleHeld = true,
            phoneAndCallLogGranted = true,
            contactsGranted = true,
            notificationsGranted = true,
            fullScreenAllowed = true,
            batteryExempt = true,
        )

    /** A Xiaomi/HyperOS phone where autostart is denied and the other rows cannot be read. */
    val xiaomiFacts =
        allGranted.copy(
            roleHeld = false,
            batteryExempt = false,
            autostart = Reading.Denied,
            lockScreen = Reading.Unknown,
            backgroundPopups = Reading.Unknown,
        )

    /** The view model on the real core, its first loaded state; the view model's work is finished when this returns. */
    fun state(
        host: HostRust,
        facts: FlowFacts,
    ): SetupUiState {
        UnconfinedMain.install()
        val vm = SetupSheetViewModel(host.ops, facts, InMemorySetupMemory()) { PermissionState.NeverAsked }
        return runBlocking {
            withTimeout(TIMEOUT_MS) {
                val state = vm.state.first { it.loaded }
                vm.viewModelScope.coroutineContext.job.children
                    .forEach { it.join() }
                state
            }
        }
    }

    private const val TIMEOUT_MS = 30_000L
}
