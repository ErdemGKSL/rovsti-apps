package dev.rovsti.phone.settings.speeddial

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.phone.R
import dev.rovsti.phone.favorites.FavoritesAction
import dev.rovsti.phone.favorites.presentFavoritePicker
import dev.rovsti.phone.settings.SettingsFilesHost

/** The Speed dial route: the screen over its view model, the contact picker (reused from Favorites) and the Undo pills. */
@Composable
fun SpeedDialEntry(
    model: SpeedDialViewModel,
    host: SettingsFilesHost,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val ui by model.state.collectAsState()
    val latest = rememberUpdatedState(ui)
    val overlays = LocalOverlay.current
    val undo = stringResource(R.string.set_undo)
    val rejected = stringResource(R.string.sd_rejected)
    val clearedAll = stringResource(R.string.sd_cleared_all)
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    LaunchedEffect(model) {
        model.effect.collect { effect ->
            when (effect) {
                is SpeedDialEffect.Assigned -> {
                    overlays.showUndo(
                        resources.getString(R.string.sd_assigned, effect.name, effect.digit),
                        { model.onAction(SpeedDialAction.Undo(effect.previous)) },
                        undo,
                    )
                }

                is SpeedDialEffect.Cleared -> {
                    overlays.showUndo(
                        resources.getString(R.string.sd_cleared, effect.digit),
                        { model.onAction(SpeedDialAction.Undo(effect.previous)) },
                        undo,
                    )
                }

                is SpeedDialEffect.ClearedAll -> {
                    overlays.showUndo(clearedAll, { model.onAction(SpeedDialAction.Undo(effect.previous)) }, undo)
                }

                SpeedDialEffect.Rejected -> {
                    overlays.showToast(rejected)
                }
            }
        }
    }
    val shareTitle = stringResource(R.string.sd_share_title)
    val voicemail = stringResource(R.string.sd_voicemail)
    val nothing = stringResource(R.string.sd_none_to_share)
    val pickTitle = { digit: Int -> resources.getString(R.string.sd_pick_title, digit) }
    SpeedDialScreen(
        ui = ui,
        onAction = model::onAction,
        onPick = { digit ->
            presentFavoritePicker(overlays, pickTitle(digit), latest.value.pickables) { action ->
                if (action is FavoritesAction.Add) model.onAction(SpeedDialAction.Assign(digit, action.id))
            }
        },
        onShare = {
            val now = latest.value
            if (now.assignedCount == 0) {
                overlays.showToast(nothing)
            } else {
                host.share(speedDialShareText(shareTitle, voicemail, now))
            }
        },
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}
