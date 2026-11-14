package dev.rosty.stops

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.rosty.design.chrome.TabItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.transition.TabNavigator
import dev.rosty.shell.RostyShell
import dev.rosty.stops.home.HomeEntry
import dev.rosty.stops.home.StopsIcons
import dev.rosty.stops.settings.AboutEntry
import dev.rosty.stops.settings.AppearanceEntry
import dev.rosty.stops.settings.LicenceDetailEntry
import dev.rosty.stops.settings.LicencesEntry
import dev.rosty.stops.settings.LogsEntry
import dev.rosty.stops.settings.StopsSettingsEntry
import kotlinx.collections.immutable.persistentListOf

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
    // The host's own bar is never shown (chromeFor hides it); the tab list only names the one stack.
    val tabs = persistentListOf(TabItem(TripTab.id, stringResource(R.string.tab_trip), StopsIcons.Navigation))
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
    ) {
        entry<StopsRoute.Home> { HomeEntry(container, navigator) }
        entry<StopsRoute.Settings> { StopsSettingsEntry(container, onOpen = navigator::push, onBack = pop) }
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
