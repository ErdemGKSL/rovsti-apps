package dev.rosty.stops.trip

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.headless.Side
import dev.rosty.core.model.headless.TripState
import dev.rosty.core.model.phone.DirRequest
import dev.rosty.core.model.stops.AlarmPlan
import dev.rosty.core.model.stops.AlarmPlanRequest
import dev.rosty.core.model.stops.ArrivalClockRequest
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.CoordFormatRequest
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.Endpoint
import dev.rosty.core.model.stops.Ends
import dev.rosty.core.model.stops.FormatDistanceRequest
import dev.rosty.core.model.stops.GtfsLineChoice
import dev.rosty.core.model.stops.GtfsLinesRequest
import dev.rosty.core.model.stops.NoticePrefs
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.core.model.stops.StoreTripEndRequest
import dev.rosty.core.model.stops.TripRecord
import dev.rosty.core.ops.ActiveTripHandle
import dev.rosty.core.ops.StopsOps
import dev.rosty.design.text.UiText
import dev.rosty.platform.location.StartResult
import dev.rosty.platform.location.TripRepository
import dev.rosty.platform.location.TripSpec
import dev.rosty.platform.location.TripStatus
import dev.rosty.stops.R
import dev.rosty.stops.home.tripSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** The trip in progress as the live card names it (`trip.json`). */
@Immutable
data class RunningTrip(
    val label: String,
    val a: Endpoint,
    val b: Endpoint,
    /** The transit line chosen for the trip (drawn on the map); known only to the app that started it. */
    val pattern: Int? = null,
) {
    /** The trip as the pick's two ends, the line on the end it was chosen for (B, as the trip UI offers it). */
    fun ends(): Ends = Ends(Destination(a.label, a.coord), Destination(b.label, b.coord, pattern = pattern))

    /** The middle of the gate: where the bearing points. */
    val center: Coord get() = Coord((a.coord.lat + b.coord.lat) / 2.0, (a.coord.lon + b.coord.lon) / 2.0)
}

/** The last fix the service reported, with its texts made (no formatting in composition). */
@Immutable
data class LiveTrip(
    val state: TripState,
    /** "420 m" to the line. */
    val distance: String,
    val side: Side?,
    val inside: Boolean,
    /** "1.2 km" to the circle, when outside it. */
    val circle: String?,
    /** The service's notification text ("420 m to Kadıköy, about 3 min"). */
    val text: String,
    val nextFixMs: Long,
    /** S41: minutes to the line, when the service has an estimate. */
    val etaMin: Int? = null,
    /** S41: the local clock time of [etaMin] from now ("18:42"). */
    val arrive: String? = null,
)

/** Why the trip is not tracking as it should; each is a row with the next action (ARCH 7.5). */
enum class TripProblem {
    /** Android refused the start, or the start failed: nothing runs and no trip is kept. */
    CouldNotStart,

    /** AD-SA8: Android refused a background restart; the trip is kept and resumes when Stops is open. */
    Restart,

    /** Android would not keep the service in the foreground. */
    Foreground,

    /** The service lost the location grant: the trip stays, the row asks for it again. */
    Permission,
}

@Immutable
data class TripUiState(
    val loading: Boolean = true,
    val trip: RunningTrip? = null,
    val live: LiveTrip? = null,
    /** S9: "1.2 km from you, NE" towards the gate, when the position is known. */
    val fromYou: String? = null,
    val busy: Boolean = false,
    val problem: TripProblem? = null,
)

sealed interface TripEffect {
    data class Toast(
        val text: UiText,
    ) : TripEffect

    data class Copy(
        val text: String,
    ) : TripEffect

    data class Share(
        val text: String,
    ) : TripEffect

    /** The trip started: the map follows it and the pick is cleared. */
    data class Started(
        val trip: RunningTrip,
    ) : TripEffect

    /** The user stopped it: its ends go back on the map ("Trip stopped"). */
    data class Stopped(
        val a: Destination,
        val b: Destination,
    ) : TripEffect

    /** The service rang: the alarm screen (E7.6) takes over. */
    data class Ring(
        val trip: RunningTrip,
    ) : TripEffect

