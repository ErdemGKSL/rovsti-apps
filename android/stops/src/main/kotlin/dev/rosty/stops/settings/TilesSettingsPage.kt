package dev.rosty.stops.settings

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosText
import dev.rosty.design.ios.foundation.IosTheme
import dev.rosty.design.overlay.LocalOverlayOrNull
import dev.rosty.design.overlay.showToast
import dev.rosty.design.overlay.showUndo
import dev.rosty.design.text.GlassTextField
import dev.rosty.design.text.resolve
import dev.rosty.shell.settings.IosSettingsGroup
import dev.rosty.shell.settings.IosSettingsItem
import dev.rosty.shell.settings.SettingsPage
import dev.rosty.stops.R
import dev.rosty.stops.StopsContainer
import dev.rosty.stops.offline.OfflineState
import dev.rosty.stops.offline.offlineText

/** Test tags of the Map tiles page. */
object TilesTags {
    const val Page = "stops-tiles-page"
    const val Url = "stops-tiles-url"
    const val Test = "stops-tiles-test"
    const val Reset = "stops-tiles-reset"
    const val ThisServer = "stops-tiles-this-server"
    const val Others = "stops-tiles-others"
    const val ClearAll = "stops-tiles-clear-all"
    const val Download = "stops-tiles-download"
    const val Done = "stops-tiles-done"

    fun cap(mb: Int) = "stops-tiles-cap-$mb"
}

/** What the rows of the Map tiles page do; no-op defaults for tests. */
@Immutable
class TilesPageActions(
    val test: () -> Unit = {},
    val reset: () -> Unit = {},
    val setCache: (Int) -> Unit = {},
    val clearOthers: () -> Unit = {},
    val clearAll: () -> Unit = {},
    val download: () -> Unit = {},
    val cancelDownload: () -> Unit = {},
    val dismissDone: () -> Unit = {},
)

