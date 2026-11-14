package dev.rosty.stops.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web-Mercator slippy-map maths, the Kotlin twin of `rosty_geo::tiles::slippy` (per-frame, so it is not a JNI call:
 * ARCH 0). Pure: no Android and no Compose types. Cross-checked against the `view.*` fixtures in `MapMathFixtureTest`.
 *
 * A "world" coordinate is a Web-Mercator position normalised to 0..1: x grows east from the antimeridian, y grows
 * south from the northern edge of the projection (latitude [MAX_MERCATOR_LAT]).
 */
object MapMath {
    /** Vector tiles exist up to this zoom; closer views draw them enlarged (`MAX_TILE_ZOOM`). */
    const val MAX_TILE_ZOOM = 14

    /** A tile covers this many dp at its own zoom (`TILE_DISPLAY_PX`). */
    const val TILE_DISPLAY_DP = 512.0

    /** Where Web Mercator ends (`MAX_MERCATOR_LAT`). */
    const val MAX_MERCATOR_LAT = 85.051_128_78

    /** The camera centre stays within this latitude (`view.rs` `MAX_LAT`). */
    const val MAX_CENTRE_LAT = 85.0

    /** The scale limits of the GPUI map (`MIN_METRES_PER_PX`, `MAX_METRES_PER_PX`), in metres per dp. */
    const val MIN_METRES_PER_DP = 0.5
    const val MAX_METRES_PER_DP = 2000.0

    /** The equator of `display_zoom` in `slippy.rs`. */
    const val EQUATOR_M = 40_075_016.686

    /** The sphere every distance in `rosty_geo` is measured on (`coord.rs`). */
    const val EARTH_RADIUS_M = 6_371_008.8

    /** The circumference of that sphere: what the width of the Mercator world is in ground metres at the equator. */
    const val SPHERE_M = 2 * PI * EARTH_RADIUS_M

    /** No tile id is deeper than this (`MAX_ID_ZOOM`). */
    const val MAX_ID_ZOOM = 24

    private const val LN2 = 0.6931471805599453

    fun log2(v: Double): Double = ln(v) / LN2

    /** `2^v` for a fractional [v]. */
    fun pow2(v: Double): Double = Math.pow(2.0, v)

    fun clampLat(lat: Double): Double = min(MAX_MERCATOR_LAT, max(-MAX_MERCATOR_LAT, lat))

    /** Longitude to world x, not wrapped (360 degrees east of a point is that point plus 1). */
    fun worldX(lon: Double): Double = (lon + 180.0) / 360.0

    /** Latitude to world y; latitudes beyond the projection are clamped to its edge. */
    fun worldY(lat: Double): Double {
        val r = Math.toRadians(clampLat(lat))
        return (1.0 - asinh(tan(r)) / PI) / 2.0
    }

    /** World x to a longitude in -180..180 (180 itself becomes -180). */
    fun lonOf(worldX: Double): Double = wrap01(worldX) * 360.0 - 180.0

    /** World x to a longitude that is not wrapped; `frac_to_coord` answers this. */
    fun lonOfUnwrapped(worldX: Double): Double = worldX * 360.0 - 180.0

    fun latOf(worldY: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * worldY))))

    /** [v] into 0..1 (1 excluded). */
    fun wrap01(v: Double): Double {
        val w = v - floor(v)
        return if (w >= 1.0) 0.0 else w
    }

    /** The shortest signed world distance from [from] to [to] across the antimeridian: -0.5..0.5. */
    fun wrapDelta(
        to: Double,
        from: Double,
    ): Double {
        val d = to - from
        return d - floor(d + 0.5)
    }

    /**
     * Ground metres per dp at [lat] when a tile of zoom [zoom] is 512 dp wide, on the sphere of `rosty_geo`
     * (so a length Rust measured, divided by this, is the pixels between its two ends on the map: `Viewport::project`).
     */
    fun metresPerDp(
        lat: Double,
        zoom: Double,
    ): Double = SPHERE_M * cosLat(lat) / (TILE_DISPLAY_DP * pow2(zoom))

    /** The inverse of [metresPerDp]: the camera zoom for a ground scale at [lat]. */
    fun zoomForScale(
        lat: Double,
        metresPerDp: Double,
    ): Double = log2(SPHERE_M * cosLat(lat) / (TILE_DISPLAY_DP * metresPerDp))

    /**
     * `display_zoom` exactly as Rust computes it (with the WGS84 equator, 0.0016 zooms above [zoomForScale]). Only
     * its rounding matters: [fetchZoom].
     */
    fun displayZoom(
        lat: Double,
        metresPerDp: Double,
    ): Double = log2(EQUATOR_M * cosLat(lat) / (TILE_DISPLAY_DP * metresPerDp))

    /** `fetch_zoom`: the tile zoom to ask the server for. */
    fun fetchZoom(displayZoom: Double): Int =
        if (displayZoom.isNaN()) 0 else Math.round(displayZoom).coerceIn(0L, MAX_TILE_ZOOM.toLong()).toInt()

    /** `clamp_scale`. */
    fun clampMetresPerDp(m: Double): Double =
        if (m.isNaN()) MAX_METRES_PER_DP else min(MAX_METRES_PER_DP, max(MIN_METRES_PER_DP, m))

    /** The lowest display zoom the camera may have at [lat] (the 2000 m/dp limit). */
    fun minZoom(lat: Double): Double = zoomForScale(lat, MAX_METRES_PER_DP)

    /** The highest display zoom the camera may have at [lat] (the 0.5 m/dp limit). */
    fun maxZoom(lat: Double): Double = zoomForScale(lat, MIN_METRES_PER_DP)

    private fun cosLat(lat: Double): Double = max(1e-6, cos(Math.toRadians(clampLat(lat))))

    private fun asinh(v: Double): Double = ln(v + Math.sqrt(v * v + 1.0))

    /** `tile_frac` x: the fractional tile column of [lon] at [z], wrapped into the world. */
    fun tileFracX(
        lon: Double,
        z: Int,
    ): Double {
        val n = tilesAcross(z)
        val x = worldX(lon) * n
        val w = x - floor(x / n) * n
        return if (w >= n) 0.0 else w
    }

    /** `tile_frac` y: may be a hair below 0 at the northern edge, like the Rust value. */
    fun tileFracY(
        lat: Double,
        z: Int,
    ): Double = worldY(lat) * tilesAcross(z)

    /** `frac_to_coord` latitude. */
    fun fracToLat(
        z: Int,
        fy: Double,
    ): Double = latOf(fy / tilesAcross(z))

    /** `frac_to_coord` longitude (not wrapped). */
    fun fracToLon(
        z: Int,
        fx: Double,
    ): Double = lonOfUnwrapped(fx / tilesAcross(z))

    fun tilesAcross(z: Int): Double = (1L shl z.coerceIn(0, MAX_ID_ZOOM)).toDouble()
}

