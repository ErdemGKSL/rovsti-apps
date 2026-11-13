package dev.rovsti.phone.settings.sim

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.phone.data.Sims
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

/** One remembered choice: [number] is the key, [label] the SIM chip text (the core's), [name] the contact if known. */
@Immutable
data class SimChoiceRow(
    val number: String,
    val name: String?,
    val simLabel: String,
    val simKey: String,
)

@Immutable
data class SimChoicesUi(
    /** False below two SIMs: the page is not offered at all. */
    val visible: Boolean = false,
    val rows: ImmutableList<SimChoiceRow> = persistentListOf(),
)

sealed interface SimChoicesAction {
    data class Forget(
        val number: String,
    ) : SimChoicesAction

    data object ForgetAll : SimChoicesAction

    data class Undo(
        val previous: Map<String, String>,
    ) : SimChoicesAction
}

sealed interface SimChoicesEffect {
    data class Forgotten(
        val name: String,
        val previous: Map<String, String>,
    ) : SimChoicesEffect

    data class ForgottenAll(
        val count: Int,
        val previous: Map<String, String>,
    ) : SimChoicesEffect
}

/**
 * The SIM choices page (E5.9b): the SIM remembered for each number, from [SimChoices] and the SIMs of
 * [dev.rovsti.phone.data.SimState] (the chip label is the core's `sim.build` text). [nameOf] finds the contact of a
 * number. Forget one or all hands back the map for the Undo pill. Below two SIMs [SimChoicesUi.visible] is false.
 */
class SimChoicesViewModel(
    private val choices: SimChoices,
    sims: StateFlow<Sims>,
    private val nameOf: (String) -> String?,
) : ViewModel() {
    private val effects = Channel<SimChoicesEffect>(Channel.BUFFERED)

    val state: StateFlow<SimChoicesUi> =
        combine(choices.choices, sims) { map, now -> build(map, now) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, SimChoicesUi())

    val effect: Flow<SimChoicesEffect> = effects.receiveAsFlow()

    fun onAction(action: SimChoicesAction) {
        when (action) {
            is SimChoicesAction.Forget -> {
                val before = choices.choices.value
                if (action.number in before) {
                    choices.forget(action.number)
                    val name = nameOf(action.number) ?: action.number
                    effects.trySend(SimChoicesEffect.Forgotten(name, before))
                }
            }

            SimChoicesAction.ForgetAll -> {
                val before = choices.clear()
                if (before.isNotEmpty()) effects.trySend(SimChoicesEffect.ForgottenAll(before.size, before))
            }

            is SimChoicesAction.Undo -> {
                choices.restore(action.previous)
            }
        }
    }

    private fun build(
        map: Map<String, String>,
        sims: Sims,
    ): SimChoicesUi {
        if (!sims.visible) return SimChoicesUi()
        val rows =
            map.entries
                .map { (number, key) ->
                    val chip = sims.chips.firstOrNull { it.key == key }
                    SimChoiceRow(number, nameOf(number), chip?.title ?: key, key)
                }.sortedWith(compareBy({ it.name ?: "￿" }, { it.number }))
        return SimChoicesUi(visible = true, rows = rows.toImmutableList())
    }
}
