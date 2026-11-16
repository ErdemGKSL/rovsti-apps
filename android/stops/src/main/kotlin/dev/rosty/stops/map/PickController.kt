package dev.rosty.stops.map

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.CoordFormatAnswer
import dev.rosty.core.model.stops.CoordFormatRequest
import dev.rosty.core.model.stops.CrossingReadAnswer
import dev.rosty.core.model.stops.CrossingReadRequest
import dev.rosty.core.model.stops.CrossingValidateAnswer
import dev.rosty.core.model.stops.CrossingValidateRequest
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.Ends
import dev.rosty.core.model.stops.GtfsBbox
import dev.rosty.core.model.stops.GtfsViewAnswer
import dev.rosty.core.model.stops.GtfsViewRequest
import dev.rosty.core.model.stops.PickEvent
import dev.rosty.core.model.stops.PickLabelsAnswer
import dev.rosty.core.model.stops.PickLabelsRequest
import dev.rosty.core.model.stops.PickReplayAnswer
import dev.rosty.core.model.stops.PickReplayRequest
import dev.rosty.core.model.stops.PointerSample
import dev.rosty.core.model.stops.SaveOfferAnswer
import dev.rosty.core.model.stops.SaveOfferRequest
import dev.rosty.core.model.stops.SavedStops
import dev.rosty.core.model.stops.Slot
import dev.rosty.core.model.stops.SnapAnswer
import dev.rosty.core.model.stops.SnapRequest
import dev.rosty.core.ops.StopsOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot

/** The Rust calls picking needs; [StopsPickOps] is the real one, tests may script their own. */
interface PickOps {
    suspend fun replay(events: List<PickEvent>): Outcome<PickReplayAnswer>

    suspend fun labels(ends: Ends): Outcome<PickLabelsAnswer>

    suspend fun snap(request: SnapRequest): Outcome<SnapAnswer>

    suspend fun validate(
        a: Coord,
        b: Coord,
    ): Outcome<CrossingValidateAnswer>

    suspend fun read(request: CrossingReadRequest): Outcome<CrossingReadAnswer>

    suspend fun formats(request: CoordFormatRequest): Outcome<CoordFormatAnswer>

    suspend fun saveOffer(request: SaveOfferRequest): Outcome<SaveOfferAnswer>

    suspend fun gtfsView(request: GtfsViewRequest): Outcome<GtfsViewAnswer>
}

/** [PickOps] over the Rust core (`stops.pick_*`, `stops.snap`, `stops.crossing_*`, `stops.coord_format`). */
class StopsPickOps(
    private val ops: StopsOps,
) : PickOps {
    override suspend fun replay(events: List<PickEvent>) = ops.pickReplay(PickReplayRequest(events))

    override suspend fun labels(ends: Ends) = ops.pickLabels(PickLabelsRequest(ends))

    override suspend fun snap(request: SnapRequest) = ops.snap(request)

    override suspend fun validate(
        a: Coord,
        b: Coord,
    ) = ops.crossingValidate(CrossingValidateRequest(a, b))

    override suspend fun read(request: CrossingReadRequest) = ops.crossingRead(request)

    override suspend fun formats(request: CoordFormatRequest) = ops.coordFormat(request)

    override suspend fun saveOffer(request: SaveOfferRequest) = ops.pickSaveOffer(request)

    override suspend fun gtfsView(request: GtfsViewRequest) = ops.gtfsView(request)
}

/** What the screen shows about the pick, straight from Rust (`pick_replay` and `pick_labels`). */
@Immutable
data class PickUiState(
    val ends: Ends = Ends(),
    /** `set_a`, `set_b`, `moving`, `ready` or `invalid`. */
    val hintKind: String = "set_a",
    /** The line that says what to do next, or "A-B 850 m" when both ends are set. */
    val hintText: String = "",
    /** "Point A" or the name of the stop A sits on. */
    val labelA: String = "",
    val labelB: String = "",
    /** "A-B 850 m", when both ends are set. */
    val pairLabel: String? = null,
    val canStart: Boolean = false,
    /** The end the next tap will move ("Move" in the pin menu). */
    val moving: Slot? = null,
)

