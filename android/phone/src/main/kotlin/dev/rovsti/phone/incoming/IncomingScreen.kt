package dev.rovsti.phone.incoming

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.rovsti.design.controls.GlassCircleButton
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.avatarColor
import dev.rovsti.design.media.AnimatedChromeMesh
import dev.rovsti.design.media.CallAura
import dev.rovsti.design.media.rememberCallAuraPalette
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.design.transition.isTransitionActive
import dev.rovsti.design.transition.rovstiSharedBounds
import dev.rovsti.phone.ANSWER_GREEN
import dev.rovsti.phone.AURA_SHOT_PHASE
import dev.rovsti.phone.DECLINE_RED
import dev.rovsti.phone.IncomingMetrics

/** The nodes the tests and the accessibility checks find. */
object IncomingTags {
    const val Screen = "incoming-screen"
    const val Aura = "incoming-aura"
    const val Name = "incoming-name"
    const val Number = "incoming-number"
    const val Sim = "incoming-sim"
    const val Lock = "incoming-lock"
    const val Answer = "incoming-answer"
    const val Decline = "incoming-decline"
    const val Message = "incoming-message"
    const val Remind = "incoming-remind"
    const val Silence = "incoming-silence"
    const val Waiting = "waiting-card"
    const val WaitingEnd = "waiting-end"
    const val WaitingHold = "waiting-hold"
    const val WaitingDecline = "waiting-decline"
    const val WaitingMessage = "waiting-message"
    const val ReplyPrefix = "incoming-reply-"
    const val ReplyCustom = "incoming-reply-custom"
}

/**
 * How the aura moves. The app lets it run; a screenshot fixes [phase] so a golden never shows a half-moved frame, and
 * `running = false` is the same still frame without a phase.
 */
data class AuraMode(
    val running: Boolean = true,
    val phase: Float? = null,
) {
    companion object {
        val Shot = AuraMode(running = false, phase = AURA_SHOT_PHASE)
    }
}

/**
 * The full-screen Incoming Call screen over the model's state. The model is built from a [CallSource]; its effects
 * (sheets, toasts, leaving) are handled once by [IncomingEffects] at the top of the app, so the screen only draws.
 */
@Composable
fun IncomingScreen(
    model: IncomingViewModel,
    modifier: Modifier = Modifier,
    aura: AuraMode = AuraMode(),
) {
    val ui by model.state.collectAsState()
    IncomingContent(ui, IncomingStrings.current(), model::onAction, modifier, aura)
}

/**
 * The screen without a model. The aura wash fills the screen (the avatar's colour drifting, reduce motion stills it),
 * a chrome mesh glows at the top, and everything else is a column: the status label, the avatar (the same node the
 * In-Call screen flies it to), the name, the number and the SIM, then Remind me / Message / Silence and the two big
 * circles. The top block scrolls when the font is large; the buttons never do.
 */
@Composable
fun IncomingContent(
    ui: IncomingUi,
    strings: IncomingStrings,
    onAction: (IncomingAction) -> Unit,
    modifier: Modifier = Modifier,
    aura: AuraMode = AuraMode(),
) {
    val caller = ui.caller
    val transition by rememberUpdatedState(isTransitionActive())
    val seed = caller?.let { avatarColor(it.avatarKey) } ?: Color.Black
    val palette = rememberCallAuraPalette(if (caller != null) seed.toArgb() else null)
    val latestAction by rememberUpdatedState(onAction)
    // Story 5.6: report the first frame of this call's screen, once (the model also guards a second report).
    LaunchedEffect(caller?.callId) {
        val id = caller?.callId ?: return@LaunchedEffect
        withFrameNanos { }
        latestAction(IncomingAction.FirstFrame(id))
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(IncomingTags.Screen)
            .semantics {
                caller?.let { contentDescription = strings.from(it.title) }
                liveRegion = LiveRegionMode.Assertive
            },
    ) {
        CallAura(
            palette = palette,
            modifier = Modifier.testTag(IncomingTags.Aura),
            running = aura.running && !ui.silenced,
            paused = { transition },
            fixedPhase = aura.phase,
        )
        AnimatedChromeMesh(
            modifier = Modifier.fillMaxWidth().height(IncomingMetrics.MeshHeight),
            paused = { transition },
            primaryColor = seed,
            secondaryColor = Color(INDIGO),
            alpha = MESH_ALPHA,
            fixedPhase = aura.phase,
        )
        if (caller != null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                    .padding(horizontal = IncomingMetrics.SidePad),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CallerBlock(ui, caller, strings, Modifier.weight(1f))
                SecondaryRow(ui, strings, onAction)
                Spacer(Modifier.height(IncomingMetrics.SecondaryLift))
                PrimaryRow(strings, onAction)
                Spacer(Modifier.height(IncomingMetrics.BottomPad))
            }
        }
    }
}

