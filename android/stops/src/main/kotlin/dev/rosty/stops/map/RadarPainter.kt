package dev.rosty.stops.map

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import dev.rosty.core.model.stops.Slot
import dev.rosty.core.model.stops.StopColour
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The colours of the radar layers; the GPUI map's `Palette` on the iOS system colours of the two themes. */
class RadarPalette(
    val you: Int,
    val line: Int,
    val stop: Int,
    val ring: Int,
    val left: Int,
    val right: Int,
    val halo: Int,
    val measure: Int,
    val captionPlate: Int,
    val captionText: Int,
    /** In [StopColour] order: orange, red, green, blue. */
    val saved: IntArray,
) {
    companion object {
        val Light =
            RadarPalette(
                you = 0xFF007AFF.toInt(),
                line = 0xFFFF9500.toInt(),
                stop = 0xD93C3C43.toInt(),
                ring = 0xFFFF3B30.toInt(),
                left = 0xFF007AFF.toInt(),
                right = 0xFF34C759.toInt(),
                halo = 0xFFFFFFFF.toInt(),
                measure = 0xFF000000.toInt(),
                captionPlate = 0xE0FFFFFF.toInt(),
                captionText = 0xFF000000.toInt(),
                saved = intArrayOf(0xFFFF9500.toInt(), 0xFFFF3B30.toInt(), 0xFF34C759.toInt(), 0xFF007AFF.toInt()),
            )
        val Dark =
            RadarPalette(
                you = 0xFF0A84FF.toInt(),
                line = 0xFFFF9F0A.toInt(),
                stop = 0xD9EBEBF5.toInt(),
                ring = 0xFFFF453A.toInt(),
                left = 0xFF0A84FF.toInt(),
                right = 0xFF30D158.toInt(),
                halo = 0xFFFFFFFF.toInt(),
                measure = 0xFFFFFFFF.toInt(),
                captionPlate = 0xE01C1C1E.toInt(),
                captionText = 0xFFFFFFFF.toInt(),
                saved = intArrayOf(0xFFFF9F0A.toInt(), 0xFFFF453A.toInt(), 0xFF30D158.toInt(), 0xFF0A84FF.toInt()),
            )

        fun of(dark: Boolean): RadarPalette = if (dark) Dark else Light

        fun withAlpha(
            argb: Int,
            alpha: Float,
        ): Int = (argb and 0x00FFFFFF) or ((alpha * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24)
    }
}

/**
 * Draws the radar layers over the tiles (E7.3) from a [RadarState] and the camera, in the paint order and with the
 * sizes of the GPUI map (`scene_paths` in `apps/stops/src/map.rs`, dp for its px):
 *
 * the chosen line, transit stops, saved stop rings, the circle on AB (two washed halves, dashed ring, the diameter
 * and its ends) or a plain line when A and B make no valid circle, the breadcrumb, the measure line, the position with
 * its accuracy disc, then the pins with their letters and captions.
 *
 * **Draw phase only, nothing allocated per frame**: one `Path`, a few `Paint`s and the text buffers are fields;
 * projecting is arithmetic on the flat coordinate arrays (`RadarPainterTest` measures it). Call from the map's
 * `overlay` lambda: `drawIntoCanvas { painter.draw(it.nativeCanvas, camera, holder) }`.
 */
class RadarPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val letter =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = WHITE
        }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val rect = RectF()
    private var dashDensity = 0f

    private var palette = RadarPalette.Light
    private var density = 1f

    // Captions are cut to their width once per text, not per frame.
    private val captionSource = arrayOfNulls<String>(2)
    private val captionShown = arrayOf("", "")
    private var captionDensity = 0f

    /** What the last frame drew, for tests: stops and saved markers inside the viewport. */
    var lastStops = 0
        private set
    var lastSaved = 0
        private set

    fun setDark(dark: Boolean) {
        palette = RadarPalette.of(dark)
    }

    fun draw(
        canvas: Canvas,
        camera: MapCamera,
        holder: RadarHolder,
    ) {
        val state = holder.observe()
        density = camera.density
        if (density != dashDensity) {
            dashDensity = density
            dashed.pathEffect = DashPathEffect(floatArrayOf(DASH_ON_DP * density, DASH_OFF_DP * density), 0f)
            letter.textSize = LETTER_SP * density
            caption.textSize = CAPTION_SP * density
        }
        if (camera.widthPx <= 0 || camera.heightPx <= 0) return

        polyline(canvas, camera, state.line, LINE_DP, palette.line, LINE_MIN_DP)
        stops(canvas, camera, state.stops)
        if (state.showSaved) saved(canvas, camera, state.saved)

        val dragSlot = holder.dragSlot
        val a = state.pinA
        val b = state.pinB
        val crossing = state.crossing
        if (crossing != null) {
            crossing(canvas, camera, crossing)
        } else if (a != null && b != null) {
            // Both ends set but no valid circle: a plain warning line. It follows a dragged end.
            val ax = pinX(camera, holder, Slot.A, a)
            val ay = pinY(camera, holder, Slot.A, a)
            val bx = pinX(camera, holder, Slot.B, b)
            val by = pinY(camera, holder, Slot.B, b)
            stroke.color = palette.ring
            stroke.strokeWidth = SEGMENT_DP * density
            canvas.drawLine(ax, ay, bx, by, stroke)
        }

        polyline(canvas, camera, state.trail, TRAIL_DP, RadarPalette.withAlpha(palette.you, TRAIL_ALPHA), TRAIL_MIN_DP)
        measure(canvas, camera, state.measure)
        state.position?.let { position(canvas, camera, it) }

        if (a != null) pin(canvas, camera, holder, Slot.A, a, dragSlot == Slot.A)
        if (b != null) pin(canvas, camera, holder, Slot.B, b, dragSlot == Slot.B)
    }

    // ---- lines ----

    /**
     * A polyline through `lat, lon` pairs; points closer than [minDp] to the last drawn one are skipped
     * (`thin_polyline`), the last point is always kept.
     */
    private fun polyline(
        canvas: Canvas,
        camera: MapCamera,
        latLon: DoubleArray,
        widthDp: Float,
        color: Int,
        minDp: Float,
    ) {
        val n = latLon.size / 2
        if (n < 2) return
        val min = minDp * density
        path.rewind()
        var lastX = camera.screenX(latLon[1])
        var lastY = camera.screenY(latLon[0])
        path.moveTo(lastX, lastY)
        for (i in 1 until n) {
            val x = camera.screenX(latLon[2 * i + 1])
            val y = camera.screenY(latLon[2 * i])
            if (i < n - 1 && hypot(x - lastX, y - lastY) < min) continue
            path.lineTo(x, y)
            lastX = x
            lastY = y
        }
        stroke.color = color
        stroke.strokeWidth = widthDp * density
        canvas.drawPath(path, stroke)
    }

    private fun measure(
        canvas: Canvas,
        camera: MapCamera,
        latLon: DoubleArray,
    ) {
        if (latLon.isEmpty()) return
        polyline(canvas, camera, latLon, MEASURE_HALO_DP, palette.halo, 0f)
        polyline(canvas, camera, latLon, MEASURE_DP, palette.measure, 0f)
        for (i in 0 until latLon.size / 2) {
            val x = camera.screenX(latLon[2 * i + 1])
            val y = camera.screenY(latLon[2 * i])
            disc(canvas, x, y, MEASURE_DOT_HALO_DP, palette.halo)
            disc(canvas, x, y, MEASURE_DOT_DP, palette.measure)
        }
    }

    // ---- markers ----

    private fun stops(
        canvas: Canvas,
        camera: MapCamera,
        stops: RadarStops,
    ) {
        lastStops = 0
        val r = STOP_DP * density
        fill.color = palette.stop
        val latLon = stops.latLon
        for (i in 0 until min(stops.size, RadarStops.CAP)) {
            val x = camera.screenX(latLon[2 * i + 1])
            val y = camera.screenY(latLon[2 * i])
            if (!inView(camera, x, y, r)) continue
            canvas.drawCircle(x, y, r, fill)
            lastStops++
        }
    }

    private fun saved(
        canvas: Canvas,
        camera: MapCamera,
        saved: RadarSaved,
    ) {
        lastSaved = 0
        val margin = SAVED_MARGIN_DP * density
        val latLon = saved.latLon
        stroke.strokeWidth = SAVED_RING_WIDTH_DP * density
        for (i in 0 until saved.size) {
            val x = camera.screenX(latLon[2 * i + 1])
            val y = camera.screenY(latLon[2 * i])
            if (!inView(camera, x, y, margin)) continue
            // Hollow rings on a white disc: neither a stop dot nor the line.
            disc(canvas, x, y, SAVED_HALO_DP, palette.halo)
            stroke.color = palette.saved[saved.colours[i].ordinal.coerceIn(0, palette.saved.size - 1)]
            canvas.drawCircle(x, y, SAVED_RING_DP * density, stroke)
            lastSaved++
        }
    }

    private fun position(
        canvas: Canvas,
        camera: MapCamera,
        position: RadarPosition,
    ) {
        val x = camera.screenX(position.lon)
        val y = camera.screenY(position.lat)
        val accuracy = min(MAX_RADIUS_DP * density, RadarHitTest.metresToPx(camera, position.accuracyM, position.lat))
        if (accuracy >= MIN_DISC_PX) {
            fill.color = RadarPalette.withAlpha(palette.you, ACCURACY_ALPHA)
            canvas.drawCircle(x, y, accuracy, fill)
        }
        disc(canvas, x, y, YOU_HALO_DP, palette.halo)
        disc(canvas, x, y, YOU_DP, palette.you)
    }

    // ---- the circle on AB ----

    private fun crossing(
        canvas: Canvas,
        camera: MapCamera,
        c: RadarCrossing,
    ) {
        polygon(canvas, camera, c.left, RadarPalette.withAlpha(palette.left, WASH_ALPHA))
        polygon(canvas, camera, c.right, RadarPalette.withAlpha(palette.right, WASH_ALPHA))
        val cx = camera.screenX(c.centreLon)
        val cy = camera.screenY(c.centreLat)
        val radius = min(MAX_RADIUS_DP * density, RadarHitTest.metresToPx(camera, c.radiusM, c.centreLat))
        if (radius >= 2f * density) {
            dashed.color = palette.ring
            dashed.strokeWidth = RING_DP * density
            canvas.drawCircle(cx, cy, radius, dashed)
        }
        val ax = camera.screenX(c.aLon)
        val ay = camera.screenY(c.aLat)
        val bx = camera.screenX(c.bLon)
        val by = camera.screenY(c.bLat)
        stroke.color = palette.ring
        stroke.strokeWidth = DIAMETER_DP * density
        canvas.drawLine(ax, ay, bx, by, stroke)
        disc(canvas, ax, ay, END_HALO_DP, palette.halo)
        disc(canvas, ax, ay, END_DP, palette.ring)
        disc(canvas, bx, by, END_HALO_DP, palette.halo)
        disc(canvas, bx, by, END_DP, palette.ring)
        // "L" and "R" half way from the centre to the top of each half.
        sideLetter(canvas, camera, c.left, cx, cy, "L", palette.left)
        sideLetter(canvas, camera, c.right, cx, cy, "R", palette.right)
    }

    private fun polygon(
        canvas: Canvas,
        camera: MapCamera,
        latLon: DoubleArray,
        color: Int,
    ) {
        val n = latLon.size / 2
        if (n < 3) return
        path.rewind()
        path.moveTo(camera.screenX(latLon[1]), camera.screenY(latLon[0]))
        for (i in 1 until n) path.lineTo(camera.screenX(latLon[2 * i + 1]), camera.screenY(latLon[2 * i]))
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
    }

    private fun sideLetter(
        canvas: Canvas,
        camera: MapCamera,
        half: DoubleArray,
        cx: Float,
        cy: Float,
        text: String,
        color: Int,
    ) {
        val n = half.size / 2
        if (n < 3) return
        // The half disc runs from one end of the diameter over the arc to the other: its middle point is the top.
        val top = n / 2
        val x = (cx + camera.screenX(half[2 * top + 1])) / 2f
        val y = (cy + camera.screenY(half[2 * top])) / 2f
        if (hypot(x - cx, y - cy) < SIDE_LETTER_MIN_DP * density) return
        letter.color = color
        canvas.drawText(text, x, y - (letter.ascent() + letter.descent()) / 2f, letter)
        letter.color = WHITE
    }

    // ---- pins ----

    private fun pinX(
        camera: MapCamera,
        holder: RadarHolder,
        slot: Slot,
        pin: RadarPin,
    ): Float = camera.screenX(if (holder.dragSlot == slot) holder.dragLon else pin.lon)

    private fun pinY(
        camera: MapCamera,
        holder: RadarHolder,
        slot: Slot,
        pin: RadarPin,
    ): Float = camera.screenY(if (holder.dragSlot == slot) holder.dragLat else pin.lat)

    private fun pin(
        canvas: Canvas,
        camera: MapCamera,
        holder: RadarHolder,
        slot: Slot,
        pin: RadarPin,
        dragged: Boolean,
    ) {
        val x = pinX(camera, holder, slot, pin)
        val y = pinY(camera, holder, slot, pin)
        val r = (if (dragged) PIN_DP * PIN_DRAG_SCALE else PIN_DP) * density
        val rim = PIN_RIM_DP * density
        val color = if (slot == Slot.A) palette.left else palette.right
        disc(canvas, x, y, 0f, palette.halo, r + rim)
        disc(canvas, x, y, 0f, color, r)
        if (pin.snapped && !dragged) {
            stroke.color = color
            stroke.strokeWidth = SNAP_RING_WIDTH_DP * density
            canvas.drawCircle(x, y, r + rim + SNAP_RING_GAP_DP * density, stroke)
        }
        canvas.drawText(if (slot == Slot.A) "A" else "B", x, y - (letter.ascent() + letter.descent()) / 2f, letter)
        caption(canvas, slot, pin.caption, x, y + r + rim + CAPTION_GAP_DP * density)
    }

    /** The name under a pin on a rounded plate, cut to [CAPTION_MAX_DP] with an ellipsis. */
    private fun caption(
        canvas: Canvas,
        slot: Slot,
        text: String,
        x: Float,
        top: Float,
    ) {
        if (text.isEmpty()) return
        val i = slot.ordinal
        val maxWidth = (CAPTION_MAX_DP - 2 * CAPTION_PAD_X_DP) * density
        if (captionSource[i] !== text || captionDensity != density) {
            captionSource[i] = text
            captionShown[i] = fitText(text, maxWidth)
        }
        captionDensity = density
        val shown = captionShown[i]
        val width = caption.measureText(shown)
        val padX = CAPTION_PAD_X_DP * density
        val padY = CAPTION_PAD_Y_DP * density
        val height = caption.descent() - caption.ascent()
        rect.set(x - width / 2f - padX, top, x + width / 2f + padX, top + height + 2 * padY)
        fill.color = palette.captionPlate
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
        caption.color = palette.captionText
        canvas.drawText(shown, x, top + padY - caption.ascent(), caption)
    }

    private fun fitText(
        text: String,
        maxWidth: Float,
    ): String {
        if (caption.measureText(text) <= maxWidth) return text
        val room = max(0f, maxWidth - caption.measureText(ELLIPSIS))
        val fits = caption.breakText(text, true, room, null)
        return text.substring(0, fits).trimEnd() + ELLIPSIS
    }

    // ---- helpers ----

    private fun disc(
        canvas: Canvas,
        x: Float,
        y: Float,
        radiusDp: Float,
        color: Int,
        radiusPx: Float = radiusDp * density,
    ) {
        fill.color = color
        canvas.drawCircle(x, y, radiusPx, fill)
    }

    private fun inView(
        camera: MapCamera,
        x: Float,
        y: Float,
        margin: Float,
    ): Boolean = x >= -margin && y >= -margin && x <= camera.widthPx + margin && y <= camera.heightPx + margin

    companion object {
        // Sizes of `scene_paths`, `paint_diameter` and `pin_labels` in the GPUI map, in dp.
        const val LINE_DP = 3.5f
        const val LINE_MIN_DP = 4f
        const val STOP_DP = 3.5f
        const val SAVED_HALO_DP = 6.5f
        const val SAVED_RING_DP = 5f
        const val SAVED_RING_WIDTH_DP = 2.5f
        const val SAVED_MARGIN_DP = 24f
        const val RING_DP = 2f
        const val DASH_ON_DP = 8f
        const val DASH_OFF_DP = 6f
        const val DIAMETER_DP = 4.5f
        const val SEGMENT_DP = 3.5f
        const val END_HALO_DP = 8f
        const val END_DP = 5.5f
        const val TRAIL_DP = 3f
        const val TRAIL_MIN_DP = 3f
        const val TRAIL_ALPHA = 0.6f
        const val MEASURE_HALO_DP = 6f
        const val MEASURE_DP = 3f
        const val MEASURE_DOT_HALO_DP = 6.5f
        const val MEASURE_DOT_DP = 4.5f
        const val YOU_HALO_DP = 9f
        const val YOU_DP = 6.5f
        const val ACCURACY_ALPHA = 0.16f
        const val WASH_ALPHA = 0.18f
        const val PIN_DP = 12f
        const val PIN_RIM_DP = 2.5f
        const val PIN_DRAG_SCALE = 1.2f
        const val SNAP_RING_GAP_DP = 3.5f
        const val SNAP_RING_WIDTH_DP = 2f
        const val CAPTION_MAX_DP = 160f
        const val CAPTION_GAP_DP = 4f
        const val CAPTION_PAD_X_DP = 8f
        const val CAPTION_PAD_Y_DP = 2f

        /** Discs and rings are never drawn larger than this (`MAX_RADIUS_PX`). */
        const val MAX_RADIUS_DP = 4000f
        private const val LETTER_SP = 13f
        private const val CAPTION_SP = 12f
        private const val SIDE_LETTER_MIN_DP = 14f
        private const val MIN_DISC_PX = 0.25f
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val ELLIPSIS = "…"
    }
}

/**
 * The `overlay` of [StopsMap] that draws [holder]: one remembered lambda (a new one per composition would be a new
 * modifier element) with its own [RadarPainter]. It reads the holder while drawing, so new radar state redraws the map
 * and recomposes nothing.
 */
@Composable
fun rememberRadarOverlay(
    holder: RadarHolder,
    dark: Boolean,
): DrawScope.(MapCamera) -> Unit {
    val painter = remember { RadarPainter() }
    painter.setDark(dark)
    return remember(holder, painter) {
        { camera -> drawIntoCanvas { painter.draw(it.nativeCanvas, camera, holder) } }
    }
}
