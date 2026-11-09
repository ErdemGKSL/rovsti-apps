package dev.rovsti.contacts.photo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.rovsti.core.model.contacts.CropView
import dev.rovsti.design.media.ZoomMath
import dev.rovsti.design.media.ZoomState

/**
 * The geometry of the crop page (pure). The crop window is a square [side] px wide; the picture is scaled so that its
 * SHORT side fills the window at zoom 1, whatever the turn (`cover`), and the owner pans and pinches it. The state is
 * the photo's [ZoomState] in px of the window: [ZoomState.scale] is the zoom (1 to the core's `max_zoom`) and
 * [ZoomState.offset] the distance of the picture's centre from the window's centre. Converting to and from the core's
 * [CropView] (turns, zoom, centre as 0..1 of the rotated picture) is exact, so the Rust `photo_plan` cuts what the
 * owner saw; the bounds are the same ones the core's `CropView::clamped` keeps.
 */
object PhotoCropMath {
    /** The size of a [width] x [height] picture after [turns] clockwise quarter turns. */
    fun rotated(
        width: Int,
        height: Int,
        turns: Int,
    ): Size = if (turns % 2 != 0) Size(height.toFloat(), width.toFloat()) else Size(width.toFloat(), height.toFloat())

    /** [rotated] scaled so that its short side is [side]: the size the picture has at zoom 1. */
    fun cover(
        rotated: Size,
        side: Float,
    ): Size {
        val short = minOf(rotated.width, rotated.height)
        if (short <= 0f || side <= 0f) return Size(side, side)
        val ratio = side / short
        return Size(rotated.width * ratio, rotated.height * ratio)
    }

    private fun window(side: Float) = Size(side, side)

    /** One drag frame: moves the picture by [delta] and keeps the window inside it. */
    fun pan(
        state: ZoomState,
        delta: Offset,
        rotated: Size,
        side: Float,
    ): ZoomState = ZoomMath.pan(state, delta, cover(rotated, side), window(side)).state

    /**
     * One pinch frame: [zoomChange] multiplies the zoom (kept within 1..[maxZoom]) and the picture point under [focus]
     * (px from the window's centre) stays under the fingers, which also moved by [panChange].
     */
    fun pinch(
        state: ZoomState,
        zoomChange: Float,
        focus: Offset,
        panChange: Offset,
        rotated: Size,
        side: Float,
        maxZoom: Float,
    ): ZoomState {
        val scale = (state.scale * zoomChange).coerceIn(1f, maxZoom.coerceAtLeast(1f))
        val ratio = scale / state.scale
        val moved = focus + panChange - (focus - state.offset) * ratio
        val limit = ZoomMath.maxOffset(cover(rotated, side), window(side), scale)
        return ZoomState(scale, Offset(moved.x.coerceIn(-limit.x, limit.x), moved.y.coerceIn(-limit.y, limit.y)))
    }

    /** The offset limits at [scale]: how far the picture's centre may sit from the window's centre. */
    fun limit(
        scale: Float,
        rotated: Size,
        side: Float,
    ): Offset = ZoomMath.maxOffset(cover(rotated, side), window(side), scale)

    /** [state] brought inside 1..[maxZoom] and inside the window's bounds. */
    fun settled(
        state: ZoomState,
        rotated: Size,
        side: Float,
        maxZoom: Float,
    ): ZoomState {
        val scale = state.scale.coerceIn(1f, maxZoom.coerceAtLeast(1f))
        val limit = limit(scale, rotated, side)
        return ZoomState(
            scale,
            Offset(state.offset.x.coerceIn(-limit.x, limit.x), state.offset.y.coerceIn(-limit.y, limit.y)),
        )
    }

    /** What the owner sees, as the core's [CropView]. */
    fun viewOf(
        state: ZoomState,
        turns: Int,
        rotated: Size,
        side: Float,
        maxZoom: Float,
    ): CropView {
        val fixed = settled(state, rotated, side, maxZoom)
        val fitted = cover(rotated, side)
        val across = fitted.width * fixed.scale
        val down = fitted.height * fixed.scale
        return CropView(
            turns = Math.floorMod(turns, 4),
            zoom = fixed.scale.toDouble(),
            cx = if (across > 0f) (0.5 - fixed.offset.x / across) else 0.5,
            cy = if (down > 0f) (0.5 - fixed.offset.y / down) else 0.5,
        )
    }

    /** The inverse of [viewOf]: where the picture sits for a [CropView]. */
    fun stateOf(
        view: CropView,
        rotated: Size,
        side: Float,
    ): ZoomState {
        val fitted = cover(rotated, side)
        val scale = view.zoom.toFloat().coerceAtLeast(1f)
        return ZoomState(
            scale,
            Offset(
                ((0.5 - view.cx) * fitted.width * scale).toFloat(),
                ((0.5 - view.cy) * fitted.height * scale).toFloat(),
            ),
        )
    }
}
