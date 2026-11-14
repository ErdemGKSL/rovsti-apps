package dev.rosty.stops.home

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.Ends
import dev.rosty.core.model.stops.Slot
import dev.rosty.core.model.stops.StoreWriteRequest
import dev.rosty.design.chrome.TabItem
import dev.rosty.design.haptics.LocalRostyHaptics
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.showToast
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.transition.TabNavigator
import dev.rosty.platform.files.ShareIntents
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
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

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
    val state by viewModel.state.collectAsState()
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
        entry = overlays.presentHomeSheet(which, title, shared) { if (open?.second === entry) open = null }
        open = which to entry
    }

    fun closeSheet() {
        open?.second?.let(overlays::dismiss)
        open = null
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
            is StopsLink.Stop -> openSheet(HomeSheet.Saved)
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
                    overlays.presentStopsQrSheet(context, container.qr, title, geoUri) { text ->
                        context.startActivity(ShareIntents.text(text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }

            override fun openInMaps(geoUri: String) {
                if (!context.openInMaps(geoUri)) overlays.showToast(resources.getString(R.string.home_no_maps_app))
            }

            // S34 "Measure from here" arrives with the measure tool (E7.9).
            override fun measureFrom(from: Coord) = Unit

            override fun saveAsStop() = openSheet(HomeSheet.Saved)

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

                override fun onSavedStopTap(id: Long) = openSheet(HomeSheet.Saved)

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

internal fun Context.findComponentActivity(): ComponentActivity {
    var current = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    error("No ComponentActivity around $this")
}