/** What picking asks of the screen. Every method has a default. */
interface PickListener {
    /** A tap on a pin: open its menu ([PickController.pinMenuModel], [PickController.pinAnchor]). */
    fun onPinTap(slot: Slot) {}

    /** A tap on the marker of saved stop [id] (S40): open the stop. */
    fun onSavedStopTap(id: Long) {}

    /** A tap on the position dot: open "my location" (S25). */
    fun onPositionTap() {}

    /** A third tap with both ends set: show the hint "Drag a pin to adjust it, or tap Clear." */
    fun onThirdTap() {}

    /** A dropped pin snapped to a stop it was not on before: a selection haptic. */
    fun onSnapped() {}

    /** A Rust call failed; the pick is unchanged. */
    fun onError(error: RustError) {}
}

/**
 * Picking A and B on the map (E7.4, AD-ST9). The rules live in Rust: every gesture is an event appended to a log and
 * `stops.pick_replay` answers the state (first tap A, second B, a third tap is "full", Move, Swap, Clear, drag);
 * `stops.snap` decides whether a tap or a dropped pin lands on a transit stop; the labels and the circle come from
 * `stops.pick_labels` and `stops.crossing_*`. This class is the [MapGesturePolicy] of the map and the glue: it resolves
 * what is under the finger with [RadarHitTest], queues the Rust calls (never on the main thread, one at a time, in
 * order) and writes the answers to [state] and to the pins and the circle of [radar].
 *
 * A dragged pin follows the finger without a Rust call per move ([RadarHolder.dragTo], read in draw); the circle
 * follows a call or two behind. A tap never moves the view. Not a ViewModel: the screen's container owns it with
 * [scope], and sets what else is on the map through [radar] (`radar.update { it.copy(position = ...) }`).
 *
 * It also keeps the transit stops of [radar] in step with the camera (`stops.gtfs_view`): when the view settles, and
 * [transitDebounceMs] later, the stops inside it (at most [RadarStops.CAP], nearest the middle first) and the line of
 * the pattern a picked stop carries are asked for; a view that changes again first cancels the request. Without an
 * imported feed the stops are left as they are. [close] ends that.
 *
 * Call from the main thread.
 */
