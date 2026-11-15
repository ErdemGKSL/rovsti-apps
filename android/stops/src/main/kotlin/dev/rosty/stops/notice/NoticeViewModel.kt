package dev.rosty.stops.notice

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.core.Empty
import dev.rosty.core.model.stops.NoticePrefs
import dev.rosty.core.model.stops.NoticeRenderRequest
import dev.rosty.core.model.stops.NoticeValidateRequest
import dev.rosty.core.model.stops.NoticeWhen
import dev.rosty.core.model.stops.NoticeWhenChoice
import dev.rosty.core.model.stops.PrefsEdit
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.core.ops.StopsOps
import dev.rosty.design.text.UiText
import dev.rosty.stops.R
import dev.rosty.stops.data.StopsStore
import dev.rosty.wa.WaLinkState
import dev.rosty.wa.WaRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the message in the field cannot be saved; worded from our strings, never from the core's English. */
enum class NoticeProblem {
    Empty,
    TooLong,
}

/** The chats of the picker. */
@Immutable
sealed interface NoticeChats {
    data object Loading : NoticeChats

    data class Loaded(
        val all: ImmutableList<NoticeChat>,
        /** [all] narrowed by the search text. */
        val shown: ImmutableList<NoticeChat>,
    ) : NoticeChats
}

@Immutable
data class NoticeUiState(
    val loading: Boolean = true,
    /** Where the link stands; the page offers the message only while WhatsApp is connected (AD-ST7). */
    val link: WaLinkState = WaLinkState.None,
    /** The stored link has been read: until then "not connected" is not known yet. */
    val linkSettled: Boolean = false,
    /** The chosen chat's id; empty for none (the message is off). */
    val chat: String = "",
    val chatName: String = "",
    /** The message as it is saved (the default of the phone's language while none was written). */
    val saved: String = "",
    val defaultTemplate: String = "",
    /** The message in the field as it would read ("Arriving at Kadıköy Pier in about 8 min."). */
    val preview: String = "",
    val problem: NoticeProblem? = null,
    val maxChars: Int = MAX_CHARS,
    /** The field holds something else than [saved]. */
    val dirty: Boolean = false,
    val sendWhen: NoticeWhen = NoticeWhen.WithAlarm,
    val whenChoices: ImmutableList<NoticeWhenChoice> = persistentListOf(),
    val chats: NoticeChats = NoticeChats.Loading,
) {
    val connected: Boolean get() = link is WaLinkState.Connected

    /** The message goes out with trips: a chat is chosen. */
    val on: Boolean get() = chat.isNotEmpty()

    companion object {
        /** `rosty_geo::notice::MAX_CHARS`, until `stops.notice_validate` answers it. */
        const val MAX_CHARS = 500
    }
}

sealed interface NoticeEffect {
    data class Toast(
        val text: UiText,
    ) : NoticeEffect

    /** The message was switched off; [undo] puts the chat, the text and the time back. */
    data class Undo(
        val text: UiText,
        val undo: () -> Unit,
    ) : NoticeEffect

    /** A chat was chosen: the picker closes. */
    data object ChatChosen : NoticeEffect
}

/**
 * Settings > Arriving-soon message (AD-ST7): one WhatsApp chat, the message with its preview, and when it goes out.
 * The rules stay in Rust: every change is one `stops.prefs_edit` through [StopsStore.edit] (`set_notice`,
 * `set_template`, `set_notice_when`, `clear_notice`), the preview is `stops.notice_render` and the check
 * `stops.notice_validate`; the labels of "when" and the default message come from `stops.prefs_choices` in the
 * phone's language. [sampleStop] stands for `{stop}` in the preview. The chats come from the gateway ([wa]) and are
 * named by [names] (see [namedChats]).
 */
