package dev.rosty.stops.map

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The map camera (ARCH 8.4 step 1): centre, fractional zoom, viewport and padding, with the Web-Mercator maths that
 * turn coordinates into screen pixels and back, pan, zoom about a pixel, fling and animated zoom.
 *
 * **It is a holder read in draw.** The numbers are plain fields; every change bumps one snapshot counter that only
 * [observe] reads, and [observe] is meant for draw and layout lambdas. A pan or a pinch therefore invalidates the draw
 * of the nodes that called [observe] and recomposes nothing. [moving] is the one value meant for composition and it
 * changes on edges only (a gesture or an animation starts, everything is at rest).
 *
 * Screen coordinates are pixels from the top-left corner of the viewport, x right and y down. [zoom] is the display
 * zoom of the GPUI map: a tile of zoom `z` is 512 dp wide at `zoom == z`. The limits
 * are the GPUI ones: the scale at the centre stays within 0.5..2000 m per dp and the centre within 85 degrees of
 * latitude. Longitude wraps around the antimeridian.
 *
 * Unlike `rosty_geo::view::Viewport` (a local equirectangular approximation around the centre) the projection here
 * is exact Web Mercator, so pins stay on the tiles at every zoom; over a city the two agree to a fraction of a pixel,
 * which is what `MapCameraFixtureTest` checks against the `view.*` fixtures.
 *
 * Call from the main thread. Non-finite input is refused: the call changes nothing and answers `false`.
 */
