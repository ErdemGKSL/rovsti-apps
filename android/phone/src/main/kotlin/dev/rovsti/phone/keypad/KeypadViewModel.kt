package dev.rovsti.phone.keypad

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.DialTextRequest
import dev.rovsti.core.model.phone.FieldAction
import dev.rovsti.core.model.phone.FieldMenuItem
import dev.rovsti.core.model.phone.FieldMenuRequest
import dev.rovsti.core.model.phone.HoldDigit
import dev.rovsti.core.model.phone.KeypadTonesRequest
import dev.rovsti.core.model.phone.PastePlan
import dev.rovsti.core.model.phone.RedialRequest
import dev.rovsti.core.model.phone.SimAccount
import dev.rovsti.core.model.phone.ToneStep
import dev.rovsti.core.model.search.T9Row
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.design.text.DialAlphabet
import dev.rovsti.design.text.backspace
import dev.rovsti.design.text.insertAtCaret
import dev.rovsti.design.text.setDialText
import dev.rovsti.phone.data.CallPlacer
import dev.rovsti.phone.data.DialRefusal
import dev.rovsti.phone.data.People
import dev.rovsti.phone.data.PeopleIndex
import dev.rovsti.phone.data.PlaceResult
import dev.rovsti.phone.data.Sims
import dev.rovsti.phone.data.SpeedDial
import dev.rovsti.platform.files.ClipboardRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A line of the T9 suggestions: the contact's name with the matched letters, and the number that matched. */
@Immutable
data class T9Item(
    val key: String,
    val contactId: Long,
    val name: String,
    val number: String,
    /** The matched letters of [name] as a half-open range, or null for a match in the number. */
    val span: IntRange?,
)

/** The state the Keypad draws. The text itself lives in [KeypadViewModel.field]. */
@Immutable
data class KeypadUi(
    val dialed: String = "",
    /** All suggestions for the digits typed (at most 50); the screen shows as many as fit and a "N more" line. */
    val t9: ImmutableList<T9Item> = persistentListOf(),
    /** A valid number that is nobody's: the "Add to Contacts" line shows. */
    val unknown: Boolean = false,
    /** What the pause and wait marks of the number stand for, e.g. `4821,12`; null without marks. */
    val toneHint: String? = null,
    val tonesOn: Boolean = true,
    /** The SIM accounts when there are two or more (the long-press menu and the chooser list them). */
    val sims: ImmutableList<SimAccount> = persistentListOf(),
)

/** What the user can do on the Keypad. */
sealed interface KeypadAction {
    data class Digit(
        val char: Char,
    ) : KeypadAction

    /** A hold on a key: the digit typed on touch-down is taken back and the key's second function runs. */
    data class Hold(
        val char: Char,
    ) : KeypadAction

    data object Backspace : KeypadAction

    data object Clear : KeypadAction

    data class Restore(
        val text: String,
    ) : KeypadAction

    data object Call : KeypadAction

    data class CallOn(
        val number: String,
        val simKey: String,
    ) : KeypadAction

    /** The call key, held. */
    data object OpenCallMenu : KeypadAction

    data object OpenFieldMenu : KeypadAction

    /** A field-menu entry; the app words the pill after it itself (F30: the core's `done_text` is English). */
    data class Field(
        val action: FieldAction,
    ) : KeypadAction

    data object Paste : KeypadAction

    data class PasteChosen(
        val number: String,
    ) : KeypadAction

    data object Share : KeypadAction

    data object CopyE164 : KeypadAction

    data class Suggestion(
        val item: T9Item,
    ) : KeypadAction

    data class FillSuggestion(
        val item: T9Item,
    ) : KeypadAction

    data class AddContact(
        val create: Boolean,
    ) : KeypadAction

    data object Message : KeypadAction

    /** The call menu's WhatsApp item: calls the typed number over WhatsApp. */
    data object WhatsAppCall : KeypadAction

    /** The typed number was changed from outside the view model (a hardware key, the system toolbar). */
    data object TextChanged : KeypadAction
}

/** Things the keypad says in its own words (the screen maps them to strings). */
enum class Notice { NoVoicemail, DialerOpened, CallFailed, NothingToPaste, Cleared, Copied, CopiedE164 }

/** U23: a notice that reports something done (paired with the confirm haptic); the others are failures or hints. */
internal fun Notice.isSuccess() = this == Notice.Cleared || this == Notice.Copied || this == Notice.CopiedE164

