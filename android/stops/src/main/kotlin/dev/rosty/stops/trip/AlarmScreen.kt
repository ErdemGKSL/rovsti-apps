package dev.rosty.stops.trip

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.motion.RostyMotion
import dev.rosty.design.overlay.OverlayEntry
import dev.rosty.design.overlay.OverlayScope
import dev.rosty.design.theme.RostyShapes
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.stops.R
import dev.rosty.stops.home.OnSheet

/** Test tags of the alarm and arrival UI. */
object AlarmTags {
    const val Screen = "alarm-screen"
    const val Dismiss = "alarm-dismiss"
    const val Snooze = "alarm-snooze"
    const val NoSnoozes = "alarm-no-snoozes"
    const val LastTrip = "last-trip"
    const val StartAgain = "last-trip-start"
    const val Save = "last-trip-save"
    const val Share = "last-trip-share"
    const val Close = "last-trip-close"
}

/**
 * The in-app alarm (GPUI `alarm_screen`): over everything while Stops is open, a red-washed full window with "Wake
 * up", "Crossed A – B" and the two actions of the native `AlarmActivity` (Dismiss, Snooze N min or "No snoozes
 * left"). Back does not close it: only an answer does. It leaves when the alarm view model drops the alarm.
 */
class AlarmEntry(
    private val viewModel: AlarmViewModel,
    title: String,
) : OverlayEntry(modal = true, title = title, dismissOnBack = false) {
    @Composable
    override fun Content(host: OverlayScope) {
        val state by viewModel.state.collectAsState()
        val alarm = state.alarm
        AnimatedVisibility(
            visibleState = this.state,
            enter = fadeIn(RostyMotion.appear(1f)),
            exit = fadeOut(RostyMotion.appear(1f)),
        ) {
            val shown = remember { arrayOfNulls<AlarmUi>(1) }
            if (alarm != null) shown[0] = alarm
            shown[0]?.let { OnSheet { AlarmContent(it, viewModel::onAction) } }
        }
    }
}

@Composable
private fun AlarmContent(
    alarm: AlarmUi,
    onAction: (AlarmAction) -> Unit,
) {
    val colors = RostyTheme.colors
    val type = RostyTheme.type
    val wash = colors.destructive
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.page)
            .background(Brush.verticalGradient(listOf(wash.copy(alpha = WASH_TOP), wash.copy(alpha = WASH_BOTTOM))))
            // The screen takes every touch: nothing under it answers while the alarm rings.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .safeDrawingPadding()
            .padding(horizontal = Spacing.xl)
            .testTag(AlarmTags.Screen),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            RostyText(stringResource(R.string.alarm_wake_up), style = type.title2, color = colors.labelSecondary)
            RostyText(
                stringResource(R.string.alarm_crossed, alarm.label),
                Modifier.semantics { traversalIndex = -1f },
                style = type.largeTitle,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Column(
                Modifier.fillMaxWidth().padding(top = Spacing.xl * 2),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                CapsuleButton(
                    stringResource(R.string.alarm_dismiss),
                    { onAction(AlarmAction.Dismiss) },
                    Modifier.fillMaxWidth().heightIn(min = BUTTON_HEIGHT).testTag(AlarmTags.Dismiss),
                    style = CapsuleStyle.Filled,
                    fillWidth = true,
                )
                val minutes = alarm.snoozeMin
                if (minutes != null) {
                    CapsuleButton(
                        stringResource(R.string.alarm_snooze, minutes),
                        { onAction(AlarmAction.Snooze) },
                        Modifier.fillMaxWidth().heightIn(min = BUTTON_HEIGHT).testTag(AlarmTags.Snooze),
                        fillWidth = true,
                    )
                } else {
                    RostyText(
                        stringResource(R.string.alarm_no_snoozes),
                        Modifier.fillMaxWidth().testTag(AlarmTags.NoSnoozes),
                        style = type.footnote,
                        color = colors.labelSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * S27, the last trip card (GPUI `last_trip_card`): "Arrived at Pier West – Pier East", "18 min · 3.4 km · 1 snooze",
 * then Start again, Save as stop, Share and Close (Close only hides it).
 */
@Composable
fun LastTripCard(viewModel: AlarmViewModel) {
    val state by viewModel.state.collectAsState()
    val last = state.shownLastTrip ?: return
    val colors = RostyTheme.colors
    val onAction = viewModel::onAction
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter)
            .background(colors.groupedSurface, RostyShapes.card)
            .padding(Spacing.l)
            .testTag(AlarmTags.LastTrip),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        RostyText(
            stringResource(
                if (last.row.record.outcome == dev.rosty.core.model.stops.Outcome.Arrived) {
                    R.string.last_trip_arrived
                } else {
                    R.string.last_trip_stopped
                },
                last.row.title,
            ),
            style = RostyTheme.type.headline,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        RostyText(last.row.summaryLine, style = RostyTheme.type.subheadline, color = colors.labelSecondary)
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            CapsuleButton(
                stringResource(R.string.last_trip_start),
                { onAction(AlarmAction.StartAgain) },
                Modifier.testTag(AlarmTags.StartAgain),
                style = CapsuleStyle.Filled,
            )
            CapsuleButton(stringResource(R.string.last_trip_save), {
                onAction(AlarmAction.SaveLastTrip)
            }, Modifier.testTag(AlarmTags.Save))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            CapsuleButton(stringResource(R.string.last_trip_share), {
                onAction(AlarmAction.ShareLastTrip)
            }, Modifier.testTag(AlarmTags.Share))
            CapsuleButton(stringResource(R.string.last_trip_close), {
                onAction(AlarmAction.CloseLastTrip)
            }, Modifier.testTag(AlarmTags.Close))
        }
    }
}

private val BUTTON_HEIGHT = 64.dp
private const val WASH_TOP = 0.45f
private const val WASH_BOTTOM = 0.12f
