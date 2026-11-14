package dev.rosty.stops.map

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.CrossingReadAnswer
import dev.rosty.core.model.stops.CrossingValidateAnswer
import dev.rosty.core.model.stops.SavedStops
import dev.rosty.core.model.stops.Slot
import dev.rosty.core.model.stops.StopColour

// A top-level value, not a companion one: the companions of the classes below refer to each other.
private val NO_RADAR_POINTS = DoubleArray(0)

/**
 * Everything the radar layers show over the tiles (ARCH 8.4 step 4, E7.3), as coarse immutable state: it changes when
 * the user picks a point, a fix arrives or a list loads, never per frame. The camera turns it into pixels in the draw
 * phase ([RadarPainter]).
 *
 * Coordinate lists are flat `lat0, lon0, lat1, lon1, ...` arrays, which the draw loop walks without allocating. The
 * arrays must not be changed after they are handed over.
 */
@Immutable
class RadarState(
    val pinA: RadarPin? = null,
    val pinB: RadarPin? = null,
    /** The circle on AB, when A and B make a valid one. Without it two pins are joined by a plain warning line. */
    val crossing: RadarCrossing? = null,
    val position: RadarPosition? = null,
    /** The breadcrumb of the trip in progress, oldest first. */
    val trail: DoubleArray = NO_RADAR_POINTS,
    /** The transit stops in view. */
    val stops: RadarStops = RadarStops.None,
    /** The chosen transit line, as the coordinates of its stops in order. */
    val line: DoubleArray = NO_RADAR_POINTS,
    /** The middles of the saved stops (S40); drawn only when [showSaved]. */
    val saved: RadarSaved = RadarSaved.None,
    val showSaved: Boolean = true,
    /** The measure tool's points in tap order (S8). */
    val measure: DoubleArray = NO_RADAR_POINTS,
) {
    fun pin(slot: Slot): RadarPin? = if (slot == Slot.A) pinA else pinB

    fun copy(
        pinA: RadarPin? = this.pinA,
        pinB: RadarPin? = this.pinB,
        crossing: RadarCrossing? = this.crossing,
        position: RadarPosition? = this.position,
        trail: DoubleArray = this.trail,
        stops: RadarStops = this.stops,
        line: DoubleArray = this.line,
        saved: RadarSaved = this.saved,
        showSaved: Boolean = this.showSaved,
        measure: DoubleArray = this.measure,
    ) = RadarState(pinA, pinB, crossing, position, trail, stops, line, saved, showSaved, measure)

    companion object {
        val NO_POINTS: DoubleArray get() = NO_RADAR_POINTS
        val Empty = RadarState()
    }
}

/** One end of the gate. [snapped]: it sits on a transit stop (drawn with a ring). [caption] is the name under it. */
@Immutable
class RadarPin(
    val lat: Double,
    val lon: Double,
    val snapped: Boolean = false,
    val caption: String = "",
)

/** Where the user is, with the accuracy of the fix in metres. */
@Immutable
class RadarPosition(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
)

/**
 * The circle whose diameter is AB and its two halves, as Rust computed them (`stops.crossing_validate` and
 * `stops.crossing_read` with `outline_points`): Kotlin only projects the points.
 */
@Immutable
class RadarCrossing(
    val aLat: Double,
    val aLon: Double,
    val bLat: Double,
    val bLon: Double,
    val centreLat: Double,
    val centreLon: Double,
    val radiusM: Double,
    /** The half to the left of A to B, a closed outline. */
    val left: DoubleArray,
    val right: DoubleArray,
) {
    companion object {
        /** Points Rust is asked for per half disc (`HALF_DISC_SEGMENTS` of the GPUI map). */
        const val OUTLINE_POINTS = 48

        fun of(
            a: Coord,
            b: Coord,
            valid: CrossingValidateAnswer,
            read: CrossingReadAnswer,
        ) = RadarCrossing(
            a.lat,
            a.lon,
            b.lat,
            b.lon,
            valid.center.lat,
            valid.center.lon,
            valid.radiusM,
            flatten(read.leftDisc),
            flatten(read.rightDisc),
        )
    }
}

