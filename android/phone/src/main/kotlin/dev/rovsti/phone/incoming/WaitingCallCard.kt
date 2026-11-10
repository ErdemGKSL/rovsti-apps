package dev.rovsti.phone.incoming

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.controls.CapsuleStyle
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.foundation.LocalIosBackdrop
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.ANSWER_GREEN
import dev.rovsti.phone.DECLINE_RED
import dev.rovsti.phone.IncomingMetrics
import dev.rovsti.phone.wa.WaAudioLabel

/**
 * The waiting-call card (story 15.8): a second caller while a call is live. A flat card at the top of the screen over
 * the call (no backdrop is sampled), the caller on one line and four pills in a 2 x 2 grid: End & Answer, Hold &
 * Answer (End held call & Answer when a call is already held), Decline and Message. It never closes by itself; the
 * call underneath keeps running, and every pill acts when the finger lifts.
 */
@Composable
fun WaitingCallCard(
    ui: WaitingUi,
    strings: IncomingStrings,
    onAction: (IncomingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The card is opaque: its pills must not sample the call page behind it (that made them muddy), so they go flat.
    CompositionLocalProvider(LocalIosBackdrop provides null) { CardBody(ui, strings, onAction, modifier) }
}

@Composable
private fun CardBody(
    ui: WaitingUi,
    strings: IncomingStrings,
    onAction: (IncomingAction) -> Unit,
    modifier: Modifier,
) {
    val colors = RovstiTheme.colors
    val shape = RoundedCornerShape(IncomingMetrics.CardRadius)
    Column(
        modifier
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .padding(top = IncomingMetrics.CardTop, start = IncomingMetrics.CardSide, end = IncomingMetrics.CardSide)
            .fillMaxWidth()
            .shadow(IncomingMetrics.CardShadow, shape)
            .clip(shape)
            .background(colors.groupedSurface)
            .padding(IncomingMetrics.CardPad)
            .testTag(IncomingTags.Waiting)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalArrangement = Arrangement.spacedBy(IncomingMetrics.CardGap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Avatar(name = ui.title, size = IncomingMetrics.CardAvatar, colorKey = ui.avatarKey)
            Column(Modifier.weight(1f)) {
                RovstiText(
                    strings.waiting(ui.title),
                    style = RovstiTheme.type.headline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ui.whatsapp) {
                    WaAudioLabel(strings.waAudio, colors.labelSecondary, Modifier.testTag(IncomingTags.WaAudio))
                }
                ui.subtitle?.let {
                    RovstiText(
                        it,
                        style = RovstiTheme.type.subheadline,
                        color = colors.labelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (ui.whatsapp) {
            // A WhatsApp offer cannot be held or answered with a message: End & Answer or Decline.
            Row(horizontalArrangement = Arrangement.spacedBy(IncomingMetrics.CardGap)) {
                EndAnswerButton(ui, strings, onAction, Modifier.weight(1f))
                DeclineButton(strings, onAction, Modifier.weight(1f))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(IncomingMetrics.CardGap)) {
                EndAnswerButton(ui, strings, onAction, Modifier.weight(1f))
                CapsuleButton(
                    if (ui.relabelled) strings.endHeldAnswer else strings.holdAnswer,
                    onClick = { onAction(IncomingAction.HoldAndAnswer) },
                    modifier = Modifier.weight(1f).testTag(IncomingTags.WaitingHold),
                    style = CapsuleStyle.Filled,
                    tint = Color(ANSWER_GREEN),
                    enabled = ui.canHold,
                    fillWidth = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(IncomingMetrics.CardGap)) {
                CapsuleButton(
                    strings.message,
                    onClick = { onAction(IncomingAction.OpenWaitingReplies) },
                    modifier = Modifier.weight(1f).testTag(IncomingTags.WaitingMessage),
                    icon = RovstiIcons.Message,
                    enabled = ui.canMessage,
                    fillWidth = true,
                )
                DeclineButton(strings, onAction, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EndAnswerButton(
    ui: WaitingUi,
    strings: IncomingStrings,
    onAction: (IncomingAction) -> Unit,
    modifier: Modifier,
) {
    CapsuleButton(
        strings.endAnswer,
        onClick = { onAction(IncomingAction.EndAndAnswer) },
        modifier = modifier.testTag(IncomingTags.WaitingEnd),
        tint = RovstiTheme.colors.destructive,
        fillWidth = true,
    )
}

@Composable
private fun DeclineButton(
    strings: IncomingStrings,
    onAction: (IncomingAction) -> Unit,
    modifier: Modifier,
) {
    CapsuleButton(
        strings.decline,
        onClick = { onAction(IncomingAction.DeclineWaiting) },
        modifier = modifier.testTag(IncomingTags.WaitingDecline),
        style = CapsuleStyle.Filled,
        tint = Color(DECLINE_RED),
        fillWidth = true,
    )
}
