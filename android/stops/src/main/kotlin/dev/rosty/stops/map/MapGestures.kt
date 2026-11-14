package dev.rosty.stops.map

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * What the screen does with touches on the map that are not pan, pinch or double tap. The map calls it; the picking
 * story (E7.4, AD-ST9) implements it. Every method has a default, so [None] is "a map that only moves".
 *
 * Positions are px from the map's top-left corner, the space of [MapCamera.screenX] and [MapCamera.screenY].
 */
interface MapGesturePolicy {
    /**
     * One finger tapped the map and no second tap followed within the double-tap window. The view did not move
     * (a map tap never moves the view). [lat] and [lon] are the coordinate that was under the finger.
     */
    fun onTap(
        x: Float,
        y: Float,
        lat: Double,
        lon: Double,
    ) {}

    /**
     * The first finger of a gesture came down at ([x], [y]). Answer a claim to take the drag away from the map at
     * once, without a long press: a pin within [PIN_GRAB_DP] of the finger. Null lets the map pan.
     */
    fun claimDrag(
        x: Float,
        y: Float,
    ): MapDragClaim? = null

    /** The user started to move the map (pan, pinch or double tap): follow-me turns off, auto-fit lets go. */
    fun onUserMove() {}

    companion object {
        /** No picking: taps do nothing and every drag pans. */
        val None: MapGesturePolicy = object : MapGesturePolicy {}

        /** A finger this close to a pin grabs it (`PIN_GRAB_PX` of the GPUI map). */
        const val PIN_GRAB_DP = 32f
    }
}

/**
 * A drag that a [MapGesturePolicy] took from the map. Exactly one of [onTap], [onDrop] and [onCancel] ends it.
 */
interface MapDragClaim {
    /** The finger moved beyond the touch slop and is now at ([x], [y]); called for every move after that. */
    fun onDrag(
        x: Float,
        y: Float,
    )

    /** The finger lifted after dragging, with its release velocity in px/s (a flick snaps to a stop). */
    fun onDrop(
        x: Float,
        y: Float,
        velocityX: Float,
        velocityY: Float,
    )

    /** The finger lifted without moving: a tap on the pin. */
    fun onTap()

    /** A second finger landed (the gesture is a pinch now) or the system took the gesture: the pin goes back. */
    fun onCancel()
}

/**
 * The map's touch handling (ARCH 8.4 step 1): one finger pans and flings, two fingers pan and zoom about their
 * midpoint (a second finger may join a drag, and the finger left after a pinch pans on), a double tap zooms in about
 * the tap. Everything else goes to [policy].
 *
 * It only writes to [camera], which is read in draw: a gesture recomposes nothing.
 */
fun Modifier.mapGestures(
    camera: MapCamera,
    policy: MapGesturePolicy = MapGesturePolicy.None,
    enabled: Boolean = true,
): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(camera, policy) {
            coroutineScope { MapGestureDetector(camera, policy, this, this@pointerInput).run() }
        }
    }

