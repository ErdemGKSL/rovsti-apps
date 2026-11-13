package dev.rovsti.phone.details

import androidx.compose.runtime.Immutable
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.phone.CallLogCsvRequest
import dev.rovsti.core.model.phone.CallType
import dev.rovsti.core.model.phone.HistoryLine
import dev.rovsti.core.model.phone.HistoryRequest
import dev.rovsti.core.model.phone.HistoryTextRequest
import dev.rovsti.core.model.phone.IdsForNumbersRequest
import dev.rovsti.core.model.phone.LocaleTime
import dev.rovsti.core.model.phone.PersonSelection
import dev.rovsti.core.model.phone.PersonStatsRequest
import dev.rovsti.core.model.phone.ReplyChoicesRequest
import dev.rovsti.core.model.phone.SimAccount
import dev.rovsti.core.model.qr.QrEncodeRequest
import dev.rovsti.core.model.qr.QrMatrix
import dev.rovsti.core.model.shared.ContactQrRequest
import dev.rovsti.core.model.shared.CountryLineRequest
import dev.rovsti.core.model.shared.NumberForm
import dev.rovsti.core.model.shared.QrNumber
import dev.rovsti.core.model.shared.RenderRequest
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.core.ops.QrOps
import dev.rovsti.core.ops.SharedOps
import dev.rovsti.phone.data.BlockRepo
import dev.rovsti.phone.data.BlockResult
import dev.rovsti.phone.data.BlockUndo
import dev.rovsti.phone.data.CallPlacer
import dev.rovsti.phone.data.DialRefusal
import dev.rovsti.phone.data.PhoneStore
import dev.rovsti.phone.data.PlaceResult
import dev.rovsti.phone.data.RecentsRepository
import dev.rovsti.phone.data.Sims
import dev.rovsti.phone.details.photo.PersonPhoto
import dev.rovsti.platform.calllog.CallLogRepository
import dev.rovsti.platform.calllog.CallLogResult
import dev.rovsti.platform.calllog.Restorable
import dev.rovsti.platform.files.ClipboardRepository
import dev.rovsti.platform.identity.CallerIdentity
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One number of the person the sheet is about. */
@Immutable
data class TargetNumber(
    val raw: String,
    val label: String? = null,
)

/**
 * Who the sheet is about: a contact (all of their numbers) or one unknown number. [name] is null for an unknown number,
 * which makes the sheet the Number sheet (the "Add to Contacts" row and the country line).
 */
@Immutable
data class PersonTarget(
    val name: String?,
    val numbers: ImmutableList<TargetNumber>,
    val contactId: Long? = null,
    /** The contact's thumbnail URI (null for none): the avatar of the sheet and the viewer behind it (E5.13). */
    val photoUri: String? = null,
)

/** A number row of the sheet. */
@Immutable
data class NumberRowUi(
    val raw: String,
    /** The number as the core writes it for the eye. */
    val display: String,
    val label: String?,
)

/** One line of "Recent calls": the core's title and detail ("Missed call", "16:13 . 2:05"). */
@Immutable
data class HistoryLineUi(
    val key: String,
    val title: String,
    val detail: String,
    val callType: CallType,
    val missed: Boolean,
    val whatsapp: Boolean,
    val blocked: Boolean,
    val line: HistoryLine,
    val number: String,
)

/** U7: the per-person totals and the core's sentences about them. */
@Immutable
data class StatsUi(
    val calls: Int,
    val incoming: Int,
    val outgoing: Int,
    val missed: Int,
    /**
     * "All time: 4 calls . 1 h 01 min", "Longest call: ..." and so on, as the core wrote them (the header line left
     * out).
     */
    val lines: ImmutableList<String>,
)

/** What the Person / Number sheet draws. */
@Immutable
data class PersonUi(
    val ready: Boolean = false,
    val title: String = "",
    /** The country of an unknown number, else the label of the only number; null for none. */
    val subtitle: String? = null,
    val named: Boolean = false,
    val colorKey: String = "",
    val numbers: ImmutableList<NumberRowUi> = persistentListOf(),
    val history: ImmutableList<HistoryLineUi> = persistentListOf(),
    val stats: StatsUi? = null,
    /** The picture behind the avatar; null for a number that is not in the book. */
    val photo: PersonPhoto? = null,
)

/** The QR code of a person: the vCard text the core fitted and the modules of its code. */
class PersonQr(
    val name: String,
    val payload: String,
    val matrix: QrMatrix,
)