@Stable
class MapCamera(
    lat: Double = DEFAULT_LAT,
    lon: Double = DEFAULT_LON,
    metresPerDp: Double = DEFAULT_METRES_PER_DP,
) {
    /** World (normalised Mercator) position of the viewport centre; x in 0..1. */
    var centreX = 0.5
        private set
    var centreY = 0.5
        private set

    /** Display zoom (fractional). */
    var zoom = 0.0
        private set

    var widthPx = 0
        private set
    var heightPx = 0
        private set

    /** Pixels per dp. */
    var density = 1f
        private set

    /** The part of the viewport covered by floating chrome or the settled sheet, in px. */
    var paddingLeft = 0f
        private set
    var paddingTop = 0f
        private set
    var paddingRight = 0f
        private set
    var paddingBottom = 0f
        private set

    private val version = mutableIntStateOf(0)
    private val movingState = mutableStateOf(false)

    /** True while a finger moves the map or an animation runs. Written on edges, safe to read in composition. */
    val moving: State<Boolean> get() = movingState

    private var gesturing = false
    private var pinching = false
    private var flingVx = 0.0
    private var flingVy = 0.0
    private var zoomAnim = false
    private var zoomFrom = 0.0
    private var zoomTarget = 0.0
    private var zoomFocalX = 0f
    private var zoomFocalY = 0f
    private var zoomElapsed = 0.0
    private var travel = false
    private var travelFromX = 0.0
    private var travelFromY = 0.0
    private var travelFromZoom = 0.0
    private var travelToX = 0.0
    private var travelToY = 0.0
    private var travelToZoom = 0.0
    private var travelElapsed = 0.0

    private val listeners = ArrayList<() -> Unit>(2)

    init {
        val ok = setView(lat, lon, metresPerDp)
        if (!ok) setView(DEFAULT_LAT, DEFAULT_LON, DEFAULT_METRES_PER_DP)
    }

    // ---- reading ----

    /**
     * Subscribes the calling draw or layout scope to camera changes and answers a number that changes with every one.
     * Do not call it in composition: that would recompose per frame.
     */
    fun observe(): Int = version.intValue

    /** A number that changes with every camera change, without subscribing (for caches and tests). */
    val generation: Int get() = changes

    private var changes = 0

    val centreLat: Double get() = MapMath.latOf(centreY)
    val centreLon: Double get() = MapMath.lonOf(centreX)

    /** Ground metres per dp at the centre (`Viewport::metres_per_px` of the GPUI map), on the sphere of `rosty_geo`. */
    val metresPerDp: Double get() = MapMath.metresPerDp(centreLat, zoom)

    /** Ground metres per screen pixel at the centre. */
    val metresPerPx: Double get() = metresPerDp / density

    /** Ground metres per dp at the latitude under screen row [y]; the scale bar uses its own row. */
    fun metresPerDpAt(y: Float): Double = MapMath.metresPerDp(latAt(y), zoom)

    /** Width of the whole world in screen pixels at the current zoom. */
    val worldPx: Double get() = MapMath.TILE_DISPLAY_DP * density * MapMath.pow2(zoom)

    /** The zoom in screen pixels: a 512 px bitmap of a tile of zoom `z` is drawn 1:1 at `pixelZoom == z`. */
    val pixelZoom: Double get() = zoom + MapMath.log2(density.toDouble())

    /** `display_zoom` as Rust computes it for this view; its rounding is the tile zoom to fetch. */
    val displayZoom: Double get() = MapMath.displayZoom(centreLat, metresPerDp)

    /** True while a pinch or a zoom animation changes the zoom; tiles are re-rasterised when it ends. */
    val zooming: Boolean get() = pinching || zoomAnim || (travel && travelFromZoom != travelToZoom)

    /**
     * The centre of the area the padding leaves free, where "centre on" puts a point. On an axis where the padding
     * leaves less than [MIN_FREE_DP] (a sheet in landscape covers nearly the whole height) the padding of that axis
     * is ignored and the focus is the middle of the viewport: aiming at a sliver would push the content off screen.
     */
    val focusX: Float get() = if (paddingCountsX) (paddingLeft + (widthPx - paddingRight)) / 2f else widthPx / 2f
    val focusY: Float get() = if (paddingCountsY) (paddingTop + (heightPx - paddingBottom)) / 2f else heightPx / 2f

    /** Whether the padding leaves a usable strip on each axis; see [focusX]. */
    val paddingCountsX: Boolean get() = widthPx - paddingLeft - paddingRight >= MIN_FREE_DP * density
    val paddingCountsY: Boolean get() = heightPx - paddingTop - paddingBottom >= MIN_FREE_DP * density

    /** The length [fit] may use on an axis: the free strip (or the whole axis, see [focusX]) less the margins. */
    private fun fitRoom(
        size: Int,
        padding: Float,
        counts: Boolean,
        marginPx: Float,
    ): Double {
        val free = if (counts) size - padding else size.toFloat()
        // A margin that would leave less than the minimum is shrunk rather than obeyed.
        val least = minOf(free, MIN_FIT_DP * density)
        return max(1.0, max(least, free - 2 * max(0f, marginPx)).toDouble())
    }

    /** Screen x of a longitude: the copy of the world nearest to the centre. */
    fun screenX(lon: Double): Float =
        (MapMath.wrapDelta(MapMath.worldX(lon), centreX) * worldPx + widthPx / 2.0).toFloat()

    /** Screen y of a latitude. */
    fun screenY(lat: Double): Float = ((MapMath.worldY(lat) - centreY) * worldPx + heightPx / 2.0).toFloat()

    /** The longitude under screen column [x], in -180..180. */
    fun lonAt(x: Float): Double = MapMath.lonOf(worldXAt(x))

    /** The latitude under screen row [y] (the edge of the projection beyond it). */
    fun latAt(y: Float): Double = MapMath.latOf(worldYAt(y).coerceIn(0.0, 1.0))

    /** World x under a screen column; not wrapped, so it grows steadily across the antimeridian. */
    fun worldXAt(x: Float): Double = centreX + (x - widthPx / 2.0) / worldPx

    fun worldYAt(y: Float): Double = centreY + (y - heightPx / 2.0) / worldPx

    /**
     * The index of the point nearest to the pixel ([x], [y]) within [maxPx], or -1 (`Viewport::nearest_on_screen`).
     * [latLon] holds `lat0, lon0, lat1, lon1, ...`.
     */
    fun nearestOnScreen(
        latLon: DoubleArray,
        x: Float,
        y: Float,
        maxPx: Float,
    ): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in 0 until latLon.size / 2) {
            val d = hypot(screenX(latLon[2 * i + 1]) - x, screenY(latLon[2 * i]) - y)
            if (d <= maxPx && d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    // ---- setting ----

    /** The viewport in px and the screen density. A zero-size viewport is allowed (nothing is laid out yet). */
    fun setViewport(
        widthPx: Int,
        heightPx: Int,
        density: Float,
    ): Boolean {
        if (widthPx < 0 || heightPx < 0 || !density.isFinite() || density <= 0f) return false
        if (widthPx == this.widthPx && heightPx == this.heightPx && density == this.density) return true
        this.widthPx = widthPx
        this.heightPx = heightPx
        this.density = density
        changed()
        return true
    }

    /**
     * The glass-covered edges of the viewport in px (ARCH 8.4: the settled sheet detent and the floating chrome).
     * [fit], [centreOn] and [travelTo] aim at the free area. Set it on settle, not per frame.
     */
    fun setPadding(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Boolean {
        if (!(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())) return false
        paddingLeft = max(0f, left)
        paddingTop = max(0f, top)
        paddingRight = max(0f, right)
        paddingBottom = max(0f, bottom)
        changed()
        return true
    }

    /** Puts the viewport centre on a coordinate at a scale (`Viewport::new`): the scale is clamped. */
    fun setView(
        lat: Double,
        lon: Double,
        metresPerDp: Double,
    ): Boolean {
        if (!lat.isFinite() || !lon.isFinite() || !metresPerDp.isFinite()) return false
        val clamped = clampCentreLat(lat)
        return jumpTo(clamped, lon, MapMath.zoomForScale(clamped, MapMath.clampMetresPerDp(metresPerDp)))
    }

    /** Puts the viewport centre on a coordinate at a display zoom, without animation. */
    fun jumpTo(
        lat: Double,
        lon: Double,
        zoom: Double = this.zoom,
    ): Boolean {
        if (!lat.isFinite() || !lon.isFinite() || !zoom.isFinite()) return false
        stopAnimations()
        centreX = MapMath.wrap01(MapMath.worldX(lon))
        centreY = MapMath.worldY(clampCentreLat(lat))
        this.zoom = zoom
        normalise()
        changed()
        return true
    }

    /** Puts a coordinate in the middle of the free (unpadded) area, keeping the zoom. */
    fun centreOn(
        lat: Double,
        lon: Double,
    ): Boolean {
        if (!jumpTo(lat, lon)) return false
        shiftCentreToFocus()
        changed()
        return true
    }

    /** Moves the content with the finger: [dx] right, [dy] down, in px (`Viewport::pan`). */
    fun panBy(
        dx: Float,
        dy: Float,
    ): Boolean {
        if (!dx.isFinite() || !dy.isFinite()) return false
        if (dx == 0f && dy == 0f) return true
        val w = worldPx
        centreX = MapMath.wrap01(centreX - dx / w)
        centreY -= dy / w
        normalise()
        changed()
        return true
    }

    /**
     * Zooms by [factor] (above 1 zooms in) keeping the coordinate under the pixel ([focalX], [focalY]) where it is
     * (`Viewport::zoom_about`). At a zoom limit the scale stops and the focal point still holds.
     */
    fun zoomAbout(
        factor: Double,
        focalX: Float,
        focalY: Float,
    ): Boolean {
        if (!factor.isFinite() || factor <= 0.0 || !focalX.isFinite() || !focalY.isFinite()) return false
        val ax = worldXAt(focalX)
        val ay = worldYAt(focalY)
        val wanted = zoom + MapMath.log2(factor)
        // The zoom limits depend on the centre latitude, which moves with the anchor: settle in a few rounds.
        repeat(ANCHOR_ROUNDS) {
            zoom = wanted
            clampZoom()
            val w = worldPx
            centreX = ax - (focalX - widthPx / 2.0) / w
            centreY = ay - (focalY - heightPx / 2.0) / w
            clampCentre()
        }
        zoom = wanted
        clampZoom()
        changed()
        return true
    }

    /**
     * Shows the box south-west ([south], [west]) to north-east ([north], [east]) in the free area with [marginPx]
     * around it (`Viewport::fit`, on Mercator). A single point gets [minMetresPerDp]. Like the Rust fit it does not
     * handle a box across the antimeridian.
     *
     * The fit never works with less than [MIN_FREE_DP] on an axis: padding that leaves less is ignored on that axis
     * (the box is fitted to the whole viewport there, partly under the chrome, instead of zooming out to the limit),
     * and a margin that would leave less than [MIN_FIT_DP] is shrunk.
     */
    fun fit(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        marginPx: Float = 0f,
        minMetresPerDp: Double = MapMath.MIN_METRES_PER_DP,
    ): Boolean {
        val finite = south.isFinite() && west.isFinite() && north.isFinite() && east.isFinite()
        if (!finite || !marginPx.isFinite() || !minMetresPerDp.isFinite()) return false
        stopAnimations()
        val x0 = MapMath.worldX(min(west, east))
        val x1 = MapMath.worldX(max(west, east))
        val y0 = MapMath.worldY(max(south, north))
        val y1 = MapMath.worldY(min(south, north))
        val availX = fitRoom(widthPx, paddingLeft + paddingRight, paddingCountsX, marginPx)
        val availY = fitRoom(heightPx, paddingTop + paddingBottom, paddingCountsY, marginPx)
        // World px needed so the spans fit; a zero span asks for infinity and the clamp below answers.
        val world = min(availX / (x1 - x0), availY / (y1 - y0))
        centreX = MapMath.wrap01((x0 + x1) / 2.0)
        centreY = (y0 + y1) / 2.0
        clampCentre()
        val wanted =
            if (world.isFinite()) {
                MapMath.log2(
                    world / (MapMath.TILE_DISPLAY_DP * density),
                )
            } else {
                Double.MAX_VALUE
            }
        val lat = centreLat
        val closest =
            MapMath.zoomForScale(
                lat,
                max(MapMath.MIN_METRES_PER_DP, min(MapMath.MAX_METRES_PER_DP, minMetresPerDp)),
            )
        zoom = min(wanted, closest)
        normalise()
        shiftCentreToFocus()
        changed()
        return true
    }

    /** [fit] for points given as `lat0, lon0, lat1, lon1, ...`. No points shows the widest view at 0, 0 like Rust. */
    fun fitPoints(
        latLon: DoubleArray,
        marginPx: Float = 0f,
        minMetresPerDp: Double = MapMath.MIN_METRES_PER_DP,
    ): Boolean {
        if (latLon.any { !it.isFinite() }) return false
        if (latLon.size < 2) return setView(0.0, 0.0, MapMath.MAX_METRES_PER_DP)
        var south = latLon[0]
        var north = latLon[0]
        var west = latLon[1]
        var east = latLon[1]
        for (i in 1 until latLon.size / 2) {
            south = min(south, latLon[2 * i])
            north = max(north, latLon[2 * i])
            west = min(west, latLon[2 * i + 1])
            east = max(east, latLon[2 * i + 1])
        }
        return fit(south, west, north, east, marginPx, minMetresPerDp)
    }

    /**
     * [fit] for a gate: the whole circle whose diameter is AB, not only its two ends. A west-east gate in a wide
     * window would otherwise fill the width and leave most of its circle above and below the free area.
     */
    fun fitGate(
        aLat: Double,
        aLon: Double,
        bLat: Double,
        bLon: Double,
        marginPx: Float = 0f,
        minMetresPerDp: Double = MapMath.MIN_METRES_PER_DP,
    ): Boolean {
        if (!aLat.isFinite() || !aLon.isFinite() || !bLat.isFinite() || !bLon.isFinite()) return false
        // On the Mercator plane, where the circle is drawn: its centre is the middle of AB, its radius half of AB.
        val ax = MapMath.worldX(aLon)
        val ay = MapMath.worldY(aLat)
        val dx = MapMath.wrapDelta(MapMath.worldX(bLon), ax)
        val dy = MapMath.worldY(bLat) - ay
        val cx = ax + dx / 2.0
        val cy = ay + dy / 2.0
        val r = Math.hypot(dx, dy) / 2.0
        return fit(
            south = MapMath.latOf(min(1.0, cy + r)),
            west = MapMath.lonOfUnwrapped(cx - r),
            north = MapMath.latOf(max(0.0, cy - r)),
            east = MapMath.lonOfUnwrapped(cx + r),
            marginPx = marginPx,
            minMetresPerDp = minMetresPerDp,
        )
    }

    // ---- gestures and animation ----

    /** The gesture layer reports a finger moving the map ([pinch]: with two fingers). Stops running animations. */
    fun setGesture(
        active: Boolean,
        pinch: Boolean = false,
    ) {
        if (active) stopAnimations()
        val was = zooming
        gesturing = active
        pinching = active && pinch
        updateMoving()
        if (was != zooming) notifyListeners()
    }

    /** A finger touched the map: whatever was running stops under it. */
    fun stopAnimations() {
        val was = animating
        flingVx = 0.0
        flingVy = 0.0
        zoomAnim = false
        travel = false
        if (was) {
            updateMoving()
            notifyListeners()
        }
    }

    /** Lets the map coast after a pan released at ([vx], [vy]) px/s; slow releases do nothing. */
    fun fling(
        vx: Float,
        vy: Float,
    ): Boolean {
        if (!vx.isFinite() || !vy.isFinite()) return false
        if (hypot(vx, vy) < MIN_FLING_DP_S * density) return true
        val cap = (MAX_FLING_DP_S * density).toDouble()
        flingVx = vx.toDouble().coerceIn(-cap, cap)
        flingVy = vy.toDouble().coerceIn(-cap, cap)
        startAnimating()
        return true
    }

    /** Zooms by [factor] about a pixel over [ZOOM_ANIMATION_S] (double tap: 2, a zoom-out button: 0.5). */
    fun animateZoomBy(
        factor: Double,
        focalX: Float = focusX,
        focalY: Float = focusY,
    ): Boolean {
        if (!factor.isFinite() || factor <= 0.0 || !focalX.isFinite() || !focalY.isFinite()) return false
        // A second double tap during the first one adds up instead of restarting from a half-way zoom.
        val base = if (zoomAnim) zoomTarget else zoom
        flingVx = 0.0
        flingVy = 0.0
        travel = false
        zoomFrom = zoom
        zoomTarget = base + MapMath.log2(factor)
        zoomFocalX = focalX
        zoomFocalY = focalY
        zoomElapsed = 0.0
        zoomAnim = true
        startAnimating()
        return true
    }

    /** Glides to a coordinate (put in the middle of the free area) and zoom over [TRAVEL_ANIMATION_S]. */
    fun travelTo(
        lat: Double,
        lon: Double,
        zoom: Double = this.zoom,
    ): Boolean {
        if (!lat.isFinite() || !lon.isFinite() || !zoom.isFinite()) return false
        stopAnimations()
        val fromX = centreX
        val fromY = centreY
        val fromZoom = this.zoom
        // Work out the end state with the real clamps, then go back to the start.
        jumpTo(lat, lon, zoom)
        shiftCentreToFocus()
        travelToX = fromX + MapMath.wrapDelta(centreX, fromX)
        travelToY = centreY
        travelToZoom = this.zoom
        centreX = fromX
        centreY = fromY
        this.zoom = fromZoom
        travelFromX = fromX
        travelFromY = fromY
        travelFromZoom = fromZoom
        travelElapsed = 0.0
        travel = true
        changed()
        startAnimating()
        return true
    }

    /** True while [advance] has work; the frame loop of the map runs only then. */
    val animating: Boolean get() = flingVx != 0.0 || flingVy != 0.0 || zoomAnim || travel

    /**
     * Advances the running animations by [dtSeconds] and answers whether any is still running. Pure arithmetic: the
     * composable calls it once per frame, tests call it with made-up times.
     */
    fun advance(dtSeconds: Double): Boolean {
        if (!dtSeconds.isFinite() || dtSeconds <= 0.0 || !animating) return animating
        val wasZooming = zooming
        if (flingVx != 0.0 || flingVy != 0.0) advanceFling(dtSeconds)
        if (zoomAnim) advanceZoom(dtSeconds)
        if (travel) advanceTravel(dtSeconds)
        if (!animating) updateMoving()
        if (wasZooming != zooming) notifyListeners()
        return animating
    }

    private fun advanceFling(dt: Double) {
        // v(t) = v0 * rate^(1000 t): the UIKit scroll deceleration, per millisecond.
        val k = 1000.0 * ln(FLING_RATE)
        val decay = exp(k * dt)
        val travelled = (decay - 1.0) / k
        val beforeY = centreY
        panBy((flingVx * travelled).toFloat(), (flingVy * travelled).toFloat())
        flingVx *= decay
        flingVy *= decay
        // Against the latitude limit the vertical part has nowhere to go.
        if (flingVy != 0.0 && centreY == beforeY) flingVy = 0.0
        if (hypot(flingVx, flingVy) < FLING_STOP_DP_S * density) {
            flingVx = 0.0
            flingVy = 0.0
        }
    }

    private fun advanceZoom(dt: Double) {
        zoomElapsed += dt
        val t = min(1.0, zoomElapsed / ZOOM_ANIMATION_S)
        val wanted = zoomFrom + (zoomTarget - zoomFrom) * easeOut(t)
        zoomAbout(MapMath.pow2(wanted - zoom), zoomFocalX, zoomFocalY)
        if (t >= 1.0) zoomAnim = false
    }

    private fun advanceTravel(dt: Double) {
        travelElapsed += dt
        val t = min(1.0, travelElapsed / TRAVEL_ANIMATION_S)
        val e = easeOut(t)
        centreX = MapMath.wrap01(travelFromX + (travelToX - travelFromX) * e)
        centreY = travelFromY + (travelToY - travelFromY) * e
        zoom = travelFromZoom + (travelToZoom - travelFromZoom) * e
        normalise()
        if (t >= 1.0) travel = false
        changed()
    }

    private fun easeOut(t: Double): Double {
        val u = 1.0 - t
        return 1.0 - u * u * u
    }

    private fun startAnimating() {
        updateMoving()
        notifyListeners()
    }

    private fun updateMoving() {
        val now = gesturing || animating
        if (movingState.value != now) {
            movingState.value = now
            notifyListeners()
        }
    }

    // ---- listeners ----

    /**
     * Calls [listener] after every change (position, viewport, moving, zooming). The tile scheduler and the frame
     * loop listen; a listener must be cheap and must not change the camera.
     */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun changed() {
        changes++
        version.intValue = changes
        notifyListeners()
    }

    private fun notifyListeners() {
        for (i in listeners.indices) listeners[i]()
    }

    // ---- clamps ----

    private fun clampCentreLat(lat: Double): Double = min(MapMath.MAX_CENTRE_LAT, max(-MapMath.MAX_CENTRE_LAT, lat))

    private fun clampCentre() {
        centreX = MapMath.wrap01(centreX)
        centreY = min(MAX_CENTRE_Y, max(MIN_CENTRE_Y, centreY))
    }

    private fun clampZoom() {
        val lat = centreLat
        zoom = min(MapMath.maxZoom(lat), max(MapMath.minZoom(lat), zoom))
    }

    private fun normalise() {
        clampCentre()
        clampZoom()
    }

    private fun shiftCentreToFocus() {
        val w = worldPx
        centreX = MapMath.wrap01(centreX - (focusX - widthPx / 2.0) / w)
        centreY -= (focusY - heightPx / 2.0) / w
        normalise()
    }

    override fun toString(): String = "MapCamera(lat=$centreLat, lon=$centreLon, zoom=$zoom, ${widthPx}x$heightPx)"

    companion object {
        /** The GPUI map's first view: `DEFAULT_CENTRE` at `CITY_MPP`. */
        const val DEFAULT_LAT = 41.0082
        const val DEFAULT_LON = 28.9784
        const val DEFAULT_METRES_PER_DP = 12.0

        /** A double tap zooms in by this (GPUI: `zoom_about(2.0, at)`). */
        const val DOUBLE_TAP_FACTOR = 2.0

        /** The least free length per axis that padding may leave for [fit] and the focus point. */
        const val MIN_FREE_DP = 120f

        /** The least length per axis the margins of [fit] may leave (a short strip keeps most of its margins). */
        const val MIN_FIT_DP = 60f

        const val ZOOM_ANIMATION_S = 0.25
        const val TRAVEL_ANIMATION_S = 0.45

        /** Velocity kept per millisecond while coasting (UIKit `decelerationRate.normal`). */
        const val FLING_RATE = 0.998
        const val MIN_FLING_DP_S = 50f
        const val MAX_FLING_DP_S = 8000f
        const val FLING_STOP_DP_S = 8f

        private const val ANCHOR_ROUNDS = 3
        private val MIN_CENTRE_Y = MapMath.worldY(MapMath.MAX_CENTRE_LAT)
        private val MAX_CENTRE_Y = MapMath.worldY(-MapMath.MAX_CENTRE_LAT)
    }
}

/** The follow-me button's state: the twin of `rosty_geo::view::FollowMode` (see the `view.follow` fixtures). */
enum class MapFollowMode(
    val wire: String,
) {
    Off("off"),
    Centred("centred"),
    Following("following"),
    ;

    fun onMeTap(hasFix: Boolean): MapFollowMode =
        when {
            this == Centred && hasFix -> Following
            this == Following && hasFix -> Off
            else -> Centred
        }

    fun onPan(): MapFollowMode = Off

    /** The view follows every new fix. */
    val watches: Boolean get() = this == Following

    companion object {
        fun fromWire(wire: String): MapFollowMode? = entries.firstOrNull { it.wire == wire }
    }
}
