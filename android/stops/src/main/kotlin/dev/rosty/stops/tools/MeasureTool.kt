package dev.rosty.stops.tools

import androidx.compose.runtime.Immutable
import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.FormatDistanceRequest
import dev.rosty.core.model.stops.MeasureGate
import dev.rosty.core.model.stops.MeasureRequest
import dev.rosty.core.ops.StopsOps
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What `stops.measure` answered for [points], with the distances already in words (`stops.format_distance`). */
@Immutable
data class MeasureResult(
    val points: ImmutableList<Coord>,
    val total: String,
    val legs: ImmutableList<String>,
    /** The first and last point as a gate ("Save as stop"), when they make one. */
    val gate: MeasureGate?,
    val gpx: String,
    val fileName: String?,
)

/** The measure card (S8, S26). */
@Immutable
data class MeasureUiState(
    val on: Boolean = false,
    val points: ImmutableList<Coord> = persistentListOf(),
    /** The answer for [points]; null below two points and while the answer is on its way. */
    val result: MeasureResult? = null,
    /** The core's cap on points, once known. */
    val maxPoints: Int? = null,
)

/** What a tap on the map did while measuring. */
enum class MeasureTap { Added, Full, Off }

/**
 * The measure tool (S8, S26): the points the user taps, in order, and what `stops.measure` says about them (legs,
 * total, GPX, the gate of the first and last point). The core keeps the cap: a point past it is refused, and the
 * answer's `accepted` trims what was sent. [today] names the GPX file (`2026-09-21`).
 *
 * Call from the main thread; every Rust call runs on [scope] and only the answer for the current points is kept.
 */
class MeasureTool(
    private val ops: StopsOps,
    private val scope: CoroutineScope,
    private val gpxName: (String) -> String,
    private val today: () -> String,
) {
    private val ui = MutableStateFlow(MeasureUiState())
    val state: StateFlow<MeasureUiState> = ui.asStateFlow()

    val on: Boolean get() = ui.value.on

    private var points: List<Coord> = emptyList()
    private var job: Job? = null

    /** Turns the tool on, starting at [from] when given (S34 "Measure from here"). */
    fun start(from: Coord? = null) {
        set(listOfNotNull(from), on = true)
    }

    /** Turns the tool off and forgets its points. */
    fun done() {
        job?.cancel()
        points = emptyList()
        ui.value = MeasureUiState(maxPoints = ui.value.maxPoints)
    }

    fun tap(at: Coord): MeasureTap {
        if (!on) return MeasureTap.Off
        val cap = ui.value.maxPoints
        if (cap != null && points.size >= cap) return MeasureTap.Full
        set(points + at)
        return MeasureTap.Added
    }

    /** Takes the last point away. */
    fun undo() {
        if (points.isNotEmpty()) set(points.dropLast(1))
    }

    /** Removes every point and answers them, for Undo. */
    fun clear(): List<Coord> {
        val old = points
        set(emptyList())
        return old
    }

    /** Puts [old] back (the Undo of [clear]). */
    fun restore(old: List<Coord>) {
        if (on) set(old)
    }

    private fun set(
        next: List<Coord>,
        on: Boolean = this.on,
    ) {
        points = next
        val shown = next.toImmutableList()
        ui.update { it.copy(on = on, points = shown, result = it.result?.takeIf { r -> r.points == shown }) }
        job?.cancel()
        if (next.size < 2) return
        job =
            scope.launch {
                val stamp = today()
                val answer =
                    (ops.measure(MeasureRequest(next, name = gpxName(stamp), today = stamp)) as? Outcome.Ok)?.value
                        ?: return@launch
                if (answer.accepted < next.size) {
                    // Past the core's cap: keep what it kept.
                    points = next.take(answer.accepted)
                    ui.update { it.copy(maxPoints = answer.maxPoints) }
                    set(points)
                    return@launch
                }
                val result =
                    MeasureResult(
                        points = shown,
                        total = distance(answer.totalM),
                        legs = answer.legsM.map { distance(it) }.toImmutableList(),
                        gate = answer.gate,
                        gpx = answer.gpx,
                        fileName = answer.fileName,
                    )
                if (points == next) ui.update { it.copy(result = result, maxPoints = answer.maxPoints) }
            }
    }

    private suspend fun distance(metres: Double): String =
        (ops.formatDistance(FormatDistanceRequest(metres)) as? Outcome.Ok)?.value?.text ?: ""
}

/** The clipboard text of a measurement (GPUI `measure_copy_text`): the summary, then one numbered line per leg. */
fun measureCopyText(
    summary: String,
    legs: List<String>,
    leg: (Int, String) -> String,
): String =
    buildString {
        append(summary)
        legs.forEachIndexed { i, text -> append('\n').append(leg(i + 1, text)) }
    }
