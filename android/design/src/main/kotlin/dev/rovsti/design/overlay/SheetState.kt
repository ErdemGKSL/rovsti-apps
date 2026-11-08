package dev.rovsti.design.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import dev.rovsti.design.motion.RovstiMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The position of one sheet (ORIGINAL; SKILL overlays 5): a single [Animatable] holding its distance from the top of
 * the window, 0 at the very top and [containerHeight] when it is gone. The finger moves it with undispatched `snapTo`
 * (so a drag and a running spring never disagree), a release settles it with a spring seeded with the finger's
 * velocity, and dismissing uses the same exit whether it came from a flick, Back or code.
 */
@Stable
class SheetState(
    private val scope: CoroutineScope,
    val dismissible: Boolean = true,
    private val onDetentChanged: () -> Unit = {},
    private val onDismissed: () -> Unit = {},
) {
    /** Distance from the top; starts out of sight. Read it in layout or draw lambdas only. */
    val offset = Animatable(Float.MAX_VALUE)

    var containerHeight = 0f
        private set

    private var offsets = FloatArray(0)

    /** The detent the sheet last came to rest on (a pixel offset); a state so a page can follow the settled detent. */
    var settledOffset by mutableFloatStateOf(Float.NaN)
        private set

    /** True from the first touch on the sheet until it has settled. */
    var isDragging by mutableStateOf(false)
        private set

    /** Set once the sheet has left the screen. */
    var isGone by mutableStateOf(false)
        private set

    private var leaving = false

    /** Tallest offset (smallest value). */
    val topOffset: Float get() = offsets.firstOrNull() ?: 0f

    /** Lowest resting offset (largest value). */
    val bottomOffset: Float get() = offsets.lastOrNull() ?: containerHeight

    /** The ascending detent offsets in pixels. */
    val detentOffsets: FloatArray get() = offsets.copyOf()

    /** Describes the geometry. Cheap and idempotent: call it whenever the window or its insets change. */
    fun configure(
        height: Float,
        detentOffsets: FloatArray,
    ) {
        containerHeight = height
        offsets = detentOffsets
        if (!settledOffset.isNaN()) {
            // A rotation or font change moved the detents: stay on the nearest one.
            settledOffset = offsets.minByOrNull { kotlin.math.abs(it - settledOffset) } ?: settledOffset
        }
    }

    /** Slides in from below to [target] (a detent offset). */
    fun present(target: Float): Job =
        scope.launch {
            offset.snapTo(containerHeight)
            settledOffset = target
            offset.animateTo(target, RovstiMotion.sheetSettle(0.5f))
        }

    /** Moves the sheet with a finger by [dy] px. Rubber band above the top detent (and below the lowest if pinned). */
    fun drag(dy: Float) {
        if (leaving) return
        isDragging = true
        val next = sheetDragOffset(offset.value + dy, topOffset, bottomOffset, containerHeight, dismissible)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(next) }
    }

    /** The finger lifted at [velocity] px/s (positive is downwards). */
    fun release(velocity: Float): Job =
        scope.launch {
            isDragging = false
            if (leaving) return@launch
            when (val target = sheetReleaseTarget(offset.value, velocity, offsets, containerHeight, dismissible)) {
                SheetTarget.Dismiss -> exit(velocity)
                is SheetTarget.Snap -> settleTo(target.offset, velocity)
            }
        }

    /** Springs to the detent at [index] (0 is the tallest). */
    fun animateToDetent(index: Int): Job =
        scope.launch { if (!leaving) settleTo(offsets[index.coerceIn(0, offsets.lastIndex)], 0f) }

    /** Back to the detent it was resting on (a cancelled back gesture). */
    fun settleBack(): Job =
        scope.launch {
            isDragging = false
            if (!leaving) offset.animateTo(settledOffset, RovstiMotion.sheetSettle(0.5f))
        }

    /** The one exit: programmatic dismissal flicks the sheet away like a finger would. */
    fun dismiss(): Job = scope.launch { exit(DismissVelocity) }

    /** Moves to [target] without animation-time state changes: the back-gesture preview. */
    fun preview(target: Float) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(target.coerceAtMost(containerHeight)) }
    }

    private suspend fun settleTo(
        target: Float,
        velocity: Float,
    ) {
        if (target != settledOffset) {
            settledOffset = target
            onDetentChanged()
        }
        offset.animateTo(target, RovstiMotion.sheetSettle(0.5f), initialVelocity = velocity)
    }

    private suspend fun exit(velocity: Float) {
        if (leaving) return
        leaving = true
        isDragging = false
        try {
            offset.animateTo(containerHeight, RovstiMotion.sheetDismiss(0.5f), initialVelocity = velocity)
        } catch (c: CancellationException) {
            leaving = false
            throw c
        }
        isGone = true
        onDismissed()
    }

    /** 0 gone .. 1 at the lowest detent or higher. */
    fun presentedFraction(): Float = sheetFraction(offset.value, bottomOffset, containerHeight)

    companion object {
        /** Speed of a programmatic dismissal, px/s. */
        const val DismissVelocity = 3000f
    }
}

/**
 * Hands a scrolling list's drag to the sheet and back (ORIGINAL). Scrolling up first lifts a sheet that was pulled
 * below its tallest detent; a downward drag the list cannot use (it is at its top) pulls the sheet down. The flick
 * that ends either gesture is passed on to [SheetState.release]. Only finger input is taken (`UserInput`), so
 * programmatic scrolls never move the sheet.
 */
fun sheetScrollConnection(sheet: SheetState): NestedScrollConnection =
    object : NestedScrollConnection {
        override fun onPreScroll(
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
            val room = sheet.topOffset - sheet.offset.value
            if (room >= -0.5f) return Offset.Zero
            val used = maxOf(available.y, room)
            sheet.drag(used)
            return Offset(0f, used)
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            sheet.drag(available.y)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (sheet.offset.value > sheet.topOffset + 0.5f) {
                sheet.release(available.y)
                return available
            }
            return Velocity.Zero
        }
    }
