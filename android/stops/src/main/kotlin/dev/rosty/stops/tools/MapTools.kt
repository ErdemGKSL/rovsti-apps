package dev.rosty.stops.tools

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.Appearance
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.CoordFormatRequest
import dev.rosty.core.model.stops.CoordFormatRow
import dev.rosty.core.model.stops.DestShareRequest
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.FollowRequest
import dev.rosty.core.model.stops.Slot
import dev.rosty.design.overlay.ButtonMenuEntry
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.showToast
import dev.rosty.design.overlay.showUndo
import dev.rosty.platform.files.ShareIntents
import dev.rosty.platform.location.TripClock
import dev.rosty.platform.system.permissions.PermissionGroup
import dev.rosty.platform.system.permissions.PermissionState
import dev.rosty.stops.R
import dev.rosty.stops.StopsContainer
import dev.rosty.stops.home.HomeMap
import dev.rosty.stops.home.presentStopsQrSheet
import dev.rosty.stops.map.MapDragClaim
import dev.rosty.stops.map.MapFollowMode
import dev.rosty.stops.map.MapGesturePolicy
import dev.rosty.stops.map.PinMenuModel
import dev.rosty.stops.map.PinMenuStrings
import dev.rosty.stops.pick.CopyFormat
import dev.rosty.stops.pick.presentCopyAsSheet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId

/** What the map tools need from the home that owns them. */
interface MapToolsHost {
    /** A trip runs: the measure tool waits (GPUI `toggle_measure`). */
    val tripRunning: Boolean

    /** The user has saved stops (the "Saved stops on map" row). */
    val hasSaved: Boolean

    /** Whether the saved stops are drawn now. */
    val savedOnMap: Boolean

    fun setSavedOnMap(on: Boolean)

    /** "Save as stop" of a measurement: the first and last point. */
    fun saveGate(
        a: Destination,
        b: Destination,
    )

    /** "Use as A / B" of my location. */
    fun use(
        slot: Slot,
        dest: Destination,
    )

    /** "Export as GPX": the system's Save as for [fileName], then [text] written to it. */
    fun exportGpx(
        fileName: String,
        text: String,
    )
}

/**
 * The map's own tools (E7.9): follow-me on the Me button (S17, `view.follow`), the layers and map options popovers
 * (S11 appearance through the common setting, S40 saved stops on map, S42 map link / share view / copy centre), the
 * measure tool (S8, S26) and the "My location" sheet (S25, the Me button held or the blue dot tapped).
 *
 * [gestures] is the map's gesture policy: while measuring a tap adds a point and every drag pans; otherwise the pick
 * controller has the map. Any pan or zoom by the user ends following.
 */
