package dev.rosty.stops.map

import dev.rosty.core.model.stops.Slot
import kotlin.math.hypot

/**
 * What is under a finger on the map (E7.3, E7.4): pure functions of the camera and the radar state, in screen pixels.
 * The radii are the GPUI map's, in dp (`apps/stops/src/map.rs`).
 */
object RadarHitTest {
    /** A finger this close to a pin grabs it (`PIN_GRAB_PX`). */
    const val PIN_GRAB_DP = MapGesturePolicy.PIN_GRAB_DP

    /** A tap this close to the middle of a saved stop opens it (`SAVED_TAP_RADIUS_PX`). */
    const val SAVED_TAP_DP = 18f

    /** A tap this close to the position dot opens "my location" (`MY_LOCATION_RADIUS_PX`). */
    const val MY_LOCATION_DP = 24f

    /**
     * The pin within [PIN_GRAB_DP] of the pixel, the nearer one when both are; null when none is. A pin that is being
     * dragged is where the finger has it.
     */
    fun pinAt(
        camera: MapCamera,
        state: RadarState,
        x: Float,
        y: Float,
    ): Slot? {
        val reach = PIN_GRAB_DP * camera.density
        val a = distanceTo(camera, state.pinA, x, y)
        val b = distanceTo(camera, state.pinB, x, y)
        return when {
            a <= reach && a <= b -> Slot.A
            b <= reach -> Slot.B
            else -> null
        }
    }

    private fun distanceTo(
        camera: MapCamera,
        pin: RadarPin?,
        x: Float,
        y: Float,
    ): Float = if (pin == null) Float.MAX_VALUE else hypot(camera.screenX(pin.lon) - x, camera.screenY(pin.lat) - y)

    /**
     * The index of the transit stop nearest to the pixel within [maxDp], or -1 (`pick_stop`). Rust decides whether a
     * tap or a drop at that distance snaps (`stops.snap`).
     */
    fun stopAt(
        camera: MapCamera,
        stops: RadarStops,
        x: Float,
        y: Float,
        maxDp: Float,
    ): Int = camera.nearestOnScreen(stops.latLon, x, y, maxDp * camera.density)

    /** Pixels from the pixel to stop [index]. */
    fun stopDistancePx(
        camera: MapCamera,
        stops: RadarStops,
        index: Int,
        x: Float,
        y: Float,
    ): Float = hypot(camera.screenX(stops.latLon[2 * index + 1]) - x, camera.screenY(stops.latLon[2 * index]) - y)

    /**
     * The id of the saved stop whose marker is within [SAVED_TAP_DP] of the pixel (the nearest one), or -1
     * (`pick_saved`). Nothing is hit while the markers are hidden.
     */
    fun savedAt(
        camera: MapCamera,
        state: RadarState,
        x: Float,
        y: Float,
    ): Long {
        if (!state.showSaved) return -1
        val index = camera.nearestOnScreen(state.saved.latLon, x, y, SAVED_TAP_DP * camera.density)
        return if (index < 0) -1 else state.saved.ids[index]
    }

    /** Whether the pixel is on the position dot (`hits_position`). */
    fun hitsPosition(
        camera: MapCamera,
        position: RadarPosition?,
        x: Float,
        y: Float,
    ): Boolean {
        if (position == null) return false
        val d = hypot(camera.screenX(position.lon) - x, camera.screenY(position.lat) - y)
        return d <= MY_LOCATION_DP * camera.density
    }

    /** Pixels of a ground length at a latitude: the accuracy disc and the circle on AB. Never negative or NaN. */
    fun metresToPx(
        camera: MapCamera,
        metres: Double,
        lat: Double,
    ): Float {
        if (!metres.isFinite() || metres <= 0.0) return 0f
        val px = metres / (MapMath.metresPerDp(lat, camera.zoom) / camera.density)
        return if (px.isFinite()) px.toFloat() else 0f
    }
}
