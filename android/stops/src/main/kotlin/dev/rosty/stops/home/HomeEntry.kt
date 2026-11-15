package dev.rosty.stops.home

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.Ends
import dev.rosty.core.model.stops.Slot
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.core.model.stops.StoreWriteRequest
import dev.rosty.design.chrome.TabItem
import dev.rosty.design.controls.ChipModel
import dev.rosty.design.haptics.LocalRostyHaptics
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.showToast
import dev.rosty.design.overlay.showUndo
import dev.rosty.design.text.resolve
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.transition.TabNavigator
import dev.rosty.platform.files.ShareIntents
import dev.rosty.platform.location.TripClock
import dev.rosty.stops.HomeTab
import dev.rosty.stops.R
import dev.rosty.stops.StopsContainer
import dev.rosty.stops.StopsLink
import dev.rosty.stops.StopsRoute
import dev.rosty.stops.history.presentHistorySheet
import dev.rosty.stops.map.MapAttribution
import dev.rosty.stops.map.MapFollowMode
import dev.rosty.stops.map.MapMotionEffect
import dev.rosty.stops.map.MapScaleBar
import dev.rosty.stops.map.PickListener
import dev.rosty.stops.map.PinMenuActions
import dev.rosty.stops.map.StopsMap
import dev.rosty.stops.map.pickAnchorOrigin
import dev.rosty.stops.map.presentPinMenu
import dev.rosty.stops.map.rememberPinMenuStrings
import dev.rosty.stops.map.rememberRadarOverlay
import dev.rosty.stops.map.showThirdTapHint
import dev.rosty.stops.pick.Picker
import dev.rosty.stops.pick.rememberPicker
import dev.rosty.stops.saved.SavedAction
import dev.rosty.stops.saved.SavedEffect
import dev.rosty.stops.saved.SavedViewModel
import dev.rosty.stops.saved.presentSavedSheet
import dev.rosty.stops.saved.presentSavedStopSheet
import dev.rosty.stops.saved.toDestination
import dev.rosty.stops.tools.MapTools
import dev.rosty.stops.tools.MeasureCard
import dev.rosty.stops.tools.rememberMapTools
import dev.rosty.stops.trip.AlarmAction
import dev.rosty.stops.trip.AlarmEffect
import dev.rosty.stops.trip.AlarmEntry
import dev.rosty.stops.trip.AlarmViewModel
import dev.rosty.stops.trip.LastTripCard
import dev.rosty.stops.trip.TripAction
import dev.rosty.stops.trip.TripEffect
import dev.rosty.stops.trip.TripProblem
import dev.rosty.stops.trip.TripSection
import dev.rosty.stops.trip.TripViewModel
import dev.rosty.stops.trip.presentPreflightSheet
import dev.rosty.stops.trip.rememberPreflight
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.TimeZone
import kotlin.math.abs

/** The home's view model, owned by the activity so a pushed page and the way back keep one state. */
@Composable
fun rememberHomeViewModel(container: StopsContainer): HomeViewModel {
    val context = LocalContext.current
    val owner = remember(context) { context.findComponentActivity() }
    return remember(owner, container) {
        val factory = viewModelFactory { initializer { HomeViewModel(container.homeData) } }
        ViewModelProvider(owner, factory)[HomeViewModel::class.java]
    }
}

/** The Saved list's view model, owned by the activity like the home's. */
@Composable
fun rememberSavedViewModel(container: StopsContainer): SavedViewModel {
    val context = LocalContext.current
    val owner = remember(context) { context.findComponentActivity() }
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer {
                    SavedViewModel(
                        store = container.saved,
                        ops = container.ops,
                        here = { container.location.lastKnown()?.let { Coord(it.lat, it.lon) } },
                        nowMs = TripClock::nowMillis,
                        tzOffsetS = { TimeZone.getDefault().getOffset(TripClock.nowMillis()) / MS_PER_S },
                    )
                }
            }
        ViewModelProvider(owner, factory)[SavedViewModel::class.java]
    }
}

private const val MS_PER_S = 1000