/**
 * A tile id packed into a `Long` (6 bits of zoom, 29 of x, 29 of y) so maps and draw loops handle it without boxing.
 */
object TileKey {
    private const val COORD_BITS = 29
    private const val COORD_MASK = (1L shl COORD_BITS) - 1

    /** Not a tile. */
    const val NONE = -1L

    fun of(
        z: Int,
        x: Int,
        y: Int,
    ): Long = (z.toLong() shl (2 * COORD_BITS)) or (x.toLong() shl COORD_BITS) or y.toLong()

    /** A tile from an unbounded column: [x] wraps around the antimeridian. */
    fun wrapped(
        z: Int,
        x: Long,
        y: Int,
    ): Long = of(z, Math.floorMod(x, 1L shl z).toInt(), y)

    /** The tile, or [NONE] when it is not on the map at that zoom (`TileId::new`). */
    fun checked(
        z: Int,
        x: Long,
        y: Long,
    ): Long {
        if (z < 0 || z > MapMath.MAX_ID_ZOOM) return NONE
        val n = 1L shl z
        return if (x in 0 until n && y in 0 until n) of(z, x.toInt(), y.toInt()) else NONE
    }

    fun z(key: Long): Int = (key ushr (2 * COORD_BITS)).toInt()

    fun x(key: Long): Int = ((key ushr COORD_BITS) and COORD_MASK).toInt()

    fun y(key: Long): Int = (key and COORD_MASK).toInt()

    /** The tile [levels] zooms up that contains [key], or [NONE] above zoom 0 (`TileId::ancestor`). */
    fun ancestor(
        key: Long,
        levels: Int,
    ): Long {
        val z = z(key)
        if (levels < 0 || levels > z) return NONE
        return of(z - levels, x(key) shr levels, y(key) shr levels)
    }

    /** The tile containing a coordinate (`tile_containing`). */
    fun containing(
        lat: Double,
        lon: Double,
        z: Int,
    ): Long {
        val zz = z.coerceIn(0, MapMath.MAX_ID_ZOOM)
        val max = (1L shl zz) - 1
        val x = floor(MapMath.tileFracX(lon, zz)).toLong().coerceIn(0, max)
        val y = floor(MapMath.tileFracY(lat, zz)).toLong().coerceIn(0, max)
        return of(zz, x.toInt(), y.toInt())
    }

    fun describe(key: Long): String = if (key == NONE) "none" else "${z(key)}/${x(key)}/${y(key)}"
}

/** How many zooms up [standIn] looks for a loaded ancestor. */
const val STAND_IN_LEVELS = 4

/**
 * The nearest loaded stand-in for a wanted tile: itself, else its ancestors up to [STAND_IN_LEVELS] zooms up, else
 * [TileKey.NONE]. The Kotlin twin of `rosty_geo::tiles::stand_in` (the draw loop inlines the same walk).
 */
inline fun standIn(
    want: Long,
    have: (Long) -> Boolean,
): Long {
    for (d in 0..STAND_IN_LEVELS) {
        val id = TileKey.ancestor(want, d)
        if (id == TileKey.NONE) return TileKey.NONE
        if (have(id)) return id
    }
    return TileKey.NONE
}
