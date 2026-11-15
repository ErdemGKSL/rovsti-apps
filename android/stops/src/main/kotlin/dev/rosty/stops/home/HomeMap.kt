package dev.rosty.stops.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.rosty.core.model.stops.Destination
import dev.rosty.design.motion.MotionGate
import dev.rosty.platform.location.Fix
import dev.rosty.platform.location.LocationRepository
import dev.rosty.platform.system.permissions.PermissionGroup
import dev.rosty.platform.system.permissions.PermissionState
import dev.rosty.stops.StopsContainer
import dev.rosty.stops.map.MapCamera
import dev.rosty.stops.map.MapTileStore
import dev.rosty.stops.map.PickController
import dev.rosty.stops.map.PickListener
import dev.rosty.stops.map.RadarPosition
import dev.rosty.stops.map.RadarSaved
import dev.rosty.stops.map.StopsPickOps
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The map of the home screen: one camera, one tile store over the container's tile source and the pick controller
 * that is the map's gesture policy (E7.2/E7.3/E7.4 pieces wired as `e7-r1-approach-map.md` says). Built once per map
 * screen; [close] goes with it.
 */
@Stable
class HomeMap(
    val camera: MapCamera,
    val tiles: MapTileStore,
    val pick: PickController,
    val motion: MotionGate,
) {
    /** The view was placed on the first data (trip, my position, saved stops) once. */
    internal var placed = false

    fun close() {
        tiles.close()
        pick.close()
    }
}

/**
 * Builds the [HomeMap] of [container] and closes it (and the tile handle) when the screen goes away. [listener] is
 * read through a state, so the screen can pass a new one each composition.
 */
@Composable
fun rememberHomeMap(
    container: StopsContainer,
    listener: PickListener,
): HomeMap {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val latest by rememberUpdatedState(listener)
    val map =
        remember(container) {
            val camera = MapCamera()
            val forward =
                object : PickListener {
                    override fun onPinTap(slot: dev.rosty.core.model.stops.Slot) = latest.onPinTap(slot)

                    override fun onSavedStopTap(id: Long) = latest.onSavedStopTap(id)

                    override fun onPositionTap() = latest.onPositionTap()

                    override fun onThirdTap() = latest.onThirdTap()

                    override fun onSnapped() = latest.onSnapped()

                    override fun onError(error: dev.rosty.core.RustError) = latest.onError(error)
                }
            HomeMap(
                camera = camera,
                tiles = MapTileStore(container.createTileSource(), scope, MapTileStore.bitmapBudget(context)),
                pick = PickController(StopsPickOps(container.ops), scope, camera, forward),
                motion = MotionGate(scope),
            )
        }
    DisposableEffect(map) {
        onDispose {
            map.close()
            container.scope.launch { container.tiles.close() }
        }
    }
    return map
}

/** The settled glass-covered edges become the camera's padding (the map aims at the free area). */
@Composable
fun FollowCameraPadding(
    map: HomeMap,
    padding: MapCameraPadding,
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    LaunchedEffect(map, padding, density, direction) {
        snapshotFlow { padding.insets }.collect { insets ->
            with(density) {
                map.camera.setPadding(
                    left = insets.calculateLeftPadding(direction).toPx(),
                    top = insets.calculateTopPadding().toPx(),
                    right = insets.calculateRightPadding(direction).toPx(),
                    bottom = insets.calculateBottomPadding().toPx(),
                )
            }
        }
    }
}

/**
 * Puts the first view on the most useful thing, once the map has a size and the files are read (pure choice in
 * [firstView]): the trip in progress, else the user's last known position, else the saved stops, else the default
 * city.
 */
@Composable
fun PlaceFirstView(
    map: HomeMap,
    state: HomeState,
    location: LocationRepository,
) {
    LaunchedEffect(map, state.loading) {
        if (state.loading || map.placed) return@LaunchedEffect
        snapshotFlow {
            map.camera.observe()
            map.camera.widthPx > 0 && map.camera.heightPx > 0
        }.first { it }
        map.placed = true
        when (val view = firstView(state, location.lastKnown())) {
            is FirstView.Fit -> {
                map.camera.fitPoints(view.latLon, marginPx = FIT_MARGIN_DP * map.camera.density)
            }

            is FirstView.Centre -> {
                // GPUI `Viewport::new(at, STREET_MPP)`: the position opens at street scale, then clears the chrome.
                map.camera.setView(view.lat, view.lon, STREET_METRES_PER_DP)
                map.camera.centreOn(view.lat, view.lon)
            }

            FirstView.Default -> {
                Unit
            }
        }
    }
}