/** One entry of the field menu. */
@Immutable
sealed interface FieldEntry {
    data class Core(
        val item: FieldMenuItem,
    ) : FieldEntry

    data object Share : FieldEntry

    data class E164(
        val text: String,
    ) : FieldEntry
}

/** One-shot results: shown once, never part of [KeypadUi]. */
sealed interface KeypadEffect {
    data class Say(
        val notice: Notice,
    ) : KeypadEffect

    /** The core refused the number; the screen words [refusal] from resources (F30). */
    data class Core(
        val refusal: DialRefusal,
    ) : KeypadEffect

    data class ChooseSim(
        val number: String,
        val choices: ImmutableList<SimAccount>,
    ) : KeypadEffect

    data class ChoosePaste(
        val numbers: ImmutableList<String>,
    ) : KeypadEffect

    data class FieldMenu(
        val entries: ImmutableList<FieldEntry>,
    ) : KeypadEffect

    data class CallMenu(
        val sims: ImmutableList<SimAccount>,
        val number: String,
        /** WhatsApp calls can be placed: the menu's WhatsApp row works. */
        val whatsApp: Boolean = false,
    ) : KeypadEffect

    /** [old] is what Clear removed: the pill's Undo restores it. */
    data class Undo(
        val old: String,
    ) : KeypadEffect

    data class AssignSpeedDial(
        val digit: Int,
    ) : KeypadEffect

    data class AddContact(
        val number: String,
        val create: Boolean,
    ) : KeypadEffect

    data class ShareText(
        val text: String,
    ) : KeypadEffect

    data class SendMessage(
        val number: String,
    ) : KeypadEffect

    /** An action that failed: the screen plays the reject haptic. */
    data object Reject : KeypadEffect
}

/**
 * The Keypad's brain (E5.2). The number lives in [field], a [TextFieldState] the field shows; every change goes through
 * its edit functions at the caret, so a number typed in the middle stays there. Every decision is the core's: what a
 * paste means (`dial.paste_plan`), the field menu (`dial.field_menu`), the redial number (`dial.redial_target`), whether
 * the tones are on (`dial.keypad_tones`), the long-press of a digit (`speed_dial.on_hold`), what may be dialed
 * (`dial.validate` inside [CallPlacer]) and the T9 matches (`search.t9`). This class orders the effects.
 *
 * Suggestions are computed one frame after the last digit ([debounceMs]), off the main thread, and a newer digit cancels
 * the older query.
 */
