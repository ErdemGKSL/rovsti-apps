package dev.rovsti.phone.recents

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.chat.CallReminderRequest
import dev.rovsti.core.model.chat.ReminderSlot
import dev.rovsti.core.model.chat.ReminderSlotOption
import dev.rovsti.core.model.chat.ReminderSlotsRequest
import dev.rovsti.core.model.phone.BlockRefusal
import dev.rovsti.core.model.phone.CallLogQueryRequest
import dev.rovsti.core.model.phone.Direction
import dev.rovsti.core.model.phone.EntryRef
import dev.rovsti.core.model.phone.RecentsFilter
import dev.rovsti.core.model.phone.RecentsRow
import dev.rovsti.core.model.phone.ReplyChoicesRequest
import dev.rovsti.core.model.phone.SectionStartsRequest
import dev.rovsti.core.model.phone.SimAccount
import dev.rovsti.core.model.shared.CountryLineRequest
import dev.rovsti.core.model.shared.NumberForm
import dev.rovsti.core.model.shared.RenderRequest
import dev.rovsti.core.ops.ChatOps
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.core.ops.SharedOps
import dev.rovsti.phone.data.BlockRepo
import dev.rovsti.phone.data.BlockResult
import dev.rovsti.phone.data.BlockUndo
import dev.rovsti.phone.data.CallPlacer
import dev.rovsti.phone.data.PhoneStore
import dev.rovsti.phone.data.PlaceResult
import dev.rovsti.phone.data.RecentsModel
import dev.rovsti.phone.data.RecentsRepository
import dev.rovsti.phone.data.Sims
import dev.rovsti.platform.calllog.CallLogRepository
import dev.rovsti.platform.calllog.CallLogResult
import dev.rovsti.platform.calllog.Restorable
import dev.rovsti.platform.files.ClipboardRepository
import dev.rovsti.platform.notify.ReminderItem
import dev.rovsti.platform.notify.RemindersRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The SIM a row was made on, as the small chip beside its time (only with two or more SIMs). */
@Immutable
data class SimChipUi(
    val label: String,
    val colorArgb: Int,
)

/** One Recents line, every word of it made by the core. */
@Immutable
data class RecentsRowUi(
    val key: String,
    val title: String,
    /** "(3)" for a run of calls, else null. */
    val count: String?,
    /** The line under the name: "Mobile", "Incoming" ...; the glyph in front of it follows [direction]. */
    val subtitle: String,
    /** U48: the country of a number that is nobody's and not from here ("Germany"), else null. */
    val country: String?,
    val time: String,
    val direction: Direction,
    val missed: Boolean,
    val whatsapp: Boolean,
    val callable: Boolean,
    val number: String,
    val hue: Int?,
    val monogram: String?,
    val named: Boolean,
    val sim: SimChipUi?,
    /** The sentence a screen reader gets for the whole row. */
    val aria: String,
    /** The log ids behind the row (a WhatsApp call has none); Delete acts on these. */
    val deletable: Boolean,
)

/** An item of the list: a day header or a row. */
sealed interface RecentsItem {
    val key: String

    @Immutable
    data class Header(
        val label: String,
        val index: Int,
    ) : RecentsItem {
        override val key: String get() = "h-$index"
    }

    @Immutable
    data class Row(
        val row: RecentsRowUi,
    ) : RecentsItem {
        override val key: String get() = row.key
    }
}

/** How many rows each filter chip would show for the current search text. */
@Immutable
data class FilterCounts(
    val all: Int = 0,
    val missed: Int = 0,
    val incoming: Int = 0,
    val outgoing: Int = 0,
    val whatsapp: Int = 0,
    val withNotes: Int = 0,
) {
    fun of(filter: RecentsFilter): Int =
        when (filter) {
            RecentsFilter.All -> all
            RecentsFilter.Missed -> missed
            RecentsFilter.Incoming -> incoming
            RecentsFilter.Outgoing -> outgoing
            RecentsFilter.WhatsApp -> whatsapp
            RecentsFilter.WithNotes -> withNotes
        }
}