    /** The trip ended elsewhere (dismissed from the alarm screen or the notification); [record] when it was kept. */
    data class Over(
        val record: TripRecord?,
    ) : TripEffect
}

sealed interface TripAction {
    /** Start: [TripSheet] asks for the preflight first, so this runs only once nothing blocks. */
    data class Start(
        val a: Destination,
        val b: Destination,
    ) : TripAction

    data object Stop : TripAction

    /** S20. */
    data object CopyStatus : TripAction

    data object ShareStatus : TripAction

    /** AD-SA8: start the service again for the kept trip (Stops came to the front, or the row was tapped). */
    data object Resume : TripAction

    /** Read `trip.json` again (the alarm screen or the notification may have ended it). */
    data object Refresh : TripAction
}

/**
 * The per-trip "Text … when arriving" toggle (the arriving-soon message over WhatsApp) belongs to the `:wa` work, not
 * to this story: the start asks this hook for the notice and the card shows its row. The stub answers no notice and
 * no row.
 */
interface ArrivingSoonHook {
    /** The arriving-soon notice of a trip from [a] to [b], or null for none. */
    suspend fun noticeFor(
        a: Destination,
        b: Destination,
    ): NoticePrefs? = null

    /** Whether the card shows the per-trip toggle row. */
    val showsRow: Boolean get() = false

    companion object {
        /** No arriving-soon message (until the `:wa` story fills it in). */
        val None: ArrivingSoonHook = object : ArrivingSoonHook {}
    }
}

/**
 * The trip (E7.5): start and stop, the live card fed by the service's `trip_status` events, S9 and S20, and the rows
 * of a trip that cannot track (ARCH 7.5, AD-SA8). Rust does the work: `stops.trip_start` writes `trip.json` (with
 * [dir]), `stops.alarm_plan` makes the alarm the service rings, `stops.store_trip_load` and `stops.store_trip_end`
 * read and end the trip, `stops.trip_status_share` writes S20, `stops.coord_format` writes S9 and
 * `stops.format_distance` the distances. [waitingText] is the first notification line in the user's language.
 */
