package dev.rovsti.phone.settings.speeddial

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.core.model.phone.SpeedDialEntry
import dev.rovsti.core.model.phone.SpeedDialMap
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.phone.data.PhoneStore
import dev.rovsti.phone.data.SpeedDial
import dev.rovsti.phone.favorites.FavPerson
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One key of the page: digit 1 is the voicemail (fixed); 2 to 9 hold a person or are free. */
@Immutable
data class SpeedDialRow(
    val digit: Int,
    val name: String? = null,
    val number: String? = null,
) {
    val voicemail: Boolean get() = digit == VOICEMAIL_DIGIT
    val assigned: Boolean get() = name != null

    companion object {
        const val VOICEMAIL_DIGIT = 1
    }
}

@Immutable
data class SpeedDialUi(
    val loaded: Boolean = false,
    val rows: ImmutableList<SpeedDialRow> = persistentListOf(),
    val assignedCount: Int = 0,
    /** The people the picker offers: contacts with a number (empty without the contacts permission). */
    val pickables: ImmutableList<FavPerson> = persistentListOf(),
)

sealed interface SpeedDialAction {
    /** Puts the contact [contactId] on [digit] with the number the contact calls by default. */
    data class Assign(
        val digit: Int,
        val contactId: Long,
    ) : SpeedDialAction

    data class Clear(
        val digit: Int,
    ) : SpeedDialAction

    data object ClearAll : SpeedDialAction

    /** Takes a change back: the whole map as it was. */
    data class Undo(
        val previous: SpeedDialMap,
    ) : SpeedDialAction
}

sealed interface SpeedDialEffect {
    data class Assigned(
        val digit: Int,
        val name: String,
        val previous: SpeedDialMap,
    ) : SpeedDialEffect

    data class Cleared(
        val digit: Int,
        val name: String,
        val previous: SpeedDialMap,
    ) : SpeedDialEffect

    data class ClearedAll(
        val count: Int,
        val previous: SpeedDialMap,
    ) : SpeedDialEffect

    /** The core refused (a person already on another key moves, a bad digit is refused). */
    data object Rejected : SpeedDialEffect
}

/**
 * The Speed dial page (E5.9b): keys 2 to 9 over `speed_dial.*` ([SpeedDial]), key 1 is the voicemail. Assigning,
 * clearing one and clearing all hand back the map as it was, for the Undo pill. The people the picker offers come from
 * [contacts] (the Phone's contact list with numbers).
 */
class SpeedDialViewModel(
    private val store: PhoneStore,
    private val dial: SpeedDial,
    private val contacts: StateFlow<List<WireContact>?>,
) : ViewModel() {
    private val effects = Channel<SpeedDialEffect>(Channel.BUFFERED)

    val state: StateFlow<SpeedDialUi> =
        kotlinx.coroutines.flow
            .combine(store.prefs.map { it.speedDial }, contacts) { map, people -> build(map, people) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, SpeedDialUi())

    val effect: Flow<SpeedDialEffect> = effects.receiveAsFlow()

    init {
        viewModelScope.launch { store.load() }
    }

    fun onAction(action: SpeedDialAction) {
        viewModelScope.launch {
            when (action) {
                is SpeedDialAction.Assign -> assign(action.digit, action.contactId)
                is SpeedDialAction.Clear -> clear(action.digit)
                SpeedDialAction.ClearAll -> clearAll()
                is SpeedDialAction.Undo -> dial.restore(action.previous)
            }
        }
    }

    private fun build(
        map: SpeedDialMap,
        people: List<WireContact>?,
    ): SpeedDialUi {
        val rows =
            (1..LAST_DIGIT).map { digit ->
                val entry = map[digit]
                SpeedDialRow(
                    digit,
                    entry?.name?.ifBlank { entry.numberRaw },
                    entry?.numberRaw?.takeIf { it.isNotBlank() },
                )
            }
        val pickables =
            people.orEmpty().mapNotNull { c ->
                val n = c.numbers.firstOrNull { it.default } ?: c.numbers.firstOrNull() ?: return@mapNotNull null
                FavPerson(
                    key = c.lookupKey ?: "id-${c.id}",
                    contactId = c.id,
                    name = c.displayName,
                    label = n.label,
                    number = n.raw,
                    photoUri = c.photoUri,
                )
            }
        return SpeedDialUi(
            loaded = true,
            rows = rows.toImmutableList(),
            assignedCount = map.size,
            pickables = pickables.toImmutableList(),
        )
    }

    private suspend fun assign(
        digit: Int,
        contactId: Long,
    ) {
        val contact = contacts.value.orEmpty().firstOrNull { it.id == contactId }
        val number = contact?.let { c -> c.numbers.firstOrNull { it.default } ?: c.numbers.firstOrNull() }
        if (contact == null || number == null) return reject()
        val entry = SpeedDialEntry(contact.lookupKey.orEmpty(), number.raw, contact.displayName)
        val change = dial.assign(digit, entry)
        if (change.changed) {
            effects.trySend(SpeedDialEffect.Assigned(digit, contact.displayName, change.previous))
        } else {
            reject()
        }
    }

    private suspend fun clear(digit: Int) {
        val name = dial.map[digit]?.let { it.name.ifBlank { it.numberRaw } }.orEmpty()
        val change = dial.unassign(digit)
        if (change.changed) effects.trySend(SpeedDialEffect.Cleared(digit, name, change.previous)) else reject()
    }

    private suspend fun clearAll() {
        val before = dial.map
        if (before.isEmpty()) return
        store.updatePrefs { it.copy(speedDial = emptyMap()) }
        effects.trySend(SpeedDialEffect.ClearedAll(before.size, before))
    }

    private fun reject() {
        effects.trySend(SpeedDialEffect.Rejected)
    }

    private companion object {
        const val LAST_DIGIT = 9
    }
}
