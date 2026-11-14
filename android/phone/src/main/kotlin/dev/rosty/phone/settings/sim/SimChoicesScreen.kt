package dev.rosty.phone.settings.sim

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rosty.design.controls.EmptyState
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosText
import dev.rosty.design.list.CustomAction
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRow
import dev.rosty.design.list.SwipeAction
import dev.rosty.design.list.SwipeRow
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.showUndo
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.phone.R
import dev.rosty.phone.settings.ActionRow
import dev.rosty.phone.settings.GroupFooter
import dev.rosty.phone.settings.NoGutter
import dev.rosty.shell.settings.SettingsPage
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the SIM choices page. */
object SimChoicesTags {
    const val Page = "sim-choices-page"
    const val ForgetAll = "sim-choices-forget-all"
    const val Empty = "sim-choices-empty"

    fun row(number: String) = "sim-choice-row-$number"
}

/**
 * SIM choices (E5.9b): the SIM remembered for each number, newest names first by name. A swipe forgets one, Forget all
 * sits under the card, both with Undo. On a phone with fewer than two SIMs the page says so instead of listing.
 */
@Composable
fun SimChoicesScreen(
    ui: SimChoicesUi,
    onAction: (SimChoicesAction) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
) {
    SettingsPage(
        title = stringResource(R.string.sim_title),
        modifier = modifier.testTag(SimChoicesTags.Page),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
        horizontalPadding = NoGutter,
    ) {
        if (!ui.visible || ui.rows.isEmpty()) {
            Box(Modifier.padding(vertical = Spacing.xl).testTag(SimChoicesTags.Empty)) {
                EmptyState(
                    icon = RostyIcons.Phone,
                    title = stringResource(if (ui.visible) R.string.sim_empty_title else R.string.sim_title),
                    message =
                        stringResource(
                            if (ui.visible) R.string.sim_empty_message else R.string.sim_single_message,
                        ),
                )
            }
        } else {
            InsetGroupedSection {
                for (item in ui.rows) {
                    row { ChoiceRow(item, onAction) }
                }
            }
            InsetGroupedSection(Modifier.padding(top = Spacing.l)) {
                row {
                    ActionRow(
                        stringResource(R.string.sim_forget_all),
                        { onAction(SimChoicesAction.ForgetAll) },
                        SimChoicesTags.ForgetAll,
                        destructive = true,
                    )
                }
            }
        }
        GroupFooter(stringResource(R.string.sim_footer), Modifier.padding(horizontal = Spacing.gutter))
    }
}

@Composable
private fun ChoiceRow(
    choice: SimChoiceRow,
    onAction: (SimChoicesAction) -> Unit,
) {
    val colors = RostyTheme.colors
    val forget = stringResource(R.string.sim_forget)
    val swipe =
        persistentListOf(SwipeAction(forget, colors.destructive) { onAction(SimChoicesAction.Forget(choice.number)) })
    SwipeRow(trailing = swipe, rowKey = choice.number) {
        Box(Modifier.background(colors.groupedSurface)) {
            ListRow(
                title = choice.name ?: choice.number,
                secondary = choice.number.takeIf { choice.name != null },
                trailing = {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(Spacing.m))
                            .background(colors.fillSecondary)
                            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                    ) {
                        IosText(choice.simLabel, color = colors.labelSecondary, maxLines = 1)
                    }
                },
                actions = persistentListOf(CustomAction(forget) { onAction(SimChoicesAction.Forget(choice.number)) }),
                modifier = Modifier.testTag(SimChoicesTags.row(choice.number)),
            )
        }
    }
}

/** The SIM choices route: the screen over its view model and the Undo pills. */
@Composable
fun SimChoicesEntry(
    model: SimChoicesViewModel,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val ui by model.state.collectAsState()
    val overlays = LocalOverlay.current
    val resources = LocalContext.current.resources
    val undo = stringResource(R.string.set_undo)
    LaunchedEffect(model) {
        model.effect.collect { effect ->
            when (effect) {
                is SimChoicesEffect.Forgotten -> {
                    overlays.showUndo(
                        resources.getString(R.string.sim_forgotten, effect.name),
                        { model.onAction(SimChoicesAction.Undo(effect.previous)) },
                        undo,
                    )
                }

                is SimChoicesEffect.ForgottenAll -> {
                    overlays.showUndo(
                        resources.getString(R.string.sim_forgotten_all, effect.count),
                        { model.onAction(SimChoicesAction.Undo(effect.previous)) },
                        undo,
                    )
                }
            }
        }
    }
    SimChoicesScreen(ui, model::onAction, onBack = onBack, onBackLongClick = onBackLongClick)
}
