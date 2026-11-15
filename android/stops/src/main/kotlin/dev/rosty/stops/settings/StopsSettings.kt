package dev.rosty.stops.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rosty.core.model.stops.Appearance
import dev.rosty.design.controls.TextButton
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosText
import dev.rosty.design.ios.foundation.IosTheme
import dev.rosty.design.overlay.LocalOverlayOrNull
import dev.rosty.design.overlay.showToast
import dev.rosty.design.overlay.showUndo
import dev.rosty.design.text.resolve
import dev.rosty.design.text.text
import dev.rosty.platform.files.FileResult
import dev.rosty.platform.files.ImportDocument
import dev.rosty.platform.files.ImportKind
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
import dev.rosty.stops.home.findComponentActivity
import dev.rosty.stops.offline.OfflineState
import dev.rosty.stops.offline.offlineText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Test tags of the Settings pages. */
object SettingsTags {
    const val Page = "stops-settings-page"
    const val Alarm = "stops-settings-alarm"
    const val TileServer = "stops-settings-tile-server"
    const val MapTiles = "stops-settings-map-tiles"
    const val OfflineRow = "stops-settings-offline"
    const val OfflineCancel = "stops-settings-offline-cancel"
    const val TransitImport = "stops-settings-transit-import"
    const val TransitRemove = "stops-settings-transit-remove"
    const val TransitSummary = "stops-settings-transit-summary"
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

/** The Settings, Alarm and Map tiles pages' view models, owned by the activity like the home's. */
@Composable
fun rememberSettingsViewModel(container: StopsContainer): SettingsViewModel {
    val context = LocalContext.current
    val owner = remember(context) { context.findComponentActivity() }
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer {
                    SettingsViewModel(
                        store = container.store,
                        ops = container.ops,
                        device = container.alarmDevice,
                        transitChanged = { container.transitVersion.update { it + 1 } },
                    )
                }
            }
        ViewModelProvider(owner, factory)[SettingsViewModel::class.java]
    }
}

@Composable
fun rememberTilesViewModel(container: StopsContainer): TilesViewModel {
    val context = LocalContext.current
    val owner = remember(context) { context.findComponentActivity() }
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer {
                    TilesViewModel(
                        store = container.store,
                        ops = container.ops,
                        tilesOps = container.tilesOps,
                        tiles = container.tiles,
                        offline = container.offline,
                        mapView = container.mapView,
                    )
                }
            }
        ViewModelProvider(owner, factory)[TilesViewModel::class.java]
    }
}

/** Shows the toasts and Undo pills of the settings view model. */
@Composable
internal fun SettingsEffects(effects: Flow<SettingsEffect>) {
    val overlays = LocalOverlayOrNull.current ?: return
    val context = LocalContext.current
    val undo = stringResource(R.string.settings_undo)
    LaunchedEffect(effects, overlays) {
        effects.collect { effect ->
            when (effect) {
                is SettingsEffect.Toast -> overlays.showToast(effect.text.resolve(context))
                is SettingsEffect.Undo -> overlays.showUndo(effect.text.resolve(context), effect.undo, undo)
            }
        }
    }
}

/**
 * The Settings page, pushed over the map (the full Trip / Saved / Settings bar stays, F16). Groups: Trip (the Alarm
 * page, then the arriving-soon slot the WhatsApp work fills: [ArrivingSoonSettingsGroup]), Map (the Map tiles page and,
 * while it runs, the offline download), Transit data (AD-ST6), then the shell's common group: Appearance, About and,
 * in developer mode, Logs.
 */
@Composable
fun StopsSettingsEntry(
    container: StopsContainer,
    onOpen: (StopsRoute) -> Unit,
    onBack: () -> Unit,
) {
    val vm = rememberSettingsViewModel(container)
    val state by vm.state.collectAsState()
    val offline by container.offline.state.collectAsState()
    SettingsEffects(vm.effects)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contract = remember { ImportDocument(kind = ImportKind.Gtfs) }
    val pickFeed =
        rememberLauncherForActivityResult(contract) { result ->
            val uri = (result as? FileResult.Ok)?.uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                when (val read = container.files.readUri(uri, contract.cap, text = false)) {
                    is FileResult.Ok -> read.path?.let(vm::importFeed) ?: vm.importFailed(tooLarge = false)
                    FileResult.TooLarge -> vm.importFailed(tooLarge = true)
                    else -> vm.importFailed(tooLarge = false)
                }
            }
        }
    val overlays = LocalOverlayOrNull.current
    val res = LocalResources.current
    StopsSettingsPage(
        state = state,
        offline = offline,
        settings = container.commonSettings,
        actions =
            SettingsPageActions(
                open = onOpen,
                importFeed = { pickFeed.launch(Unit) },
                removeFeed = {
                    overlays?.confirm(
                        res,
                        heading = res.getString(R.string.settings_transit_remove_title),
                        message = res.getString(R.string.settings_transit_remove_message),
                        action = res.getString(R.string.settings_transit_remove_action),
                        onConfirm = vm::removeFeed,
                    )
                },
                cancelDownload = container.offline::cancel,
            ),
        extraGroups = { ArrivingSoonSettingsGroup(container, onOpen) },
        onBack = onBack,
    )
}

/** What the rows of the Settings page do. Every member has a no-op default, so a test names only what it taps. */
@Immutable
class SettingsPageActions(
    val open: (StopsRoute) -> Unit = {},
    val importFeed: () -> Unit = {},
    val removeFeed: () -> Unit = {},
    val cancelDownload: () -> Unit = {},
)