private class MapGestureDetector(
    private val camera: MapCamera,
    private val policy: MapGesturePolicy,
    private val scope: CoroutineScope,
    private val input: PointerInputScope,
) {
    private val tracker = VelocityTracker()
    private val doubleTapSlop = with(input) { DOUBLE_TAP_SLOP_DP.dp.toPx() }
    private val minPinch = with(input) { MIN_PINCH_DP.dp.toPx() }

    // The first half of a possible double tap.
    private var pendingJob: Job? = null
    private var pendingAt = Offset.Zero
    private var pendingTime = 0L
    private var pendingLat = 0.0
    private var pendingLon = 0.0

    suspend fun run() {
        input.awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // A touch stops a fling or a zoom under it, and then it is not a tap.
            val interrupted = camera.animating
            camera.stopAnimations()
            var claim = policy.claimDrag(down.position.x, down.position.y)
            val slop = viewConfiguration.touchSlop
            val start = down.position
            var anchor = start
            var finger = down.id
            var moved = false
            var twoFingers = false
            var pinching = false
            var first = PointerId(-1)
            var second = PointerId(-1)
            var lastSpan = 0f
            var lastMid = Offset.Zero
            var mapMoving = false
            var last = down
            var finished = false
            tracker.resetTracking()
            tracker.addPosition(down.uptimeMillis, down.position)
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val changes = event.changes
                    var pressed = 0
                    var a: PointerInputChange? = null
                    var b: PointerInputChange? = null
                    for (i in changes.indices) {
                        val c = changes[i]
                        if (!c.pressed) continue
                        if (pressed == 0) {
                            a = c
                        } else if (pressed == 1) {
                            b = c
                        }
                        pressed++
                    }
                    if (a == null) {
                        for (i in changes.indices) if (changes[i].id == finger) last = changes[i]
                        break
                    }
                    if (b != null) {
                        // Two fingers: a grabbed pin goes back and the gesture is a pinch from here on.
                        claim?.onCancel()
                        claim = null
                        twoFingers = true
                        val mid = (a.position + b.position) / 2f
                        val span = hypot(a.position.x - b.position.x, a.position.y - b.position.y)
                        if (!pinching || a.id != first || b.id != second) {
                            pinching = true
                            first = a.id
                            second = b.id
                            if (!mapMoving) {
                                mapMoving = true
                                deliverPendingTap()
                                policy.onUserMove()
                            }
                            camera.setGesture(active = true, pinch = true)
                        } else {
                            camera.panBy(mid.x - lastMid.x, mid.y - lastMid.y)
                            if (lastSpan >= minPinch && span > 0f) {
                                camera.zoomAbout((span / lastSpan).toDouble(), mid.x, mid.y)
                            }
                        }
                        lastMid = mid
                        lastSpan = span
                        for (i in changes.indices) changes[i].consume()
                        continue
                    }
                    if (pinching || a.id != finger) {
                        // One finger of a pinch lifted: the other one pans on.
                        pinching = false
                        finger = a.id
                        anchor = a.position
                        moved = true
                        tracker.resetTracking()
                        camera.setGesture(active = true, pinch = false)
                    }
                    last = a
                    tracker.addPosition(a.uptimeMillis, a.position)
                    if (!moved && (a.position - start).getDistance() > slop) {
                        moved = true
                        anchor = a.position
                        if (claim == null) {
                            mapMoving = true
                            deliverPendingTap()
                            policy.onUserMove()
                            camera.setGesture(active = true, pinch = false)
                        }
                    }
                    if (moved) {
                        val held = claim
                        if (held != null) {
                            held.onDrag(a.position.x, a.position.y)
                        } else {
                            camera.panBy(a.position.x - anchor.x, a.position.y - anchor.y)
                            anchor = a.position
                        }
                        a.consume()
                    }
                }
                finished = true
                // The lift itself is a sample: a pause before it reads as slow, not as the speed of the last move.
                tracker.addPosition(last.uptimeMillis, last.position)
                val at = last.position
                val held = claim
                claim = null
                when {
                    held != null && moved -> {
                        val v = tracker.calculateVelocity()
                        held.onDrop(at.x, at.y, v.x, v.y)
                    }

                    held != null -> {
                        held.onTap()
                    }

                    !moved && !twoFingers -> {
                        if (!interrupted) tap(at, last.uptimeMillis)
                    }

                    !pinching -> {
                        val v = tracker.calculateVelocity()
                        camera.fling(v.x, v.y)
                    }
                }
            } finally {
                if (!finished) claim?.onCancel()
                camera.setGesture(active = false)
            }
        }
    }

    private fun tap(
        at: Offset,
        time: Long,
    ) {
        val waiting = pendingJob
        if (waiting != null && waiting.isActive) {
            val near = (at - pendingAt).getDistance() <= doubleTapSlop
            if (near && time - pendingTime <= DOUBLE_TAP_MS) {
                waiting.cancel()
                pendingJob = null
                policy.onUserMove()
                camera.animateZoomBy(MapCamera.DOUBLE_TAP_FACTOR, at.x, at.y)
                return
            }
            deliverPendingTap()
        }
        pendingAt = at
        pendingTime = time
        pendingLat = camera.latAt(at.y)
        pendingLon = camera.lonAt(at.x)
        // A single tap waits out the double-tap window before it acts.
        pendingJob =
            scope.launch {
                delay(DOUBLE_TAP_MS)
                pendingJob = null
                policy.onTap(pendingAt.x, pendingAt.y, pendingLat, pendingLon)
            }
    }

    /** The map is about to move: a tap that still waits for its double-tap window acts now, on what it touched. */
    private fun deliverPendingTap() {
        val waiting = pendingJob ?: return
        pendingJob = null
        if (!waiting.isActive) return
        waiting.cancel()
        policy.onTap(pendingAt.x, pendingAt.y, pendingLat, pendingLon)
    }

    companion object {
        /** Two taps this close in time and space are a double tap (`DOUBLE_TAP_MS`, `DOUBLE_TAP_SLOP_PX`). */
        const val DOUBLE_TAP_MS = 300L
        const val DOUBLE_TAP_SLOP_DP = 40f

        /** Fingers closer than this do not zoom (`MIN_PINCH_PX`). */
        const val MIN_PINCH_DP = 12f
    }
}

/** The double-tap window of [mapGestures], for tests and for callers that time their own hints. */
const val MAP_DOUBLE_TAP_MS = 300L
