package dev.rosty.stops.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import dev.rosty.core.model.stops.TileBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * A decoded tile made ready to paint: one `Path` per polygon (its exterior ring and holes, even-odd) and one per
 * polyline, each with its bounding box, built once per buffer. A bitmap of any part of the tile then only replays the
 * paths that touch it.
 *
 * Follows `docs/kotlin-ui/tile-buffer.md`: records in paint order; every polygon is filled on its own, so overlapping
 * polygons (the 1/16 margin) and half transparent fills never cancel each other; strokes have round joins and caps.
 */
class PreparedTile(
    val buffer: TileBuffer,
) {
    internal class Record(
        val argb: Int,
        val width14: Float,
        val fill: Boolean,
        val paths: Array<Path>,
        /** `left, top, right, bottom` per path, tile units. */
        val bounds: FloatArray,
    )

    internal val records: Array<Record>

    /** Rough heap and native size, for the buffer cache's budget. */
    val sizeBytes: Long

    init {
        var points = 0L
        var paths = 0L
        val out = ArrayList<Record>(buffer.classes.size)
        var previous: TileBuffer.TileClass? = null
        for (c in buffer.classes) {
            val fill = c.kind == TileBuffer.Kind.Polygon
            val last = out.lastOrNull()
            val record =
                if (last != null && previous != null && sameGeometry(previous, c)) {
                    // A cased line is written twice with the same parts: share the paths.
                    Record(c.argb, c.width14, false, last.paths, last.bounds)
                } else {
                    val built = if (fill) polygonPaths(c.parts) else linePaths(c.parts)
                    paths += built.size
                    points += c.pointCount
                    Record(c.argb, c.width14, fill, built, boundsOf(built))
                }
            out += record
            previous = c
        }
        records = out.toTypedArray()
        sizeBytes = points * BYTES_PER_POINT + paths * BYTES_PER_PATH + BASE_BYTES
    }

    private fun sameGeometry(
        a: TileBuffer.TileClass,
        b: TileBuffer.TileClass,
    ): Boolean {
        if (a.kind != TileBuffer.Kind.Line || b.kind != TileBuffer.Kind.Line) return false
        if (a.paintId != b.paintId || !a.casing || b.casing || a.parts.size != b.parts.size) return false
        for (i in a.parts.indices) {
            if (!a.parts[i].contentEquals(b.parts[i])) return false
        }
        return true
    }

    private fun polygonPaths(parts: List<FloatArray>): Array<Path> {
        val out = ArrayList<Path>()
        var current: Path? = null
        for (ring in parts) {
            if (ring.size < 6) continue
            // A ring with positive area starts a polygon, a negative one is a hole of the polygon before it.
            if (current == null || TileBuffer.ringArea2(ring) > 0.0) {
                current = Path().apply { fillType = Path.FillType.EVEN_ODD }
                out += current
            }
            current.moveTo(ring[0], ring[1])
            var i = 2
            while (i < ring.size) {
                current.lineTo(ring[i], ring[i + 1])
                i += 2
            }
            current.close()
        }
        return out.toTypedArray()
    }

    private fun linePaths(parts: List<FloatArray>): Array<Path> {
        val out = ArrayList<Path>(parts.size)
        for (line in parts) {
            if (line.size < 4) continue
            val path = Path()
            path.moveTo(line[0], line[1])
            var i = 2
            while (i < line.size) {
                path.lineTo(line[i], line[i + 1])
                i += 2
            }
            out += path
        }
        return out.toTypedArray()
    }

    private fun boundsOf(paths: Array<Path>): FloatArray {
        val out = FloatArray(4 * paths.size)
        val box = android.graphics.RectF()
        for (i in paths.indices) {
            // Also settles the path's lazily computed bounds, so later reads from two raster threads only read.
            @Suppress("DEPRECATION")
            paths[i].computeBounds(box, true)
            out[4 * i] = box.left
            out[4 * i + 1] = box.top
            out[4 * i + 2] = box.right
            out[4 * i + 3] = box.bottom
        }
        return out
    }

    private companion object {
        const val BYTES_PER_POINT = 16L
        const val BYTES_PER_PATH = 160L
        const val BASE_BYTES = 4096L
    }
}

