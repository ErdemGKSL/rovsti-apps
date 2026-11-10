package dev.rovsti.phone.incall

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.core.model.phone.AudioRoute
import dev.rovsti.design.chrome.BackdropScope
import dev.rovsti.design.controls.CallControlButton
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
import dev.rovsti.design.overlay.AnchorHolder
import dev.rovsti.design.overlay.captureAnchor
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.design.transition.isTransitionActive
import dev.rovsti.design.transition.rovstiSharedBounds
import dev.rovsti.phone.DECLINE_RED
import dev.rovsti.phone.InCallMetrics
import dev.rovsti.phone.incoming.AuraMode
import dev.rovsti.phone.incoming.AvatarKey
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/** The nodes the tests and the accessibility checks find. */
object InCallTags {
    const val Screen = "incall-screen"
    const val Aura = "incall-aura"
    const val Avatar = "incall-avatar"
    const val Name = "incall-name"
    const val Number = "incall-number"
    const val Status = "incall-status"
    const val Timer = "incall-timer"
    const val Sim = "incall-sim"
    const val Collapse = "incall-collapse"
    const val Mute = "incall-mute"
    const val Keypad = "incall-keypad"
    const val Audio = "incall-audio"
    const val AddCall = "incall-add-call"
    const val Hold = "incall-hold"
    const val Note = "incall-note"
    const val End = "incall-end"
    const val Digits = "incall-digits"
    const val Paste = "incall-paste"
    const val Remembered = "incall-remembered"
    const val Hide = "incall-hide"
    const val RouteList = "incall-route-list"
    const val Mini = "incall-mini"
    const val MiniEnd = "incall-mini-end"

    fun key(c: Char) = "incall-key-$c"

    fun route(id: String) = "incall-route-$id"
}

/** Counts the recompositions of [InCallContent] and of the timer text (the tests prove the timer is alone). */
internal object InCallProbe {
    val content = AtomicInteger()
    val timer = AtomicInteger()
}

/**
 * The timer text for [call], one string per second: the core writes it ([format]), `produceState` ticks it on the frame
 * of each second boundary. It is a [State] so only the text that reads it recomposes (see [TimerText]).
 */
@Composable
fun rememberCallTimer(
    call: InCallCall?,
    now: () -> Long,
    format: suspend (Long) -> String,
): State<String> {
    val latestNow by rememberUpdatedState(now)
    val latestFormat by rememberUpdatedState(format)
    val live = call != null && call.connectTimeMs > 0 && call.phase != CallPhase.Ended
    return produceState("", call?.callId, call?.connectTimeMs, live) {
        val connect = call?.connectTimeMs ?: 0L
        if (!live) {
            value = ""
            return@produceState
        }
        while (true) {
            val ms = (latestNow() - connect).coerceAtLeast(0)
            value = latestFormat(ms / MS_PER_S)
            delay(MS_PER_S - ms % MS_PER_S)
        }
    }
}

