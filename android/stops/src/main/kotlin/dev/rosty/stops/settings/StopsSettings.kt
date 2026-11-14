package dev.rosty.stops.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rosty.core.model.stops.Appearance
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosText
import dev.rosty.shell.settings.AboutPage
import dev.rosty.shell.settings.AppearancePage
import dev.rosty.shell.settings.CommonSettings
import dev.rosty.shell.settings.CommonSettingsGroup
import dev.rosty.shell.settings.CommonSettingsPage
import dev.rosty.shell.settings.IosSettingsGroup
import dev.rosty.shell.settings.IosSettingsItem
import dev.rosty.shell.settings.LicenceDetailPage
import dev.rosty.shell.settings.LicencesPage
import dev.rosty.shell.settings.SettingsPage
import dev.rosty.shell.settings.aboutInfo
import dev.rosty.shell.settings.dev.LogsPage
import dev.rosty.shell.settings.rememberCoreVersion
import dev.rosty.shell.settings.rememberLicenceData
import dev.rosty.stops.R
import dev.rosty.stops.StopsContainer
import dev.rosty.stops.StopsRoute
import dev.rosty.stops.home.rememberHomeViewModel

/** Test tags of the Settings page. */
object SettingsTags {
    const val Page = "stops-settings-page"
    const val TileServer = "stops-settings-tile-server"
}

/** Where each common row leads. */
fun commonRoute(page: CommonSettingsPage): StopsRoute =
    when (page) {
        CommonSettingsPage.Appearance -> StopsRoute.Appearance

        // Stops keeps no haptics preference (the row is hidden); a stray request opens About.
        CommonSettingsPage.Haptics -> StopsRoute.About

        CommonSettingsPage.About -> StopsRoute.About

        CommonSettingsPage.Licences -> StopsRoute.Licences

        CommonSettingsPage.Logs -> StopsRoute.Logs()
    }

/**
 * The Settings page, pushed over the map. E7.10 adds the alarm, units, tiles, GTFS and arriving-soon groups; this
 * story has the map's tile server (read from `stops-prefs.json`) and ends with the shell's common group: Appearance,
 * About (version; holding it 10 s turns developer mode on) and, in developer mode, the Logs page.
 */
@Composable
fun StopsSettingsEntry(
    container: StopsContainer,
    onOpen: (StopsRoute) -> Unit,
    onBack: () -> Unit,
) {
    val home by rememberHomeViewModel(container).state.collectAsState()
    StopsSettingsPage(home.tileServer, container.commonSettings, onOpen, onBack)
}

@Composable
fun StopsSettingsPage(
    tileServer: String,
    settings: CommonSettings,
    onOpen: (StopsRoute) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(
        title = stringResource(R.string.tab_settings),
        modifier = Modifier.testTag(SettingsTags.Page),
        onBack = onBack,
    ) {
        IosSettingsGroup(
            title = stringResource(R.string.settings_group_map),
            items =
                listOf(
                    IosSettingsItem(
                        icon = rememberVectorPainter(RostyIcons.Globe),
                        title = { IosText(stringResource(R.string.settings_tile_server)) },
                        trailingContent = {
                            IosText(
                                tileServer,
                                Modifier.testTag(SettingsTags.TileServer),
                                maxLines = 1,
                            )
                        },
                        trailingShrinks = true,
                    ),
                ),
        )
        CommonSettingsGroup(settings) { onOpen(commonRoute(it)) }
    }
}

@Composable
fun AppearanceEntry(
    settings: CommonSettings,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val value by settings.appearance.collectAsState()
    AppearancePage(
        selected = value ?: Appearance.Auto,
        onSelect = settings::setAppearance,
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}

@Composable
fun AboutEntry(
    container: StopsContainer,
    onOpenLicences: () -> Unit,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val appName = LocalResources.current.getString(R.string.app_name)
    val coreVersion = rememberCoreVersion(container.rust).value
    val info = remember(context, appName, coreVersion) { context.aboutInfo(appName, coreVersion) }
    AboutPage(info, onOpenLicences = onOpenLicences, onBack = onBack, onBackLongClick = onBackLongClick)
}

/** Developer mode's Logs page of Stops. */
@Composable
fun LogsEntry(
    container: StopsContainer,
    tag: String?,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    LogsPage(
        devLog = container.devLog,
        clipboard = container.clipboard,
        files = container.files,
        appName = LocalResources.current.getString(R.string.app_name),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
        initialTag = tag,
    )
}

@Composable
fun LicencesEntry(
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val data by rememberLicenceData()
    LicencesPage(data, onOpen = onOpen, onBack = onBack, onBackLongClick = onBackLongClick)
}

@Composable
fun LicenceDetailEntry(
    id: String,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val data by rememberLicenceData()
    data?.let { LicenceDetailPage(id, it, onBack = onBack, onBackLongClick = onBackLongClick) }
}