/** The alarm's view model, owned by the activity like the home's. */
@Composable
fun rememberAlarmViewModel(container: StopsContainer): AlarmViewModel {
    val context = LocalContext.current
    val owner = remember(context) { context.findComponentActivity() }
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer {
                    AlarmViewModel(
                        ops = container.ops,
                        control = container.alarm,
                        prefs = { (container.store.load() as? Outcome.Ok)?.value ?: StopsPrefs() },
                        dir = container.dataDir,
                        nowMs = TripClock::nowMillis,
                        tzOffsetS = { TimeZone.getDefault().getOffset(TripClock.nowMillis()) / MS_PER_S },
                    )
                }
            }
        ViewModelProvider(owner, factory)[AlarmViewModel::class.java]
    }
}

/** The trip's view model, owned by the activity like the home's. */
@Composable
fun rememberTripViewModel(container: StopsContainer): TripViewModel {
    val context = LocalContext.current
    val owner = remember(context) { context.findComponentActivity() }
    val waiting = stringResource(R.string.trip_waiting_fix)
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer {
                    TripViewModel(
                        ops = container.ops,
                        trips = container.trips,
                        prefs = { (container.store.load() as? Outcome.Ok)?.value ?: StopsPrefs() },
                        dir = container.dataDir,
                        here = { container.location.lastKnown()?.let { Coord(it.lat, it.lon) } },
                        nowMs = TripClock::nowMillis,
                        waitingText = waiting,
                    )
                }
            }
        ViewModelProvider(owner, factory)[TripViewModel::class.java]
    }
}

/** The items of the home's folded bar (the GPUI tabs and icons: navigation, bookmark, gear). */
@Composable
fun homeTabItems() =
    persistentListOf(
        TabItem(HomeTab.Trip.id, stringResource(R.string.tab_trip), StopsIcons.Navigation),
        TabItem(HomeTab.Saved.id, stringResource(R.string.tab_saved), StopsIcons.Bookmark),
        TabItem(HomeTab.Settings.id, stringResource(R.string.tab_settings), RostyIcons.Gear),
    )

/**
 * The home route: [StopsHome] over its view model. The bar's Trip is the map (it closes a sheet that is open), Saved
 * opens the Saved sheet over the map and Settings pushes the Settings page. A link the activity received (a stop
 * shortcut, a shared place) is consumed here once.
 */