/** The one text that changes every second. It reads [timer] itself, so a tick recomposes this and nothing else. */
@Composable
internal fun TimerText(
    timer: State<String>,
    modifier: Modifier = Modifier,
) {
    SideEffect { InCallProbe.timer.incrementAndGet() }
    RovstiText(
        timer.value,
        modifier = modifier,
        style = RovstiTheme.type.title2.copy(fontFeatureSettings = "tnum"),
        color = OnAura,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

/**
 * The In-Call screen of one call: the aura of the contact's colour, the avatar that flew in from Incoming, the name and
 * the timer, then either the 3 x 2 grid of controls or the tone keypad, and the End button.
 */
@Composable
fun InCallContent(
    ui: InCallUi,
    strings: InCallStrings,
    timer: State<String>,
    onAction: (InCallAction) -> Unit,
    onAudio: (Rect) -> Unit,
    onTimerMenu: (Rect) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    aura: AuraMode = AuraMode(),
    /** The caller's photo model (a contacts URI); null shows the aura alone. */
    photo: Any? = null,
) {
    SideEffect { InCallProbe.content.incrementAndGet() }
    val call = ui.call
    val transition by rememberUpdatedState(isTransitionActive())
    val seed = call?.let { avatarColor(it.avatarKey) } ?: Color.Black
    val palette = rememberCallAuraPalette(if (call != null) seed.toArgb() else null)
    val photoState = rememberCallPhotoState()
    // The aura, the mesh and the photo are recorded into this screen's own backdrop; the frosted discs (chrome) sample it.
    BackdropScope(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag(InCallTags.Screen)
                .semantics { liveRegion = LiveRegionMode.Polite },
        baseBg = Color.Black,
        chrome = {
            if (call != null) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                        .padding(horizontal = InCallMetrics.SidePad),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CollapseRow(strings, onCollapse)
                    CallerBlock(ui, call, strings, timer, photoState, onAction, onTimerMenu, Modifier.weight(1f))
                    if (ui.keypad.open) {
                        TonePanel(ui, strings, onAction)
                    } else {
                        ControlGrid(ui, strings, onAction, onAudio)
                    }
                    Spacer(Modifier.height(InCallMetrics.EndTop))
                    EndButton(ui, strings, onAction)
                    Spacer(Modifier.height(InCallMetrics.BottomPad))
                }
            }
        },
    ) {
        CallAura(
            palette = palette,
            modifier = Modifier.testTag(InCallTags.Aura),
            running = aura.running && !ui.held,
            paused = { transition },
            fixedPhase = aura.phase,
        )
        AnimatedChromeMesh(
            modifier = Modifier.fillMaxWidth().height(InCallMetrics.MeshHeight),
            paused = { transition },
            primaryColor = seed,
            secondaryColor = Color(INDIGO),
            alpha = MESH_ALPHA,
            fixedPhase = aura.phase,
        )
        // The photo fades in over the aura once it has loaded; the first frame is the aura.
        CallBackdrop(photo = photo, state = photoState, cacheKey = call?.contactId?.toString())
    }
}

@Composable
private fun CollapseRow(
    strings: InCallStrings,
    onCollapse: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        CallControlButton(
            onClick = onCollapse,
            size = InCallMetrics.TopBar,
            touchTarget = Spacing.gutter * 3,
            debugTag = "call.collapse",
            modifier =
                Modifier
                    .testTag(InCallTags.Collapse)
                    .semantics(mergeDescendants = true) { contentDescription = strings.collapse },
        ) { glyph ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                RovstiIcon(
                    RovstiIcons.Back,
                    null,
                    modifier = Modifier.rotate(CHEVRON_DOWN),
                    tint = glyph,
                    size = InCallMetrics.TopIcon,
                )
            }
        }
    }
}

