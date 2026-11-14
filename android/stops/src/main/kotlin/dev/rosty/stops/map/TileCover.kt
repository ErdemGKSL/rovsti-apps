package dev.rosty.stops.map

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The tiles of one zoom that cover a view, nearest the centre first (`rosty_geo::tiles::TileCover`). */
class TileCover(
    val zoom: Int,
    /** Packed [TileKey]s. */
    val tiles: LongArray,
) {
    operator fun contains(key: Long): Boolean = tiles.any { it == key }
}

/**
 * Which tiles a view needs. The Kotlin twin of `tiles_for_view` (checked against the `view.tiles` fixtures); the
 * scheduler uses [cover] for the bitmaps and the data tiles under them. Not per frame: the draw loop walks the visible
 * range itself without allocating.
 */
object TileCovers {
    /**
     * `tiles_for_view`: the tiles of the fetch zoom that the viewport touches plus a ring of one tile, at most
     * [maxTiles]; a view that would need more drops to a lower zoom, and to nothing when even zoom 0 is too many.
     */
    fun forView(
        camera: MapCamera,
        maxTiles: Int,
    ): TileCover {
        var zoom = MapMath.fetchZoom(camera.displayZoom)
        while (true) {
            val count = count(camera, zoom, 0f, 1)
            if (count <= maxTiles || zoom == 0) {
                return if (count <= maxTiles) cover(camera, zoom, 0f, 1) else TileCover(zoom, LongArray(0))
            }
            zoom--
        }
    }

    /**
     * The tiles of [zoom] within [marginPx] of the viewport plus [ring] whole tiles around, nearest the centre first
     * (ties: lower row, then lower column, like Rust). Columns wrap around the antimeridian; a view wider than the
     * world lists every column once.
     */
    fun cover(
        camera: MapCamera,
        zoom: Int,
        marginPx: Float = 0f,
        ring: Int = 0,
    ): TileCover {
        val z = zoom.coerceIn(0, MapMath.MAX_ID_ZOOM)
        val n = 1L shl z
        val x0 = firstColumn(camera, z, marginPx, ring)
        val x1 = lastColumn(camera, z, marginPx, ring, x0)
        val y0 = firstRow(camera, z, marginPx, ring)
        val y1 = lastRow(camera, z, marginPx, ring)
        val cx = camera.centreX * n
        val cy = camera.centreY * n
        val columns = (x1 - x0 + 1).toInt()
        val rows = y1 - y0 + 1
        val total = columns * rows
        val order = arrayOfNulls<Slot>(total)
        var i = 0
        for (ux in x0..x1) {
            for (y in y0..y1) {
                val dx = ux + 0.5 - cx
                val dy = y + 0.5 - cy
                order[i++] = Slot(dx * dx + dy * dy, ux, y)
            }
        }
        order.sortWith(SLOT_ORDER)
        return TileCover(z, LongArray(total) { TileKey.wrapped(z, order[it]!!.ux, order[it]!!.y) })
    }

    /** How many tiles [cover] would list, without building them. */
    fun count(
        camera: MapCamera,
        zoom: Int,
        marginPx: Float = 0f,
        ring: Int = 0,
    ): Int {
        val z = zoom.coerceIn(0, MapMath.MAX_ID_ZOOM)
        val x0 = firstColumn(camera, z, marginPx, ring)
        val x1 = lastColumn(camera, z, marginPx, ring, x0)
        val rows = lastRow(camera, z, marginPx, ring) - firstRow(camera, z, marginPx, ring) + 1
        return min(Int.MAX_VALUE.toLong(), (x1 - x0 + 1) * rows).toInt()
    }

    private class Slot(
        val d: Double,
        val ux: Long,
        val y: Int,
    )

    private val SLOT_ORDER = compareBy<Slot?>({ it!!.d }, { it!!.y }, { it!!.ux })

    private fun firstColumn(
        camera: MapCamera,
        z: Int,
        marginPx: Float,
        ring: Int,
    ): Long = floor(camera.worldXAt(-marginPx) * (1L shl z)).toLong() - ring

    private fun lastColumn(
        camera: MapCamera,
        z: Int,
        marginPx: Float,
        ring: Int,
        first: Long,
    ): Long {
        val n = 1L shl z
        val last = floor(camera.worldXAt(camera.widthPx + marginPx) * n).toLong() + ring
        return min(last, first + n - 1)
    }

    private fun firstRow(
        camera: MapCamera,
        z: Int,
        marginPx: Float,
        ring: Int,
    ): Int = row(camera.worldYAt(-marginPx), z, -ring)

    private fun lastRow(
        camera: MapCamera,
        z: Int,
        marginPx: Float,
        ring: Int,
    ): Int = row(camera.worldYAt(camera.heightPx + marginPx), z, ring)

    private fun row(
        worldY: Double,
        z: Int,
        ring: Int,
    ): Int {
        val n = 1L shl z
        // Like Rust, a view edge beyond the projection counts as its last row before the ring is added.
        val inside = min(1.0, max(0.0, worldY))
        return (floor(inside * n).toLong() + ring).coerceIn(0, n - 1).toInt()
    }
}
