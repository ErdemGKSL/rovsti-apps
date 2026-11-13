package dev.rovsti.phone.settings.sim

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The SIM the user picked for a number while the phone asks every time ("remembered SIM"): number key (digits, with a
 * leading +) to the account key of `Sims.chips`. Phone has no `sim-choices.json` (that is the Messages file), so this is
 * a small store of its own. [CallPlacer][dev.rovsti.phone.data.CallPlacer] remembers a pick and uses it next time; the
 * Settings page lists and forgets them.
 */
interface SimChoices {
    val choices: StateFlow<Map<String, String>>

    fun remember(
        number: String,
        accountKey: String,
    )

    fun forget(number: String)

    /** Forgets everything and answers what there was, for Undo. */
    fun clear(): Map<String, String>

    /** Puts [previous] back as it was. */
    fun restore(previous: Map<String, String>)

    fun keyOf(number: String): String? = null
}

/** The number key: digits only, with a leading + kept; null for something that is no number. */
fun simNumberKey(number: String): String? {
    val digits = number.filter { it.isDigit() }
    if (digits.length < MIN_DIGITS) return null
    return if (number.trimStart().startsWith("+")) "+$digits" else digits
}

private const val MIN_DIGITS = 3

/** [SimChoices] in memory (tests and previews). */
class MemorySimChoices(
    initial: Map<String, String> = emptyMap(),
) : SimChoices {
    private val state = MutableStateFlow(initial)
    override val choices: StateFlow<Map<String, String>> = state.asStateFlow()

    override fun remember(
        number: String,
        accountKey: String,
    ) {
        val key = simNumberKey(number) ?: return
        state.value = state.value + (key to accountKey)
    }

    override fun forget(number: String) {
        state.value = state.value - (simNumberKey(number) ?: number)
    }

    override fun clear(): Map<String, String> = state.value.also { state.value = emptyMap() }

    override fun restore(previous: Map<String, String>) {
        state.value = previous
    }

    override fun keyOf(number: String): String? = simNumberKey(number)?.let { state.value[it] }
}

/** [SimChoices] in a `SharedPreferences` file (one entry per number). */
class PrefsSimChoices(
    private val prefs: SharedPreferences,
) : SimChoices {
    private val state = MutableStateFlow(read())
    override val choices: StateFlow<Map<String, String>> = state.asStateFlow()

    private fun read(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    override fun remember(
        number: String,
        accountKey: String,
    ) {
        val key = simNumberKey(number) ?: return
        prefs.edit().putString(key, accountKey).apply()
        state.value = state.value + (key to accountKey)
    }

    override fun forget(number: String) {
        val key = simNumberKey(number) ?: number
        prefs.edit().remove(key).apply()
        state.value = state.value - key
    }

    override fun clear(): Map<String, String> {
        val before = state.value
        prefs.edit().clear().apply()
        state.value = emptyMap()
        return before
    }

    override fun restore(previous: Map<String, String>) {
        prefs
            .edit()
            .clear()
            .apply {
                previous.forEach { (k, v) -> putString(k, v) }
            }.apply()
        state.value = previous
    }

    override fun keyOf(number: String): String? = simNumberKey(number)?.let { state.value[it] }
}
