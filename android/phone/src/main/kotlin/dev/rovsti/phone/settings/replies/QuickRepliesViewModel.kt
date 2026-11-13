package dev.rovsti.phone.settings.replies

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.phone.ReplyChoicesRequest
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.phone.data.PhoneStore
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Quick replies page: the decline-with-message choices, the core's limits and whether they are the user's own. */
@Immutable
data class QuickRepliesUi(
    val loaded: Boolean = false,
    val replies: ImmutableList<String> = persistentListOf(),
    /** True once the list differs from the core's defaults; false while the defaults show. */
    val custom: Boolean = false,
    val max: Int = 0,
    val maxChars: Int = 0,
    val counterAt: Int = 0,
    val canAdd: Boolean = true,
)

sealed interface QuickRepliesAction {
    data class Add(
        val text: String,
    ) : QuickRepliesAction

    data class Edit(
        val index: Int,
        val text: String,
    ) : QuickRepliesAction

    data class Delete(
        val index: Int,
    ) : QuickRepliesAction

    /** A drop: the row that was at [from] now sits at [to]. */
    data class Move(
        val from: Int,
        val to: Int,
    ) : QuickRepliesAction

    data object RestoreDefaults : QuickRepliesAction

    /** Puts back the list that was saved before the change an Undo pill offered. */
    data class Undo(
        val previous: List<String>,
    ) : QuickRepliesAction
}

sealed interface QuickRepliesEffect {
    /** [previous] is the saved list before the change. */
    data class Deleted(
        val text: String,
        val previous: List<String>,
    ) : QuickRepliesEffect

    data class DefaultsRestored(
        val previous: List<String>,
    ) : QuickRepliesEffect

    /** The change was refused: blank text, the list is full, or it is the last reply. */
    data object Rejected : QuickRepliesEffect
}

/**
 * Quick replies (E5.9b): the texts "Decline with message" offers. The list comes from `phone.reply_choices` (the saved
 * replies, else the defaults, with the core's limits) over the preferences ([PhoneStore]). Any change saves the whole
 * list; "Restore defaults" saves the core's defaults again.
 * Delete, restore and every undo are one list put back.
 */
class QuickRepliesViewModel(
    private val store: PhoneStore,
    private val ops: PhoneOps,
) : ViewModel() {
    private val effects = Channel<QuickRepliesEffect>(Channel.BUFFERED)

    val state: StateFlow<QuickRepliesUi> =
        store.prefs
            .map { it.quickReplies }
            .distinctUntilChanged()
            .mapLatest { saved -> build(saved) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, QuickRepliesUi())

    val effect: Flow<QuickRepliesEffect> = effects.receiveAsFlow()

    init {
        viewModelScope.launch { store.load() }
    }

    fun onAction(action: QuickRepliesAction) {
        viewModelScope.launch {
            when (action) {
                is QuickRepliesAction.Add -> add(action.text)
                is QuickRepliesAction.Edit -> edit(action.index, action.text)
                is QuickRepliesAction.Delete -> delete(action.index)
                is QuickRepliesAction.Move -> move(action.from, action.to)
                QuickRepliesAction.RestoreDefaults -> restoreDefaults()
                is QuickRepliesAction.Undo -> save(action.previous)
            }
        }
    }

    private suspend fun defaults(): List<String> =
        (ops.replyChoices(ReplyChoicesRequest(emptyList())) as? Outcome.Ok)?.value?.choices.orEmpty()

    private suspend fun build(saved: List<String>): QuickRepliesUi {
        val answer =
            (ops.replyChoices(ReplyChoicesRequest(saved)) as? Outcome.Ok)?.value
                ?: return QuickRepliesUi()
        return QuickRepliesUi(
            loaded = true,
            replies = answer.choices.toImmutableList(),
            custom = answer.choices != defaults(),
            max = answer.max,
            maxChars = answer.maxChars,
            counterAt = answer.counterAt,
            canAdd = answer.canAdd,
        )
    }

    private fun current() = state.value

    /** The text as the core allows it: trimmed and cut to the character limit. */
    private fun clean(text: String): String =
        text.trim().let {
            if (current().maxChars >
                0
            ) {
                it.take(current().maxChars)
            } else {
                it
            }
        }

    private suspend fun add(text: String) {
        val ui = current()
        val reply = clean(text)
        if (reply.isEmpty() || !ui.canAdd || ui.replies.any { it.equals(reply, ignoreCase = true) }) return reject()
        save(ui.replies + reply)
    }

    private suspend fun edit(
        index: Int,
        text: String,
    ) {
        val ui = current()
        val reply = clean(text)
        if (reply.isEmpty() || index !in ui.replies.indices) return reject()
        // Two equal replies would share a list key; the same text as before is simply no change.
        if (ui.replies.withIndex().any { (i, r) -> i != index && r.equals(reply, ignoreCase = true) }) return reject()
        save(ui.replies.toMutableList().also { it[index] = reply })
    }

    private suspend fun delete(index: Int) {
        val ui = current()
        // The last reply stays: a list with nothing in it means "the defaults" to the core.
        if (index !in ui.replies.indices || ui.replies.size <= 1) return reject()
        val previous = store.prefs.value.quickReplies
        val gone = ui.replies[index]
        save(ui.replies.toMutableList().also { it.removeAt(index) })
        effects.trySend(QuickRepliesEffect.Deleted(gone, previous))
    }

    private suspend fun move(
        from: Int,
        to: Int,
    ) {
        val list = current().replies.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from))
        save(list)
    }

    private suspend fun restoreDefaults() {
        val previous = store.prefs.value.quickReplies
        if (!current().custom) return
        save(defaults())
        effects.trySend(QuickRepliesEffect.DefaultsRestored(previous))
    }

    private suspend fun save(list: List<String>) = store.updatePrefs { it.copy(quickReplies = list) }

    private fun reject() {
        effects.trySend(QuickRepliesEffect.Rejected)
    }
}