/**
 * Paints tiles into bitmaps (ARCH 8.4 step 3) with `android.graphics.Canvas`, off the main thread. One instance is
 * used by one thread at a time; its two paints are built once and only recoloured per record.
 *
 * A bitmap is a **raster tile**: [TILE_PX] pixels of slippy tile `rasterZoom/x/y`, painted from the data tile that
 * contains it (the same tile, or an ancestor when the screen is denser than the data or the view is closer than zoom
 * 14). So bitmaps are always drawn close to 1:1 and never blurry at rest.
 */
class TileRasterizer {
    private val fill =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
    private val stroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
    private val canvas = Canvas()

    /**
     * Paints raster tile [rasterZoom]/[x]/[y] from [tile] into [into]. [displayZoom] is the display zoom the bitmap is
     * made for (it sets the line widths, `line_width`), [density] the px per dp.
     */
    fun render(
        tile: PreparedTile,
        rasterZoom: Int,
        x: Int,
        y: Int,
        displayZoom: Double,
        density: Float,
        into: Bitmap,
    ) {
        val buffer = tile.buffer
        val levels = rasterZoom - buffer.z
        require(levels in 0..MAX_LEVELS_BELOW_DATA) { "raster zoom $rasterZoom is not under data zoom ${buffer.z}" }
        val span = 1 shl levels
        require(x shr levels == buffer.x && y shr levels == buffer.y) { "raster tile is outside its data tile" }
        val extent = buffer.extent.toFloat()
        // Tile units per bitmap, and where this bitmap starts inside the data tile.
        val units = extent / span
        val left = (x - (buffer.x shl levels)) * units
        val top = (y - (buffer.y shl levels)) * units
        val scale = into.width / units

        into.eraseColor(TileBuffer.rgbaToArgb(buffer.background))
        canvas.setBitmap(into)
        val save = canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-left, -top)
        val right = left + units
        val bottom = top + into.height / scale
        for (record in tile.records) {
            if (record.fill) {
                fill.color = record.argb
                replay(record, fill, left, top, right, bottom, 0f)
            } else {
                val widthPx = lineWidthPx(record.width14, displayZoom, density)
                val width = widthPx / scale
                stroke.color = record.argb
                stroke.strokeWidth = width
                replay(record, stroke, left, top, right, bottom, width)
            }
        }
        canvas.restoreToCount(save)
        canvas.setBitmap(null)
    }

    private fun replay(
        record: PreparedTile.Record,
        paint: Paint,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        grow: Float,
    ) {
        val b = record.bounds
        val paths = record.paths
        for (i in paths.indices) {
            val o = 4 * i
            if (b[o + 2] + grow < left || b[o] - grow > right || b[o + 3] + grow < top ||
                b[o + 1] - grow > bottom
            ) {
                continue
            }
            canvas.drawPath(paths[i], paint)
        }
    }

    companion object {
        /** Side of a tile bitmap in px (ARCH 8.4 step 3). */
        const val TILE_PX = 512

        /** Bytes of one `ARGB_8888` tile bitmap. */
        const val TILE_BYTES = TILE_PX.toLong() * TILE_PX * 4

        /** A bitmap may show at most 1/64 of its data tile's side (zoom 14 data at raster zoom 20). */
        const val MAX_LEVELS_BELOW_DATA = 6

        const val MAX_RASTER_ZOOM = MapMath.MAX_TILE_ZOOM + MAX_LEVELS_BELOW_DATA

        /**
         * `rosty_geo::tiles::line_width` in dp: `max(0.5, width14 * 2^clamp(displayZoom - 14, -3, 2))`.
         */
        fun lineWidthDp(
            width14: Float,
            displayZoom: Double,
        ): Float {
            val scale = MapMath.pow2(min(2.0, max(-3.0, displayZoom - 14.0)))
            return max(0.5, width14 * scale).toFloat()
        }

        fun lineWidthPx(
            width14: Float,
            displayZoom: Double,
            density: Float,
        ): Float = lineWidthDp(width14, displayZoom) * density

        fun newBitmap(): Bitmap = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
    }
}