/** Select mode: the keys of the chosen rows. */
@Immutable
data class RecentsSelect(
    val selected: ImmutableSet<String> = persistentSetOf(),
    val allSelected: Boolean = false,
)

/** What the Recents screen draws. */
@Immutable
data class RecentsUi(
    /** False until the first model has arrived. */
    val ready: Boolean = false,
    val items: ImmutableList<RecentsItem> = persistentListOf(),
    val rowCount: Int = 0,
    val filter: RecentsFilter = RecentsFilter.All,
    val query: String = "",
    val counts: FilterCounts = FilterCounts(),
    /** The core's sentence for an empty list (it depends on the search and the filter); null while rows show. */
    val emptyText: String? = null,
    val select: RecentsSelect? = null,
    /** Two or more SIMs: rows may carry a chip. */
    val dualSim: Boolean = false,
)

/** What the user can do on Recents. */
sealed interface RecentsAction {
    data class SetFilter(
        val filter: RecentsFilter,
    ) : RecentsAction

    data class SetQuery(
        val query: String,
    ) : RecentsAction

    /** The tab came to the front: the missed calls are seen (U39). */
    data object Opened : RecentsAction

    data class CallBack(
        val key: String,
    ) : RecentsAction

    data class CallOn(
        val number: String,
        val simKey: String,
    ) : RecentsAction

    data class Message(
        val key: String,
    ) : RecentsAction

    data class AskQuickReply(
        val key: String,
    ) : RecentsAction

    data class QuickReply(
        val key: String,
        val text: String,
    ) : RecentsAction

    data class AskRemind(
        val key: String,
    ) : RecentsAction

    data class Remind(
        val key: String,
        val slot: ReminderSlot,
    ) : RecentsAction

    data class CopyNumber(
        val key: String,
        val withCountry: Boolean,
    ) : RecentsAction

    data class AskFormats(
        val key: String,
        val share: Boolean,
    ) : RecentsAction

    data class AddContact(
        val key: String,
    ) : RecentsAction

    data class AskBlock(
        val keys: List<String>,
    ) : RecentsAction

    data class ConfirmBlock(
        val numbers: List<String>,
    ) : RecentsAction

    data class Delete(
        val keys: List<String>,
    ) : RecentsAction

    data class Undo(
        val undo: RecentsUndo,
    ) : RecentsAction

    data object EnterSelect : RecentsAction

    data class EnterSelectWith(
        val key: String,
    ) : RecentsAction

    data class Toggle(
        val key: String,
    ) : RecentsAction

    data object ToggleAll : RecentsAction

    data object ExitSelect : RecentsAction

    data object DeleteSelected : RecentsAction

    data object BlockSelected : RecentsAction

    data object ShareSelected : RecentsAction
}

/** What Undo puts back: deleted log rows, or a block. One pill, one value, however many rows. */
sealed interface RecentsUndo {
    data class Deleted(
        val rows: Restorable,
    ) : RecentsUndo

    data class Blocked(
        val undo: BlockUndo,
    ) : RecentsUndo
}

/** Words the screen says in its own voice (it maps them to strings). */
enum class RecentsNotice {
    NoNumber,
    Copied,
    CopiedWithCountry,
    CallFailed,
    DialerOpened,
    NotDefaultDialer,
    BlockRefused,
    AlreadyBlocked,
    BlockFailed,
    DeleteFailed,
    DeletePermission,
    WhatsAppSoon,
    ReminderFailed,
    NothingToDelete,
    UndoFailed,
}

/** One-shot results. */
sealed interface RecentsEffect {
    data class Say(
        val notice: RecentsNotice,
    ) : RecentsEffect

    /** A sentence the core wrote. */
    data class Core(
        val text: String,
    ) : RecentsEffect

    data class ChooseSim(
        val number: String,
        val choices: ImmutableList<SimAccount>,
    ) : RecentsEffect

    /** Block asks first: [names] are what the confirmation shows (one name, or "3 numbers"). */
    data class ConfirmBlock(
        val numbers: List<String>,
        val name: String?,
    ) : RecentsEffect