@Composable
fun HomeEntry(
    container: StopsContainer,
    navigator: TabNavigator<StopsRoute>,
) {
    val viewModel = rememberHomeViewModel(container)
    val saved = rememberSavedViewModel(container)
    val trip = rememberTripViewModel(container)
    val alarm = rememberAlarmViewModel(container)
    val preflight = rememberPreflight()
    // The History sheet (E7.11); what it hands back goes through these, defined once the map exists.
    var historyStart: (Destination, Destination) -> Unit = { _, _ -> }
    var historyShow: (Destination, Destination) -> Unit = { _, _ -> }
    val history =
        rememberHistory(
            container,
            object : HistoryHost {
                override val tripRunning: Boolean get() = trip.state.value.trip != null

                override fun start(
                    a: Destination,
                    b: Destination,
                ) = historyStart(a, b)

                override fun showOnMap(
                    a: Destination,
                    b: Destination,
                ) = historyShow(a, b)

                override fun saveGate(
                    a: Destination,
                    b: Destination,
                ) = saved.onAction(SavedAction.SaveGate(a, b))

                override fun reloaded() {
                    viewModel.refresh()
                    saved.refresh()
                    container.scope.launch { container.saved.reload() }
                }
            },
        )
    val loaded by viewModel.state.collectAsState()
    val savedFile by container.saved.saved.collectAsState()
    // The saved stops follow every edit (the map markers, "Save as stop" offers) without reading the files again.
    val state =
        savedFile?.let { file -> loaded.copy(saved = file.stops.toImmutableList(), savedFile = file) } ?: loaded
    val overlays = LocalOverlay.current
    val resources = LocalResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalRostyHaptics.current
    val menuStrings = rememberPinMenuStrings()
    var open by remember { mutableStateOf<Pair<HomeSheet, SheetEntry>?>(null) }

    // The pick sheets (E7.8) and the map tools (E7.9); made once the map exists, below.
    var picker: Picker? = null
    var tools: MapTools? = null

    fun openSheet(
        which: HomeSheet,
        shared: String? = null,
    ) {
        open?.let { (kind, entry) ->
            if (kind == which && shared == null) return
            overlays.dismiss(entry)
        }
        val title =
            resources.getString(
                when (which) {
                    HomeSheet.Saved -> R.string.sheet_saved_title
                    HomeSheet.History -> R.string.sheet_history_title
                    HomeSheet.Pick -> R.string.sheet_pick_title
                },
            )
        lateinit var entry: SheetEntry
        val gone = { if (open?.second === entry) open = null }
        entry =
            when (which) {
                HomeSheet.Saved -> overlays.presentSavedSheet(title, saved) { gone() }
                HomeSheet.History -> overlays.presentHistorySheet(title, history.first, history.second) { gone() }
                HomeSheet.Pick -> overlays.presentHomeSheet(which, title, shared) { gone() }
            }
        open = which to entry
    }

    fun closeSheet() {
        open?.second?.let(overlays::dismiss)
        open = null
        picker?.close()
    }

    // Start: the preflight sheet first while something blocks (ARCH 7.5), else straight away.
    fun startTrip(
        a: Destination,
        b: Destination,
    ) {
        if (preflight.nextStep == null) {
            trip.onAction(TripAction.Start(a, b))
        } else {
            overlays.presentPreflightSheet(resources.getString(R.string.trip_preflight_title)) {
                trip.onAction(TripAction.Start(a, b))
            }
        }
    }

    val askedSheet by container.pendingSheet.collectAsState()
    LaunchedEffect(askedSheet) {
        askedSheet?.let {
            openSheet(it)
            container.pendingSheet.value = null
        }
    }

    val link by container.pendingLink.collectAsState()
    LaunchedEffect(link) {
        when (val now = link) {
            null -> Unit

            // A stop shortcut starts that stop's trip (GPUI `open_stop`).
            is StopsLink.Stop -> saved.onAction(SavedAction.Start(now.id))

            // A shared place or stop: the Confirm sheet, or both ends of a stop (E7.8).
            is StopsLink.Place -> picker?.offer(now.text)
        }
        if (link != null) container.pendingLink.value = null
    }

    lateinit var map: HomeMap
    val pinActions =
        object : PinMenuActions {
            override fun copy(
                label: String,
                text: String,
            ) {
                if (container.clipboard.copy(text)) overlays.showToast(resources.getString(R.string.home_copied))
            }

            override fun showQr(
                title: String,
                geoUri: String,
            ) {
                scope.launch {
                    overlays.presentStopsQrSheet(context, container.qr, title, geoUri) { context.share(it) }
                }
            }

            override fun openInMaps(geoUri: String) {
                if (!context.openInMaps(geoUri)) overlays.showToast(resources.getString(R.string.home_no_maps_app))
            }

            override fun measureFrom(from: Coord) {
                tools?.measureFrom(from)
            }

            override fun saveAsStop() {
                val ends = map.pick.state.value.ends
                val a = ends.a ?: return
                val b = ends.b ?: return
                saved.onAction(SavedAction.SaveGate(a, b))
            }

            // The lines of a transit stop belong to the pick sheets (E7.8).
            override fun showLines(stopId: String) = Unit

            override fun move(slot: Slot) = map.pick.beginMove(slot)

            override fun swap() = map.pick.swap()

            override fun remove(slot: Slot) = map.pick.remove(slot)
        }
    map =
        rememberHomeMap(
            container,
            object : PickListener {
                override fun onPinTap(slot: Slot) {
                    val anchor = map.pick.pinAnchor(slot) ?: return
                    scope.launch {
                        val model = map.pick.pinMenuModel(slot) ?: return@launch
                        overlays.presentPinMenu(anchor, model, menuStrings, pinActions)
                    }
                }

                override fun onSavedStopTap(id: Long) {
                    overlays.presentSavedStopSheet(saved, id)
                }

                override fun onPositionTap() {
                    tools?.openMyLocation()
                }

                override fun onThirdTap() {
                    overlays.showThirdTapHint(menuStrings)
                }

                override fun onSnapped() = haptics.selection()
            },
        )
    picker = rememberPicker(container, map, overlays) { trip.state.value.trip != null }
    historyStart = { a, b ->
        closeSheet()
        map.showPair(a, b)
        startTrip(a, b)
    }
    historyShow = { a, b ->
        closeSheet()
        map.showPair(a, b)
    }

    var savedOnMap by remember { mutableStateOf<Boolean?>(null) }
    val mapTools =
        rememberMapTools(
            container,
            map,
            overlays,
            menuStrings,
            mapToolsHost(container, trip, state, savedOnMap, { savedOnMap = it }, saved, picker),
        )
    tools = mapTools
    val measuring by mapTools.measure.state.collectAsState()
    val pickState by map.pick.state.collectAsState()
    val padding = remember { MapCameraPadding() }
    FollowCameraPadding(map, padding)
    PlaceFirstView(map, state, container.location)
    FeedRadar(map, savedOnMap?.let { state.copy(savedOnMap = it) } ?: state)
    LivePosition(map, container)
    MapMotionEffect(map.camera, map.motion)
    // The picked ends survive a restart, as the GPUI app kept them in `draft.json`.
    LaunchedEffect(map) {
        map.pick.state
            .map { it.ends }
            .distinctUntilChanged()
            .drop(1)
            .collect { ends ->
                container.ops.storeWrite(StoreWriteRequest("draft", Json.encodeToJsonElement(Ends.serializer(), ends)))
            }
    }
    LaunchedEffect(saved, map) {
        saved.effects.collect { effect ->
            when (effect) {
                is SavedEffect.ShowOnMap -> {
                    closeSheet()
                    map.showPair(effect.a, effect.b)
                }

                // The trip story (E7.5) starts the trip; until then the stop goes on the map.
                is SavedEffect.Start -> {
                    closeSheet()
                    val a = effect.stop.a.toDestination()
                    val b = effect.stop.b.toDestination()
                    map.showPair(a, b)
                    startTrip(a, b)
                }

                else -> {
                    savedEffect(effect, context, container, overlays, resources) { saved.onAction(it) }
                }
            }
        }
    }
    LaunchedEffect(trip, map) {
        trip.effects.collect { effect ->
            when (effect) {
                is TripEffect.Started -> {
                    map.showGate(effect.trip)
                    viewModel.refresh()
                }

                is TripEffect.Stopped -> {
                    map.showPair(effect.a, effect.b)
                    overlays.showToast(resources.getString(R.string.trip_stopped))
                    viewModel.refresh()
                    saved.refresh()
                }

                is TripEffect.Over -> {
                    map.pick.clear()
                    viewModel.refresh()
                    saved.refresh()
                    alarm.onAction(AlarmAction.Refresh)
                }

                is TripEffect.Ring -> {
                    alarm.onAction(AlarmAction.Ring(effect.trip))
                }

                is TripEffect.Toast -> {
                    overlays.showToast(effect.text.resolve(context))
                }

                is TripEffect.Copy -> {
                    if (container.clipboard.copy(effect.text)) {
                        overlays.showToast(resources.getString(R.string.trip_status_copied))
                    }
                }

                is TripEffect.Share -> {
                    context.share(effect.text)
                }
            }
        }
    }
    // AD-SA8: a trip whose restart Android refused resumes when Stops comes to the front; the trip file is read again
    // on every resume (the alarm screen or the notification may have ended it).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(trip, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            trip.onAction(TripAction.Refresh)
            alarm.onAction(AlarmAction.Refresh)
            if (trip.state.value.problem == TripProblem.Restart) trip.onAction(TripAction.Resume)
        }
    }
    val tripState by trip.state.collectAsState()
    val savedState by saved.state.collectAsState()
    // The trip in progress is on the map: its pins, its circle and its line (the pick controller draws them through
    // Rust as for a pick); picking is off until it ends (GPUI `pins_grabbable`).
    val runningTrip = tripState.trip
    LaunchedEffect(map, runningTrip) {
        map.pick.enabled = runningTrip == null
        if (runningTrip != null) map.pick.restore(runningTrip.ends())
    }
    // While it runs the view follows the gate (GPUI `map.follow`): re-fitted when the sheet settles on another detent,
    // so the gate stays in the free area above it.
    val density = LocalDensity.current
    LaunchedEffect(map, runningTrip, padding, density) {
        val trip = runningTrip ?: return@LaunchedEffect
        snapshotFlow { padding.insets }.collectLatest { insets ->
            // The camera takes the new padding in its own effect first.
            val bottom = with(density) { insets.calculateBottomPadding().toPx() }
            snapshotFlow {
                map.camera.observe()
                map.camera.paddingBottom
            }.first { abs(it - bottom) < 1f }
            map.showGate(trip)
        }
    }
    // The in-app alarm: presented while the alarm rings, gone with the answer.
    val alarmState by alarm.state.collectAsState()
    val alarmTitle = stringResource(R.string.alarm_title)
    val alarmEntry = remember(alarm) { arrayOfNulls<AlarmEntry>(1) }
    LaunchedEffect(alarmState.alarm != null) {
        val ringing = alarmState.alarm != null
        val entry = alarmEntry[0]
        if (ringing && entry == null) {
            closeSheet()
            alarmEntry[0] = AlarmEntry(alarm, alarmTitle).also { overlays.present(it) }
        } else if (!ringing && entry != null) {
            overlays.dismiss(entry)
            alarmEntry[0] = null
        }
    }
    LaunchedEffect(alarm) {
        alarm.effects.collect { effect ->
            when (effect) {
                is AlarmEffect.Dismissed -> {
                    val ends = effect.ends
                    val text = resources.getString(R.string.alarm_dismissed)
                    if (ends == null) {
                        overlays.showToast(text)
                    } else {
                        // S21: Re-arm starts the same A and B again.
                        overlays.showUndo(
                            text,
                            { startTrip(ends.first, ends.second) },
                            resources.getString(R.string.alarm_rearm),
                        )
                    }
                }

                is AlarmEffect.Snoozed -> {
                    overlays.showToast(
                        resources.getQuantityString(R.plurals.alarm_snoozed, effect.minutes, effect.minutes),
                    )
                }

                is AlarmEffect.Share -> {
                    context.share(effect.text)
                }

                is AlarmEffect.Start -> {
                    map.showPair(effect.a, effect.b)
                    startTrip(effect.a, effect.b)
                }

                is AlarmEffect.Save -> {
                    saved.onAction(SavedAction.SaveGate(effect.a, effect.b))
                }

                AlarmEffect.TripOver -> {
                    trip.onAction(TripAction.Refresh)
                    viewModel.refresh()
                    saved.refresh()
                }
            }
        }
    }
    val dark = RostyTheme.colors.isDark
    val overlay = rememberRadarOverlay(map.pick.radar, dark)

    StopsHome(
        state = state,
        map = { modifier ->
            StopsMap(
                map.camera,
                map.tiles,
                dark,
                modifier.pickAnchorOrigin(map.pick).testTag(HomeMapTag),
                gesturePolicy = mapTools.gestures,
                overlay = overlay,
            )
        },
        tabs = homeTabItems(),
        selectedTab = if (open?.first == HomeSheet.Saved) HomeTab.Saved else HomeTab.Trip,
        camera = padding,
        hint = if (runningTrip != null) null else pickState.pairLabel ?: pickState.hintText,
        topCard = if (measuring.on) ({ MeasureCard(mapTools) }) else null,
        following = mapTools.follow == MapFollowMode.Following,
        mapFooter = {
            MapScaleBar(map.camera, Modifier.testTag(HomeTags.ScaleBar))
            MapAttribution()
        },
        motionFrozen = { map.motion.frozen },
        // S37: one tap starts the trip to the saved stop nearest the position, while nothing is picked.
        chips = nearestChips(savedState.nearest, pickState.ends, tripState.trip != null || measuring.on),
        onChip = { id ->
            savedState.nearest?.takeIf { id == NEAREST_CHIP }?.let { saved.onAction(SavedAction.Start(it.id)) }
        },
        sheetContent = {
            val live = tripState.live
            MapSheetContent(
                state = state,
                onOpenSaved = { openSheet(HomeSheet.Saved) },
                onOpenHistory = { openSheet(HomeSheet.History) },
                title = tripState.trip?.let { stringResource(R.string.home_sheet_trip) },
                subtitle =
                    if (runningTrip != null) {
                        live?.let { stringResource(R.string.home_trip_live, it.distance, runningTrip.label) }
                    } else {
                        pickState.pairLabel
                    },
                trip = {
                    TripSection(
                        viewModel = trip,
                        a = pickState.ends.a,
                        b = pickState.ends.b,
                        onStart = ::startTrip,
                        onClear = { map.pick.clear() },
                        onLine = { slot, dest -> map.pick.place(slot, dest) },
                        overlays = overlays,
                        lastTrip = { LastTripCard(alarm) },
                    )
                },
            )
        },
        actions =
            HomeActions(
                onTab = { tab ->
                    when (tab) {
                        HomeTab.Trip -> {
                            closeSheet()
                        }

                        HomeTab.Saved -> {
                            openSheet(HomeSheet.Saved)
                        }

                        HomeTab.Settings -> {
                            closeSheet()
                            navigator.push(StopsRoute.Settings)
                        }
                    }
                },
                onSearch = {
                    closeSheet()
                    picker?.open()
                },
                onOpenSaved = { openSheet(HomeSheet.Saved) },
                onOpenHistory = { openSheet(HomeSheet.History) },
                onLocate = mapTools::onLocate,
                onLocateLong = mapTools::openMyLocation,
                onLayers = mapTools::openLayers,
                onMapOptions = mapTools::openOptions,
            ),
    )
}

