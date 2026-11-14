package dev.rosty.stops.home

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rosty.design.chrome.TabItem
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.transition.TabNavigator
import dev.rosty.stops.HomeTab
import dev.rosty.stops.R
import dev.rosty.stops.StopsContainer
import dev.rosty.stops.StopsLink
import dev.rosty.stops.StopsRoute
import kotlinx.collections.immutable.persistentListOf

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
    mapLayer: MapLayer,
) {
    val viewModel = rememberHomeViewModel(container)
    val state by viewModel.state.collectAsState()
    val overlays = LocalOverlay.current
    val resources = LocalResources.current
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

    val link by container.pendingLink.collectAsState()
    LaunchedEffect(link) {
        when (val now = link) {
            null -> Unit
            is StopsLink.Stop -> openSheet(HomeSheet.Saved)
            is StopsLink.Place -> openSheet(HomeSheet.Pick, now.text)
        }
        if (link != null) container.pendingLink.value = null
    }

    StopsHome(
        state = state,
        mapLayer = mapLayer,
        tabs = homeTabItems(),
        selectedTab = if (open?.first == HomeSheet.Saved) HomeTab.Saved else HomeTab.Trip,
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