class NoticeViewModel(
    private val store: StopsStore,
    private val ops: StopsOps,
    private val wa: WaRepository,
    private val names: suspend (List<dev.rosty.core.model.wa.RichChat>) -> List<NoticeChat>,
    private val sampleStop: String,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val work: CoroutineScope = scope ?: viewModelScope
    private val mutable = MutableStateFlow(NoticeUiState())
    val state: StateFlow<NoticeUiState> = mutable.asStateFlow()

    /** The message being written; the page's field edits it directly. */
    val templateField = TextFieldState()

    /** The picker's search text. */
    val searchField = TextFieldState()

    private val effectChannel = Channel<NoticeEffect>(Channel.BUFFERED)
    val effects: Flow<NoticeEffect> = effectChannel.receiveAsFlow()

    private var chatsJob: Job? = null

    init {
        work.launch {
            val choices = (ops.prefsChoices(Empty) as? Outcome.Ok)?.value
            store.load()
            mutable.update {
                it.copy(
                    defaultTemplate = choices?.defaultTemplate.orEmpty(),
                    whenChoices = choices?.noticeWhen.orEmpty().toImmutableList(),
                )
            }
            store.prefs.collect(::onPrefs)
        }
        work.launch {
            combine(wa.linkState, wa.settled) { link, settled -> link to settled }.collect { (link, settled) ->
                mutable.update { it.copy(link = link, linkSettled = settled) }
            }
        }
        work.launch {
            snapshotFlow { templateField.text.toString() }.collectLatest { text ->
                delay(PREVIEW_DEBOUNCE_MS)
                check(text)
            }
        }
        work.launch {
            snapshotFlow { searchField.text.toString() }.collect { query ->
                mutable.update { state ->
                    val chats = state.chats as? NoticeChats.Loaded ?: return@update state
                    state.copy(chats = chats.copy(shown = filterChats(chats.all, query).toImmutableList()))
                }
            }
        }
    }

    /** The stored preferences changed (here or in another screen): the page follows; the field only when untouched. */
    private fun onPrefs(prefs: StopsPrefs) {
        val before = mutable.value
        val notice = prefs.notice
        val saved = notice?.template ?: before.defaultTemplate
        val untouched = before.loading || templateField.text.toString() == before.saved
        mutable.value =
            before.copy(
                loading = false,
                chat = notice?.chat.orEmpty(),
                chatName = notice?.chatName.orEmpty(),
                saved = saved,
                sendWhen = notice?.sendWhen ?: NoticeWhen.WithAlarm,
                dirty = !untouched && templateField.text.toString() != saved,
            )
        if (untouched && templateField.text.toString() != saved) templateField.setTextAndPlaceCursorAtEnd(saved)
    }

    /** The preview and the problem of [text], and whether it differs from the saved message. */
    private suspend fun check(text: String) {
        val problem =
            when (val checked = ops.noticeValidate(NoticeValidateRequest(text))) {
                is Outcome.Ok -> null
                is Outcome.Err -> problemOf(checked.error)
            }
        val preview =
            if (problem == NoticeProblem.Empty) {
                ""
            } else {
                val request = NoticeRenderRequest(text, sampleStop, etaS = PREVIEW_ETA_S)
                (ops.noticeRender(request) as? Outcome.Ok)?.value?.text.orEmpty()
            }
        // An answer for a text the field has left behind is dropped.
        if (templateField.text.toString() != text) return
        mutable.update { it.copy(preview = preview, problem = problem, dirty = text.trim() != it.saved.trim()) }
    }

    private fun problemOf(error: RustError): NoticeProblem =
        if (error.reason == "too_long") NoticeProblem.TooLong else NoticeProblem.Empty

    /** Saves the message in the field (`set_template`); a refused one stays in the field with its problem. */
    fun saveTemplate() {
        val text = templateField.text.toString()
        work.launch {
            when (val saved = store.edit(PrefsEdit.SetTemplate(text))) {
                is Outcome.Ok -> {
                    val kept =
                        saved.value.prefs.notice
                            ?.template ?: text.trim()
                    templateField.setTextAndPlaceCursorAtEnd(kept)
                    mutable.update { it.copy(saved = kept, dirty = false, problem = null) }
                }

                is Outcome.Err -> {
                    if (saved.error is RustError.Invalid) {
                        mutable.update { it.copy(problem = problemOf(saved.error)) }
                    } else {
                        effectChannel.trySend(NoticeEffect.Toast(UiText.Res(R.string.notice_save_failed)))
                    }
                }
            }
        }
    }

    /** The default message of the phone's language goes into the field and is saved. */
    fun useDefault() {
        templateField.setTextAndPlaceCursorAtEnd(mutable.value.defaultTemplate)
        saveTemplate()
    }

    fun setWhen(sendWhen: NoticeWhen) {
        if (sendWhen == mutable.value.sendWhen) return
        work.launch { report(store.edit(PrefsEdit.SetNoticeWhen(sendWhen))) }
    }

    /** The chat the message goes to; its name is kept as the picker showed it. */
    fun chooseChat(chat: NoticeChat) {
        work.launch {
            if (report(store.edit(PrefsEdit.SetNotice(chat.jid, chat.name)))) {
                effectChannel.trySend(NoticeEffect.ChatChosen)
            }
        }
    }

    /** Switches the message off (`clear_notice`); the Undo pill puts the chat, the text and the time back. */
    fun turnOff() {
        val was = store.prefs.value.notice ?: return
        work.launch {
            if (!report(store.edit(PrefsEdit.ClearNotice))) return@launch
            effectChannel.trySend(NoticeEffect.Undo(UiText.Res(R.string.notice_turned_off)) { restore(was) })
        }
    }

    private fun restore(was: NoticePrefs) {
        work.launch {
            store.edit(PrefsEdit.SetNotice(was.chat, was.chatName))
            store.edit(PrefsEdit.SetTemplate(was.template))
            store.edit(PrefsEdit.SetNoticeWhen(was.sendWhen))
        }
    }

    private fun report(outcome: Outcome<*>): Boolean {
        if (outcome is Outcome.Ok) return true
        effectChannel.trySend(NoticeEffect.Toast(UiText.Res(R.string.notice_save_failed)))
        return false
    }

    /** The picker opened: the chats of the gateway, named; they follow the gateway while the picker is open. */
    fun openChats() {
        searchField.setTextAndPlaceCursorAtEnd("")
        chatsJob?.cancel()
        mutable.update { it.copy(chats = NoticeChats.Loading) }
        chatsJob =
            work.launch {
                wa.chats().collect { chats ->
                    val named = names(chats).toImmutableList()
                    val shown = filterChats(named, searchField.text.toString()).toImmutableList()
                    mutable.update { it.copy(chats = NoticeChats.Loaded(named, shown)) }
                }
            }
    }

    fun closeChats() {
        chatsJob?.cancel()
        chatsJob = null
    }

    /** Suspends until the first load is done (tests). */
    suspend fun awaitLoaded(): NoticeUiState = state.first { !it.loading }

    private companion object {
        const val PREVIEW_DEBOUNCE_MS = 150L

        /** The preview's ETA: 8 minutes. */
        const val PREVIEW_ETA_S = 480L
    }
}