/** The effects of the Saved list that only need the overlays, the clipboard, the launcher or the share sheet. */
private suspend fun savedEffect(
    effect: SavedEffect,
    context: Context,
    container: StopsContainer,
    overlays: OverlayController,
    resources: Resources,
    onAction: (SavedAction) -> Unit,
) {
    val undo = resources.getString(R.string.saved_undo)
    when (effect) {
        is SavedEffect.Toast -> {
            overlays.showToast(effect.text.resolve(context))
        }

        is SavedEffect.Deleted -> {
            overlays.showUndo(
                resources.getString(R.string.saved_deleted, effect.stop.name),
                { onAction(SavedAction.Restore(effect.stop, effect.index)) },
                undo,
            )
        }

        is SavedEffect.Saved -> {
            overlays.showUndo(
                resources.getString(R.string.saved_saved_as, effect.name),
                { onAction(SavedAction.Unsave(effect.id)) },
                undo,
            )
        }

        is SavedEffect.Copy -> {
            if (container.clipboard.copy(effect.text)) overlays.showToast(effect.done.resolve(context))
        }

        is SavedEffect.Share -> {
            context.share(effect.text)
        }

        is SavedEffect.Qr -> {
            overlays.presentStopsQrSheet(context, container.qr, effect.title, effect.payload) { context.share(it) }
        }

        is SavedEffect.Shortcut -> {
            val pinned = container.shortcuts.pinStop(effect.spec)
            overlays.showToast(resources.getString(if (pinned) R.string.saved_pinned else R.string.saved_not_pinned))
        }

        is SavedEffect.OpenInMaps -> {
            if (!context.openInMaps(effect.geoUri)) overlays.showToast(resources.getString(R.string.home_no_maps_app))
        }

        is SavedEffect.ShowOnMap, is SavedEffect.Start -> {
            Unit
        }
    }
}

private fun Context.share(text: String) {
    try {
        startActivity(ShareIntents.text(text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: android.content.ActivityNotFoundException) {
        Unit
    }
}

private const val NEAREST_CHIP = "nearest"

/** The S37 chip: shown with a nearby saved stop, nothing picked and no trip running (GPUI `nearest_row_visible`). */
@Composable
private fun nearestChips(
    nearest: dev.rosty.core.model.stops.NearestStop?,
    ends: Ends,
    tripRunning: Boolean,
): kotlinx.collections.immutable.ImmutableList<ChipModel> {
    if (nearest == null || ends.a != null || ends.b != null || tripRunning) return persistentListOf()
    return persistentListOf(ChipModel(NEAREST_CHIP, stringResource(R.string.home_nearest, nearest.name)))
}

internal fun Context.findComponentActivity(): ComponentActivity {
    var current = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    error("No ComponentActivity around $this")
}