    data class ChooseReply(
        val key: String,
        val choices: ImmutableList<String>,
    ) : RecentsEffect

    data class ChooseRemind(
        val key: String,
        val slots: ImmutableList<ReminderSlotOption>,
    ) : RecentsEffect

    /** The formats of one number ("Copy as" / "Share as"): the form and its text. */
    data class ChooseFormat(
        val share: Boolean,
        val forms: ImmutableList<Pair<NumberForm, String>>,
    ) : RecentsEffect

    /** An action that can be taken back: the pill says [text] and Undo runs [undo]. */
    data class Undoable(
        val text: String?,
        val kind: UndoKind,
        val count: Int,
        val undo: RecentsUndo,
    ) : RecentsEffect

    data class SendMessage(
        val number: String,
        val body: String?,
    ) : RecentsEffect

    data class AddContact(
        val number: String,
    ) : RecentsEffect

    data class ShareText(
        val text: String,
    ) : RecentsEffect

    /** A reminder was set: the core's "Reminder at 18:05". */
    data class Reminded(
        val text: String,
    ) : RecentsEffect

    /** An action failed: the screen plays the reject haptic. */
    data object Reject : RecentsEffect
}

/** Which action an [RecentsEffect.Undoable] pill belongs to. */
enum class UndoKind { Deleted, Blocked }

