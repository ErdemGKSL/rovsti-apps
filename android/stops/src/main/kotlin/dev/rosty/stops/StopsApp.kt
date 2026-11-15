package dev.rosty.stops

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.transition.TabNavigator
import dev.rosty.design.transition.TabSelection
import dev.rosty.shell.RostyShell
import dev.rosty.stops.home.HomeEntry
import dev.rosty.stops.home.HomeSheet
import dev.rosty.stops.home.homeTabItems
import dev.rosty.stops.settings.AboutEntry
import dev.rosty.stops.settings.AlarmSettingsEntry
import dev.rosty.stops.settings.AppearanceEntry
import dev.rosty.stops.settings.LicenceDetailEntry
import dev.rosty.stops.settings.LicencesEntry
import dev.rosty.stops.settings.LogsEntry
import dev.rosty.stops.settings.StopsSettingsEntry
import dev.rosty.stops.settings.TilesSettingsEntry

/**
 * The screens of Rosty Stops: one [RostyShell] over the activity's navigator (the shell provides the overlay host and
 * the backdrop root above every page).
 */
@Composable
fun StopsApp(
    container: StopsContainer,
    navigator: TabNavigator<StopsRoute>,
    overlays: OverlayController = remember { OverlayController() },
) {
    // The Trip / Saved / Settings bar: the map draws it folded itself, the pushed pages get it from the host (F16).
    val tabs = homeTabItems()
    val selection =
        remember(navigator, container) {
            TabSelection<StopsRoute>(selectedId = { selectedHomeTab(it).id }) { id ->
                when (barTap(id)) {
                    BarTap.ToMap -> {
                        navigator.select(TripTab)
                    }

                    BarTap.ToSaved -> {
                        navigator.select(TripTab)
                        container.pendingSheet.value = HomeSheet.Saved
                    }

                    BarTap.ToSettings -> {
                        while (navigator.current != StopsRoute.Settings && navigator.pop()) Unit
                    }

                    null -> {
                        Unit
                    }
                }
            }
        }
    val pop: () -> Unit =
        remember(navigator) {
            {
                navigator.pop()
                Unit
            }
        }
    // A long press on a back button jumps to the map.
    val popToRoot: () -> Unit = remember(navigator) { { navigator.select(TripTab) } }
    RostyShell(
        navigator = navigator,
        tabs = tabs,
        chromeFor = ::chromeFor,
        overlays = overlays,
        modifier = Modifier.fillMaxSize(),
        tabSelection = selection,
    ) {
        entry<StopsRoute.Home> { HomeEntry(container, navigator) }
        entry<StopsRoute.Settings> { StopsSettingsEntry(container, onOpen = navigator::push, onBack = pop) }
        entry<StopsRoute.AlarmSettings> { AlarmSettingsEntry(container, pop, popToRoot) }
        entry<StopsRoute.MapTiles> { TilesSettingsEntry(container, pop, popToRoot) }
        entry<StopsRoute.Appearance> { AppearanceEntry(container.commonSettings, pop, popToRoot) }
        entry<StopsRoute.About> {
            AboutEntry(container, onOpenLicences = { navigator.push(StopsRoute.Licences) }, pop, popToRoot)
        }
        entry<StopsRoute.Licences> {
            LicencesEntry(onOpen = { navigator.push(StopsRoute.LicenceDetail(it)) }, pop, popToRoot)
        }
        entry<StopsRoute.LicenceDetail> { route -> LicenceDetailEntry(route.id, pop, popToRoot) }
        entry<StopsRoute.Logs> { route -> LogsEntry(container, route.tag, pop, popToRoot) }
    }
}
