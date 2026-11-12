package dev.rovsti.phone.details

import androidx.compose.runtime.Immutable
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.chat.CallReminderRequest
import dev.rovsti.core.model.chat.ReminderSlot
import dev.rovsti.core.model.chat.ReminderSlotOption
import dev.rovsti.core.model.chat.ReminderSlotsRequest
import dev.rovsti.core.model.phone.CallDetailsRequest
import dev.rovsti.core.model.phone.CallNote
import dev.rovsti.core.model.phone.EntryRef
import dev.rovsti.core.model.phone.HistoryLine
import dev.rovsti.core.model.phone.LocaleTime
import dev.rovsti.core.model.phone.NoteRemoveRequest
import dev.rovsti.core.model.phone.NoteRestoreRequest
import dev.rovsti.core.model.phone.NoteSetRequest
import dev.rovsti.core.ops.ChatOps
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.phone.data.PhoneStore
import dev.rovsti.phone.data.RecentsRepository
import dev.rovsti.phone.data.Sims
import dev.rovsti.phone.details.photo.PersonPhoto
import dev.rovsti.platform.calllog.CallLogRepository
import dev.rovsti.platform.calllog.CallLogResult
import dev.rovsti.platform.calllog.Restorable
import dev.rovsti.platform.files.ClipboardRepository
import dev.rovsti.platform.notify.ReminderItem
import dev.rovsti.platform.notify.RemindersRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The call the sheet is about: the history line (the core's) and whose call it was. */
@Immutable
data class CallDetailsTarget(
    val line: HistoryLine,
    val who: String?,
    /** The person's photo for the avatar of the sheet (null for a number that is not in the book). */
    val photo: PersonPhoto? = null,
)

/** What the Call details sheet draws. */
@Immutable
data class CallDetailsUi(
    val ready: Boolean = false,
    val title: String = "",
    val whenText: String = "",
    val duration: String? = null,
    val channel: String = "",
    val number: String = "",
    /** The saved note; empty for none. */
    val note: String = "",
    /** Which calls this sheet can act on: a WhatsApp call lives in its own list and is not deleted from the log. */
    val deletable: Boolean = false,
    val copyText: String = "",
    val shareText: String = "",
)

sealed interface CallDetailsAction {
    data class SaveNote(
        val text: String,
    ) : CallDetailsAction

    data object DeleteNote : CallDetailsAction

    data object Copy : CallDetailsAction

    data object Share : CallDetailsAction

    data object DeleteCall : CallDetailsAction

    data object AskRemind : CallDetailsAction

    data class Remind(
        val slot: ReminderSlot,
    ) : CallDetailsAction

    /** "Pick a time...": the reminder for [atMs] (the time the owner chose). */
    data class RemindAtTime(
        val atMs: Long,
    ) : CallDetailsAction

    data class Undo(
        val undo: CallDetailsUndo,
    ) : CallDetailsAction
}

/** What Undo puts back: the removed note, or the deleted call. */
sealed interface CallDetailsUndo {
    data class Note(
        val note: CallNote,
    ) : CallDetailsUndo

    data class Call(
        val rows: Restorable,
    ) : CallDetailsUndo
}

enum class CallDetailsNotice {
    NoteSaved,
    Copied,
    DeleteFailed,
    DeletePermission,
    NothingToDelete,
    ReminderFailed,
    NoteFailed,
    UndoFailed,
}

enum class CallDetailsUndoKind { NoteDeleted, CallDeleted }

sealed interface CallDetailsEffect {
    data class Say(
        val notice: CallDetailsNotice,
    ) : CallDetailsEffect

    data object Reject : CallDetailsEffect

    data class ChooseRemind(
        val slots: ImmutableList<ReminderSlotOption>,
    ) : CallDetailsEffect

    data class Reminded(
        val text: String,
    ) : CallDetailsEffect

    /** A reminder was set for a time the owner picked: the screen words it in the phone's clock style. */
    data class RemindedAt(
        val atMs: Long,
    ) : CallDetailsEffect

    data class ShareText(
        val text: String,
    ) : CallDetailsEffect

    data class Undoable(
        val kind: CallDetailsUndoKind,
        val undo: CallDetailsUndo,
    ) : CallDetailsEffect

    /** The call is gone (deleted): the sheet closes. */
    data object Close : CallDetailsEffect
}

/**
 * The brain of the Call details sheet (E5.4, U11). The sheet's words, the copy text and the share text come from
 * `call_notes.details`; the note is one entry of the notes file (`call_notes.set` / `remove` / `restore`, keyed by
 * number
 * and start time); Remind me is `call_reminder.make` armed through the reminders store (U44). Deleting the call is one
 * `CallLogRepository.delete` with an Undo snapshot, and the note stays (a restored call finds it again).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallDetailsViewModel(
    val target: CallDetailsTarget,
    private val recents: RecentsRepository,
    private val callLog: CallLogRepository,
    private val ops: PhoneOps,
    private val chat: ChatOps,
    private val store: PhoneStore,
    private val clipboard: ClipboardRepository,
    private val reminders: RemindersRepository<ReminderItem>,
    private val sims: StateFlow<Sims>,
    private val locale: () -> LocaleTime,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tzOffsetS: () -> Int = {
        java.util.TimeZone
            .getDefault()
            .getOffset(clock()) / MS_PER_S
    },
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    private val effects = Channel<CallDetailsEffect>(Channel.BUFFERED)
    val effect = effects.receiveAsFlow()

    private val line = target.line

    @Volatile private var shown = CallDetailsUi()

    val state: StateFlow<CallDetailsUi> =
        store.notes
            .map { notes ->
                notes.notes
                    .firstOrNull { it.number == line.number && it.atMs == line.dateMs }
                    ?.text
                    .orEmpty()
            }.mapLatest { build(it) }
            .flowOn(compute)
            .stateIn(scope, SharingStarted.Eagerly, CallDetailsUi())

    fun close() {
        scope.cancel()
    }

    private fun logId(): Long? = (line.entry as? EntryRef.Log)?.id

    private suspend fun build(note: String): CallDetailsUi {
        val sim =
            if (sims.value.visible) {
                val key = recents.callEntries.firstOrNull { it.id == logId() }?.accountKey
                sims.value.chips
                    .firstOrNull { it.key == key }
                    ?.title
            } else {
                null
            }
        val answer =
            (
                ops.callDetails(
                    CallDetailsRequest(line, line.number, sim, locale(), note.ifEmpty { null }, target.who),
                ) as? Outcome.Ok
            )?.value ?: return shown.copy(note = note)
        val ui =
            CallDetailsUi(
                ready = true,
                title = answer.details.title,
                whenText = answer.details.whenText,
                duration = answer.details.duration,
                channel = answer.details.channel,
                number = answer.details.number,
                note = note,
                deletable = logId() != null,
                copyText = answer.copyText,
                shareText = answer.shareText,
            )
        shown = ui
        return ui
    }

    fun onAction(action: CallDetailsAction) {
        when (action) {
            is CallDetailsAction.SaveNote -> scope.launch { saveNote(action.text) }
            CallDetailsAction.DeleteNote -> scope.launch { deleteNote() }
            CallDetailsAction.Copy -> copy()
            CallDetailsAction.Share -> say(CallDetailsEffect.ShareText(shown.shareText))
            CallDetailsAction.DeleteCall -> scope.launch { deleteCall() }
            CallDetailsAction.AskRemind -> scope.launch { askRemind() }
            is CallDetailsAction.Remind -> scope.launch { remind(action.slot) }
            is CallDetailsAction.RemindAtTime -> scope.launch { remind(ReminderSlot.In15Min, action.atMs) }
            is CallDetailsAction.Undo -> scope.launch { undo(action.undo) }
        }
    }

    private fun say(effect: CallDetailsEffect) {
        effects.trySend(effect)
    }

    private fun notice(n: CallDetailsNotice) = say(CallDetailsEffect.Say(n))

    private fun copy() {
        clipboard.copy(shown.copyText)
        notice(CallDetailsNotice.Copied)
    }

    /**
     * An empty note is a removed note (with its Undo); anything else is stored by the core's rules (trimmed,
     * capped).
     */
    private suspend fun saveNote(text: String) {
        if (text.isBlank()) return deleteNote()
        val set = ops.noteSet(NoteSetRequest(store.notes.value, line.number, line.dateMs, text, clock()))
        val answer = (set as? Outcome.Ok)?.value
        if (answer == null) {
            say(CallDetailsEffect.Reject)
            return notice(CallDetailsNotice.NoteFailed)
        }
        store.updateNotes { answer.notes }
        notice(CallDetailsNotice.NoteSaved)
    }

    private suspend fun deleteNote() {
        val out = (ops.noteRemove(NoteRemoveRequest(store.notes.value, line.number, line.dateMs)) as? Outcome.Ok)?.value
        val removed = out?.removed ?: return
        store.updateNotes { out.notes }
        say(CallDetailsEffect.Undoable(CallDetailsUndoKind.NoteDeleted, CallDetailsUndo.Note(removed)))
    }

    private suspend fun deleteCall() {
        val id = logId() ?: return notice(CallDetailsNotice.NothingToDelete)
        when (val result = callLog.delete(listOf(id))) {
            is CallLogResult.Ok -> {
                recents.refreshNow()
                say(CallDetailsEffect.Undoable(CallDetailsUndoKind.CallDeleted, CallDetailsUndo.Call(result.value)))
                say(CallDetailsEffect.Close)
            }

            is CallLogResult.Failed -> {
                say(CallDetailsEffect.Reject)
                notice(
                    if (result.reason == CallLogResult.Reason.Permission) {
                        CallDetailsNotice.DeletePermission
                    } else {
                        CallDetailsNotice.DeleteFailed
                    },
                )
            }
        }
    }

    /** U44: the slots are the core's (15 minutes, an hour, tonight, tomorrow ...), the alarm is armed by the store. */
    private suspend fun askRemind() {
        val slots =
            (
                chat.reminderSlots(
                    ReminderSlotsRequest(clock(), tzOffsetS()),
                ) as? Outcome.Ok
            )?.value?.slots.orEmpty()
        if (slots.isEmpty()) {
            notice(
                CallDetailsNotice.ReminderFailed,
            )
        } else {
            say(CallDetailsEffect.ChooseRemind(slots.toImmutableList()))
        }
    }

    private suspend fun remind(
        slot: ReminderSlot,
        atMs: Long? = null,
    ) {
        val made =
            (
                chat.callReminder(
                    CallReminderRequest(line.number, target.who, slot, clock(), tzOffsetS()),
                ) as? Outcome.Ok
            )?.value
        val json = made?.armJson?.let { if (atMs == null) it else ReminderItem.retime(it, atMs) }
        if (made != null && json != null && reminders.arm(json)) {
            say(if (atMs == null) CallDetailsEffect.Reminded(made.setText) else CallDetailsEffect.RemindedAt(atMs))
        } else {
            say(CallDetailsEffect.Reject)
            notice(CallDetailsNotice.ReminderFailed)
        }
    }

    private suspend fun undo(undo: CallDetailsUndo) {
        val ok =
            when (undo) {
                is CallDetailsUndo.Note -> {
                    val restored = ops.noteRestore(NoteRestoreRequest(store.notes.value, undo.note)) as? Outcome.Ok
                    restored?.value?.let { r -> store.updateNotes { r.notes } }
                    restored != null
                }

                is CallDetailsUndo.Call -> {
                    (callLog.restore(undo.rows.rows) is CallLogResult.Ok).also { recents.refreshNow() }
                }
            }
        if (!ok) notice(CallDetailsNotice.UndoFailed)
    }

    private companion object {
        const val MS_PER_S = 1_000
    }
}