/**
 * The Recents brain (E5.3). Rows come from [RecentsRepository] (the core grouped, ordered and worded them); this class
 * filters them (`call_log.query`, with the call notes, so the search also finds a note), counts the chips, cuts the day
 * sections (`recents.section_starts`) and orders the effects of the row menu, swipes and select mode. Nothing here
 * sorts or words anything itself.
 *
 * Every change of the input (model, query, filter, notes, SIMs) rebuilds the list on [compute]; a newer input cancels
 * the older build. Select mode keeps the chosen row keys, so a row that disappears (deleted elsewhere) drops out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentsViewModel(
    private val recents: RecentsRepository,
    private val callLog: CallLogRepository,
    private val ops: PhoneOps,
    private val shared: SharedOps,
    private val chat: ChatOps,
    private val placer: CallPlacer,
    private val block: BlockRepo,
    private val sims: StateFlow<Sims>,
    private val store: PhoneStore,
    private val clipboard: ClipboardRepository,
    private val reminders: RemindersRepository<ReminderItem>,
    /** The calling code of home ("90"): numbers from elsewhere get their country line (U48). */
    private val homeCc: suspend () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tzOffsetS: () -> Int = {
        java.util.TimeZone
            .getDefault()
            .getOffset(clock()) / MS_PER_S
    },
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val filter = MutableStateFlow(RecentsFilter.All)
    private val query = MutableStateFlow("")
    private val selecting = MutableStateFlow<Set<String>?>(null)
    private val effects = Channel<RecentsEffect>(Channel.BUFFERED)
    val effect = effects.receiveAsFlow()

    /** Rows by key, for the actions (the list the user sees right now). */
    @Volatile private var byKey: Map<String, Pair<RecentsRow, RecentsRowUi>> = emptyMap()

    @Volatile private var visibleKeys: List<String> = emptyList()
    private val countries = HashMap<String, String?>()

    /** The unseen missed calls: the badge on the tab (the bar shows "99+" above 99). */
    val missedCount: StateFlow<Int> =
        recents.model.map { it.missedCount }.distinctUntilChanged().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            0,
        )

    private val built: StateFlow<RecentsUi> =
        combine(recents.model, filter, query, store.notes, sims) { model, f, q, notes, s ->
            Inputs(model, f, q, notes, s)
        }.mapLatest { build(it) }
            .flowOn(compute)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RecentsUi())

    val state: StateFlow<RecentsUi> =
        combine(built, selecting) { ui, chosen ->
            if (chosen == null) {
                ui.copy(select = null)
            } else {
                val live = chosen.filter { it in visibleKeysSet(ui) }.toImmutableSet()
                ui.copy(select = RecentsSelect(live, ui.rowCount > 0 && live.size == ui.rowCount))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RecentsUi())

    /** Select mode as the tab bar's accessory needs it; null outside select mode. */
    val selection: StateFlow<RecentsSelect?> =
        state.map { it.select }.distinctUntilChanged().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            null,
        )

    private data class Inputs(
        val model: RecentsModel,
        val filter: RecentsFilter,
        val query: String,
        val notes: dev.rovsti.core.model.phone.Notes,
        val sims: Sims,
    )

    private fun visibleKeysSet(ui: RecentsUi): Set<String> =
        ui.items.mapNotNullTo(HashSet()) { (it as? RecentsItem.Row)?.row?.key }

    private suspend fun build(input: Inputs): RecentsUi {
        val model = input.model
        if (!model.ready) return RecentsUi(filter = input.filter, query = input.query)
        val rows = model.rows

        suspend fun query(f: RecentsFilter): List<Int>? =
            (
                ops.callLogQuery(CallLogQueryRequest(rows, input.query, f, input.notes))
                    as? Outcome.Ok
            )?.value?.visible
        val answer =
            (
                ops.callLogQuery(CallLogQueryRequest(rows, input.query, input.filter, input.notes))
                    as? Outcome.Ok
            )?.value
        val visible = answer?.visible ?: rows.indices.toList()
        val counts =
            FilterCounts(
                all = query(RecentsFilter.All)?.size ?: rows.size,
                missed = query(RecentsFilter.Missed)?.size ?: 0,
                incoming = query(RecentsFilter.Incoming)?.size ?: 0,
                outgoing = query(RecentsFilter.Outgoing)?.size ?: 0,
                whatsapp = query(RecentsFilter.WhatsApp)?.size ?: 0,
                withNotes = query(RecentsFilter.WithNotes)?.size ?: 0,
            )
        val sections =
            (
                ops.sectionStarts(SectionStartsRequest(rows, visible, tzOffsetS(), clock()))
                    as? Outcome.Ok
            )?.value
        val starts = sections?.starts.orEmpty()
        val labels = sections?.labels.orEmpty()
        val home = homeCc()
        val chips = input.sims.chips.associateBy { it.key }
        val items = ArrayList<RecentsItem>(visible.size + starts.size)
        val index = HashMap<String, Pair<RecentsRow, RecentsRowUi>>()
        val keys = ArrayList<String>(visible.size)
        var section = 0
        for ((pos, i) in visible.withIndex()) {
            if (section < starts.size && starts[section] == pos) {
                items += RecentsItem.Header(labels.getOrElse(section) { "" }, section)
                section++
            }
            val row = rows.getOrNull(i) ?: continue
            val view = model.views.getOrNull(i) ?: continue
            val ui = rowUi(row, view, input.sims.visible, chips, home)
            items += RecentsItem.Row(ui)
            index[ui.key] = row to ui
            keys += ui.key
        }
        byKey = index
        visibleKeys = keys
        return RecentsUi(
            ready = true,
            items = items.toImmutableList(),
            rowCount = keys.size,
            filter = input.filter,
            query = input.query,
            counts = counts,
            emptyText = if (keys.isEmpty()) answer?.emptyText.orEmpty() else null,
            dualSim = input.sims.visible,
        )
    }

    private suspend fun rowUi(
        row: RecentsRow,
        view: dev.rovsti.core.model.phone.RowView,
        dual: Boolean,
        chips: Map<String, SimAccount>,
        home: String?,
    ): RecentsRowUi {
        val sim = if (dual) row.accountKey?.let { chips[it] }?.let { SimChipUi(it.chip, it.colorArgb) } else null
        val country =
            if (!view.named && row.callable) {
                synchronized(countries) { countries.containsKey(row.numberRaw) }.let { known ->
                    if (known) {
                        synchronized(countries) { countries[row.numberRaw] }
                    } else {
                        val line =
                            (shared.countryLine(CountryLineRequest(row.numberRaw, home)) as? Outcome.Ok)?.value?.line
                        synchronized(countries) { countries[row.numberRaw] = line }
                        line
                    }
                }
            } else {
                null
            }
        return RecentsRowUi(
            key = keyOf(row),
            title = view.title,
            count = view.count,
            subtitle = view.subtitle,
            country = country,
            time = view.time,
            direction = view.direction,
            missed = view.missed,
            whatsapp = view.whatsapp,
            callable = view.callable,
            number = view.number,
            hue = view.hue,
            monogram = view.monogram,
            named = view.named,
            sim = sim,
            aria = view.aria,
            deletable = row.refs.any { it is EntryRef.Log },
        )
    }

    fun onAction(action: RecentsAction) {
        when (action) {
            is RecentsAction.SetFilter -> {
                filter.value = action.filter
            }

            is RecentsAction.SetQuery -> {
                query.value = action.query
            }

            RecentsAction.Opened -> {
                opened()
            }

            is RecentsAction.CallBack -> {
                withRow(action.key) { call(it.numberRaw) }
            }

            is RecentsAction.CallOn -> {
                viewModelScope.launch { place(action.number, action.simKey) }
            }

            is RecentsAction.Message -> {
                withRow(action.key) { say(RecentsEffect.SendMessage(it.numberRaw, null)) }
            }

            is RecentsAction.AskQuickReply -> {
                askReply(action.key)
            }

            is RecentsAction.QuickReply -> {
                withRow(action.key) { say(RecentsEffect.SendMessage(it.numberRaw, action.text)) }
            }

            is RecentsAction.AskRemind -> {
                askRemind(action.key)
            }

            is RecentsAction.Remind -> {
                remind(action.key, action.slot)
            }

            is RecentsAction.CopyNumber -> {
                copyNumber(action.key, action.withCountry)
            }

            is RecentsAction.AskFormats -> {
                askFormats(action.key, action.share)
            }

            is RecentsAction.AddContact -> {
                withRow(action.key) { say(RecentsEffect.AddContact(it.numberRaw)) }
            }

            is RecentsAction.AskBlock -> {
                askBlock(action.keys)
            }

            is RecentsAction.ConfirmBlock -> {
                blockNumbers(action.numbers)
            }

            is RecentsAction.Delete -> {
                delete(action.keys)
            }

            is RecentsAction.Undo -> {
                undo(action.undo)
            }

            RecentsAction.EnterSelect -> {
                selecting.value = emptySet()
            }

            is RecentsAction.EnterSelectWith -> {
                selecting.value = setOf(action.key)
            }

            is RecentsAction.Toggle -> {
                selecting.update {
                    it?.let { s ->
                        if (action.key in
                            s
                        ) {
                            s - action.key
                        } else {
                            s + action.key
                        }
                    }
                }
            }

            RecentsAction.ToggleAll -> {
                toggleAll()
            }

            RecentsAction.ExitSelect -> {
                selecting.value = null
            }

            RecentsAction.DeleteSelected -> {
                delete(chosen())
            }

            RecentsAction.BlockSelected -> {
                askBlock(chosen())
            }

            RecentsAction.ShareSelected -> {
                shareSelected()
            }
        }
    }

    /** The grouped row behind [key] (the list the user sees right now); the details sheet opens on it. */
    fun rowOf(key: String): RecentsRow? = byKey[key]?.first

    /** Copies [text] (a number in the form the user picked). */
    fun copyText(text: String) {
        clipboard.copy(text)
        notice(RecentsNotice.Copied)
    }

    // ---- helpers

    private fun chosen(): List<String> = visibleKeys.filter { it in (selecting.value ?: emptySet()) }

    private fun toggleAll() {
        val all = visibleKeys
        selecting.update { s ->
            if (s == null) {
                null
            } else if (s.containsAll(all) && all.isNotEmpty()) {
                emptySet()
            } else {
                all.toSet()
            }
        }
    }

    private fun say(effect: RecentsEffect) {
        effects.trySend(effect)
    }

    private fun notice(n: RecentsNotice) = say(RecentsEffect.Say(n))

    private inline fun withRow(
        key: String,
        block: (RecentsRow) -> Unit,
    ) {
        byKey[key]?.first?.let(block)
    }

    /** U39: the missed calls are seen now: the badge goes, the system notification goes, the time is kept. */
    private fun opened() {
        viewModelScope.launch {
            callLog.markMissedRead()
            val now = clock()
            store.updatePrefs { it.copy(ui = it.ui.copy(missedSeenMs = now)) }
            recents.refreshNow()
        }
    }

    private fun call(number: String) {
        viewModelScope.launch { place(number, null) }
    }

    private suspend fun place(
        number: String,
        simKey: String?,
    ) {
        when (val result = placer.place(number, simKey)) {
            is PlaceResult.Placed -> {
                Unit
            }

            is PlaceResult.NeedsSim -> {
                say(RecentsEffect.ChooseSim(result.number, result.choices))
            }

            is PlaceResult.HandedToDialer -> {
                notice(RecentsNotice.DialerOpened)
            }

            is PlaceResult.Invalid -> {
                say(RecentsEffect.Core(result.reason))
            }

            is PlaceResult.Failed -> {
                say(RecentsEffect.Reject)
                notice(RecentsNotice.CallFailed)
            }
        }
    }

    private fun askReply(key: String) {
        val row = byKey[key]?.first ?: return
        viewModelScope.launch {
            val replies = store.prefs.value.quickReplies
            val choices = (ops.replyChoices(ReplyChoicesRequest(replies)) as? Outcome.Ok)?.value?.choices.orEmpty()
            if (choices.isNotEmpty() && row.callable) {
                say(RecentsEffect.ChooseReply(key, choices.toImmutableList()))
            } else {
                notice(RecentsNotice.NoNumber)
            }
        }
    }

    private fun askRemind(key: String) {
        viewModelScope.launch {
            val now = clock()
            val out = chat.reminderSlots(ReminderSlotsRequest(now, tzOffsetS()))
            val slots = (out as? Outcome.Ok)?.value?.slots.orEmpty()
            if (slots.isEmpty()) {
                notice(
                    RecentsNotice.ReminderFailed,
                )
            } else {
                say(RecentsEffect.ChooseRemind(key, slots.toImmutableList()))
            }
        }
    }

    private fun remind(
        key: String,
        slot: ReminderSlot,
    ) {
        val row = byKey[key]?.first ?: return
        viewModelScope.launch {
            val out =
                chat.callReminder(
                    CallReminderRequest(
                        number = row.numberRaw,
                        name = row.name,
                        slot = slot,
                        nowMs = clock(),
                        tzOffsetS = tzOffsetS(),
                    ),
                )
            val made = (out as? Outcome.Ok)?.value
            if (made != null && reminders.arm(made.armJson)) {
                say(RecentsEffect.Reminded(made.setText))
            } else {
                say(RecentsEffect.Reject)
                notice(RecentsNotice.ReminderFailed)
            }
        }
    }

    private fun copyNumber(
        key: String,
        withCountry: Boolean,
    ) {
        val row = byKey[key]?.first ?: return
        viewModelScope.launch {
            val text =
                if (withCountry) {
                    val e164 =
                        (
                            shared.render(
                                RenderRequest(row.numberRaw, NumberForm.E164, homeCc()),
                            ) as? Outcome.Ok
                        )?.value?.text
                    e164 ?: row.numberRaw
                } else {
                    row.numberRaw
                }
            clipboard.copy(text)
            notice(if (withCountry) RecentsNotice.CopiedWithCountry else RecentsNotice.Copied)
        }
    }

    private fun askFormats(
        key: String,
        share: Boolean,
    ) {
        val row = byKey[key]?.first ?: return
        viewModelScope.launch {
            val home = homeCc()
            val forms = mutableListOf<Pair<NumberForm, String>>()
            for (form in listOf(NumberForm.Display, NumberForm.National, NumberForm.E164, NumberForm.TelLink)) {
                val text =
                    (shared.render(RenderRequest(row.numberRaw, form, home)) as? Outcome.Ok)?.value?.text ?: continue
                if (forms.none { it.second == text }) forms += form to text
            }
            if (forms.isEmpty()) {
                notice(
                    RecentsNotice.NoNumber,
                )
            } else {
                say(RecentsEffect.ChooseFormat(share, forms.toImmutableList()))
            }
        }
    }

    private fun askBlock(keys: List<String>) {
        val rows = keys.mapNotNull { byKey[it]?.first }.filter { it.callable }
        if (rows.isEmpty()) {
            notice(RecentsNotice.NoNumber)
            return
        }
        val name = rows.singleOrNull()?.let { it.name ?: it.numberRaw }
        say(RecentsEffect.ConfirmBlock(rows.map { it.numberRaw }.distinct(), name))
    }

    private fun blockNumbers(numbers: List<String>) {
        viewModelScope.launch {
            val undo = mutableListOf<String>()
            var failure: RecentsNotice? = null
            for (number in numbers) {
                when (val result = block.block(number)) {
                    is BlockResult.Done -> undo += result.undo.unblock
                    is BlockResult.Refused -> failure = failure ?: refusal(result.reason)
                    BlockResult.AlreadyBlocked -> failure = failure ?: RecentsNotice.AlreadyBlocked
                    BlockResult.Unavailable -> failure = failure ?: RecentsNotice.NotDefaultDialer
                    BlockResult.Failed -> failure = failure ?: RecentsNotice.BlockFailed
                }
            }
            if (undo.isNotEmpty()) {
                selecting.update { it?.let { emptySet() } }
                say(
                    RecentsEffect.Undoable(
                        null,
                        UndoKind.Blocked,
                        undo.size,
                        RecentsUndo.Blocked(BlockUndo(unblock = undo)),
                    ),
                )
            } else {
                say(RecentsEffect.Reject)
            }
            if (failure != null && undo.isEmpty()) notice(failure)
        }
    }

    private fun refusal(reason: BlockRefusal): RecentsNotice =
        when (reason) {
            BlockRefusal.NoNumber -> RecentsNotice.NoNumber
            else -> RecentsNotice.BlockRefused
        }

    private fun delete(keys: List<String>) {
        val rows = keys.mapNotNull { byKey[it]?.first }
        val ids = rows.flatMap { r -> r.refs.mapNotNull { (it as? EntryRef.Log)?.id } }.distinct()
        if (ids.isEmpty()) {
            notice(if (rows.any { it.whatsapp }) RecentsNotice.WhatsAppSoon else RecentsNotice.NothingToDelete)
            return
        }
        viewModelScope.launch {
            when (val result = callLog.delete(ids)) {
                is CallLogResult.Ok -> {
                    selecting.update { it?.let { emptySet() } }
                    recents.refreshNow()
                    say(RecentsEffect.Undoable(null, UndoKind.Deleted, rows.size, RecentsUndo.Deleted(result.value)))
                }

                is CallLogResult.Failed -> {
                    say(RecentsEffect.Reject)
                    notice(
                        if (result.reason == CallLogResult.Reason.Permission) {
                            RecentsNotice.DeletePermission
                        } else {
                            RecentsNotice.DeleteFailed
                        },
                    )
                }
            }
        }
    }

    private fun undo(undo: RecentsUndo) {
        viewModelScope.launch {
            val ok =
                when (undo) {
                    is RecentsUndo.Deleted -> {
                        (callLog.restore(undo.rows.rows) is CallLogResult.Ok).also { recents.refreshNow() }
                    }

                    is RecentsUndo.Blocked -> {
                        block.undo(undo.undo)
                    }
                }
            if (!ok) notice(RecentsNotice.UndoFailed)
        }
    }

    private fun shareSelected() {
        val rows = chosen().mapNotNull { byKey[it] }
        if (rows.isEmpty()) return
        val text =
            rows.joinToString(
                "\n",
            ) { (row, ui) -> "${ui.title.takeIf { row.name != null } ?: ""} ${row.numberRaw}".trim() }
        say(RecentsEffect.ShareText(text))
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val MS_PER_S = 1_000

        fun keyOf(row: RecentsRow): String =
            when (val ref = row.refs.firstOrNull()) {
                is EntryRef.Log -> "log-${ref.id}"
                is EntryRef.WhatsApp -> "wa-${ref.callId}"
                null -> "${row.normalized}-${row.lastMs}"
            }
    }
}
