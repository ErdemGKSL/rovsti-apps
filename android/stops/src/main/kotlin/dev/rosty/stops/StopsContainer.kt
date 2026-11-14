package dev.rosty.stops

import android.content.Context
import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.TilesOpenRequest
import dev.rosty.core.ops.NativeQrOps
import dev.rosty.core.ops.NativeStopsOps
import dev.rosty.core.ops.NativeTilesOps
import dev.rosty.core.ops.QrOps
import dev.rosty.core.ops.StopsOps
import dev.rosty.core.ops.TileHandle
import dev.rosty.core.ops.TilesOps
import dev.rosty.platform.location.AndroidLocationRepository
import dev.rosty.platform.location.AndroidTripRepository
import dev.rosty.platform.location.LocationRepository
import dev.rosty.platform.location.TripRepository
import dev.rosty.platform.system.permissions.AppPermissions
import dev.rosty.shell.ShellContainer
import dev.rosty.shell.settings.CommonSettings
import dev.rosty.stops.data.StopsCommonSettings
import dev.rosty.stops.data.StopsStore
import dev.rosty.stops.data.StopsTiles
import dev.rosty.stops.home.CoreHomeData
import dev.rosty.stops.home.HomeData
import dev.rosty.stops.home.HomeSheet
import dev.rosty.stops.map.TileSource
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * What Rosty Stops adds to the shared [ShellContainer] (ARCH 5.3). Every member is lazy and built by a `create*` seam,
 * so a cold path (the boot receiver, the trip service) pays for what it touches and tests swap in fakes without the
 * native library.
 */
open class StopsContainer(
    context: Context,
) : ShellContainer(context, AppPermissions.stops) {
    /** Every Stops decision the Rust core makes (E2.12): trip, pick, saved stops, history, prefs, GTFS, `view.*`. */
    val ops: StopsOps by lazy { createOps() }

    /** The tile cache and source management (E2.13). */
    val tilesOps: TilesOps by lazy { createTilesOps() }

    /** `stops-prefs.json` as a flow; the settings pages and the activity's appearance follow it. */
    val store: StopsStore by lazy { StopsStore(ops) }

    /** Appearance lives in `stops-prefs.json` (S11); Stops has no haptics preference, so that row is hidden. */
    override val commonSettings: CommonSettings by lazy { StopsCommonSettings(store, scope) }

    /** The map's tile source for the user's server (default VersaTiles), cached where the GPUI app kept it. */
    val tiles: StopsTiles by lazy {
        StopsTiles(
            prefs = { store.prefs.value },
            cacheRoot = File(context.noBackupFilesDir, TILES_DIR).absolutePath,
            open = { request -> openTiles(request) },
        )
    }

    /**
     * Where the map screen gets its tiles: the open [tiles] handle, opened on the first tile asked for (so the
     * container stays cheap) and reopened when the server changes.
     */
    open fun createTileSource(): TileSource =
        TileSource { z, x, y, dark ->
            when (val handle = tiles.handle()) {
                is Outcome.Err -> handle
                is Outcome.Ok -> handle.value.tile(z, x, y, dark = dark)
            }
        }

    /** QR codes of places and stops (S3). */
    val qr: QrOps by lazy { createQr() }

    /** One-shot fixes and live updates for the map's blue dot and follow mode. */
    val location: LocationRepository by lazy { createLocation() }

    /** Starts and stops `TripService` and reports its status. */
    val trips: TripRepository by lazy { createTrips() }

    /** What the first screen reads (prefs, trip, saved stops, history, GTFS). */
    val homeData: HomeData by lazy { createHomeData() }

    /** A link an intent brought (a shared place, a stop shortcut); the home consumes and clears it. */
    val pendingLink: MutableStateFlow<StopsLink?> = MutableStateFlow(null)

    /** A sheet a page asked the map to open on the way back (the bar's Saved on Settings); the home clears it. */
    val pendingSheet: MutableStateFlow<HomeSheet?> = MutableStateFlow(null)

    protected open fun createOps(): StopsOps = NativeStopsOps(rust)

    protected open fun createTilesOps(): TilesOps = NativeTilesOps(rust)

    protected open suspend fun openTiles(request: TilesOpenRequest): Outcome<TileHandle> =
        TileHandle.open(request, tilesOps)

    protected open fun createQr(): QrOps = NativeQrOps(rust)

    protected open fun createLocation(): LocationRepository = AndroidLocationRepository(context)

    protected open fun createTrips(): TripRepository = AndroidTripRepository(context)

    protected open fun createHomeData(): HomeData = CoreHomeData(ops, store)

    private companion object {
        /** `rosty_stops::tiles::cache_root`: the GPUI app's cache, one folder per server under it. */
        const val TILES_DIR = "tiles"
    }
}