/** Transit stops to draw and to snap to. [ids] and [names] run parallel to the coordinate pairs. */
@Immutable
class RadarStops(
    val latLon: DoubleArray,
    val ids: List<String>,
    val names: List<String>,
) {
    val size: Int get() = latLon.size / 2

    init {
        require(ids.size == size && names.size == size) { "ids and names must match the coordinates" }
    }

    companion object {
        val None = RadarStops(NO_RADAR_POINTS, emptyList(), emptyList())

        /** The GPUI map never draws more stops than this (`STOP_CAP`). */
        const val CAP = 300
    }
}

/** Saved stops as markers: the middle of each gate, its colour tag and its id. */
@Immutable
class RadarSaved(
    val latLon: DoubleArray,
    val colours: List<StopColour>,
    val ids: LongArray,
) {
    val size: Int get() = ids.size

    init {
        require(latLon.size == 2 * size && colours.size == size) { "colours and ids must match the coordinates" }
    }

    companion object {
        val None = RadarSaved(NO_RADAR_POINTS, emptyList(), LongArray(0))

        /** The markers of `saved.json`: the middle of A and B of every stop (`SavedStop::center`). */
        fun of(saved: SavedStops): RadarSaved {
            val stops = saved.stops
            val latLon = DoubleArray(2 * stops.size)
            for (i in stops.indices) {
                latLon[2 * i] = (stops[i].a.coord.lat + stops[i].b.coord.lat) / 2.0
                latLon[2 * i + 1] = midLon(stops[i].a.coord.lon, stops[i].b.coord.lon)
            }
            return RadarSaved(latLon, stops.map { it.colour }, LongArray(stops.size) { stops[it].id })
        }

        /** The longitude half way between two, the short way round the antimeridian. */
        internal fun midLon(
            a: Double,
            b: Double,
        ): Double {
            var d = b - a
            if (d > 180.0) d -= 360.0
            if (d < -180.0) d += 360.0
            var mid = a + d / 2.0
            if (mid >= 180.0) mid -= 360.0
            if (mid < -180.0) mid += 360.0
            return mid
        }
    }
}

/** `lat0, lon0, lat1, lon1, ...` of a coordinate list. */
fun flatten(coords: List<Coord>): DoubleArray =
    DoubleArray(2 * coords.size) { if (it % 2 == 0) coords[it / 2].lat else coords[it / 2].lon }

/**
 * Holds the [RadarState] for the draw phase. A new state, or a pin following the finger, redraws the map node and
 * recomposes nothing: both are read only by [observe], which the overlay lambda calls while drawing.
 */
@Stable
class RadarHolder {
    private val current = mutableStateOf(RadarState.Empty)
    private val dragTick = mutableIntStateOf(0)

    /** The pin in the finger, or null. Plain fields: they change with every move of the finger. */
    var dragSlot: Slot? = null
        private set
    var dragLat = 0.0
        private set
    var dragLon = 0.0
        private set

    /** The state without subscribing (for event handlers and hit tests). */
    val state: RadarState get() = current.value

    /** Subscribes the calling draw scope to state and drag changes. Do not call in composition. */
    fun observe(): RadarState {
        dragTick.intValue
        return current.value
    }

    fun set(state: RadarState) {
        current.value = state
    }

    inline fun update(change: (RadarState) -> RadarState) = set(change(state))

    /** The pin of [slot] is in the finger at this coordinate: it is drawn there, larger. */
    fun dragTo(
        slot: Slot,
        lat: Double,
        lon: Double,
    ) {
        dragSlot = slot
        dragLat = lat
        dragLon = lon
        dragTick.intValue++
    }

    /** The finger let go: the pin is drawn where the state says again. */
    fun endDrag() {
        if (dragSlot == null) return
        dragSlot = null
        dragTick.intValue++
    }
}
