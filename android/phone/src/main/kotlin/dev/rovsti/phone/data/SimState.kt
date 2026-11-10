package dev.rovsti.phone.data

import dev.rovsti.core.Outcome
import dev.rovsti.core.model.phone.PhoneAccounts
import dev.rovsti.core.model.phone.SimAccount
import dev.rovsti.core.model.phone.SimBuildAnswer
import dev.rovsti.core.model.phone.SimBuildRequest
import dev.rovsti.core.model.phone.SimDotRequest
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.platform.telephony.SimRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The SIMs as the screens use them. [chips] are the call-capable accounts with the short chip label the core made;
 * [visible] is false with fewer than two SIMs (no chip, no picker). [ask] is "ask every time": two or more accounts and
 * no default chosen in the system settings. The app never changes the system default.
 */
data class Sims(
    val visible: Boolean = false,
    val ask: Boolean = false,
    val defaultKey: String? = null,
    val defaultLabel: String = "",
    val chips: ImmutableList<SimAccount> = persistentListOf(),
    /** The accounts as Telecom listed them, the input of the choice rule ([CallPlacer]). */
    val accounts: PhoneAccounts = PhoneAccounts(),
)

/**
 * `SimRepository` through `sim.build`: every change of subscriptions or of the default account builds a new [Sims].
 * [dot] asks the core for a SIM dot colour that reads against a fill (`sim.dot`). A failed build keeps the last value.
 */
class SimState(
    repository: SimRepository,
    private val ops: PhoneOps,
    scope: CoroutineScope,
) {
    private val state = MutableStateFlow(Sims())

    val sims: StateFlow<Sims> = state.asStateFlow()

    init {
        scope.launch {
            repository.accounts().collect { accounts ->
                val built = ops.simBuild(SimBuildRequest(accounts))
                if (built is Outcome.Ok) state.value = of(accounts, built.value)
            }
        }
    }

    /** The colour (ARGB) for a SIM dot on [fillArgb]: the account colour moved until it reads (the core's minimum contrast). */
    suspend fun dot(
        colorArgb: Int,
        fillArgb: Int,
    ): Int =
        when (val out = ops.simDot(SimDotRequest(colorArgb, fillArgb))) {
            is Outcome.Ok -> out.value.argb
            is Outcome.Err -> colorArgb
        }

    private fun of(
        accounts: PhoneAccounts,
        built: SimBuildAnswer,
    ) = Sims(
        visible = built.visible,
        ask = built.ask,
        defaultKey = built.defaultKey,
        defaultLabel = built.defaultLabel,
        chips = built.accounts.toImmutableList(),
        accounts = accounts,
    )
}
