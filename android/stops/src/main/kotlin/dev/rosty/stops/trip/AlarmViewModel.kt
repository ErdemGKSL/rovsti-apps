package dev.rosty.stops.trip

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.Outcome
import dev.rosty.core.model.phone.DirRequest
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.History
import dev.rosty.core.model.stops.HistoryListRequest
import dev.rosty.core.model.stops.HistoryRow
import dev.rosty.core.model.stops.HistoryTextRequest
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.core.model.stops.StoreReadRequest
import dev.rosty.core.ops.ActiveTripHandle
import dev.rosty.core.ops.StopsOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The ringing alarm as the in-app screen shows it (GPUI `alarm_screen`). */
@Immutable
data class AlarmUi(
    /** The pair, for "Crossed Pier West – Pier East". */
    val label: String,
    /** The snooze length when snoozes are left, else null ("No snoozes left"). */
    val snoozeMin: Int?,
)

/** S27: the newest trip that ended in the last 12 hours, as Rust words it (`history_list` `last_trip`). */
@Immutable
data class LastTripUi(
    val row: HistoryRow,
)

@Immutable
data class AlarmUiState(
    val alarm: AlarmUi? = null,
    val lastTrip: LastTripUi? = null,
    /** Record ids the user closed (Close only hides the card). */
    val hidden: Set<Long> = emptySet(),
) {
    val shownLastTrip: LastTripUi? get() = lastTrip?.takeIf { it.row.record.id !in hidden }
}

sealed interface AlarmEffect {
    /** S21: "Alarm dismissed" with Re-arm when the ends are known (GPUI `dismiss_pill`). */
    data class Dismissed(
        val ends: Pair<Destination, Destination>?,
    ) : AlarmEffect

    /** "Snoozed. Ringing again in N min." */
    data class Snoozed(
        val minutes: Int,
    ) : AlarmEffect

    data class Share(
        val text: String,
    ) : AlarmEffect

    /** Start again / Re-arm: the same A and B as a new trip (the home runs the preflight). */
    data class Start(
        val a: Destination,
        val b: Destination,
    ) : AlarmEffect

    /** S27 "Save as stop": the saved list takes the pair. */
    data class Save(
        val a: Destination,
        val b: Destination,
    ) : AlarmEffect

    /** The trip ended: the trip card and the home read their files again. */
    data object TripOver : AlarmEffect
}

sealed interface AlarmAction {
    data object Dismiss : AlarmAction

    data object Snooze : AlarmAction

    /** The trip rang (the service's fix said so) while the `ringing` event is still on its way. */
    data class Ring(
        val trip: RunningTrip,
    ) : AlarmAction

    data object StartAgain : AlarmAction

    data object SaveLastTrip : AlarmAction

    data object ShareLastTrip : AlarmAction

    data object CloseLastTrip : AlarmAction

    /** Read the history and the trip again (a resume, a trip that ended). */
    data object Refresh : AlarmAction
}

/**
 * The alarm and arrival (E7.6): the in-app alarm screen with the native `AlarmActivity`'s actions (Dismiss, Snooze;
 * AD-SA5 time-outs come back as [AlarmEvent.Later]), S21 Re-arm after a dismissal, and S27 the last trip card
 * (Start again, Save as stop, Share, Close). Rust words everything: `stops.history_list` (the card),
 * `stops.history_text` (its share text), the trip's snoozes left (`stops.trip_restore` of `trip.json`).
 */