class KeypadViewModel(
    private val ops: PhoneOps,
    private val people: PeopleIndex,
    private val placer: CallPlacer,
    private val speedDial: SpeedDial,
    private val sims: StateFlow<Sims>,
    private val clipboard: ClipboardRepository,
    private val redialEntries: () -> List<CallLogEntry>,
    private val voicemailNumber: () -> String?,
    private val toneSetting: () -> Boolean?,
    private val systemTones: () -> Boolean,
    /** `+` form of a typed number (`format.render`), null when it has none. */
    private val e164: suspend (String) -> String?,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val debounceMs: Long = FRAME_MS,
    /** WhatsApp calls can be placed now (the instance is linked). */
    private val whatsAppAvailable: () -> Boolean = { false },
    private val placeWhatsApp: (String) -> Unit = {},
) : ViewModel() {
    /** The number field's text; the screen hands it to `DialField`. */
    val field = TextFieldState()

    private val ui = MutableStateFlow(KeypadUi())
    val state: StateFlow<KeypadUi> = ui.asStateFlow()

    private val effects = Channel<KeypadEffect>(Channel.BUFFERED)
    val effect = effects.receiveAsFlow()

    private var queryJob: Job? = null

    init {
        viewModelScope.launch {
            val on = (ops.keypadTones(KeypadTonesRequest(toneSetting(), systemTones())) as? Outcome.Ok)?.value?.on
            ui.update { it.copy(tonesOn = on ?: true) }
        }
        viewModelScope.launch {
            sims.collect { s ->
                ui.update { it.copy(sims = if (s.visible) s.chips else persistentListOf()) }
            }
        }
    }

    fun onAction(action: KeypadAction) {
        when (action) {
            is KeypadAction.Digit -> {
                type(action.char)
            }

            is KeypadAction.Hold -> {
                hold(action.char)
            }

            KeypadAction.Backspace -> {
                field.backspace()
                changed()
            }

            KeypadAction.Clear -> {
                clear()
            }

            is KeypadAction.Restore -> {
                field.setDialText(action.text)
                changed()
            }

            KeypadAction.Call -> {
                call()
            }

            is KeypadAction.CallOn -> {
                place(action.number, action.simKey)
            }

            KeypadAction.OpenCallMenu -> {
                openCallMenu()
            }

            KeypadAction.OpenFieldMenu -> {
                openFieldMenu()
            }

            is KeypadAction.Field -> {
                fieldAction(action.action)
            }

            KeypadAction.Paste -> {
                paste()
            }

            is KeypadAction.PasteChosen -> {
                field.insertAtCaret(action.number)
                changed()
            }

            KeypadAction.Share -> {
                send(KeypadEffect.ShareText(text()))
            }

            KeypadAction.CopyE164 -> {
                copyE164()
            }

            is KeypadAction.Suggestion -> {
                field.setDialText(action.item.number)
                changed()
                call()
            }

            is KeypadAction.FillSuggestion -> {
                field.setDialText(action.item.number)
                changed()
            }

            is KeypadAction.AddContact -> {
                send(KeypadEffect.AddContact(text(), action.create))
            }

            KeypadAction.Message -> {
                send(KeypadEffect.SendMessage(text()))
            }

            KeypadAction.WhatsAppCall -> {
                text().takeIf { it.isNotEmpty() }?.let(placeWhatsApp)
            }

            KeypadAction.TextChanged -> {
                changed()
            }
        }
    }

    private fun text(): String = field.text.toString()

    private fun send(effect: KeypadEffect) {
        effects.trySend(effect)
    }

    private fun type(c: Char) {
        if (!DialAlphabet.isDial(c)) return
        val selection = field.selection
        if (text().length - (selection.max - selection.min) >= DialAlphabet.MAX_LEN) {
            send(KeypadEffect.Reject)
            return
        }
        field.insertAtCaret(c.toString())
        changed()
    }

    private fun hold(c: Char) {
        // The key already typed its digit on touch-down: take it back, then do what the hold means.
        field.backspace()
        when (c) {
            '0' -> type('+')
            '*' -> type(',')
            '#' -> type(';')
            else -> viewModelScope.launch { speedHold(c) }
        }
        changed()
    }

    private suspend fun speedHold(c: Char) {
        when (val result = speedDial.onHold(c, text())) {
            HoldDigit.Voicemail -> {
                val number = voicemailNumber()
                if (number.isNullOrBlank()) send(KeypadEffect.Say(Notice.NoVoicemail)) else place(number, null)
            }

            is HoldDigit.Call -> {
                place(result.numberRaw, null)
            }

            is HoldDigit.Unassigned -> {
                send(KeypadEffect.AssignSpeedDial(result.digit))
            }

            HoldDigit.Ignore -> {
                type(c)
            }
        }
    }

    private fun clear() {
        val old = text()
        if (old.isEmpty()) return
        field.setDialText("")
        changed()
        send(KeypadEffect.Undo(old))
    }

    private fun call() {
        viewModelScope.launch {
            val typed = text()
            if (typed.isEmpty()) {
                redial()
            } else {
                place(typed, null)
            }
        }
    }

    private suspend fun redial() {
        val target = (ops.redialTarget(RedialRequest(redialEntries())) as? Outcome.Ok)?.value?.number
        if (target == null) {
            send(KeypadEffect.Reject)
        } else {
            field.setDialText(target)
            changed()
        }
    }

    private fun place(
        number: String,
        simKey: String?,
    ) {
        viewModelScope.launch { handle(placer.place(number, simKey)) }
    }

    private fun handle(result: PlaceResult) {
        when (result) {
            is PlaceResult.Placed -> {
                Unit
            }

            is PlaceResult.NeedsSim -> {
                send(KeypadEffect.ChooseSim(result.number, result.choices))
            }

            is PlaceResult.HandedToDialer -> {
                send(KeypadEffect.Say(Notice.DialerOpened))
            }

            is PlaceResult.Invalid -> {
                send(KeypadEffect.Reject)
                send(KeypadEffect.Core(result.refusal))
            }

            is PlaceResult.Failed -> {
                send(KeypadEffect.Say(Notice.CallFailed))
            }
        }
    }

    private fun openCallMenu() {
        // Nothing typed: the held call key is just the call key (redial).
        if (text().isEmpty()) call() else send(KeypadEffect.CallMenu(ui.value.sims, text(), whatsAppAvailable()))
    }

    private fun openFieldMenu() {
        viewModelScope.launch {
            val typed = text()
            val clip = clipboard.primaryText().orEmpty()
            val core =
                (ops.fieldMenu(FieldMenuRequest(typed, clip.isNotBlank())) as? Outcome.Ok)?.value?.items.orEmpty()
            if (core.isEmpty()) return@launch
            val entries = core.map<FieldMenuItem, FieldEntry> { FieldEntry.Core(it) }.toMutableList()
            if (typed.isNotEmpty()) {
                entries += FieldEntry.Share
                e164(typed)?.takeIf { it != typed }?.let { entries += FieldEntry.E164(it) }
            }
            send(KeypadEffect.FieldMenu(entries.toImmutableList()))
        }
    }

    private fun fieldAction(action: FieldAction) {
        when (action) {
            FieldAction.Copy -> {
                if (clipboard.copy(text())) send(KeypadEffect.Say(Notice.Copied))
            }

            FieldAction.Paste -> {
                paste()
            }

            FieldAction.Clear -> {
                clear()
            }
        }
    }

    private fun copyE164() {
        viewModelScope.launch {
            val plus = e164(text())
            if (plus != null && clipboard.copy(plus)) send(KeypadEffect.Say(Notice.CopiedE164))
        }
    }

    private fun paste() {
        viewModelScope.launch {
            val clip = clipboard.primaryText().orEmpty()
            val plan = (ops.pastePlan(DialTextRequest(clip)) as? Outcome.Ok)?.value?.plan ?: PastePlan.Nothing
            when (plan) {
                PastePlan.Nothing -> {
                    send(KeypadEffect.Reject)
                    send(KeypadEffect.Say(Notice.NothingToPaste))
                }

                is PastePlan.One -> {
                    field.insertAtCaret(plan.number)
                    changed()
                }

                is PastePlan.Choose -> {
                    send(KeypadEffect.ChoosePaste(plan.numbers.toImmutableList()))
                }
            }
        }
    }

    /** The text changed: show it now, and (re)compute everything that depends on it one frame later. */
    private fun changed() {
        val typed = text()
        if (typed == ui.value.dialed && queryJob?.isActive != true) return
        ui.update { it.copy(dialed = typed, t9 = if (typed.isEmpty()) persistentListOf() else it.t9) }
        queryJob?.cancel()
        queryJob =
            viewModelScope.launch {
                delay(debounceMs)
                if (typed.isEmpty()) {
                    ui.update { it.copy(t9 = persistentListOf(), unknown = false, toneHint = null) }
                    return@launch
                }
                val digits = typed.filter { it.isDigit() }
                val rows = withContext(compute) { people.t9(digits) }
                ui.update { it.copy(t9 = rows.map(::item).toImmutableList()) }
                val unknown = unknown(typed)
                val hint = toneHint(typed)
                ui.update { it.copy(unknown = unknown, toneHint = hint) }
            }
    }

    private suspend fun unknown(typed: String): Boolean {
        if (typed.length < MIN_UNKNOWN || people.people.value !is People.Ready) return false
        if (typed.any { it == '*' || it == '#' || it == ',' || it == ';' }) return false
        if (ops.validate(DialTextRequest(typed)) !is Outcome.Ok) return false
        return people.matchNumber(typed) == null
    }

    private suspend fun toneHint(typed: String): String? {
        val plan = (ops.tonePlan(DialTextRequest(typed)) as? Outcome.Ok)?.value ?: return null
        if (plan.steps.none { it is ToneStep.Pause }) return null
        return (ops.tonePreview(DialTextRequest(typed)) as? Outcome.Ok)?.value?.text
    }

    private fun item(row: T9Row) =
        T9Item(
            key = "${row.id}:${row.numberIdx}",
            contactId = row.id,
            name = row.name,
            number = row.number,
            span = row.nameSpan?.takeIf { it.size == 2 && it[1] > it[0] }?.let { it[0] until it[1] },
        )

    private companion object {
        const val FRAME_MS = 16L
        const val MIN_UNKNOWN = 3
    }
}
