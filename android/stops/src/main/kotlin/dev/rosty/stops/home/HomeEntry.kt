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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import dev.rosty.stops.map.MapAttribution
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
import dev.rosty.stops.saved.SavedAction
import dev.rosty.stops.saved.SavedEffect
import dev.rosty.stops.saved.SavedViewModel
import dev.rosty.stops.saved.presentSavedSheet
import dev.rosty.stops.saved.presentSavedStopSheet
import dev.rosty.stops.saved.toDestination
import dev.rosty.stops.trip.TripAction
import dev.rosty.stops.trip.TripEffect
import dev.rosty.stops.trip.TripProblem
import dev.rosty.stops.trip.TripSection
import dev.rosty.stops.trip.TripViewModel
import dev.rosty.stops.trip.presentPreflightSheet
import dev.rosty.stops.trip.rememberPreflight
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.TimeZone

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
    val preflight = rememberPreflight()
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
            if (which == HomeSheet.Saved) {
                overlays.presentSavedSheet(title, saved) { gone() }
            } else {
                overlays.presentHomeSheet(which, title, shared) { gone() }
            }
        open = which to entry
    }

    fun closeSheet() {
        open?.second?.let(overlays::dismiss)
        open = null
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

            is StopsLink.Place -> openSheet(HomeSheet.Pick, now.text)
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

            // S34 "Measure from here" arrives with the measure tool (E7.9).
            override fun measureFrom(from: Coord) = Unit

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

                override fun onThirdTap() {
                    overlays.showThirdTapHint(menuStrings)
                }

                override fun onSnapped() = haptics.selection()
            },
        )
    val pickState by map.pick.state.collectAsState()
    val padding = remember { MapCameraPadding() }
    FollowCameraPadding(map, padding)
    PlaceFirstView(map, state, container.location)
    FeedRadar(map, state)
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
                    map.pick.clear()
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
                    viewModel.refresh()
                    saved.refresh()
                }

                is TripEffect.Ring -> {
                    Unit
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
            if (trip.state.value.problem == TripProblem.Restart) trip.onAction(TripAction.Resume)
        }
    }
    val tripState by trip.state.collectAsState()
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
                gesturePolicy = map.pick,
                overlay = overlay,
            )
        },
        tabs = homeTabItems(),
        selectedTab = if (open?.first == HomeSheet.Saved) HomeTab.Saved else HomeTab.Trip,
        camera = padding,
        hint = pickState.pairLabel ?: pickState.hintText,
        mapFooter = {
            MapScaleBar(map.camera, Modifier.testTag(HomeTags.ScaleBar))
            MapAttribution()
        },
        motionFrozen = { map.motion.frozen },
        sheetContent = {
            val live = tripState.live
            MapSheetContent(
                state = state,
                onOpenSaved = { openSheet(HomeSheet.Saved) },
                onOpenHistory = { openSheet(HomeSheet.History) },
                title = tripState.trip?.let { stringResource(R.string.home_sheet_trip) },
                subtitle =
                    tripState.trip?.let { running ->
                        live?.let { stringResource(R.string.home_trip_live, it.distance, running.label) }
                    } ?: pickState.pairLabel,
                trip = {
                    TripSection(
                        viewModel = trip,
                        a = pickState.ends.a,
                        b = pickState.ends.b,
                        onStart = ::startTrip,
                        onClear = { map.pick.clear() },
                        onLine = { slot, dest -> map.pick.place(slot, dest) },
                        overlays = overlays,
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
                onSearch = { openSheet(HomeSheet.Pick) },
                onOpenSaved = { openSheet(HomeSheet.Saved) },
                onOpenHistory = { openSheet(HomeSheet.History) },
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

internal fun Context.findComponentActivity(): ComponentActivity {
    var current = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    error("No ComponentActivity around $this")
}