@Stable
class MapTools internal constructor(
    private val container: StopsContainer,
    private val map: HomeMap,
    private val overlays: OverlayController,
    private val context: Context,
    private val scope: CoroutineScope,
    private val strings: PinMenuStrings,
    private val host: () -> MapToolsHost,
) {
    val measure =
        MeasureTool(
            container.ops,
            scope,
            gpxName = { context.getString(R.string.measure_gpx_name, it) },
            today = {
                Instant
                    .ofEpochMilli(
                        TripClock.nowMillis(),
                    ).atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .toString()
            },
        )

    /** The Me button's state (S17); the button is tinted while [MapFollowMode.Following]. */
    var follow by mutableStateOf(MapFollowMode.Off)
        private set

    val gestures: MapGesturePolicy =
        object : MapGesturePolicy {
            override fun onTap(
                x: Float,
                y: Float,
                lat: Double,
                lon: Double,
            ) {
                if (measure.on) {
                    if (measure.tap(Coord(lat, lon)) == MeasureTap.Full) {
                        val cap = measure.state.value.maxPoints ?: 0
                        overlays.showToast(context.getString(R.string.measure_full, cap))
                    }
                } else {
                    map.pick.onTap(x, y, lat, lon)
                }
            }

            override fun claimDrag(
                x: Float,
                y: Float,
            ): MapDragClaim? = if (measure.on) null else map.pick.claimDrag(x, y)

            override fun onUserMove() {
                map.pick.onUserMove()
                if (follow != MapFollowMode.Off) scope.launch { step("pan") }
            }
        }

    private fun toast(id: Int) = overlays.showToast(context.getString(id))

    private fun fix(): Coord? =
        map.pick.radar.state.position
            ?.let { Coord(it.lat, it.lon) }

    /** The next follow mode from the core; announces a change of following (GPUI `sync_follow`). */
    private suspend fun step(event: String): MapFollowMode? {
        val before = follow
        val answer =
            (container.ops.viewFollow(FollowRequest(before.wire, event, hasFix = fix() != null)) as? Outcome.Ok)?.value
                ?: return null
        val next = MapFollowMode.fromWire(answer.mode) ?: return null
        follow = next
        if (next.watches !=
            before.watches
        ) {
            toast(if (next.watches) R.string.tools_following else R.string.tools_stopped_following)
        }
        return next
    }

    /** S17: centre once, then follow every fix, then let go. */
    fun onLocate() {
        scope.launch {
            when (step("me_tap")) {
                MapFollowMode.Centred -> centreOnMe()
                MapFollowMode.Following -> fix()?.let { map.camera.centreOn(it.lat, it.lon) }
                MapFollowMode.Off, null -> Unit
            }
        }
    }

    private suspend fun centreOnMe() {
        fix()?.let {
            showAtStreet(it)
            return
        }
        if (container.permissions.state(PermissionGroup.LOCATION).first() != PermissionState.Granted) {
            toast(R.string.tools_allow_location)
            return
        }
        toast(R.string.tools_looking)
        val first =
            withTimeoutOrNull(LOCATE_WAIT_MS) {
                snapshotFlow { map.pick.radar.state.position }.filterNotNull().first()
            }
        if (first != null && follow == MapFollowMode.Centred) showAtStreet(Coord(first.lat, first.lon))
    }

    private fun showAtStreet(at: Coord) {
        // GPUI `locate_me`: the position at street scale, in the middle of the free area.
        map.camera.setView(at.lat, at.lon, STREET_METRES_PER_DP)
        map.camera.centreOn(at.lat, at.lon)
    }

    /** Every new fix re-centres the view while following. */
    internal suspend fun followFixes() {
        snapshotFlow { map.pick.radar.state.position }.filterNotNull().collect { position ->
            if (follow == MapFollowMode.Following) map.camera.centreOn(position.lat, position.lon)
        }
    }

    // ---- S25 my location ----

    /** The Me button held, or the blue dot tapped. */
    fun openMyLocation() {
        val position = map.pick.radar.state.position
        if (position == null) {
            toast(R.string.tools_no_fix)
            return
        }
        val at = Coord(position.lat, position.lon)
        val title = context.getString(R.string.tools_my_location)
        overlays.presentMyLocationSheet(
            MyLocationModel(title, context.getString(R.string.tools_accuracy, position.accuracyM.roundedMetres())),
            object : MyLocationActions {
                override fun copyAs() = copyAs(title, at)

                override fun share() = sharePoint(at, title)

                override fun qr() = showQr(title, at)

                override fun use(slot: Slot) = host().use(slot, Destination(title, at))
            },
        )
    }

    // ---- the popovers ----

    /** The layers popover: saved stops on the map (S40) and the measure tool (S8). */
    fun openLayers(anchor: Rect) {
        val now = host()
        val items = ArrayList<MenuItem>()
        if (now.hasSaved) {
            items +=
                MenuItem.Action(context.getString(R.string.tools_saved_on_map), checked = now.savedOnMap) {
                    val on = !now.savedOnMap
                    now.setSavedOnMap(on)
                    toast(if (on) R.string.tools_saved_shown else R.string.tools_saved_hidden)
                }
        }
        items += MenuItem.Action(context.getString(R.string.tools_measure), checked = measure.on) { toggleMeasure() }
        overlays.present(ButtonMenuEntry(anchor, items.toImmutableList()))
    }

    /** The map options popover: the common Appearance (S11) and this view's links (S42). */
    fun openOptions(anchor: Rect) {
        val settings = container.commonSettings
        val current = settings.appearance.value ?: Appearance.Auto
        val items = ArrayList<MenuItem>()
        for ((choice, label) in appearances()) {
            items +=
                MenuItem.Action(label, checked = choice == current) {
                    settings.setAppearance(choice)
                    overlays.showToast(context.getString(R.string.tools_appearance_set, label))
                }
        }
        items += MenuItem.Separator
        items +=
            MenuItem.Action(context.getString(R.string.tools_copy_link)) {
                viewLinks { copy(it.osmUrl, R.string.tools_link_copied) }
            }
        items += MenuItem.Action(context.getString(R.string.tools_share_view)) { viewLinks { share(it.shareText) } }
        items +=
            MenuItem.Action(context.getString(R.string.tools_copy_centre)) {
                copyAs(context.getString(R.string.tools_centre_title), centre())
            }
        overlays.present(ButtonMenuEntry(anchor, items.toImmutableList()))
    }

    private fun appearances(): List<Pair<Appearance, String>> =
        listOf(
            Appearance.Auto to context.getString(R.string.tools_appearance_auto),
            Appearance.Light to context.getString(R.string.tools_appearance_light),
            Appearance.Dark to context.getString(R.string.tools_appearance_dark),
        )

    /** The middle of the free area (what the user sees as the centre of the view). */
    private fun centre(): Coord {
        val camera = map.camera
        val x = camera.paddingLeft + (camera.widthPx - camera.paddingLeft - camera.paddingRight) / 2f
        val y = camera.paddingTop + (camera.heightPx - camera.paddingTop - camera.paddingBottom) / 2f
        return Coord(camera.latAt(y), camera.lonAt(x))
    }

    private fun viewLinks(use: (dev.rosty.core.model.stops.ViewLinks) -> Unit) {
        val at = centre()
        val zoom = map.camera.zoom
        scope.launch {
            val links = (container.ops.coordFormat(CoordFormatRequest(at, zoom = zoom)) as? Outcome.Ok)?.value?.view
            links?.let(use)
        }
    }

    // ---- shared helpers ----

    private fun copy(
        text: String,
        done: Int,
    ) {
        if (container.clipboard.copy(text)) toast(done)
    }

    private fun share(text: String) {
        try {
            context.startActivity(ShareIntents.text(text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: android.content.ActivityNotFoundException) {
            copy(text, R.string.home_copied)
        }
    }

    private fun formats(
        at: Coord,
        label: String? = null,
        then: (List<CoordFormatRow>) -> Unit,
    ) {
        scope.launch {
            val answer = (container.ops.coordFormat(CoordFormatRequest(at, label = label)) as? Outcome.Ok)?.value
            then(answer?.formats.orEmpty())
        }
    }

    private fun copyAs(
        title: String,
        at: Coord,
    ) = formats(at) { rows ->
        val formats = rows.map { CopyFormat(strings.formats[it.id] ?: it.label, it.text) }.toImmutableList()
        overlays.presentCopyAsSheet(title, formats) { format ->
            if (container.clipboard.copy(format.text)) {
                overlays.showToast(context.getString(R.string.tools_copied_format, format.label))
            }
        }
    }

    private fun sharePoint(
        at: Coord,
        label: String,
    ) {
        scope.launch {
            val text = (container.ops.destShare(DestShareRequest(at, label = label)) as? Outcome.Ok)?.value?.text
            text?.let(::share)
        }
    }

    private fun showQr(
        title: String,
        at: Coord,
    ) = formats(at, title) { rows ->
        val geo = rows.firstOrNull { it.id == PinMenuModel.FORMAT_GEO }?.text ?: return@formats
        scope.launch { overlays.presentStopsQrSheet(context, container.qr, title, geo) { share(it) } }
    }

    // ---- the measure tool ----

    /** The ruler on or off (the layers popover). */
    fun toggleMeasure() {
        if (measure.on) {
            measure.done()
            toast(R.string.measure_finished)
            return
        }
        if (host().tripRunning) {
            toast(R.string.measure_after_trip)
            return
        }
        measure.start()
    }

    /** S34 "Measure from here" of the pin menu. */
    fun measureFrom(from: Coord) {
        if (host().tripRunning) {
            toast(R.string.measure_after_trip)
            return
        }
        measure.start(from)
    }

    fun clearMeasure() {
        val old = measure.clear()
        // The GPUI card asked a second tap above two points; Undo gives the points back instead.
        if (old.isNotEmpty()) {
            overlays.showUndo(context.getString(R.string.measure_cleared), {
                measure.restore(old)
            }, context.getString(R.string.measure_undo))
        }
    }

    /** The rows of "More" (GPUI `measure_actions`): copy and GPX from two points, Save as stop when they make a gate. */
    fun moreItems(
        summary: String,
        result: MeasureResult,
    ): List<MenuItem> {
        val items = ArrayList<MenuItem>()
        items +=
            MenuItem.Action(context.getString(R.string.measure_copy)) {
                val text =
                    measureCopyText(summary, result.legs) { n, leg -> context.getString(R.string.measure_leg, n, leg) }
                copy(text, R.string.measure_copied)
            }
        result.fileName?.let { name ->
            items +=
                MenuItem.Action(context.getString(R.string.measure_gpx)) { host().exportGpx(name, result.gpx) }
        }
        result.gate?.let { gate ->
            items +=
                MenuItem.Action(strings.saveAsStop) {
                    host().saveGate(Destination("", gate.a), Destination("", gate.b))
                }
        }
        return items
    }

    private companion object {
        /** GPUI `map::STREET_MPP`: one place opens at street scale. */
        const val STREET_METRES_PER_DP = 1.5

        const val LOCATE_WAIT_MS = 15_000L
    }
}

private fun Double.roundedMetres(): Int = if (isFinite()) Math.round(coerceAtLeast(0.0)).toInt() else 0

/** The home's [MapTools]; [host] is read when a tool needs it, so the screen may pass a new one each composition. */
@Composable
fun rememberMapTools(
    container: StopsContainer,
    map: HomeMap,
    overlays: OverlayController,
    strings: PinMenuStrings,
    host: MapToolsHost,
): MapTools {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(host)
    val tools = remember(container, map) { MapTools(container, map, overlays, context, scope, strings) { latest } }
    LaunchedEffect(tools) { tools.followFixes() }
    // The radar draws the measured line and its points.
    LaunchedEffect(tools) {
        tools.measure.state.collect { state ->
            val flat = DoubleArray(state.points.size * 2)
            state.points.forEachIndexed { i, c ->
                flat[2 * i] = c.lat
                flat[2 * i + 1] = c.lon
            }
            map.pick.radar.update { it.copy(measure = flat) }
        }
    }
    return tools
}