@Composable
fun StopsSettingsPage(
    state: SettingsUiState,
    offline: OfflineState,
    settings: CommonSettings,
    actions: SettingsPageActions,
    onBack: () -> Unit,
    /** Groups after the Trip group: the arriving-soon message and WhatsApp (filled by the WhatsApp work). */
    extraGroups: @Composable ColumnScope.() -> Unit = {},
) {
    SettingsPage(
        title = stringResource(R.string.tab_settings),
        modifier = Modifier.testTag(SettingsTags.Page),
        onBack = onBack,
    ) {
        IosSettingsGroup(
            title = stringResource(R.string.settings_group_trip),
            items =
                listOf(
                    IosSettingsItem(
                        icon = tile(RostyIcons.Bell),
                        title = {
                            IosText(
                                stringResource(R.string.settings_alarm),
                                Modifier.testTag(SettingsTags.Alarm),
                            )
                        },
                        description = { IosText(stringResource(alarmSummary(state.alarm))) },
                        onClick = { actions.open(StopsRoute.AlarmSettings) },
                    ),
                ),
        )
        extraGroups()
        IosSettingsGroup(
            title = stringResource(R.string.settings_group_map),
            items =
                buildList {
                    add(
                        IosSettingsItem(
                            icon = tile(RostyIcons.Map),
                            title = {
                                IosText(
                                    stringResource(R.string.settings_map_tiles),
                                    Modifier.testTag(SettingsTags.MapTiles),
                                )
                            },
                            // The host is long: it reads whole under the title rather than cut beside it.
                            description = {
                                IosText(state.tileHost, Modifier.testTag(SettingsTags.TileServer), maxLines = 1)
                            },
                            onClick = { actions.open(StopsRoute.MapTiles) },
                        ),
                    )
                    if (offline.active) add(offlineProgressItem(offline, actions.cancelDownload))
                },
        )
        TransitGroup(state.transit, actions)
        CommonSettingsGroup(settings) { actions.open(commonRoute(it)) }
    }
}

/** "Sound and vibration", "Sound only" or "Vibration only" (Rust keeps one of them on). */
@StringRes
internal fun alarmSummary(alarm: AlarmUi): Int =
    when {
        alarm.sound && alarm.vibrate -> R.string.settings_alarm_both
        alarm.sound -> R.string.settings_alarm_sound_only
        else -> R.string.settings_alarm_vibration_only
    }

/** The running download: its line, a bar and Cancel (the notification's Cancel does the same). */
@Composable
internal fun offlineProgressItem(
    offline: OfflineState,
    onCancel: () -> Unit,
): IosSettingsItem =
    IosSettingsItem(
        icon = tile(RostyIcons.Download),
        title = {
            val context = LocalContext.current
            IosText(offlineText(context, offline), Modifier.testTag(SettingsTags.OfflineRow))
        },
        description = {
            val fraction = (offline as? OfflineState.Running)?.fraction ?: 0f
            Column {
                Spacer(Modifier.height(SettingsMetrics.ProgressGap))
                ProgressBar(fraction)
            }
        },
        trailingContent = {
            TextButton(
                stringResource(R.string.offline_cancel),
                onClick = onCancel,
                modifier = Modifier.testTag(SettingsTags.OfflineCancel),
            )
        },
    )

@Composable
private fun TransitGroup(
    transit: TransitUi,
    actions: SettingsPageActions,
) {
    val items =
        buildList {
            when {
                transit.reading -> {
                    add(
                        IosSettingsItem(
                            icon = tile(RostyIcons.Folder),
                            title = { IosText(stringResource(R.string.settings_transit_reading)) },
                            description = { IosText(stringResource(R.string.settings_transit_reading_desc)) },
                        ),
                    )
                }

                transit.hasFeed -> {
                    add(
                        IosSettingsItem(
                            icon = tile(RostyIcons.Map),
                            title = {
                                IosText(transit.summary.orEmpty(), Modifier.testTag(SettingsTags.TransitSummary))
                            },
                            description = transit.imported?.let { { IosText(it) } },
                        ),
                    )
                    add(
                        IosSettingsItem(
                            icon = tile(RostyIcons.Folder),
                            title = {
                                IosText(
                                    stringResource(R.string.settings_transit_replace),
                                    Modifier.testTag(SettingsTags.TransitImport),
                                )
                            },
                            trailingContent = { NoChevron() },
                            onClick = actions.importFeed,
                        ),
                    )
                    add(
                        IosSettingsItem(
                            icon = tile(RostyIcons.Trash),
                            title = {
                                IosText(
                                    stringResource(R.string.settings_transit_remove),
                                    Modifier.testTag(SettingsTags.TransitRemove),
                                    color = IosTheme.colors.systemRed,
                                )
                            },
                            trailingContent = { NoChevron() },
                            onClick = actions.removeFeed,
                        ),
                    )
                }

                else -> {
                    add(
                        IosSettingsItem(
                            icon = tile(RostyIcons.Folder),
                            title = {
                                IosText(
                                    stringResource(R.string.settings_transit_import),
                                    Modifier.testTag(SettingsTags.TransitImport),
                                )
                            },
                            description = { IosText(stringResource(R.string.settings_transit_import_desc)) },
                            trailingContent = { NoChevron() },
                            onClick = actions.importFeed,
                        ),
                    )
                }
            }
        }
    IosSettingsGroup(title = stringResource(R.string.settings_group_transit), items = items)
    val error = transit.error
    when {
        error != null -> Footnote(error.text())
        !transit.hasFeed && !transit.reading -> Footnote(stringResource(R.string.settings_transit_footer))
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