/** Settings > Map tiles, pushed over the map. */
@Composable
fun TilesSettingsEntry(
    container: StopsContainer,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val vm = rememberTilesViewModel(container)
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val overlays = LocalOverlayOrNull.current
    val undo = stringResource(R.string.settings_undo)
    LaunchedEffect(vm) { vm.measure() }
    LaunchedEffect(vm, overlays) {
        val shown = overlays ?: return@LaunchedEffect
        vm.effects.collect { effect ->
            when (effect) {
                is TilesEffect.Toast -> {
                    shown.showToast(effect.text.resolve(context))
                }

                is TilesEffect.Undo -> {
                    shown.showUndo(effect.text.resolve(context), effect.undo, undo)
                }

                is TilesEffect.Freed -> {
                    shown.showToast(
                        context.getString(R.string.tiles_freed, bytesText(context, effect.bytes)),
                    )
                }
            }
        }
    }
    TilesSettingsPage(
        state = state,
        url = vm.urlField,
        actions =
            TilesPageActions(
                test = vm::testAndSave,
                reset = vm::resetServer,
                setCache = { mb ->
                    if (vm.shrinks(mb)) {
                        overlays?.confirm(
                            context,
                            heading = context.getString(R.string.tiles_shrink_title, mb),
                            message = context.getString(R.string.tiles_shrink_message),
                            action = context.getString(R.string.tiles_shrink_action),
                        ) { vm.setCache(mb) }
                    } else {
                        vm.setCache(mb)
                    }
                },
                clearOthers = {
                    overlays?.confirm(
                        context,
                        heading = context.getString(R.string.tiles_clear_others_title),
                        message = context.getString(R.string.tiles_clear_others_message),
                        action = context.getString(R.string.tiles_clear_action),
                        onConfirm = vm::clearOthers,
                    )
                },
                clearAll = {
                    overlays?.confirm(
                        context,
                        heading = context.getString(R.string.tiles_clear_all_title),
                        message = context.getString(R.string.tiles_clear_all_message),
                        action = context.getString(R.string.tiles_clear_action),
                        onConfirm = vm::clearAll,
                    )
                },
                download = {
                    val area = state.area ?: return@TilesPageActions
                    overlays?.confirm(
                        context,
                        heading =
                            context.resources.getQuantityString(
                                R.plurals.offline_confirm_title,
                                area.tiles,
                                area.tiles,
                            ),
                        message = areaText(context, area),
                        action = context.getString(R.string.offline_confirm_action),
                        destructive = false,
                        onConfirm = vm::download,
                    )
                },
                cancelDownload = vm::cancelDownload,
                dismissDone = container.offline::dismiss,
            ),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}

/** "11 tiles, about 1 MB, up to zoom 14", and the hint to zoom in when the budget cut it short. */
internal fun areaText(
    context: Context,
    area: AreaUi,
): String {
    val text =
        context.resources.getQuantityString(
            R.plurals.offline_estimate,
            area.tiles,
            area.tiles,
            bytesText(context, area.bytes),
            area.topZoom,
        )
    return if (area.capped) context.getString(R.string.offline_estimate_capped, text) else text
}

@Composable
fun TilesSettingsPage(
    state: TilesUiState,
    url: TextFieldState,
    actions: TilesPageActions,
    onBack: () -> Unit = {},
    onBackLongClick: () -> Unit = {},
) {
    val context = LocalContext.current
    SettingsPage(
        title = stringResource(R.string.settings_map_tiles),
        modifier = Modifier.testTag(TilesTags.Page),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    ) {
        // The server: the field, its test, the way back to the default.
        IosText(
            stringResource(R.string.tiles_server),
            style = IosTheme.typography.subheadline,
            color = IosTheme.colors.secondaryLabel,
            modifier = Modifier.padding(horizontal = SettingsMetrics.FieldInset + SettingsMetrics.AfterFooter),
        )
        GlassTextField(
            state = url,
            placeholder = state.defaultUrl,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsMetrics.FieldInset, vertical = SettingsMetrics.FieldGap)
                    .testTag(TilesTags.Url),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
            onSubmit = actions.test,
        )
        Footnote(stringResource(R.string.tiles_url_footer))
        IosSettingsGroup(
            items =
                buildList {
                    add(
                        IosSettingsItem(
                            icon = tile(RostyIcons.Globe),
                            title = {
                                IosText(
                                    stringResource(R.string.tiles_test_save),
                                    Modifier.testTag(TilesTags.Test),
                                )
                            },
                            description = { IosText(stringResource(R.string.tiles_test_save_desc)) },
                            trailingContent = {
                                if (state.testing) Value(stringResource(R.string.tiles_testing)) else NoChevron()
                            },
                            enabled = !state.testing,
                            onClick = actions.test,
                        ),
                    )
                    if (!state.isDefault) {
                        add(
                            IosSettingsItem(
                                icon = tile(RostyIcons.Rotate),
                                title = {
                                    IosText(
                                        stringResource(R.string.tiles_reset),
                                        Modifier.testTag(TilesTags.Reset),
                                    )
                                },
                                description = { IosText(state.defaultUrl) },
                                trailingContent = { NoChevron() },
                                onClick = actions.reset,
                            ),
                        )
                    }
                },
        )
        // What the phone keeps.
        val cache = state.cache
        IosSettingsGroup(
            title = stringResource(R.string.tiles_downloaded),
            items =
                buildList {
                    add(
                        IosSettingsItem(
                            icon = tile(RostyIcons.Map),
                            title = { IosText(state.host, Modifier.testTag(TilesTags.ThisServer), maxLines = 1) },
                            trailingContent = {
                                Value(
                                    cache?.let { bytesText(context, it.sourceBytes) }
                                        ?: stringResource(R.string.tiles_measuring),
                                )
                            },
                            trailingShrinks = true,
                        ),
                    )
                    if (cache != null && cache.otherBytes > 0) {
                        add(
                            IosSettingsItem(
                                icon = tile(RostyIcons.Globe),
                                title = {
                                    IosText(
                                        stringResource(R.string.tiles_other_servers),
                                        Modifier.testTag(TilesTags.Others),
                                    )
                                },
                                description = { IosText(bytesText(context, cache.otherBytes)) },
                                trailingContent = { Value(stringResource(R.string.tiles_clear)) },
                                onClick = actions.clearOthers,
                            ),
                        )
                    }
                    add(
                        IosSettingsItem(
                            icon = tile(RostyIcons.Trash),
                            title = {
                                IosText(
                                    stringResource(R.string.tiles_clear_all),
                                    Modifier.testTag(TilesTags.ClearAll),
                                    color = IosTheme.colors.systemRed,
                                )
                            },
                            trailingContent = { NoChevron() },
                            onClick = actions.clearAll,
                        ),
                    )
                },
        )
        if (state.cacheChoices.isNotEmpty()) {
            IosSettingsGroup(
                title = stringResource(R.string.tiles_cache_size),
                items =
                    state.cacheChoices.map { mb ->
                        choiceItem(
                            stringResource(R.string.tiles_megabytes, mb),
                            mb == state.cacheMb,
                            TilesTags.cap(mb),
                        ) {
                            actions.setCache(mb)
                        }
                    },
            )
            Footnote(stringResource(R.string.tiles_cache_footer))
        }
        // S10: the area the map showed last.
        IosSettingsGroup(
            title = stringResource(R.string.offline_title),
            items = offlineItems(context, state, actions),
        )
        Footnote(stringResource(R.string.offline_footer))
    }
}

@Composable
private fun offlineItems(
    context: Context,
    state: TilesUiState,
    actions: TilesPageActions,
): List<IosSettingsItem> {
    val offline = state.offline
    return when {
        offline.active -> {
            listOf(offlineProgressItem(offline, actions.cancelDownload))
        }

        offline is OfflineState.Finished || offline is OfflineState.Failed -> {
            listOf(
                IosSettingsItem(
                    icon = tile(RostyIcons.Download),
                    title = { IosText(offlineText(context, offline), Modifier.testTag(TilesTags.Done)) },
                    trailingContent = { Value(stringResource(R.string.offline_ok)) },
                    onClick = actions.dismissDone,
                ),
            )
        }

        else -> {
            val area = state.area
            listOf(
                IosSettingsItem(
                    icon = tile(RostyIcons.Download),
                    title = {
                        IosText(
                            stringResource(R.string.offline_download),
                            Modifier.testTag(TilesTags.Download),
                        )
                    },
                    description = {
                        IosText(area?.let { areaText(context, it) } ?: stringResource(R.string.offline_no_view))
                    },
                    trailingContent = { NoChevron() },
                    enabled = area != null && area.tiles > 0,
                    onClick = actions.download,
                ),
            )
        }
    }
}
