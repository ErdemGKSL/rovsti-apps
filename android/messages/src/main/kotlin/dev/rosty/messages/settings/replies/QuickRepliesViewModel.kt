package dev.rosty.messages.settings.replies

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.chat.RepliesAction
import dev.rosty.core.model.chat.RepliesAnswer
import dev.rosty.core.model.chat.RepliesRequest
import dev.rosty.core.ops.ChatOps
import dev.rosty.messages.data.MessagesStore
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The Quick replies page (M11): the saved replies and the core's limits. */
@Immutable
data class QuickRepliesUi(
    val loaded: Boolean = false,
    val replies: ImmutableList<String> = persistentListOf(),
    val max: Int = 0,
    val maxChars: Int = 0,
) {
    val canAdd: Boolean get() = loaded && replies.size < max
}

/** Why a reply was not saved, from `quick_replies.edit`'s `detail.reason`. */
enum class ReplyProblem { Empty, TooLong, Duplicate, Full, Failed }

sealed interface QuickRepliesAction {
    /** Adds [text]; [done] gets null when it was saved, else why not (the sheet stays open and says it). */
    data class Add(
        val text: String,
        val done: (ReplyProblem?) -> Unit = {},
    ) : QuickRepliesAction

    data class Edit(
        val index: Int,
        val text: String,
        val done: (ReplyProblem?) -> Unit = {},
    ) : QuickRepliesAction

    data class Delete(
        val index: Int,
    ) : QuickRepliesAction

    /** A drop: the reply at [from] now sits at [to]. */
    data class Move(
        val from: Int,
        val to: Int,
    ) : QuickRepliesAction

    /** Undo of a delete: [text] goes back to [index]. */
    data class Restore(
        val index: Int,
        val text: String,
    ) : QuickRepliesAction
}

sealed interface QuickRepliesEffect {
    data class Deleted(
        val index: Int,
        val text: String,
    ) : QuickRepliesEffect
}

/**
 * M11 quick replies over `messages-prefs.json` (`quick_replies` field). Every rule (trim, 500 characters, no case-folded
 * duplicates, 20 replies, where an undone delete lands, a move) is `quick_replies.edit` in Rust; Kotlin only saves the
 * list it answers. An edit is the reply taken out, the new text added (so the core judges it against the others) and
 * put back at its place.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class QuickRepliesViewModel(
    private val store: MessagesStore,
    private val chat: ChatOps,
) : ViewModel() {
    private val effects = Channel<QuickRepliesEffect>(Channel.BUFFERED)
    private val lock = Mutex()

    val state: StateFlow<QuickRepliesUi> =
        store.prefs
            .map { it?.quickReplies }
            .distinctUntilChanged()
            .mapLatest { list -> list?.let { build(it) } ?: QuickRepliesUi() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, QuickRepliesUi())

    val effect: Flow<QuickRepliesEffect> = effects.receiveAsFlow()

    init {
        viewModelScope.launch { store.load() }
    }

    fun onAction(action: QuickRepliesAction) {
        viewModelScope.launch {
            lock.withLock {
                when (action) {
                    is QuickRepliesAction.Add -> action.done(add(action.text))
                    is QuickRepliesAction.Edit -> action.done(edit(action.index, action.text))
                    is QuickRepliesAction.Delete -> delete(action.index)
                    is QuickRepliesAction.Move -> move(action.from, action.to)
                    is QuickRepliesAction.Restore -> restore(action.index, action.text)
                }
            }
        }
    }

    private suspend fun build(list: List<String>): QuickRepliesUi {
        val answer = (call(list, RepliesAction.Show) as? Outcome.Ok)?.value ?: return QuickRepliesUi()
        return QuickRepliesUi(
            loaded = true,
            replies = answer.list.toImmutableList(),
            max = answer.maxReplies,
            maxChars = answer.maxChars,
        )
    }

    private suspend fun call(
        list: List<String>,
        action: RepliesAction,
        text: String = "",
        index: Int = 0,
    ): Outcome<RepliesAnswer> = chat.replies(RepliesRequest(list, action, text, index))

    private suspend fun saved(): List<String>? {
        store.load()
        return store.prefs.value?.quickReplies
    }

    private suspend fun save(list: List<String>) = store.updatePrefs { it.copy(quickReplies = list) }

    private suspend fun add(text: String): ReplyProblem? {
        val list = saved() ?: return ReplyProblem.Failed
        return when (val answer = call(list, RepliesAction.Add, text)) {
            is Outcome.Ok -> {
                save(answer.value.list)
                null
            }

            is Outcome.Err -> {
                problemOf(answer.error)
            }
        }
    }

    private suspend fun edit(
        index: Int,
        text: String,
    ): ReplyProblem? {
        val list = saved() ?: return ReplyProblem.Failed
        if (index !in list.indices) return ReplyProblem.Failed
        val rest =
            (call(list, RepliesAction.Remove, index = index) as? Outcome.Ok)?.value?.list ?: return ReplyProblem.Failed
        val added =
            when (val answer = call(rest, RepliesAction.Add, text)) {
                is Outcome.Ok -> answer.value
                is Outcome.Err -> return problemOf(answer.error)
            }
        val at = added.index ?: return ReplyProblem.Failed
        val clean = added.list[at]
        val without =
            (call(added.list, RepliesAction.Remove, index = at) as? Outcome.Ok)?.value?.list
                ?: return ReplyProblem.Failed
        val back =
            (call(without, RepliesAction.Restore, clean, index) as? Outcome.Ok)?.value?.list
                ?: return ReplyProblem.Failed
        save(back)
        return null
    }

    private suspend fun delete(index: Int) {
        val list = saved() ?: return
        val answer = (call(list, RepliesAction.Remove, index = index) as? Outcome.Ok)?.value ?: return
        val removed = answer.removed ?: return
        save(answer.list)
        effects.trySend(QuickRepliesEffect.Deleted(index, removed))
    }

    private suspend fun restore(
        index: Int,
        text: String,
    ) {
        val list = saved() ?: return
        val answer = (call(list, RepliesAction.Restore, text, index) as? Outcome.Ok)?.value ?: return
        if (answer.changed) save(answer.list)
    }

    /** One `move_up` per step: down the list is the next reply moving up past it. */
    private suspend fun move(
        from: Int,
        to: Int,
    ) {
        var list = saved() ?: return
        if (from !in list.indices || to !in list.indices || from == to) return
        val steps = if (from > to) (from downTo to + 1).toList() else (from + 1..to).toList()
        for (step in steps) {
            list = (call(list, RepliesAction.MoveUp, index = step) as? Outcome.Ok)?.value?.list ?: return
        }
        save(list)
    }

    companion object {
        fun problemOf(error: RustError): ReplyProblem =
            when (error.reason) {
                "empty" -> ReplyProblem.Empty
                "too_long" -> ReplyProblem.TooLong
                "duplicate" -> ReplyProblem.Duplicate
                "full" -> ReplyProblem.Full
                else -> ReplyProblem.Failed
            }
    }
}