private const val FIT_MARGIN_DP = 48f

/** The scale of a view on one place (GPUI `map::STREET_MPP`). */
private const val STREET_METRES_PER_DP = 1.5

/** The view fits the gate of a trip that just started (GPUI follows the trip). */
fun HomeMap.showGate(trip: dev.rosty.stops.trip.RunningTrip) {
    if (camera.widthPx > 0 && camera.heightPx > 0) {
        camera.fitPoints(
            doubleArrayOf(trip.a.coord.lat, trip.a.coord.lon, trip.b.coord.lat, trip.b.coord.lon),
            marginPx = FIT_MARGIN_DP * camera.density,
        )
    }
}

/** Both ends of a saved stop (or a recent trip) go on the map and the view fits them (GPUI `choose_pair`). */
fun HomeMap.showPair(
    a: Destination,
    b: Destination,
) {
    pick.setPair(a, b)
    if (camera.widthPx > 0 && camera.heightPx > 0) {
        camera.fitPoints(
            doubleArrayOf(a.coord.lat, a.coord.lon, b.coord.lat, b.coord.lon),
            marginPx = FIT_MARGIN_DP * camera.density,
        )
    }
}

/** Where the first view goes. */
sealed interface FirstView {
    class Fit(
        val latLon: DoubleArray,
    ) : FirstView

    data class Centre(
        val lat: Double,
        val lon: Double,
    ) : FirstView

    data object Default : FirstView
}

/** The first view for [state] and the last known [fix] (pure). */
fun firstView(
    state: HomeState,
    fix: Fix?,
): FirstView {
    val trip = state.trip
    if (trip?.a != null && trip.b != null) {
        return FirstView.Fit(doubleArrayOf(trip.a.coord.lat, trip.a.coord.lon, trip.b.coord.lat, trip.b.coord.lon))
    }
    if (fix != null && fix.lat.isFinite() && fix.lon.isFinite()) return FirstView.Centre(fix.lat, fix.lon)
    if (state.saved.isNotEmpty()) {
        val points = DoubleArray(state.saved.size * 4)
        state.saved.forEachIndexed { i, stop ->
            points[4 * i] = stop.a.coord.lat
            points[4 * i + 1] = stop.a.coord.lon
            points[4 * i + 2] = stop.b.coord.lat
            points[4 * i + 3] = stop.b.coord.lon
        }
        return FirstView.Fit(points)
    }
    return FirstView.Default
}

/** The draft ends of the last session go back on the map once, and the saved stops follow the files (S40). */
@Composable
fun FeedRadar(
    map: HomeMap,
    state: HomeState,
) {
    val draft = state.draft
    val saved = state.savedFile
    LaunchedEffect(map, draft) {
        if (draft != null && (draft.a != null || draft.b != null) && state.trip == null) map.pick.restore(draft)
    }
    LaunchedEffect(map, saved, state.savedOnMap) {
        map.pick.savedStops = saved
        val markers = saved?.let { RadarSaved.of(it) } ?: RadarSaved.None
        map.pick.radar.update { it.copy(saved = markers, showSaved = state.savedOnMap) }
    }
}

/**
 * Live position for the blue dot (GPUI: foreground only, stopped in `onPause`): [LocationRepository.updates] runs
 * only while the screen is resumed and the location permission is granted; the last known fix shows first.
 */
@Composable
fun LivePosition(
    map: HomeMap,
    container: StopsContainer,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(map, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            container.permissions.state(PermissionGroup.LOCATION).collectLatest { permission ->
                val fixes: Flow<Fix> =
                    if (permission == PermissionState.Granted) container.location.updates() else emptyFlow()
                if (permission == PermissionState.Granted) container.location.lastKnown()?.let { map.showFix(it) }
                fixes.collect { map.showFix(it) }
            }
        }
    }
}

private fun HomeMap.showFix(fix: Fix) {
    if (!fix.lat.isFinite() || !fix.lon.isFinite()) return
    pick.radar.update { it.copy(position = RadarPosition(fix.lat, fix.lon, fix.accuracyM)) }
}

/** "Open in maps" (S4): the `geo:` URI to whatever map app the user has; false when there is none. */
fun Context.openInMaps(geoUri: String): Boolean =
    try {
        startActivity(
            Intent
                .createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(geoUri)), null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