class TripViewModel(
    private val ops: StopsOps,
    private val trips: TripRepository,
    private val prefs: suspend () -> StopsPrefs,
    private val dir: String,
    private val here: suspend () -> Coord?,
    private val nowMs: () -> Long,
    private val waitingText: String,
    private val tzOffsetS: () -> Int = {
        java.util.TimeZone
            .getDefault()
            .getOffset(nowMs()) / MS_PER_S
    },
    private val arrivingSoon: ArrivingSoonHook = ArrivingSoonHook.None,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val work: CoroutineScope = scope ?: viewModelScope
    private val mutable = MutableStateFlow(TripUiState())
    val state: StateFlow<TripUiState> = mutable.asStateFlow()

    private val effectChannel = Channel<TripEffect>(Channel.BUFFERED)
    val effects: Flow<TripEffect> = effectChannel.receiveAsFlow()

    /** The raw `trip.json` of [TripUiState.trip], for the share text and a resume. */
    private var tripJson: JsonElement? = null

    init {
        work.launch { reload() }
        work.launch { trips.status.collect(::onStatus) }
    }

    fun onAction(action: TripAction) {
        when (action) {
            is TripAction.Start -> work.launch { start(action.a, action.b) }
            TripAction.Stop -> work.launch { stop() }
            TripAction.CopyStatus -> work.launch { statusText()?.let { emit(TripEffect.Copy(it)) } }
            TripAction.ShareStatus -> work.launch { statusText()?.let { emit(TripEffect.Share(it)) } }
            TripAction.Resume -> work.launch { resume() }
            TripAction.Refresh -> work.launch { reload() }
        }
    }

    /**
     * The lines that call at a transit stop picked as an end (`stops.gtfs_lines`, the "line-aware" part of E7.5 that
     * is left after AD-ST6): choosing one puts its pattern on the point and the map draws the line.
     */
    suspend fun lines(dest: Destination): List<GtfsLineChoice> {
        val stop = dest.gtfsStop ?: return emptyList()
        return (ops.gtfsLines(GtfsLinesRequest(stopId = stop)) as? Outcome.Ok)?.value?.lines.orEmpty()
    }

    private fun emit(effect: TripEffect) {
        effectChannel.trySend(effect)
    }

    private suspend fun reload() {
        val answer = (ops.storeTripLoad(DirRequest(dir)) as? Outcome.Ok)?.value
        val json = answer?.trip
        tripJson = json
        val summary = json?.let { tripSummary(it, answer.label) }
        val trip =
            if (summary?.a != null && summary.b != null) {
                val kept =
                    mutable.value.trip
                        ?.takeIf { it.a == summary.a && it.b == summary.b }
                        ?.pattern
                RunningTrip(summary.label, summary.a, summary.b, kept)
            } else {
                null
            }
        val was = mutable.value.trip
        mutable.value =
            mutable.value.copy(
                loading = false,
                trip = trip,
                live = if (trip == null) null else mutable.value.live,
                problem =
                    if (trip == null &&
                        mutable.value.problem != TripProblem.CouldNotStart
                    ) {
                        null
                    } else {
                        mutable.value.problem
                    },
            )
        if (was != null && trip == null) emit(TripEffect.Over(null))
        if (trip != null) refreshBearing(trip)
    }

    private suspend fun refreshBearing(trip: RunningTrip) {
        val position = here() ?: return
        val answer = (ops.coordFormat(CoordFormatRequest(trip.center, here = position)) as? Outcome.Ok)?.value
        mutable.value = mutable.value.copy(fromYou = answer?.fromYou)
    }

    private suspend fun start(
        a: Destination,
        b: Destination,
    ) {
        val now = mutable.value
        if (now.busy) return
        if (now.trip != null) {
            emit(TripEffect.Toast(UiText.Res(R.string.trip_already_running)))
            return
        }
        mutable.value = now.copy(busy = true, problem = null)
        try {
            val plan = (ops.alarmPlan(AlarmPlanRequest(prefs())) as? Outcome.Ok)?.value?.plan
            val started = nowMs()
            val opened =
                ActiveTripHandle.start(
                    id = started,
                    a = Endpoint(a.label, a.coord),
                    b = Endpoint(b.label, b.coord),
                    startedMs = started,
                    notice = arrivingSoon.noticeFor(a, b),
                    dir = dir,
                    ops = ops,
                )
            val handle =
                when (opened) {
                    is Outcome.Err -> {
                        emit(TripEffect.Toast(startRefusal(opened.error)))
                        return
                    }

                    is Outcome.Ok -> {
                        opened.value
                    }
                }
            val view = handle.opened
            handle.close()
            val spec =
                TripSpec(
                    dir = dir,
                    title = view.label,
                    text = waitingText,
                    nextFixMs = FIRST_FIX_MS,
                    highAccuracy = true,
                    alarm = plan?.let { Json.encodeToString(AlarmPlan.serializer(), it) },
                )
            if (trips.start(spec) == StartResult.Started) {
                val trip = RunningTrip(view.label, view.a, view.b, b.pattern ?: a.pattern)
                mutable.value = mutable.value.copy(trip = trip, live = null, problem = null)
                reloadJson()
                emit(TripEffect.Started(trip))
                refreshBearing(trip)
            } else {
                // A refused start leaves nothing behind (GPUI `start_trip` removes the new trip file).
                forget()
                mutable.value = mutable.value.copy(problem = TripProblem.CouldNotStart)
                emit(TripEffect.Toast(UiText.Res(R.string.trip_could_not_start)))
            }
        } finally {
            mutable.value = mutable.value.copy(busy = false)
        }
    }

    private suspend fun reloadJson() {
        tripJson = (ops.storeTripLoad(DirRequest(dir)) as? Outcome.Ok)?.value?.trip
    }

    /** A refused start leaves no trip behind: `stops.store_trip_clear` removes the new trip file, no history record. */
    private suspend fun forget() {
        ops.storeTripClear(DirRequest(dir))
    }

    private suspend fun stop() {
        val trip = mutable.value.trip ?: return
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        trips.stop()
        ops.storeTripEnd(StoreTripEndRequest("cancelled", nowMs(), dir))
        tripJson = null
        mutable.value = TripUiState(loading = false)
        emit(TripEffect.Stopped(Destination(trip.a.label, trip.a.coord), Destination(trip.b.label, trip.b.coord)))
    }

    /** AD-SA8: the kept trip's service starts again (`backend::resume_trip`). */
    private suspend fun resume() {
        val trip = mutable.value.trip ?: return
        val plan = (ops.alarmPlan(AlarmPlanRequest(prefs())) as? Outcome.Ok)?.value?.plan
        val spec =
            TripSpec(
                dir = dir,
                title = trip.label,
                text = waitingText,
                nextFixMs = FIRST_FIX_MS,
                highAccuracy = true,
                alarm = plan?.let { Json.encodeToString(AlarmPlan.serializer(), it) },
            )
        val started = trips.start(spec) == StartResult.Started
        mutable.value = mutable.value.copy(problem = if (started) null else TripProblem.Restart)
    }

    private suspend fun onStatus(status: TripStatus) {
        when (status) {
            is TripStatus.Fixed -> {
                val out = status.answer
                val trip = mutable.value.trip
                val live =
                    LiveTrip(
                        state = out.state,
                        distance = distance(out.distanceM),
                        side = out.side,
                        inside = out.inside,
                        circle = out.circleM?.takeIf { !out.inside }?.let { distance(it) },
                        text = out.text,
                        nextFixMs = out.nextFixMs,
                        etaMin = out.etaMin,
                        arrive = out.etaMin?.let { clock(it) },
                    )
                val problem =
                    mutable.value.problem.takeUnless {
                        it == TripProblem.Restart ||
                            it == TripProblem.Permission
                    }
                mutable.value = mutable.value.copy(live = live, problem = problem)
                if (trip != null) {
                    refreshBearing(trip)
                    if (out.ring) emit(TripEffect.Ring(trip))
                }
            }

            TripStatus.Searching -> {
                Unit
            }

            TripStatus.Stopped, TripStatus.Ended -> {
                reload()
            }

            is TripStatus.Error -> {
                problemOf(status.code)?.let { mutable.value = mutable.value.copy(problem = it) }
            }

            is TripStatus.Other -> {
                Unit
            }
        }
    }

    /** S41: `stops.arrival_clock` for [etaMin] minutes from now. */
    private suspend fun clock(etaMin: Int): String? =
        (ops.arrivalClock(ArrivalClockRequest(nowMs(), etaMin, tzOffsetS())) as? Outcome.Ok)?.value?.clock

    private suspend fun distance(metres: Double): String =
        (ops.formatDistance(FormatDistanceRequest(metres)) as? Outcome.Ok)?.value?.text ?: "${metres.toInt()} m"

    /** S20: the status text from Rust for the kept trip (opened for the call, then let go). */
    private suspend fun statusText(): String? {
        val json = tripJson ?: return null
        val handle = (ActiveTripHandle.restore(json, ops = ops) as? Outcome.Ok)?.value
        if (handle == null) {
            emit(TripEffect.Toast(UiText.Res(R.string.trip_status_failed)))
            return null
        }
        val live = mutable.value.live
        val text =
            handle.statusShare(
                lineM = null,
                etaMin = live?.etaMin,
                here = here(),
                crossed = live != null && live.state != TripState.Tracking,
                arrive = live?.arrive,
            )
        handle.close()
        return (text as? Outcome.Ok)?.value?.text
    }

    companion object {
        /** The first fix comes quickly so the card fills (GPUI `start_spec`). */
        const val FIRST_FIX_MS = 5_000L

        private const val MS_PER_S = 1000

        /**
         * The row of a `trip_status` error code (pure); `full_screen` has none of its own, the card's full-screen row
         * already says it.
         */
        fun problemOf(code: String): TripProblem? =
            when (code) {
                "restart" -> TripProblem.Restart
                "permission" -> TripProblem.Permission
                "full_screen" -> null
                else -> TripProblem.Foreground
            }

        /** The words of a refused `stops.trip_start` (a gate that is too short or too long, or the same point twice). */
        fun startRefusal(error: RustError): UiText =
            UiText.Res(if (error is RustError.Invalid) R.string.trip_error_gate else R.string.trip_could_not_start)
    }
}
