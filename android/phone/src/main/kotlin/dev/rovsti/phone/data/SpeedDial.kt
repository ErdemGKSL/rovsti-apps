package dev.rovsti.phone.data

import dev.rovsti.core.Outcome
import dev.rovsti.core.model.phone.AssignRequest
import dev.rovsti.core.model.phone.DigitForRequest
import dev.rovsti.core.model.phone.HoldDigit
import dev.rovsti.core.model.phone.OnHoldRequest
import dev.rovsti.core.model.phone.SpeedDialBadge
import dev.rovsti.core.model.phone.SpeedDialEntry
import dev.rovsti.core.model.phone.SpeedDialMap
import dev.rovsti.core.model.phone.SpeedDialMapRequest
import dev.rovsti.core.model.phone.UnassignRequest
import dev.rovsti.core.ops.PhoneOps

/** What [SpeedDial.assign] and [SpeedDial.unassign] did. [previous] is the map before, for Undo. */
data class SpeedDialChange(
    val changed: Boolean,
    val previous: SpeedDialMap,
)

/**
 * Speed dial: digit 2 to 9 holds a person (1 is the voicemail). The map lives in the preferences ([PhoneStore]); every
 * decision is the core's (`speed_dial.on_hold`, `assign`, `unassign`, `free_digits`, `digit_for`, `badges`). A failed op
 * changes nothing.
 */
class SpeedDial(
    private val store: PhoneStore,
    private val ops: PhoneOps,
) {
    /** The map now. */
    val map: SpeedDialMap get() = store.prefs.value.speedDial

    /** What holding [digit] on the keypad does when the field held [dialedBefore]. */
    suspend fun onHold(
        digit: Char,
        dialedBefore: String,
    ): HoldDigit =
        when (val out = ops.speedDialOnHold(OnHoldRequest(digit.toString(), dialedBefore, map))) {
            is Outcome.Ok -> out.value.result
            is Outcome.Err -> HoldDigit.Ignore
        }

    /** Puts [entry] on [digit]. */
    suspend fun assign(
        digit: Int,
        entry: SpeedDialEntry,
    ): SpeedDialChange {
        val before = map
        val next =
            (ops.speedDialAssign(AssignRequest(before, digit, entry)) as? Outcome.Ok)?.value?.map
                ?: return SpeedDialChange(false, before)
        store.updatePrefs { it.copy(speedDial = next) }
        return SpeedDialChange(next != before, before)
    }

    /** Empties [digit]. */
    suspend fun unassign(digit: Int): SpeedDialChange {
        val before = map
        val out =
            (ops.speedDialUnassign(UnassignRequest(before, digit)) as? Outcome.Ok)?.value
                ?: return SpeedDialChange(false, before)
        if (!out.removed) return SpeedDialChange(false, before)
        store.updatePrefs { it.copy(speedDial = out.map) }
        return SpeedDialChange(true, before)
    }

    /** Takes a change back. */
    suspend fun restore(previous: SpeedDialMap) = store.updatePrefs { it.copy(speedDial = previous) }

    /** The digits still free, ascending. */
    suspend fun freeDigits(): List<Int> =
        (ops.speedDialFreeDigits(SpeedDialMapRequest(map)) as? Outcome.Ok)?.value?.digits.orEmpty()

    /** The digit that holds one of [numbers], null for none. */
    suspend fun digitFor(numbers: List<String>): Int? =
        (ops.speedDialDigitFor(DigitForRequest(map, numbers)) as? Outcome.Ok)?.value?.digit

    /** The badges the keypad draws on the assigned keys. */
    suspend fun badges(): List<SpeedDialBadge> =
        (ops.speedDialBadges(SpeedDialMapRequest(map)) as? Outcome.Ok)?.value?.badges.orEmpty()
}