@Composable
private fun CallerBlock(
    ui: IncomingUi,
    caller: IncomingCaller,
    strings: IncomingStrings,
    modifier: Modifier,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(IncomingMetrics.LabelTop))
        RovstiText(
            strings.title,
            style = RovstiTheme.type.subheadline,
            color = OnAuraSecondary,
            maxLines = 1,
        )
        if (ui.locked) {
            Spacer(Modifier.height(IncomingMetrics.LockChipGap))
            RovstiText(
                strings.locked,
                modifier =
                    Modifier
                        .testTag(IncomingTags.Lock)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = LOCK_CHIP_ALPHA))
                        .padding(horizontal = IncomingMetrics.ChipPadX, vertical = Spacing.xs),
                style = RovstiTheme.type.caption1,
                color = OnAura,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(IncomingMetrics.AvatarGap))
        Avatar(
            name = caller.title,
            modifier = Modifier.rovstiSharedBounds(AvatarKey),
            size = IncomingMetrics.Avatar,
            colorKey = caller.avatarKey,
        )
        Spacer(Modifier.height(IncomingMetrics.AvatarGap))
        RovstiText(
            caller.title,
            modifier = Modifier.testTag(IncomingTags.Name).semantics { heading() },
            style = RovstiTheme.type.largeTitle,
            color = OnAura,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        caller.subtitle?.let {
            Spacer(Modifier.height(IncomingMetrics.NameGap))
            RovstiText(
                it,
                modifier = Modifier.testTag(IncomingTags.Number),
                style = RovstiTheme.type.body,
                color = OnAuraSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        caller.sim?.let {
            Spacer(Modifier.height(IncomingMetrics.NameGap))
            SimPill(it, strings)
        }
    }
}

/** The SIM the call arrives on: a coloured dot and its short label. Only drawn with two or more SIMs. */
@Composable
private fun SimPill(
    sim: SimChip,
    strings: IncomingStrings,
) {
    Row(
        Modifier
            .testTag(IncomingTags.Sim)
            .height(IncomingMetrics.ChipHeight)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = LOCK_CHIP_ALPHA))
            .padding(horizontal = IncomingMetrics.ChipPadX)
            .semantics(mergeDescendants = true) { contentDescription = strings.sim(sim.label) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Box(Modifier.size(IncomingMetrics.ChipDot).clip(CircleShape).background(Color(sim.colorArgb)))
        RovstiText(sim.label, style = RovstiTheme.type.footnote, color = OnAura, maxLines = 1)
    }
}

@Composable
private fun SecondaryRow(
    ui: IncomingUi,
    strings: IncomingStrings,
    onAction: (IncomingAction) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(IncomingMetrics.SecondaryGap), verticalAlignment = Alignment.Top) {
        CircleAction(
            RovstiIcons.Bell,
            strings.remind,
            IncomingTags.Remind,
            IncomingMetrics.Secondary,
        ) { onAction(IncomingAction.OpenReminders) }
        if (ui.canMessage) {
            CircleAction(
                RovstiIcons.Message,
                strings.message,
                IncomingTags.Message,
                IncomingMetrics.Secondary,
            ) { onAction(IncomingAction.OpenReplies) }
        }
        CircleAction(
            RovstiIcons.BellSlash,
            if (ui.silenced) strings.silenced else strings.silence,
            IncomingTags.Silence,
            IncomingMetrics.Secondary,
            enabled = !ui.silenced,
        ) { onAction(IncomingAction.Silence) }
    }
}

@Composable
private fun PrimaryRow(
    strings: IncomingStrings,
    onAction: (IncomingAction) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(IncomingMetrics.ButtonGap), verticalAlignment = Alignment.Top) {
        CircleAction(
            RovstiIcons.Phone,
            strings.decline,
            IncomingTags.Decline,
            IncomingMetrics.Button,
            fill = Color(DECLINE_RED),
            iconRotation = DECLINE_ROTATION,
        ) { onAction(IncomingAction.Decline) }
        CircleAction(
            RovstiIcons.Phone,
            strings.answer,
            IncomingTags.Answer,
            IncomingMetrics.Button,
            fill = Color(ANSWER_GREEN),
        ) { onAction(IncomingAction.Answer) }
    }
}

/**
 * A glass circle with a caption under it. [fill] paints the call colour inside the circle (Answer, Decline); without
 * it the glass shows. Both act when the finger lifts (the button's click), so a stray touch never answers.
 */
@Composable
private fun CircleAction(
    icon: ImageVector,
    label: String,
    tag: String,
    size: Dp,
    fill: Color = Color.Unspecified,
    iconRotation: Float = 0f,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(size + Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassCircleButton(
            onClick = onClick,
            enabled = enabled,
            size = size,
            alpha = if (enabled) 1f else DISABLED_ALPHA,
            modifier =
                Modifier
                    .testTag(tag)
                    .semantics(mergeDescendants = true) {
                        contentDescription = label
                        if (!enabled) stateDescription = label
                    },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (fill != Color.Unspecified) Modifier.background(fill) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                RovstiIcon(
                    icon,
                    null,
                    modifier = Modifier.rotate(iconRotation),
                    tint = if (fill != Color.Unspecified) Color.White else OnAura,
                    size =
                        if (size ==
                            IncomingMetrics.Button
                        ) {
                            IncomingMetrics.PrimaryIcon
                        } else {
                            IncomingMetrics.SecondaryIcon
                        },
                )
            }
        }
        Spacer(Modifier.height(IncomingMetrics.CaptionGap))
        RovstiText(
            label,
            style = RovstiTheme.type.footnote,
            color = if (enabled) OnAura else OnAuraSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The key of the avatar that flies to the In-Call screen (S3). */
const val AvatarKey = "call-avatar"

private val OnAura = Color.White
private val OnAuraSecondary = Color.White.copy(alpha = 0.78f)
private const val INDIGO = 0xFF5E5CE6
private const val MESH_ALPHA = 0.28f
private const val LOCK_CHIP_ALPHA = 0.18f
private const val DISABLED_ALPHA = 0.55f
private const val DECLINE_ROTATION = 135f
