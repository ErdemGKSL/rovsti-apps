package dev.rovsti.shell.permissions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.controls.CapsuleStyle
import dev.rovsti.design.controls.TextButton
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.platform.system.permissions.NextAction
import dev.rovsti.platform.system.permissions.PermissionController
import dev.rovsti.platform.system.permissions.PermissionGroup
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.platform.system.permissions.nextAction
import dev.rovsti.shell.R
import kotlinx.collections.immutable.persistentListOf

/**
 * Watches [group] and, while Android would still show its dialog (NeverAsked, DeniedOnce), explains why in a rationale
 * sheet at the medium detent: an icon, [rationale] and two buttons. "Continue" closes the sheet and launches the
 * system dialog through [PermissionController.contract]; "Not now" (or swiping the sheet away) closes it, and it does
 * not come back during this visit, so a refusal is never nagged. Returns the live state, so the caller can hide what
 * needs the permission and show a [PermissionRow] when the state is one that only Settings can change
 * (DeniedForGood, RevokedWhileRunning; the sheet never appears for those).
 *
 * The result of the dialog is not read here: the next `onResume` refreshes the controller, which is the only place that
 * does (the activity's), and this composable re-reads the flow.
 */
@Composable
fun PermissionGate(
    group: PermissionGroup,
    rationale: PermissionRationale = rationaleFor(group),
    controller: PermissionController = LocalPermissionController.current,
    overlays: OverlayController = LocalOverlay.current,
    onNotNow: () -> Unit = {},
): PermissionState {
    val state by controller.state(group).collectAsState()
    val launcher =
        rememberLauncherForActivityResult(remember(controller, group) { controller.contract(group) }) {
            // The dialog is over: the system calls onResume next, which refreshes. Nothing to do with the map here.
        }
    var declined by rememberSaveable(group.id) { mutableStateOf(false) }
    val notNow by rememberUpdatedState(onNotNow)
    val asks = nextAction(state) == NextAction.Ask && !declined
    DisposableEffect(asks, overlays, rationale) {
        val entry =
            if (asks) {
                overlays.present(
                    SheetEntry(
                        title = rationale.title,
                        detents = persistentListOf(Detent.Medium),
                        onDismissed = {
                            declined = true
                            notNow()
                        },
                    ) { sheet ->
                        RationaleBody(
                            rationale = rationale,
                            onContinue = {
                                declined = true
                                sheet.dismiss()
                                launcher.launch(Unit)
                            },
                            onNotNow = { sheet.dismiss() },
                        )
                    },
                )
            } else {
                null
            }
        onDispose { entry?.let { overlays.dismiss(it) } }
    }
    return state
}

@Composable
private fun RationaleBody(
    rationale: PermissionRationale,
    onContinue: () -> Unit,
    onNotNow: () -> Unit,
) {
    val colors = RovstiTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.m, bottom = Spacing.s)
            .testTag(PermissionTags.Rationale),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Box(
            Modifier.size(PermissionMetrics.heroTile).clip(CircleShape).background(colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            RovstiIcon(rationale.icon, null, tint = colors.onAccent, size = PermissionMetrics.heroGlyph)
        }
        RovstiText(
            rationale.title,
            style = RovstiTheme.type.title2,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        RovstiText(
            rationale.body,
            style = RovstiTheme.type.subheadline,
            color = colors.labelSecondary,
            textAlign = TextAlign.Center,
        )
        Column(
            Modifier.fillMaxWidth().padding(top = Spacing.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CapsuleButton(
                text = stringResource(R.string.permission_continue),
                onClick = onContinue,
                style = CapsuleStyle.Filled,
                modifier = Modifier.fillMaxWidth().testTag(PermissionTags.Continue),
            )
            TextButton(
                text = stringResource(R.string.permission_not_now),
                onClick = onNotNow,
                modifier = Modifier.testTag(PermissionTags.NotNow),
            )
        }
    }
}