class AlarmViewModel(
    private val ops: StopsOps,
    private val control: AlarmControl,
    private val prefs: suspend () -> StopsPrefs,
    private val dir: String,
    private val nowMs: () -> Long,
    private val tzOffsetS: () -> Int,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val work: CoroutineScope = scope ?: viewModelScope
    private val mutable = MutableStateFlow(AlarmUiState())
    val state: StateFlow<AlarmUiState> = mutable.asStateFlow()

    private val effectChannel = Channel<AlarmEffect>(Channel.BUFFERED)
    val effects: Flow<AlarmEffect> = effectChannel.receiveAsFlow()

    /** The ends of the trip that rang, for Re-arm after the trip file is gone. */
    private var ringingEnds: Pair<Destination, Destination>? = null

    init {
        work.launch { readLastTrip() }
        work.launch { control.events.collect(::onEvent) }
    }

    fun onAction(action: AlarmAction) {
        when (action) {
            AlarmAction.Dismiss -> {
                mutable.value = mutable.value.copy(alarm = null)
                control.dismiss()
            }

            AlarmAction.Snooze -> {
                mutable.value = mutable.value.copy(alarm = null)
                control.snooze()
            }

            is AlarmAction.Ring -> {
                work.launch { show(action.trip) }
            }

            AlarmAction.StartAgain -> {
                ends()?.let { (a, b) -> emit(AlarmEffect.Start(a, b)) }
            }

            AlarmAction.SaveLastTrip -> {
                ends()?.let { (a, b) -> emit(AlarmEffect.Save(a, b)) }
            }

            AlarmAction.ShareLastTrip -> {
                work.launch { shareLastTrip() }
            }

            AlarmAction.CloseLastTrip -> {
                val id =
                    mutable.value.lastTrip
                        ?.row
                        ?.record
                        ?.id ?: return
                mutable.value = mutable.value.copy(hidden = mutable.value.hidden + id)
            }

            AlarmAction.Refresh -> {
                work.launch { readLastTrip() }
            }
        }
    }

    private fun emit(effect: AlarmEffect) {
        effectChannel.trySend(effect)
    }

    private suspend fun onEvent(event: AlarmEvent) {
        when (event) {
            AlarmEvent.Ringing -> {
                tripOnFile()?.let { show(it) }
            }

            is AlarmEvent.Later -> {
                mutable.value = mutable.value.copy(alarm = null)
                val minutes = ((event.atMs - nowMs()).coerceAtLeast(0) + MS_PER_MIN - 1) / MS_PER_MIN
                emit(AlarmEffect.Snoozed(minutes.toInt()))
            }

            AlarmEvent.Dismissed -> {
                mutable.value = mutable.value.copy(alarm = null)
                emit(AlarmEffect.Dismissed(ringingEnds))
                ringingEnds = null
                emit(AlarmEffect.TripOver)
                readLastTrip()
            }
        }
    }

    private suspend fun show(trip: RunningTrip) {
        ringingEnds = Destination(trip.a.label, trip.a.coord) to Destination(trip.b.label, trip.b.coord)
        val left = snoozesLeft()
        val minutes = prefs().snoozeMin.coerceIn(1, MAX_SNOOZE_MIN)
        mutable.value = mutable.value.copy(alarm = AlarmUi(trip.label, if (left == 0) null else minutes))
    }

    /** The trip in `trip.json` (the alarm may ring while the trip card has not read it yet). */
    private suspend fun tripOnFile(): RunningTrip? {
        val answer = (ops.storeTripLoad(DirRequest(dir)) as? Outcome.Ok)?.value ?: return null
        val summary =
            answer.trip?.let {
                dev.rosty.stops.home
                    .tripSummary(it, answer.label)
            } ?: return null
        val a = summary.a ?: return null
        val b = summary.b ?: return null
        return RunningTrip(summary.label, a, b)
    }

    /** The snoozes the trip has left (Rust caps them); unknown counts as some left. */
    private suspend fun snoozesLeft(): Int? {
        val json = (ops.storeTripLoad(DirRequest(dir)) as? Outcome.Ok)?.value?.trip ?: return null
        val handle = (ActiveTripHandle.restore(json, ops = ops) as? Outcome.Ok)?.value ?: return null
        val left = handle.opened.snoozesLeft
        handle.close()
        return left
    }

    private suspend fun readLastTrip() {
        val value = (ops.storeRead(StoreReadRequest(HISTORY, dir)) as? Outcome.Ok)?.value?.value
        val history =
            value?.let {
                try {
                    Lenient.decodeFromJsonElement(History.serializer(), it)
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        val last =
            history?.let { (ops.historyList(HistoryListRequest(it, nowMs())) as? Outcome.Ok)?.value?.lastTrip }
        mutable.value = mutable.value.copy(lastTrip = last?.let { LastTripUi(it) })
    }

    private fun ends(): Pair<Destination, Destination>? {
        val record =
            mutable.value.lastTrip
                ?.row
                ?.record ?: return null
        val a = record.a ?: return null
        val b = record.b ?: return null
        return Destination(a.label, a.coord) to Destination(b.label, b.coord)
    }

    private suspend fun shareLastTrip() {
        val record =
            mutable.value.lastTrip
                ?.row
                ?.record ?: return
        val answer = ops.historyText(HistoryTextRequest(record = record, tzOffsetS = tzOffsetS()))
        (answer as? Outcome.Ok)?.value?.share?.let { emit(AlarmEffect.Share(it)) }
    }

    private companion object {
        const val HISTORY = "history"
        const val MS_PER_MIN = 60_000L

        /** The GPUI snooze label clamps the length to 1..15 min. */
        const val MAX_SNOOZE_MIN = 15

        val Lenient = Json { ignoreUnknownKeys = true }
    }
}