@Stable
class PickController(
    private val ops: PickOps,
    private val scope: CoroutineScope,
    private val camera: MapCamera,
    private val listener: PickListener = object : PickListener {},
    /** The Stops data directory, null for the app's own (`gtfs.json` is read from it). */
    private val dataDir: String? = null,
    private val transitDebounceMs: Long = TRANSIT_DEBOUNCE_MS,
) : MapGesturePolicy {
    /** The layers state the map's overlay draws. */
    val radar = RadarHolder()

    private val ui = MutableStateFlow(PickUiState())
    val state: StateFlow<PickUiState> get() = ui

    /**
     * Taps drop pins and pins can be grabbed. False while a trip runs, the alarm rings or the measure tool is on
     * (`pins_grabbable`), and while a sheet covers the map: the map then only moves.
     */
    var enabled = true

    /** The saved stops, for "Save as stop" in the pin menu; null hides the row. */
    var savedStops: SavedStops? = null

    /** Where the map is in its window, for [pinAnchor]. Set by [pickAnchorOrigin]. */
    var mapOriginInWindow: Offset = Offset.Zero

    private val events = ArrayList<PickEvent>()
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val live = Channel<LiveCrossing>(Channel.CONFLATED)
    private var settledCrossing: RadarCrossing? = null
    private var dragGeneration = 0

    /** Work queued or running. */
    var pending = 0
        private set
    private var liveSent = 0
    private var liveDone = 0

    private val transitWake = Channel<Unit>(Channel.CONFLATED)
    private var transitSent = 0
    private var transitDone = 0
    private var transitShown: GtfsViewRequest? = null
    private val cameraListener: () -> Unit = { if (!camera.moving.value) wakeTransit() }

    /** Requests to `stops.gtfs_view` so far (tests count them). */
    var transitRequests = 0
        private set

    /** Every gesture so far has been answered and the circle and the stops are up to date (tests wait for it). */
    val idle: Boolean get() = pending == 0 && liveDone == liveSent && transitDone == transitSent

    private class LiveCrossing(
        val a: Coord,
        val b: Coord,
        val generation: Int,
        val sequence: Int,
    )

    init {
        scope.launch {
            for (work in queue) {
                try {
                    work()
                } finally {
                    pending--
                }
            }
        }
        camera.addListener(cameraListener)
        scope.launch {
            transitWake.receiveAsFlow().collectLatest {
                val sequence = transitSent
                // The view changed again before this ran out: collectLatest starts over and this request is dropped.
                delay(transitDebounceMs)
                loadTransit()
                transitDone = sequence
            }
        }
        wakeTransit()
        scope.launch {
            for (request in live) {
                val crossing = crossing(request.a, request.b)
                // Only while that drag is still on: a late answer must not overwrite the settled circle.
                if (request.generation == dragGeneration && radar.dragSlot != null) {
                    radar.update { it.copy(crossing = crossing) }
                }
                liveDone = request.sequence
            }
        }
    }

    private fun enqueue(work: suspend () -> Unit) {
        pending++
        queue.trySend(work)
    }

    // ---- transit stops in view ----

    /** Stops following the camera. The controller's scope ends the rest. */
    fun close() {
        camera.removeListener(cameraListener)
    }

    /** Asks for the stops in view again: a feed was imported or removed. */
    fun refreshTransit() {
        transitShown = null
        wakeTransit()
    }

    private fun wakeTransit() {
        transitSent++
        transitWake.trySend(Unit)
    }

    /** The box the map shows plus the margin that keeps dots from popping at the edge (8 dp, as in GPUI). */
    private fun viewBox(): GtfsBbox? {
        val w = camera.widthPx
        val h = camera.heightPx
        if (w <= 0 || h <= 0) return null
        val margin = TRANSIT_MARGIN_DP * camera.density
        val south = camera.latAt(h + margin)
        val north = camera.latAt(-margin)
        // A view as wide as the world shows every longitude; otherwise west may be greater than east (antimeridian).
        if (camera.worldPx <= w + 2 * margin) return GtfsBbox(south, -180.0, north, 180.0)
        return GtfsBbox(south, camera.lonAt(-margin), north, camera.lonAt(w + margin))
    }

    private suspend fun loadTransit() {
        val bbox = viewBox() ?: return
        val ends = ui.value.ends
        val request = GtfsViewRequest(bbox, RadarStops.CAP, pattern = ends.a?.pattern ?: ends.b?.pattern, dir = dataDir)
        if (request == transitShown) return
        transitRequests++
        val answer = (ops.gtfsView(request) as? Outcome.Ok)?.value ?: return
        transitShown = request
        // No feed: whatever stops the screen put there itself stay.
        if (!answer.hasFeed) return
        val stops =
            RadarStops(
                DoubleArray(2 * answer.stops.size) { i ->
                    answer.stops[i / 2].coord.let {
                        if (i % 2 ==
                            0
                        ) {
                            it.lat
                        } else {
                            it.lon
                        }
                    }
                },
                answer.stops.map { it.id },
                answer.stops.map { it.name },
            )
        val line = answer.line?.let(::flatten) ?: RadarState.NO_POINTS
        radar.update { it.copy(stops = stops, line = line) }
    }

    // ---- the map's gestures ----

    override fun onTap(
        x: Float,
        y: Float,
        lat: Double,
        lon: Double,
    ) {
        if (!enabled) return
        val now = radar.state
        if (RadarHitTest.hitsPosition(camera, now.position, x, y)) {
            listener.onPositionTap()
            return
        }
        val saved = RadarHitTest.savedAt(camera, now, x, y)
        if (saved >= 0) {
            listener.onSavedStopTap(saved)
            return
        }
        // What is under the finger is judged now, while the camera is where the user saw it.
        val stops = now.stops
        val near = RadarHitTest.stopAt(camera, stops, x, y, SNAP_SEARCH_DP)
        val nearDp = if (near < 0) null else RadarHitTest.stopDistancePx(camera, stops, near, x, y) / camera.density
        enqueue {
            var dest = Destination(label = "", coord = Coord(lat, lon))
            if (near >= 0 && nearDp != null) {
                val answer = ops.snap(SnapRequest(candidatePx = nearDp.toDouble()))
                if (answer is Outcome.Ok && answer.value.tapSnaps) dest = stopDestination(stops, near)
            }
            val replayed = submit(PickEvent.Tap(dest))
            if (replayed?.outcomes?.lastOrNull() == OUTCOME_FULL) listener.onThirdTap()
        }
    }

    override fun claimDrag(
        x: Float,
        y: Float,
    ): MapDragClaim? {
        if (!enabled) return null
        val slot = RadarHitTest.pinAt(camera, radar.state, x, y) ?: return null
        val pin = radar.state.pin(slot) ?: return null
        return PinClaim(slot, x - camera.screenX(pin.lon), y - camera.screenY(pin.lat), x, y)
    }

    /** A pin in the finger. The offset keeps the pin from jumping under the finger when it is grabbed off-centre. */
    private inner class PinClaim(
        private val slot: Slot,
        private val offsetX: Float,
        private val offsetY: Float,
        private val startX: Float,
        private val startY: Float,
    ) : MapDragClaim {
        private val generation = ++dragGeneration

        // The last samples of the finger, for Rust's velocity tracker (16, like its ring).
        private val times = LongArray(SAMPLES)
        private val xs = FloatArray(SAMPLES)
        private val ys = FloatArray(SAMPLES)
        private var count = 0

        private fun sample(
            x: Float,
            y: Float,
            uptimeMs: Long,
        ) {
            val i = count % SAMPLES
            times[i] = uptimeMs
            xs[i] = x
            ys[i] = y
            count++
        }

        override fun onDrag(
            x: Float,
            y: Float,
            uptimeMs: Long,
        ) {
            sample(x, y, uptimeMs)
            val lat = camera.latAt(y - offsetY)
            val lon = camera.lonAt(x - offsetX)
            radar.dragTo(slot, lat, lon)
            val other = ui.value.ends.let { if (slot == Slot.A) it.b else it.a } ?: return
            val here = Coord(lat, lon)
            val a = if (slot == Slot.A) here else other.coord
            val b = if (slot == Slot.A) other.coord else here
            live.trySend(LiveCrossing(a, b, generation, ++liveSent))
        }

        override fun onDrop(
            x: Float,
            y: Float,
            velocityX: Float,
            velocityY: Float,
            uptimeMs: Long,
        ) {
            sample(x, y, uptimeMs)
            val px = x - offsetX
            val py = y - offsetY
            val lat = camera.latAt(py)
            val lon = camera.lonAt(px)
            radar.dragTo(slot, lat, lon)
            val density = camera.density
            val stops = radar.state.stops
            val near = RadarHitTest.stopAt(camera, stops, px, py, SNAP_SEARCH_DP)
            val nearDp = if (near < 0) null else RadarHitTest.stopDistancePx(camera, stops, near, px, py) / density
            val origin = ui.value.ends.let { if (slot == Slot.A) it.a else it.b }
            val travelDp = hypot(x - startX, y - startY) / density
            val samples = samples(density)
            enqueue {
                var dest = Destination(label = "", coord = Coord(lat, lon))
                if (near >= 0 && nearDp != null) {
                    val request =
                        SnapRequest(
                            samples = samples,
                            nowMs = samples.last().tMs,
                            candidatePx = nearDp.toDouble(),
                            travelPx = travelDp.toDouble(),
                            startedOnCandidate = origin?.gtfsStop != null && origin.gtfsStop == stops.ids[near],
                        )
                    val answer = ops.snap(request)
                    val snaps = answer is Outcome.Ok && answer.value.decision == DECISION_SNAP
                    if (snaps) dest = stopDestination(stops, near)
                }
                submit(PickEvent.DragStart(slot), apply = false)
                submit(PickEvent.DragEnd(slot, dest))
                if (generation == dragGeneration) radar.endDrag()
                if (dest.gtfsStop != null && dest.gtfsStop != origin?.gtfsStop) listener.onSnapped()
            }
        }

        override fun onTap() {
            listener.onPinTap(slot)
        }

        override fun onCancel() {
            // A second finger landed: the pin goes back and the two fingers pinch.
            if (generation == dragGeneration) {
                dragGeneration++
                radar.endDrag()
                radar.update { it.copy(crossing = settledCrossing) }
            }
        }

        /** The samples in dp and in milliseconds from the first one, oldest first. */
        private fun samples(density: Float): List<PointerSample> {
            val n = minOf(count, SAMPLES)
            val first = if (count <= SAMPLES) 0 else count % SAMPLES
            val t0 = times[first]
            return List(n) {
                val i = (first + it) % SAMPLES
                PointerSample(times[i] - t0, (xs[i] / density).toDouble(), (ys[i] / density).toDouble())
            }
        }
    }

    // ---- actions of the chrome and the pin menu ----

    /** "Move": the next tap on the map moves this end. */
    fun beginMove(slot: Slot) = event(PickEvent.BeginMove(slot))

    fun cancelMove() = event(PickEvent.CancelMove)

    fun swap() = event(PickEvent.Swap)

    fun clear() = event(PickEvent.Clear)

    fun remove(slot: Slot) = event(PickEvent.Remove(slot))

    /** Puts [dest] on one end (a pick sheet row, a shared link). */
    fun place(
        slot: Slot,
        dest: Destination,
    ) = event(PickEvent.Place(slot, dest))

    /** Both ends at once (a saved stop, a recent trip). */
    fun setPair(
        a: Destination,
        b: Destination,
    ) = event(PickEvent.SetPair(a, b))

    /** The draft ends of the last session. */
    fun restore(ends: Ends) = event(PickEvent.Restore(ends))

    private fun event(event: PickEvent) = enqueue { submit(event) }

    /**
     * Appends [event] to the log and asks Rust for the state. A refused call drops the event again. With [apply]
     * false the answer is not shown (an intermediate event of a pair).
     */
    private suspend fun submit(
        event: PickEvent,
        apply: Boolean = true,
    ): PickReplayAnswer? {
        events += event
        return when (val answer = ops.replay(events.toList())) {
            is Outcome.Err -> {
                events.removeAt(events.size - 1)
                listener.onError(answer.error)
                null
            }

            is Outcome.Ok -> {
                if (apply) apply(answer.value)
                answer.value
            }
        }
    }

    private suspend fun apply(answer: PickReplayAnswer) {
        val ends = answer.ends
        // Nothing transient left: the log can start over from the ends, so it never grows without bound.
        if (answer.moving == null && answer.dragging == null && answer.menu == null) {
            events.clear()
            events += PickEvent.Restore(ends)
        }
        val labels = (ops.labels(ends) as? Outcome.Ok)?.value
        val a = ends.a
        val b = ends.b
        val crossing = if (a != null && b != null) crossing(a.coord, b.coord) else null
        settledCrossing = crossing
        val labelA = labels?.a?.label ?: labels?.emptyLabelA.orEmpty()
        val labelB = labels?.b?.label ?: labels?.emptyLabelB.orEmpty()
        ui.value =
            PickUiState(
                ends = ends,
                hintKind = answer.hint.kind,
                hintText = answer.hint.text,
                labelA = labelA,
                labelB = labelB,
                pairLabel = labels?.pair,
                canStart = answer.canStart,
                moving = answer.moving,
            )
        radar.update {
            it.copy(
                pinA = a?.let { d -> RadarPin(d.coord.lat, d.coord.lon, d.gtfsStop != null, labelA) },
                pinB = b?.let { d -> RadarPin(d.coord.lat, d.coord.lon, d.gtfsStop != null, labelB) },
                crossing = crossing,
            )
        }
        // A picked stop may carry the pattern whose line is drawn.
        wakeTransit()
    }

    /** The circle on AB from Rust, or null when A and B make no valid one (too close, too far). */
    private suspend fun crossing(
        a: Coord,
        b: Coord,
    ): RadarCrossing? {
        val valid = (ops.validate(a, b) as? Outcome.Ok)?.value ?: return null
        val request = CrossingReadRequest(a, b, at = a, outlinePoints = RadarCrossing.OUTLINE_POINTS)
        val read = (ops.read(request) as? Outcome.Ok)?.value ?: return null
        return RadarCrossing.of(a, b, valid, read)
    }

    private fun stopDestination(
        stops: RadarStops,
        index: Int,
    ) = Destination(
        label = stops.names[index],
        coord = Coord(stops.latLon[2 * index], stops.latLon[2 * index + 1]),
        gtfsStop = stops.ids[index],
        pattern = null,
    )

    // ---- the pin menu ----

    /** The window rectangle of a pin, to anchor its menu to; null when that end is not set. */
    fun pinAnchor(slot: Slot): Rect? {
        val pin = radar.state.pin(slot) ?: return null
        val r = (RadarPainter.PIN_DP + RadarPainter.PIN_RIM_DP) * camera.density
        val centre = mapOriginInWindow + Offset(camera.screenX(pin.lon), camera.screenY(pin.lat))
        return Rect(centre.x - r, centre.y - r, centre.x + r, centre.y + r)
    }

    /** What the menu of a pin shows (S1 formats, S34 distance from you), or null when that end is not set. */
    suspend fun pinMenuModel(slot: Slot): PinMenuModel? {
        val ends = ui.value.ends
        val dest = (if (slot == Slot.A) ends.a else ends.b) ?: return null
        val here = radar.state.position?.let { Coord(it.lat, it.lon) }
        val request = CoordFormatRequest(at = dest.coord, label = dest.label.ifBlank { null }, here = here)
        val formats = (ops.formats(request) as? Outcome.Ok)?.value ?: return null
        val offer = saveOffer()
        return PinMenuModel(
            slot = slot,
            title = if (slot == Slot.A) ui.value.labelA else ui.value.labelB,
            coord = dest.coord,
            formats = formats.formats,
            fromYou = formats.fromYou,
            stopId = dest.gtfsStop,
            canSave = offer?.kind == OFFER_SAVE,
            canSwap = ends.a != null && ends.b != null,
        )
    }

    /**
     * Whether the pair on the map can be saved as a stop (`stops.pick_save_offer`): `save`, `saved` with the stop
     * that has this gate, or `none` while an end is missing or the two cannot make a stop. A first run has no
     * saved file yet: that is an empty list, not "cannot save".
     */
    suspend fun saveOffer(): SaveOfferAnswer? {
        val saved = savedStops ?: SavedStops(version = 2, nextId = 1, stops = emptyList())
        return (ops.saveOffer(SaveOfferRequest(ui.value.ends, saved)) as? Outcome.Ok)?.value
    }

    companion object {
        /** `stops.pick_save_offer` kinds. */
        const val OFFER_SAVED = "saved"

        /**
         * How far around the finger a transit stop is looked for, in dp: the largest radius Rust can accept
         * (`snap::FAST_RADIUS_PX`). Whether the stop found takes the pin is Rust's decision (`stops.snap`).
         */
        const val SNAP_SEARCH_DP = 56f

        /** How long the view must rest before its transit stops are asked for. */
        const val TRANSIT_DEBOUNCE_MS = 150L
        private const val TRANSIT_MARGIN_DP = 8f
        private const val SAMPLES = 16
        private const val OUTCOME_FULL = "full"
        private const val DECISION_SNAP = "snap"
        const val OFFER_SAVE = "save"
    }
}

/** Tells [controller] where the map is in its window, so pin menus open at the pin. Put it on the map. */
fun Modifier.pickAnchorOrigin(controller: PickController): Modifier =
    onGloballyPositioned { controller.mapOriginInWindow = it.positionInWindow() }
