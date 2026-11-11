package dev.rovsti.phone.incoming

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.rovsti.design.controls.GlassCircleButton
import dev.rovsti.design.controls.PlainIconAction
import dev.rovsti.design.controls.SlideToAnswer
import dev.rovsti.design.controls.SlideToAnswerDefaults
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.avatarColor
import dev.rovsti.design.media.AnimatedChromeMesh
import dev.rovsti.design.media.CallAura
import dev.rovsti.design.media.CallBackdrop
import dev.rovsti.design.media.CallPhotoState
import dev.rovsti.design.media.rememberCallAuraPalette
import dev.rovsti.design.media.rememberCallPhotoState
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.design.transition.isTransitionActive
import dev.rovsti.design.transition.rovstiSharedBounds
import dev.rovsti.phone.ANSWER_GREEN
import dev.rovsti.phone.AURA_SHOT_PHASE
import dev.rovsti.phone.DECLINE_RED
import dev.rovsti.phone.IncomingMetrics
import dev.rovsti.phone.wa.WaAudioLabel

/** The nodes the tests and the accessibility checks find. */
object IncomingTags {
    const val WaAudio = "incoming-wa-audio"
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
    photos: CallPhotos = CallPhotos.None,
) {
    val ui by model.state.collectAsState()
    val photo = rememberCallPhoto(photos, ui.caller?.contactId, ui.caller?.photoJid)
    IncomingContent(ui, IncomingStrings.current(), model::onAction, modifier, aura, photo)
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
    /** The caller's photo model (a contacts URI); null shows the aura alone. */
    photo: Any? = null,
) {
    val caller = ui.caller
    val photoState = rememberCallPhotoState()
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
        // The photo fades in over the aura once it has loaded; the first frame is the aura (never waits for the photo).
        CallBackdrop(photo = photo, state = photoState, cacheKey = caller?.contactId?.toString())
        if (caller != null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CallerBlock(
                    ui,
                    caller,
                    strings,
                    photoState,
                    Modifier.weight(1f).padding(horizontal = IncomingMetrics.SidePad),
                )
                if (ui.locked) {
                    // Locked: slide to answer, with a small Decline under the track (a stray touch cannot answer).
                    SecondaryRow(ui, strings, onAction, Modifier.padding(horizontal = IncomingMetrics.SidePad))
                    Spacer(Modifier.height(IncomingMetrics.SecondaryLift))
                    SlideToAnswer(
                        label = strings.slideAnswer,
                        onAnswer = { onAction(IncomingAction.Answer) },
                        modifier =
                            Modifier
                                .padding(
                                    horizontal = SlideToAnswerDefaults.Gutter,
                                ).testTag(IncomingTags.Answer),
                        handleColor = Color(ANSWER_GREEN),
                    )
                    DeclineText(strings, onAction)
                    Spacer(Modifier.height(IncomingMetrics.LockedBottomPad))
                } else {
                    SecondaryRow(ui, strings, onAction, Modifier.padding(horizontal = IncomingMetrics.SidePad))
                    Spacer(Modifier.height(IncomingMetrics.SecondaryLift))
                    PrimaryRow(strings, onAction, Modifier.padding(horizontal = IncomingMetrics.SidePad))
                    Spacer(Modifier.height(IncomingMetrics.BottomPad))
                }
            }
        }
    }
}

@Composable
private fun CallerBlock(
    ui: IncomingUi,
    caller: IncomingCaller,
    strings: IncomingStrings,
    photoState: CallPhotoState,
    modifier: Modifier,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(IncomingMetrics.LabelTop))
        if (caller.whatsapp) {
            WaAudioLabel(strings.waAudio, OnAuraSecondary, Modifier.testTag(IncomingTags.WaAudio))
        } else {
            RovstiText(
                strings.title,
                style = RovstiTheme.type.subheadline,
                color = OnAuraSecondary,
                maxLines = 1,
            )
        }
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
        // The avatar fades out as the photo fades in and is gone once the photo shows; its space stays.
        val avatarGone by photoState.avatarGone
        if (avatarGone) {
            Spacer(Modifier.size(IncomingMetrics.Avatar))
        } else {
            CallAvatar(
                caller,
                Modifier
                    .rovstiSharedBounds(AvatarKey)
                    .graphicsLayer { alpha = 1f - photoState.alpha.value },
                IncomingMetrics.Avatar,
            )
        }
        Spacer(Modifier.height(IncomingMetrics.AvatarGap))
        CallNameText(
            caller.title,
            modifier = Modifier.fillMaxWidth().testTag(IncomingTags.Name).semantics { heading() },
            color = OnAura,
        )
        caller.subtitle?.let {
            Spacer(Modifier.height(IncomingMetrics.NameGap))
            RovstiText(
                it,
                modifier = Modifier.testTag(IncomingTags.Number),
                style = RovstiTheme.type.callout,
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
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Cell(Modifier.weight(1f)) {
            PlainIconAction(
                RovstiIcons.Bell,
                strings.remind,
                { onAction(IncomingAction.OpenReminders) },
                Modifier.testTag(IncomingTags.Remind),
                color = OnAura,
            )
        }
        if (ui.canMessage) {
            Cell(Modifier.weight(1f)) {
                PlainIconAction(
                    RovstiIcons.Message,
                    strings.message,
                    { onAction(IncomingAction.OpenReplies) },
                    Modifier.testTag(IncomingTags.Message),
                    color = OnAura,
                )
            }
        }
        if (!ui.locked) {
            Cell(Modifier.weight(1f)) {
                PlainIconAction(
                    RovstiIcons.BellSlash,
                    if (ui.silenced) strings.silenced else strings.silence,
                    { onAction(IncomingAction.Silence) },
                    Modifier.testTag(IncomingTags.Silence),
                    color = OnAura,
                    enabled = !ui.silenced,
                )
            }
        }
    }
}

