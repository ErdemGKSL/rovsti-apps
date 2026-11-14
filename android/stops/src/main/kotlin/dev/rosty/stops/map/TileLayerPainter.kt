package dev.rosty.stops.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the tile layer for one frame (ARCH 8.4 step 3): every visible raster tile of the nearest integer zoom, placed
 * by the camera. A tile whose bitmap is not cached shows, in this order:
 *
 * 1. its nearest cached ancestor, enlarged (`rosty_geo::tiles::stand_in`: up to [STAND_IN_LEVELS] zooms up), which is
 *    what a zoom-in shows until the sharper tiles are painted;
 * 2. its cached children and grandchildren, reduced, which is what a zoom-out shows;
 * 3. the "radar" plate of the GPUI map with a hairline grid.
 *
 * **Nothing is allocated here.** The rectangles and paints are fields, tile ids are packed longs, the cache lookup
 * does not box, and edges are rounded to whole pixels from shared coordinates so neighbours never leave a seam.
 * `TileLayerPainterTest` measures the allocation of drawing 16 cached tiles (E7.13 AC1).
 */
class TileLayerPainter(
    private val store: MapTileStore,
) {
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val platePaint = Paint().apply { style = Paint.Style.FILL }
    private val gridPaint =
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
    private val src = Rect()
    private val dst = RectF()

    /** Tiles drawn from their own bitmap, from a stand-in, and as radar in the last frame (tests, diagnostics). */
    var lastExact = 0
        private set
    var lastStandIn = 0
        private set
    var lastMissing = 0
        private set

    /** The colours where no tile is loaded: the GPUI map's plate (`secondary_background`) and its `hairline`. */
    fun setRadarColors(
        plateArgb: Int,
        gridArgb: Int,
    ) {
        platePaint.color = plateArgb
        gridPaint.color = gridArgb
    }

    fun draw(
        canvas: Canvas,
        camera: MapCamera,
    ) {
        lastExact = 0
        lastStandIn = 0
        lastMissing = 0
        val width = camera.widthPx
        val height = camera.heightPx
        if (width <= 0 || height <= 0) return
        val save = canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), platePaint)
        gridPaint.strokeWidth = max(1f, camera.density)

        val pixelZoom = camera.pixelZoom
        val zoom = MapTileStore.rasterZoomFor(pixelZoom)
        val n = 1L shl zoom
        // Screen px per raster tile, and the view's top-left corner in tile units (columns not wrapped).
        val size = TileRasterizer.TILE_PX * MapMath.pow2(pixelZoom - zoom)
        val left = camera.worldXAt(0f) * n
        val top = camera.worldYAt(0f) * n
        val x0 = floor(left).toLong()
        // The last column and row that have at least a sliver inside the viewport.
        val x1 = max(x0, ceil(left + width / size).toLong() - 1)
        val y0 = max(0L, floor(top).toLong())
        val y1 = min(n - 1, ceil(top + height / size).toLong() - 1)
        if (y1 < y0 || (x1 - x0 + 1) * (y1 - y0 + 1) > MAX_TILES_PER_FRAME) {
            canvas.restoreToCount(save)
            return
        }

        var ux = x0
        while (ux <= x1) {
            val l = Math.round((ux - left) * size).toFloat()
            val r = Math.round((ux + 1 - left) * size).toFloat()
            var y = y0
            while (y <= y1) {
                val t = Math.round((y - top) * size).toFloat()
                val b = Math.round((y + 1 - top) * size).toFloat()
                drawTile(canvas, TileKey.wrapped(zoom, ux, y.toInt()), l, t, r, b)
                y++
            }
            ux++
        }
        canvas.restoreToCount(save)
        store.onDrawn()
    }

    private fun drawTile(
        canvas: Canvas,
        key: Long,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
    ) {
        val own = store.bitmaps.get(key)
        if (own != null) {
            dst.set(l, t, r, b)
            canvas.drawBitmap(own, null, dst, bitmapPaint)
            lastExact++
            return
        }
        // The twin of `stand_in`: the nearest ancestor that is loaded, up to four zooms up.
        var d = 1
        while (d <= STAND_IN_LEVELS) {
            val ancestor = TileKey.ancestor(key, d)
            if (ancestor == TileKey.NONE) break
            val bitmap = store.bitmaps.get(ancestor)
            if (bitmap != null) {
                val part = TileRasterizer.TILE_PX shr d
                val mask = (1 shl d) - 1
                val sx = (TileKey.x(key) and mask) * part
                val sy = (TileKey.y(key) and mask) * part
                src.set(sx, sy, sx + part, sy + part)
                dst.set(l, t, r, b)
                canvas.drawBitmap(bitmap, src, dst, bitmapPaint)
                lastStandIn++
                return
            }
            d++
        }
        dst.set(l, t, r, b)
        canvas.drawRect(dst, gridPaint)
        if (drawChildren(canvas, key, l, t, r, b, CHILD_LEVELS)) lastStandIn++ else lastMissing++
    }

    /** Draws the cached descendants of [key] into its rectangle; answers whether any was there. */
    private fun drawChildren(
        canvas: Canvas,
        key: Long,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        levels: Int,
    ): Boolean {
        val z = TileKey.z(key) + 1
        if (levels == 0 || z > TileRasterizer.MAX_RASTER_ZOOM) return false
        val mx = Math.round((l + r) / 2f).toFloat()
        val my = Math.round((t + b) / 2f).toFloat()
        var any = false
        var i = 0
        while (i < 4) {
            val cx = i and 1
            val cy = i shr 1
            val child = TileKey.of(z, TileKey.x(key) * 2 + cx, TileKey.y(key) * 2 + cy)
            val cl = if (cx == 0) l else mx
            val cr = if (cx == 0) mx else r
            val ct = if (cy == 0) t else my
            val cb = if (cy == 0) my else b
            val bitmap: Bitmap? = store.bitmaps.get(child)
            if (bitmap != null) {
                dst.set(cl, ct, cr, cb)
                canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
                any = true
            } else if (drawChildren(canvas, child, cl, ct, cr, cb, levels - 1)) {
                any = true
            }
            i++
        }
        return any
    }

    companion object {
        /** How many zooms down a missing tile looks for cached descendants. */
        const val CHILD_LEVELS = 2

        /** More visible tiles than this means a viewport no device has; the frame shows the plate only. */
        const val MAX_TILES_PER_FRAME = 400
    }
}
