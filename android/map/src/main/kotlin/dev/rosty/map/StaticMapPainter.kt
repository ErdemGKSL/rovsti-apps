package dev.rosty.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.TileBuffer
import dev.rosty.core.model.wa.StaticMap
import kotlin.math.min

/** One decoded, styled data tile `z/x/y` in the light or [dark] palette; the real one reads a `TileHandle`. */
fun interface MapTiles {
    suspend fun tile(
        z: Int,
        x: Int,
        y: Int,
        dark: Boolean,
    ): Outcome<TileBuffer>
}

/**
 * Paints a small static map (E6-WA R5: the map of a place card) with Stops' tile painting: the tiles `wa.static_map`
 * named, each painted from its data tile (zoom 14 at most) by a [TileRasterizer], then scaled into its place. A tile
 * that does not load leaves its square in the background colour of the tiles that did; with none at all there is no
 * picture. Not thread safe: one painter per caller, called from a background dispatcher.
 */
class StaticMapPainter(
    private val tiles: MapTiles,
) {
    private val rasterizer = TileRasterizer()
    private val tile = TileRasterizer.newBitmap()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** The [width] x [height] picture of [map], or null when no tile loaded. [density] sets the line widths. */
    suspend fun paint(
        map: StaticMap,
        width: Int,
        height: Int,
        dark: Boolean,
        density: Float,
    ): Bitmap? {
        if (width <= 0 || height <= 0) return null
        var out: Bitmap? = null
        for (spot in map.tiles) {
            val dataZoom = min(spot.z, TileRasterizer.MAX_DATA_ZOOM)
            val levels = spot.z - dataZoom
            val buffer =
                (tiles.tile(dataZoom, spot.x shr levels, spot.y shr levels, dark) as? Outcome.Ok)?.value ?: continue
            rasterizer.render(PreparedTile(buffer), spot.z, spot.x, spot.y, spot.z.toDouble(), density, tile)
            val target =
                out ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(TileBuffer.rgbaToArgb(buffer.background))
                    out = it
                }
            Canvas(target).drawBitmap(
                tile,
                null,
                Rect(spot.left, spot.top, spot.left + map.tilePx, spot.top + map.tilePx),
                paint,
            )
        }
        return out
    }
}