@Composable
private fun Cell(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.TopCenter) { content() }
}

/** Decline and Answer: the disc centres sit at 1/4 and 3/4 of the width, as on iOS. */
@Composable
private fun PrimaryRow(
    strings: IncomingStrings,
    onAction: (IncomingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Cell(Modifier.weight(1f)) {
            CallDisc(
                strings.decline,
                IncomingTags.Decline,
                Color(DECLINE_RED),
                DECLINE_ROTATION,
            ) { onAction(IncomingAction.Decline) }
        }
        Cell(Modifier.weight(1f)) {
            CallDisc(strings.answer, IncomingTags.Answer, Color(ANSWER_GREEN), 0f) { onAction(IncomingAction.Answer) }
        }
    }
}

/** The small "Decline" under the slide track: plain text, a 48 dp high target, acts when the finger lifts. */
@Composable
private fun DeclineText(
    strings: IncomingStrings,
    onAction: (IncomingAction) -> Unit,
) {
    Box(
        Modifier
            .height(IncomingMetrics.DeclineTarget)
            .defaultMinSize(minWidth = IncomingMetrics.DeclineTarget)
            .testTag(IncomingTags.Decline)
            .clickable(role = Role.Button, onClickLabel = strings.decline) { onAction(IncomingAction.Decline) }
            .padding(horizontal = Spacing.l),
        contentAlignment = Alignment.Center,
    ) {
        RovstiText(strings.decline, style = RovstiTheme.type.callout, color = OnAura, maxLines = 1)
    }
}

/** A solid call disc (white filled handset on [fill]) with its caption; it acts when the finger lifts. */
@Composable
private fun CallDisc(
    label: String,
    tag: String,
    fill: Color,
    rotation: Float,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GlassCircleButton(
            onClick = onClick,
            size = IncomingMetrics.Button,
            forceFlat = true,
            modifier = Modifier.testTag(tag).semantics(mergeDescendants = true) { contentDescription = label },
        ) {
            Box(Modifier.fillMaxSize().background(fill), contentAlignment = Alignment.Center) {
                RovstiIcon(
                    RovstiIcons.PhoneFill,
                    null,
                    modifier = Modifier.rotate(rotation),
                    tint = Color.White,
                    size = IncomingMetrics.PrimaryIcon,
                )
            }
        }
        Spacer(Modifier.height(IncomingMetrics.CaptionGap))
        RovstiText(label, style = RovstiTheme.type.footnote, color = OnAura, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/**
 * The caller's picture on the aura: a photo as it is, else white initials on a 22 % white disc with a hairline white
 * rim (the palette colour could match the aura and vanish into it).
 */
@Composable
private fun CallAvatar(
    caller: IncomingCaller,
    modifier: Modifier,
    size: Dp,
) {
    Avatar(
        name = caller.title,
        modifier = modifier,
        size = size,
        colorKey = caller.avatarKey,
        onAura = true,
    )
}

/** The key of the avatar that flies to the In-Call screen (S3). */
const val AvatarKey = "call-avatar"

private val OnAura = Color.White
private val OnAuraSecondary = Color.White.copy(alpha = 0.78f)
private const val INDIGO = 0xFF5E5CE6
private const val MESH_ALPHA = 0.28f
private const val LOCK_CHIP_ALPHA = 0.18f
private const val DECLINE_ROTATION = 135f
