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
import dev.rosty.platform.contacts.AndroidShortcutsRepository
import dev.rosty.platform.contacts.ShortcutsRepository
import dev.rosty.platform.location.AndroidLocationRepository
import dev.rosty.platform.location.AndroidTripRepository
import dev.rosty.platform.location.LocationRepository
import dev.rosty.platform.location.TripClock
import dev.rosty.platform.location.TripRepository
import dev.rosty.platform.system.permissions.AppPermissions
import dev.rosty.shell.ShellContainer
import dev.rosty.shell.settings.CommonSettings
import dev.rosty.stops.data.SavedStore
import dev.rosty.stops.data.StopsCommonSettings
import dev.rosty.stops.data.StopsStore
import dev.rosty.stops.data.StopsTiles
import dev.rosty.stops.home.CoreHomeData
import dev.rosty.stops.home.HomeData
import dev.rosty.stops.home.HomeSheet
import dev.rosty.stops.map.TileSource
import dev.rosty.stops.offline.MapArea
import dev.rosty.stops.offline.OfflineAreaWorker
import dev.rosty.stops.offline.OfflineDownloads
import dev.rosty.stops.settings.AlarmDevice
import dev.rosty.stops.settings.AndroidAlarmDevice
import dev.rosty.stops.trip.AlarmControl
import dev.rosty.stops.trip.AndroidAlarmControl
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

    /** The saved stops (E7.7): one `saved.json` for the list, the map markers and "Save as stop". */
    val saved: SavedStore by lazy { SavedStore(ops, nowMs = TripClock::nowMillis) }

    /** The data directory Rust was started with: the trip service reads `trip.json` there. */
    open val dataDir: String get() = context.noBackupFilesDir.absolutePath

    /** The ringing alarm of `:platform` for the in-app alarm screen (E7.6). */
    val alarm: AlarmControl by lazy { createAlarm() }

    /** S18: stop shortcuts on the Home screen. */
    val shortcuts: ShortcutsRepository by lazy { createShortcuts() }

    /** A link an intent brought (a shared place, a stop shortcut); the home consumes and clears it. */
    val pendingLink: MutableStateFlow<StopsLink?> = MutableStateFlow(null)

    /** A sheet a page asked the map to open on the way back (the bar's Saved on Settings); the home clears it. */
    val pendingSheet: MutableStateFlow<HomeSheet?> = MutableStateFlow(null)

    /** S10: the offline area download, run by a foreground worker; Settings and its notification follow it. */
    val offline: OfflineDownloads by lazy {
        OfflineDownloads(tiles, scope, schedule = ::scheduleOffline, unschedule = ::unscheduleOffline)
    }

    /** The area the map showed when it last came to rest (the home writes it); Settings downloads it (S10). */
    val mapView: MutableStateFlow<MapArea?> = MutableStateFlow(null)

    /** Bumped when the transit feed was imported or removed; the home's pick controller asks for its stops again. */
    val transitVersion: MutableStateFlow<Int> = MutableStateFlow(0)

    /** The sound picker's previews, the volume test and the test ring (S12-S14). */
    val alarmDevice: AlarmDevice by lazy { createAlarmDevice() }

    protected open fun createOps(): StopsOps = NativeStopsOps(rust)

    protected open fun scheduleOffline(area: MapArea) = OfflineAreaWorker.enqueue(context, area)

    protected open fun unscheduleOffline() = OfflineAreaWorker.cancelQueued(context)

    protected open fun createAlarmDevice(): AlarmDevice = AndroidAlarmDevice(context)

    protected open fun createTilesOps(): TilesOps = NativeTilesOps(rust)

    protected open suspend fun openTiles(request: TilesOpenRequest): Outcome<TileHandle> =
        TileHandle.open(request, tilesOps)

    protected open fun createQr(): QrOps = NativeQrOps(rust)

    protected open fun createLocation(): LocationRepository = AndroidLocationRepository(context)

    protected open fun createTrips(): TripRepository = AndroidTripRepository(context)

    protected open fun createHomeData(): HomeData = CoreHomeData(ops, store)

    protected open fun createAlarm(): AlarmControl = AndroidAlarmControl(context)

    protected open fun createShortcuts(): ShortcutsRepository = AndroidShortcutsRepository(context)

    private companion object {
        /** `rosty_stops::tiles::cache_root`: the GPUI app's cache, one folder per server under it. */
        const val TILES_DIR = "tiles"
    }
}