/** What Undo puts back on the sheet: deleted log rows, or a block. */
sealed interface PersonUndo {
    data class Deleted(
        val rows: Restorable,
    ) : PersonUndo

    data class Blocked(
        val undo: BlockUndo,
    ) : PersonUndo
}

sealed interface PersonAction {
    data class Call(
        val number: String,
    ) : PersonAction

    /** A long press on a number: the SIM chooser even when a default SIM exists. */
    data class AskSim(
        val number: String,
    ) : PersonAction

    data class CallOn(
        val number: String,
        val simKey: String,
    ) : PersonAction

    data object Message : PersonAction

    /** The More menu's WhatsApp call item: calls the first number over WhatsApp. */
    data object WhatsAppCall : PersonAction

    data class CopyNumber(
        val number: String,
    ) : PersonAction

    data object CopyAll : PersonAction

    data object ShareDetails : PersonAction

    data object ShareStats : PersonAction

    data object ShowQr : PersonAction

    data object ExportCsv : PersonAction

    data object AskQuickReply : PersonAction

    data class QuickReply(
        val text: String,
    ) : PersonAction

    data object AskBlock : PersonAction

    data object ConfirmBlock : PersonAction

    data object AddContact : PersonAction

    data object AskDeleteHistory : PersonAction

    data object ConfirmDeleteHistory : PersonAction

    data class OpenCall(
        val key: String,
    ) : PersonAction

    data class Undo(
        val undo: PersonUndo,
    ) : PersonAction
}

/** Words the sheet says in the app's voice. */
enum class PersonNotice {
    NoNumber,
    Copied,
    CopiedAll,
    CallFailed,
    DialerOpened,
    NotDefaultDialer,
    BlockRefused,
    AlreadyBlocked,
    BlockFailed,
    DeleteFailed,
    DeletePermission,
    NothingToDelete,
    NoCallsToExport,
    QrFailed,
    UndoFailed,
}

/** One-shot results of the sheet. */
sealed interface PersonEffect {
    data class Say(
        val notice: PersonNotice,
    ) : PersonEffect

    /** The core refused the number; the sheet words [refusal] from resources (F30). */
    data class Core(
        val refusal: DialRefusal,
    ) : PersonEffect

    data object Reject : PersonEffect

    data class ChooseSim(
        val number: String,
        val choices: ImmutableList<SimAccount>,
    ) : PersonEffect

    data class ChooseReply(
        val choices: ImmutableList<String>,
    ) : PersonEffect

    data class ConfirmBlock(
        val name: String,
    ) : PersonEffect

    data class ConfirmDeleteHistory(
        val name: String,
        val calls: Int,
    ) : PersonEffect

    data class Undoable(
        val kind: PersonUndoKind,
        val count: Int,
        val undo: PersonUndo,
    ) : PersonEffect

    data class SendMessage(
        val number: String,
        val body: String?,
    ) : PersonEffect

    data class AddContact(
        val number: String,
    ) : PersonEffect

    data class ShareText(
        val text: String,
    ) : PersonEffect

    data class SaveCsv(
        val fileName: String,
        val csv: String,
    ) : PersonEffect

    data class ShowQr(
        val qr: PersonQr,
    ) : PersonEffect

    data class OpenCall(
        val line: HistoryLine,
        val who: String?,
    ) : PersonEffect
}

enum class PersonUndoKind { HistoryDeleted, Blocked }