@Composable
private fun CallerBlock(
    ui: InCallUi,
    call: InCallCall,
    strings: InCallStrings,
    timer: State<String>,
    photoState: CallPhotoState,
    onAction: (InCallAction) -> Unit,
    onTimerMenu: (Rect) -> Unit,
    modifier: Modifier,
) {
    val anchor = remember { AnchorHolder() }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(InCallMetrics.LabelTop))
        // The avatar fades out as the photo fades in and is gone once the photo shows; its space stays.
        val avatarGone by photoState.avatarGone
        if (avatarGone) {
            Spacer(Modifier.size(InCallMetrics.Avatar))
        } else {
            Avatar(
                name = call.title,
                modifier =
                    Modifier
                        .testTag(InCallTags.Avatar)
                        .rovstiSharedBounds(AvatarKey)
                        .graphicsLayer { alpha = 1f - photoState.alpha.value },
                size = InCallMetrics.Avatar,
                colorKey = call.avatarKey,
                onAura = true,
            )
        }
        Spacer(Modifier.height(InCallMetrics.AvatarGap))
        // Touch and hold on the name block copies the number (U17).
        Column(
            Modifier
                .pointerInput(call.number) { detectTapGestures(onLongPress = { onAction(InCallAction.CopyNumber) }) }
                .semantics {
                    onLongClick(label = strings.copyNumber) {
                        onAction(InCallAction.CopyNumber)
                        true
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RovstiText(
                call.title,
                modifier = Modifier.testTag(InCallTags.Name).semantics { heading() },
                style = RovstiTheme.type.largeTitle,
                color = OnAura,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            call.subtitle?.let {
                Spacer(Modifier.height(InCallMetrics.NameGap))
                RovstiText(
                    it,
                    modifier = Modifier.testTag(InCallTags.Number),
                    style = RovstiTheme.type.body,
                    color = OnAuraSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(InCallMetrics.NameGap))
        StatusLine(ui, call, strings, timer, anchor, onTimerMenu)
        call.sim?.let {
            Spacer(Modifier.height(InCallMetrics.NameGap * 2))
            SimPill(it.label, it.colorArgb)
        }
    }
}

/** Calling... / the running timer / On hold / the ended summary. A tap on the timer opens its menu (U37). */
@Composable
private fun StatusLine(
    ui: InCallUi,
    call: InCallCall,
    strings: InCallStrings,
    timer: State<String>,
    anchor: AnchorHolder,
    onTimerMenu: (Rect) -> Unit,
) {
    val ended = ui.ended
    when {
        ended != null -> {
            RovstiText(
                strings.summary(call.title, ended, named = call.title != call.number),
                modifier = Modifier.testTag(InCallTags.Status),
                style = RovstiTheme.type.title3,
                color = OnAura,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }

        call.phase == CallPhase.Holding -> {
            RovstiText(
                strings.held,
                modifier = Modifier.testTag(InCallTags.Status),
                style = RovstiTheme.type.title3,
                color = OnAura,
                maxLines = 1,
            )
        }

        call.connectTimeMs <= 0 -> {
            RovstiText(
                strings.calling,
                modifier = Modifier.testTag(InCallTags.Status),
                style = RovstiTheme.type.title3,
                color = OnAuraSecondary,
                maxLines = 1,
            )
        }

        else -> {
            Box(
                Modifier
                    .captureAnchor(anchor)
                    .testTag(InCallTags.Timer)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onTimerMenu(anchor.rect) }
                    .padding(horizontal = Spacing.m, vertical = Spacing.xs),
            ) {
                TimerText(timer)
            }
        }
    }
}

/** The SIM the call runs on: a coloured dot and its short label. Only drawn with two or more SIMs. */
@Composable
private fun SimPill(
    label: String,
    colorArgb: Int,
) {
    Row(
        Modifier
            .testTag(InCallTags.Sim)
            .height(InCallMetrics.ChipHeight)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = CHIP_ALPHA))
            .padding(horizontal = InCallMetrics.ChipPadX)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Box(Modifier.size(InCallMetrics.ChipDot).clip(CircleShape).background(Color(colorArgb)))
        RovstiText(label, style = RovstiTheme.type.footnote, color = OnAura, maxLines = 1)
    }
}

/** Mute, Keypad, Audio / Add call, Hold, Note: three circles to a row, a caption under each. */
@Composable
private fun ControlGrid(
    ui: InCallUi,
    strings: InCallStrings,
    onAction: (InCallAction) -> Unit,
    onAudio: (Rect) -> Unit,
) {
    val enabled = ui.live
    val audioAnchor = remember { AnchorHolder() }
    Column(verticalArrangement = Arrangement.spacedBy(InCallMetrics.RowGap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(InCallMetrics.ControlGap)) {
            ControlButton(
                icon = if (ui.muted) RovstiIcons.MicSlash else RovstiIcons.Mic,
                label = if (ui.muted) strings.unmute else strings.mute,
                tag = InCallTags.Mute,
                active = ui.muted,
                enabled = enabled,
            ) { onAction(InCallAction.ToggleMute) }
            ControlButton(
                icon = RovstiIcons.Keypad,
                label = strings.keypad,
                tag = InCallTags.Keypad,
                enabled = enabled,
            ) { onAction(InCallAction.ToggleKeypad) }
            ControlButton(
                icon = audioIcon(ui.route),
                label = strings.audio,
                tag = InCallTags.Audio,
                active = ui.route == AudioRoute.Speaker,
                enabled = enabled,
                anchor = audioAnchor,
            ) { if (ui.routeOpensList) onAudio(audioAnchor.rect) else onAction(InCallAction.ToggleRoute) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(InCallMetrics.ControlGap)) {
            ControlButton(
                icon = RovstiIcons.Plus,
                label = strings.addCall,
                tag = InCallTags.AddCall,
                enabled = false,
                disabledReason = strings.addCallLater,
            ) {}
            ControlButton(
                icon = RovstiIcons.Pause,
                label = if (ui.held) strings.resume else strings.hold,
                tag = InCallTags.Hold,
                active = ui.held,
                enabled = enabled && (ui.canHold || ui.held),
            ) { onAction(InCallAction.ToggleHold) }
            ControlButton(
                icon = RovstiIcons.Pencil,
                label = if (ui.hasNote) strings.noteSaved else strings.note,
                tag = InCallTags.Note,
                active = ui.hasNote,
                enabled = enabled && ui.call?.number?.isNotBlank() == true,
            ) { onAction(InCallAction.OpenNote) }
        }
    }
}

private fun audioIcon(route: AudioRoute): ImageVector =
    when (route) {
        AudioRoute.Speaker -> RovstiIcons.Speaker
        AudioRoute.Bluetooth -> RovstiIcons.Bluetooth
        AudioRoute.WiredHeadset -> RovstiIcons.Headphones
        else -> RovstiIcons.Phone
    }

/**
 * A frosted circle with a caption under it. An [active] control (muted, on hold, speaker on) turns solid white with a
 * dark glyph, as on iOS; a disabled one dims to 45 % and says why to a screen reader.
 */
@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    tag: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    disabledReason: String? = null,
    anchor: AnchorHolder? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier.width(InCallMetrics.Control + Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CallControlButton(
            onClick = onClick,
            enabled = enabled,
            active = active,
            size = InCallMetrics.Control,
            debugTag = "call.control",
            modifier =
                Modifier
                    .then(if (anchor != null) Modifier.captureAnchor(anchor) else Modifier)
                    .testTag(tag)
                    .semantics(mergeDescendants = true) {
                        contentDescription = label
                        if (active) stateDescription = label
                        if (!enabled && disabledReason != null) stateDescription = disabledReason
                    },
        ) { glyph ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                RovstiIcon(icon, null, tint = glyph, size = InCallMetrics.ControlIcon)
            }
        }
        Spacer(Modifier.height(InCallMetrics.CaptionGap))
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

@Composable
private fun EndButton(
    ui: InCallUi,
    strings: InCallStrings,
    onAction: (InCallAction) -> Unit,
) {
    CallControlButton(
        onClick = { onAction(InCallAction.End) },
        enabled = ui.live,
        size = InCallMetrics.End,
        solid = Color(DECLINE_RED),
        modifier =
            Modifier
                .testTag(InCallTags.End)
                .semantics(mergeDescendants = true) { contentDescription = strings.end },
    ) { glyph ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            RovstiIcon(
                RovstiIcons.PhoneFill,
                null,
                modifier = Modifier.rotate(HANG_UP),
                tint = glyph,
                size = InCallMetrics.EndIcon,
            )
        }
    }
}

private const val MS_PER_S = 1000L
private const val CHEVRON_DOWN = -90f
private const val HANG_UP = 135f
private const val INDIGO = 0xFF5E5CE6
private const val MESH_ALPHA = 0.28f
private const val CHIP_ALPHA = 0.18f

internal val OnAura = Color.White
internal val OnAuraSecondary = Color.White.copy(alpha = 0.78f)