/**
 * The brain of the Contact and Number sheets (E5.4). The history, the line texts, the totals, the CSV, the QR
 * payload and
 * the number forms are the core's (`recents.history`, `recents.history_text`, `call_stats.person`, `call_log.csv`,
 * `format.contact_qr`, `format.render`); this class orders them, runs the effects (call, block, delete with Undo) and
 * keeps nothing but what the sheet draws. It rebuilds on every new Recents model, so a call that ends while the
 * sheet is
 * open appears in its history.
 *
 * [scope] belongs to the sheet: the presenter creates it and calls [close] when the sheet is gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonSheetViewModel(
    val target: PersonTarget,
    private val recents: RecentsRepository,
    private val callLog: CallLogRepository,
    private val ops: PhoneOps,
    private val shared: SharedOps,
    private val qr: QrOps,
    private val placer: CallPlacer,
    private val block: BlockRepo,
    private val sims: StateFlow<Sims>,
    private val store: PhoneStore,
    private val clipboard: ClipboardRepository,
    private val homeCc: suspend () -> String?,
    private val locale: () -> LocaleTime,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tzOffsetS: () -> Int = {
        java.util.TimeZone
            .getDefault()
            .getOffset(clock()) / MS_PER_S
    },
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    /** Places a WhatsApp call to a number (the More menu's WhatsApp item). */
    private val placeWhatsApp: (String) -> Unit = {},
    /** WhatsApp calls can be placed (the link is connected): enables the menu item. */
    val whatsAppAvailable: StateFlow<Boolean> = MutableStateFlow(false),
    /**
     * The caller-ID (F39): a sheet opened for a bare number (Blocked, Statistics, a favorite number) names the contact
     * that owns it now, the same way Recents does. Asked on every build, so it follows contact edits.
     */
    private val identify: suspend (String) -> CallerIdentity? = { null },
) {
    private val effects = Channel<PersonEffect>(Channel.BUFFERED)
    val effect = effects.receiveAsFlow()

    private val numbers: List<String> = target.numbers.map { it.raw }

    @Volatile private var statsShare: String? = null

    @Volatile private var shown: PersonUi = PersonUi()

    @Volatile private var lines: Map<String, HistoryLineUi> = emptyMap()

    val state: StateFlow<PersonUi> =
        recents.model
            .mapLatest { build() }
            .flowOn(compute)
            .stateIn(scope, SharingStarted.Eagerly, PersonUi())

    /** Stops everything the sheet started. */
    fun close() {
        scope.cancel()
    }

    private suspend fun render(
        raw: String,
        home: String?,
    ): String = (shared.render(RenderRequest(raw, NumberForm.Display, home)) as? Outcome.Ok)?.value?.text ?: raw

    private suspend fun build(): PersonUi {
        val entries = recents.callEntries
        val extras = recents.extraCalls
        val home = homeCc()
        val now = clock()
        val rows = target.numbers.map { NumberRowUi(it.raw, render(it.raw, home), it.label) }
        val owner =
            if (target.name == null && target.contactId == null) {
                target.numbers
                    .singleOrNull()
                    ?.let { identify(it.raw) }
                    ?.takeIf { it.contactId != null }
            } else {
                null
            }
        val name = target.name ?: owner?.title
        val title = name ?: rows.firstOrNull()?.display.orEmpty()
        val history =
            (
                ops.history(
                    HistoryRequest(entries, extras, numbers, HISTORY_LIMIT),
                ) as? Outcome.Ok
            )?.value?.lines.orEmpty()
        val lineUi =
            history.mapIndexed { at, line ->
                val text = (ops.historyText(HistoryTextRequest(line, now, locale())) as? Outcome.Ok)?.value
                HistoryLineUi(
                    key = "${line.entry}-$at",
                    title = text?.title.orEmpty(),
                    detail = text?.detail.orEmpty(),
                    callType = line.callType,
                    missed = line.callType == CallType.Missed,
                    whatsapp = line.whatsapp,
                    blocked = line.blocked,
                    line = line,
                    number = line.number,
                )
            }
        val stats =
            if (history.isEmpty()) {
                null
            } else {
                (
                    ops.statsPerson(
                        PersonStatsRequest(entries, extras, numbers, null, name.orEmpty(), now, tzOffsetS()),
                    ) as? Outcome.Ok
                )?.value
            }
        statsShare = stats?.shareText
        val subtitle =
            if (name == null) {
                target.numbers.firstOrNull()?.let {
                    (shared.countryLine(CountryLineRequest(it.raw, home)) as? Outcome.Ok)?.value?.line
                }
            } else {
                target.numbers.singleOrNull()?.label
            }
        lines = lineUi.associateBy { it.key }
        val ui =
            PersonUi(
                ready = true,
                title = title,
                subtitle = subtitle,
                named = name != null,
                colorKey = target.numbers.firstOrNull()?.raw ?: title,
                numbers = rows.toImmutableList(),
                photo =
                    target.contactId?.let { PersonPhoto(it, target.photoUri) }
                        ?: owner?.contactId?.let { PersonPhoto(it, owner.photoUri) },
                history = lineUi.toImmutableList(),
                stats =
                    stats?.let {
                        StatsUi(
                            calls = it.all.calls,
                            incoming = it.all.incoming,
                            outgoing = it.all.outgoing,
                            missed = it.all.missed,
                            lines =
                                it.shareText
                                    .lines()
                                    .drop(1)
                                    .filter { l -> l.isNotBlank() }
                                    .toImmutableList(),
                        )
                    },
            )
        shown = ui
        return ui
    }

    fun onAction(action: PersonAction) {
        when (action) {
            is PersonAction.Call -> scope.launch { place(action.number, null) }
            is PersonAction.AskSim -> askSim(action.number)
            is PersonAction.CallOn -> scope.launch { place(action.number, action.simKey) }
            PersonAction.Message -> primary()?.let { say(PersonEffect.SendMessage(it, null)) }
            PersonAction.WhatsAppCall -> primary()?.let(placeWhatsApp)
            is PersonAction.CopyNumber -> copy(action.number)
            PersonAction.CopyAll -> copyAll()
            PersonAction.ShareDetails -> shareDetails()
            PersonAction.ShareStats -> statsShare?.let { say(PersonEffect.ShareText(it)) }
            PersonAction.ShowQr -> scope.launch { showQr() }
            PersonAction.ExportCsv -> scope.launch { exportCsv() }
            PersonAction.AskQuickReply -> scope.launch { askReply() }
            is PersonAction.QuickReply -> primary()?.let { say(PersonEffect.SendMessage(it, action.text)) }
            PersonAction.AskBlock -> askBlock()
            PersonAction.ConfirmBlock -> scope.launch { blockAll() }
            PersonAction.AddContact -> primary()?.let { say(PersonEffect.AddContact(it)) }
            PersonAction.AskDeleteHistory -> askDeleteHistory()
            PersonAction.ConfirmDeleteHistory -> scope.launch { deleteHistory() }
            is PersonAction.OpenCall -> lines[action.key]?.let { say(PersonEffect.OpenCall(it.line, target.name)) }
            is PersonAction.Undo -> scope.launch { undo(action.undo) }
        }
    }

    private fun primary(): String? = numbers.firstOrNull()

    private fun say(effect: PersonEffect) {
        effects.trySend(effect)
    }

    private fun notice(n: PersonNotice) = say(PersonEffect.Say(n))

    private fun displayName(): String = shown.title.ifEmpty { numbers.firstOrNull().orEmpty() }

    private suspend fun place(
        number: String,
        simKey: String?,
    ) {
        when (val result = placer.place(number, simKey)) {
            is PlaceResult.Placed -> {
                Unit
            }

            is PlaceResult.NeedsSim -> {
                say(PersonEffect.ChooseSim(result.number, result.choices))
            }

            is PlaceResult.HandedToDialer -> {
                notice(PersonNotice.DialerOpened)
            }

            is PlaceResult.Invalid -> {
                say(PersonEffect.Core(result.refusal))
            }

            is PlaceResult.Failed -> {
                say(PersonEffect.Reject)
                notice(PersonNotice.CallFailed)
            }
        }
    }

    /** A hold on a number: the chooser lists the SIMs even when the system has a default (a one-off other choice). */
    private fun askSim(number: String) {
        val now = sims.value
        if (now.visible && now.chips.isNotEmpty()) {
            say(PersonEffect.ChooseSim(number, now.chips))
        } else {
            scope.launch { place(number, null) }
        }
    }

    private fun copy(number: String) {
        clipboard.copy(shown.numbers.firstOrNull { it.raw == number }?.display ?: number)
        notice(PersonNotice.Copied)
    }

    /** U36: every number of the person, one per line, as the sheet shows them. */
    private fun copyAll() {
        val text = shown.numbers.joinToString("\n") { it.display }
        if (text.isEmpty()) return notice(PersonNotice.NoNumber)
        clipboard.copy(text)
        notice(PersonNotice.CopiedAll)
    }

    /** U26: the name and every number, ready to paste into a message. */
    private fun shareDetails() {
        val ui = shown
        val text =
            buildString {
                append(ui.title)
                for (n in ui.numbers) {
                    if (n.display == ui.title) continue
                    append('\n')
                    append(listOfNotNull(n.label, n.display).joinToString(": "))
                }
            }
        if (text.isBlank()) notice(PersonNotice.NoNumber) else say(PersonEffect.ShareText(text))
    }

    /** U9: `format.contact_qr` fits the vCard into a code, `qr.encode` makes the modules. */
    private suspend fun showQr() {
        val name = displayName()
        val card =
            (
                shared.contactQr(
                    ContactQrRequest(name, target.numbers.map { QrNumber(it.raw, it.label) }, homeCc()),
                ) as? Outcome.Ok
            )?.value
        val matrix =
            card
                ?.let {
                    (
                        qr.encode(
                            QrEncodeRequest(it.payload),
                        ) as? Outcome.Ok
                    )?.value
                }?.let(QrMatrix::fromAnswer)
        if (card == null || matrix == null) {
            say(PersonEffect.Reject)
            notice(PersonNotice.QrFailed)
        } else {
            say(PersonEffect.ShowQr(PersonQr(name, card.payload, matrix)))
        }
    }

    /** U16: this person's calls, with their notes, as a CSV the user saves where they choose. */
    private suspend fun exportCsv() {
        val request =
            CallLogCsvRequest(
                entries = recents.callEntries,
                extras = recents.extraCalls,
                tzOffsetS = tzOffsetS(),
                nowMs = clock(),
                notes = store.notes.value,
                person = PersonSelection(numbers, target.name.orEmpty()),
            )
        val answer = (ops.callLogCsv(request) as? Outcome.Ok)?.value
        val csv = answer?.csv
        val file = answer?.fileName
        if (csv == null || file == null) {
            notice(PersonNotice.NoCallsToExport)
        } else {
            say(PersonEffect.SaveCsv(file, csv))
        }
    }

    private suspend fun askReply() {
        val replies = store.prefs.value.quickReplies
        val choices = (ops.replyChoices(ReplyChoicesRequest(replies)) as? Outcome.Ok)?.value?.choices.orEmpty()
        if (choices.isEmpty() ||
            primary() == null
        ) {
            notice(PersonNotice.NoNumber)
        } else {
            say(PersonEffect.ChooseReply(choices.toImmutableList()))
        }
    }

    private fun askBlock() {
        if (primary() == null) return notice(PersonNotice.NoNumber)
        say(PersonEffect.ConfirmBlock(displayName()))
    }

    private suspend fun blockAll() {
        val undo = mutableListOf<String>()
        var failure: PersonNotice? = null
        for (number in numbers) {
            when (val result = block.block(number)) {
                is BlockResult.Done -> undo += result.undo.unblock
                is BlockResult.Refused -> failure = failure ?: PersonNotice.BlockRefused
                BlockResult.AlreadyBlocked -> failure = failure ?: PersonNotice.AlreadyBlocked
                BlockResult.Unavailable -> failure = failure ?: PersonNotice.NotDefaultDialer
                BlockResult.Failed -> failure = failure ?: PersonNotice.BlockFailed
            }
        }
        if (undo.isNotEmpty()) {
            say(PersonEffect.Undoable(PersonUndoKind.Blocked, undo.size, PersonUndo.Blocked(BlockUndo(unblock = undo))))
        } else {
            say(PersonEffect.Reject)
            failure?.let(::notice)
        }
    }

    private fun askDeleteHistory() {
        val calls = shown.history.size
        if (calls == 0) return notice(PersonNotice.NothingToDelete)
        say(PersonEffect.ConfirmDeleteHistory(displayName(), calls))
    }

    /**
     * Deletes every call with the person's numbers (log and blocked calls; a WhatsApp call is kept by its own list).
     */
    private suspend fun deleteHistory() {
        val found =
            (
                ops.idsForNumbers(IdsForNumbersRequest(recents.callEntries, recents.extraCalls, numbers)) as? Outcome.Ok
            )?.value
        val ids = found?.let { (it.ids + it.blockedIds).distinct() }.orEmpty()
        if (ids.isEmpty()) return notice(PersonNotice.NothingToDelete)
        when (val result = callLog.delete(ids)) {
            is CallLogResult.Ok -> {
                recents.refreshNow()
                say(PersonEffect.Undoable(PersonUndoKind.HistoryDeleted, ids.size, PersonUndo.Deleted(result.value)))
            }

            is CallLogResult.Failed -> {
                say(PersonEffect.Reject)
                notice(
                    if (result.reason == CallLogResult.Reason.Permission) {
                        PersonNotice.DeletePermission
                    } else {
                        PersonNotice.DeleteFailed
                    },
                )
            }
        }
    }

    private suspend fun undo(undo: PersonUndo) {
        val ok =
            when (undo) {
                is PersonUndo.Deleted -> {
                    (
                        callLog.restore(
                            undo.rows.rows,
                        ) is CallLogResult.Ok
                    ).also { recents.refreshNow() }
                }

                is PersonUndo.Blocked -> {
                    block.undo(undo.undo)
                }
            }
        if (!ok) notice(PersonNotice.UndoFailed)
    }

    private companion object {
        const val HISTORY_LIMIT = 30
        const val MS_PER_S = 1_000
    }
}
