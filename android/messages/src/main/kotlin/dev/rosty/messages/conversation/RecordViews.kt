package dev.rosty.messages.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import dev.rosty.design.glass.GlassPreset
import dev.rosty.design.glass.rostyGlass
import dev.rosty.design.haptics.HapticEvent
import dev.rosty.design.haptics.LocalRostyHaptics
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.motion.RostyMotion
import dev.rosty.design.text.imeOverlapPadding
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.messages.theme.MediaMetrics
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** Test tags of the voice note recorder of the composer. */
object RecordTags {
    const val Mic = "record-mic"
    const val Holding = "record-holding"
    const val CancelHint = "record-cancel-hint"
    const val Lock = "record-lock"
    const val Panel = "record-panel"
    const val Stop = "record-stop"
    const val Preview = "record-preview"
    const val Delete = "record-delete"
    const val Send = "record-send"
    const val Elapsed = "record-elapsed"
}

/** What the microphone of the composer does (E6-WA R3); the WhatsApp thread wires its recorder to it. */
class RecordActions(
    /** The finger went down on the microphone: true when recording began (false: no permission yet, busy). */
    val onStart: () -> Boolean,
    /** The finger lifted while holding: send. */
    val onRelease: () -> Unit,
    /** Slid toward the start edge past the line. */
    val onCancel: () -> Unit,
    /** Slid up past the line: hands-free. */
    val onLock: () -> Unit,
    val onStop: () -> Unit,
    val onPreview: () -> Unit,
    val onDelete: () -> Unit,
    val onSend: () -> Unit,
)

/** What a drag of the held microphone means. */
enum class MicGesture { None, Cancel, Lock }

/**
 * Pure: what a drag of the held microphone by ([dx], [dy]) px means. Sliding toward the start edge (left, right in a
 * right-to-left layout) by [cancelPx] cancels, sliding up by [lockPx] locks; when both lines are crossed the move that
 * went further past its line wins.
 */
fun micGesture(
    dx: Float,
    dy: Float,
    cancelPx: Float,
    lockPx: Float,
    rtl: Boolean,
): MicGesture {
    val cancel = (if (rtl) dx else -dx) / cancelPx
    val lock = -dy / lockPx
    return when {
        cancel >= 1f && cancel >= lock -> MicGesture.Cancel
        lock >= 1f -> MicGesture.Lock
        else -> MicGesture.None
    }
}

/**
 * Where the held microphone was dragged, in px toward the start edge and up, each clamped to its line. The finger owns
 * the values while it is down; on release, cancel or lock they spring back.
 */
@Stable
internal class RecordDrag {
    val toStart = Animatable(0f)
    val up = Animatable(0f)
}

/**
 * The microphone beside the empty composer: hold to record. While held it grows into an accent disc with a halo that
 * follows the input level and travels with the finger (only toward the start edge and up, to the lines). A screen
 * reader's double tap starts a hands-free recording instead (Stop, Delete and Send then show).
 */
@Composable
internal fun MicButton(
    holding: Boolean,
    level: () -> Float,
    actions: RecordActions,
    drag: RecordDrag,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalRostyHaptics.current
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val cancelPx = with(density) { MediaMetrics.RecordCancel.toPx() }
    val lockPx = with(density) { MediaMetrics.RecordLock.toPx() }
    val current by rememberUpdatedState(actions)
    val held by rememberUpdatedState(holding)
    val grow by animateFloatAsState(
        if (holding) 1f else 0f,
        RostyMotion.GlassPressUp.spec(VISIBLE_FRACTION),
        label = "micGrow",
    )
    val label = stringResource(R.string.wa_record_mic)
    Box(
        modifier
            .size(Sizes.touch)
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick(label) {
                    if (current.onStart()) current.onLock()
                    true
                }
            }.pointerInput(cancelPx, lockPx, rtl) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (!current.onStart()) return@awaitEachGesture
                    haptics.play(HapticEvent.LongPress)
                    var verdict = MicGesture.None
                    var shown = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        // The recorder may have ended on its own (a call, the app left): the finger no longer counts.
                        // (The first events can come before the page shows the recording at all.)
                        if (held) {
                            shown = true
                        } else if (shown) {
                            break
                        }
                        if (!change.pressed) {
                            current.onRelease()
                            break
                        }
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        val toStart = (if (rtl) dx else -dx).coerceIn(0f, cancelPx)
                        val up = (-dy).coerceIn(0f, lockPx)
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { drag.toStart.snapTo(toStart) }
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { drag.up.snapTo(up) }
                        verdict = micGesture(dx, dy, cancelPx, lockPx, rtl)
                        if (verdict != MicGesture.None) break
                    }
                    when (verdict) {
                        MicGesture.Cancel -> {
                            haptics.play(HapticEvent.Reject)
                            current.onCancel()
                        }

                        MicGesture.Lock -> {
                            haptics.play(HapticEvent.ThresholdTick)
                            current.onLock()
                        }

                        MicGesture.None -> {
                            Unit
                        }
                    }
                    scope.launch { drag.toStart.animateTo(0f, RostyMotion.previewReturn(OFFSET_THRESHOLD)) }
                    scope.launch { drag.up.animateTo(0f, RostyMotion.previewReturn(OFFSET_THRESHOLD)) }
                }
            }.testTag(RecordTags.Mic),
        contentAlignment = Alignment.Center,
    ) {
        // The touch target stays where it is (the finger's positions are read in its frame); what is drawn travels.
        Box(
            Modifier.size(Sizes.touch).graphicsLayer {
                translationX = if (rtl) drag.toStart.value else -drag.toStart.value
                translationY = -drag.up.value
            },
            contentAlignment = Alignment.Center,
        ) { MicDisc(grow = { grow }, level = level, holding = holding) }
    }
}

/** The disc, the halo and the glyph of the microphone. */
@Composable
private fun MicDisc(
    grow: () -> Float,
    level: () -> Float,
    holding: Boolean,
) {
    val colors = RostyTheme.colors
    // The halo and the disc are drawn bigger than the button while held, outside its bounds.
    Box(
        Modifier
            .size(MediaMetrics.MicDisc)
            .graphicsLayer {
                val g = grow()
                val s = g * (HALO_BASE + HALO_SWING * level())
                scaleX = s
                scaleY = s
                alpha = g * HALO_ALPHA
            }.background(colors.accent, CircleShape),
    )
    Box(
        Modifier
            .size(MediaMetrics.MicDisc)
            .graphicsLayer {
                val g = grow()
                val s = DISC_REST + (DISC_HELD - DISC_REST) * g
                scaleX = s
                scaleY = s
                alpha = g.coerceIn(0f, 1f)
            }.background(colors.accent, CircleShape),
    )
    val tint = if (holding) colors.onAccent else colors.accent
    RostyIcon(RostyIcons.Mic, null, tint = tint, size = MediaMetrics.MicGlyph)
}

/**
 * The composer while the microphone is held: the red dot, the length so far, the level meter and "Slide to cancel",
 * which travels with the finger and fades as it nears the line.
 */
@Composable
internal fun HoldingRow(
    record: RecordUi,
    drag: RecordDrag,
    modifier: Modifier = Modifier,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val cancelPx = with(LocalDensity.current) { MediaMetrics.RecordCancel.toPx() }
    Row(
        modifier.testTag(RecordTags.Holding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecordingDot()
        RostyText(
            record.elapsed,
            modifier = Modifier.padding(start = Spacing.s).testTag(RecordTags.Elapsed),
            style = RostyTheme.type.body,
            color = RostyTheme.colors.labelPrimary,
            maxLines = 1,
        )
        LevelMeter(
            { record.level },
            Modifier.padding(start = Spacing.s).size(MediaMetrics.MeterWidth, MediaMetrics.MeterHeight),
        )
        Row(
            Modifier
                .weight(1f)
                .padding(start = Spacing.s)
                // The hint slides inside its own room: never over the meter.
                .clipToBounds()
                .graphicsLayer {
                    val slid = drag.toStart.value
                    translationX = if (rtl) slid else -slid
                    alpha = 1f - HINT_FADE * (slid / cancelPx).coerceIn(0f, 1f)
                }.testTag(RecordTags.CancelHint),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RostyIcon(
                RostyIcons.ChevronRight,
                null,
                modifier = Modifier.graphicsLayer { rotationZ = if (rtl) 0f else HALF_TURN },
                tint = RostyTheme.colors.labelSecondary,
                size = MediaMetrics.HintGlyph,
            )
            RostyText(
                stringResource(R.string.wa_record_cancel_hint),
                modifier = Modifier.padding(start = Spacing.xs),
                style = RostyTheme.type.subheadline,
                color = RostyTheme.colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The capsule above the held microphone: a lock and an arrow up; it rises with the finger. */
@Composable
internal fun LockPill(
    shown: Boolean,
    drag: RecordDrag,
    modifier: Modifier = Modifier,
) {
    val appear by animateFloatAsState(if (shown) 1f else 0f, RostyMotion.appear(VISIBLE_FRACTION), label = "lock")
    if (appear <= 0f && !shown) return
    val lockPx = with(LocalDensity.current) { MediaMetrics.RecordLock.toPx() }
    val shape = RoundedCornerShape(MediaMetrics.LockWidth / 2)
    val label = stringResource(R.string.wa_record_lock)
    val ink = RostyTheme.colors.labelSecondary
    Column(
        modifier
            .graphicsLayer {
                alpha = appear
                translationY = (1f - appear) * size.height / 2f - drag.up.value * LOCK_RISE
            }.width(MediaMetrics.LockWidth)
            .rostyGlass(GlassPreset.Button, shape, "record-lock", outerShadow = false)
            .padding(vertical = Spacing.s)
            .semantics { contentDescription = label }
            .testTag(RecordTags.Lock),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Canvas(Modifier.size(MediaMetrics.LockGlyph)) {
            val closed = (drag.up.value / lockPx).coerceIn(0f, 1f)
            drawLock(ink, closed)
        }
        RostyIcon(
            RostyIcons.ChevronRight,
            null,
            modifier = Modifier.graphicsLayer { rotationZ = -QUARTER_TURN },
            tint = ink,
            size = MediaMetrics.HintGlyph,
        )
    }
}

/**
 * The recorder after the slide up ([RecordPhase.Locked]) and after Stop ([RecordPhase.Review]), in place of the
 * composer: the take on top (recording: the dot, the length and the level; review: play, the bars with the played
 * part and the length), and Delete, Stop (while recording) and Send below.
 */
@Composable
internal fun RecordPanel(
    record: RecordUi,
    actions: RecordActions,
    modifier: Modifier = Modifier,
) {
    val colors = RostyTheme.colors
    val shape = RoundedCornerShape(MediaMetrics.PanelCorner)
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imeOverlapPadding(excludeNavigationBar = false)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.s)
            .rostyGlass(GlassPreset.Bar, shape, "record-panel", outerShadow = false)
            .padding(horizontal = Spacing.s, vertical = Spacing.xs)
            .testTag(RecordTags.Panel),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Sizes.touch).padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (record.phase == RecordPhase.Review) {
                val play = stringResource(if (record.playing) R.string.wa_voice_pause else R.string.wa_voice_play)
                PanelButton(play, RecordTags.Preview, actions.onPreview) {
                    Box(
                        Modifier.size(MediaMetrics.VoicePlay).background(colors.accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (record.playing) {
                            RostyIcon(RostyIcons.Pause, null, tint = colors.onAccent, size = MediaMetrics.VoiceGlyph)
                        } else {
                            PlayGlyph(colors.onAccent, MediaMetrics.VoiceGlyph)
                        }
                    }
                }
                Canvas(Modifier.weight(1f).height(MediaMetrics.VoiceTouch).padding(horizontal = Spacing.s)) {
                    drawWaveform(
                        record.bars,
                        record.progress,
                        colors.accent,
                        colors.labelSecondary.copy(alpha = REST_ALPHA),
                    )
                }
            } else {
                Box(Modifier.size(Sizes.touch), contentAlignment = Alignment.Center) { RecordingDot() }
                LevelMeter(
                    { record.level },
                    Modifier.weight(1f).height(MediaMetrics.MeterHeight).padding(horizontal = Spacing.s),
                    bars = PANEL_METER_BARS,
                )
            }
            RostyText(
                record.elapsed,
                modifier = Modifier.padding(end = Spacing.s).testTag(RecordTags.Elapsed),
                style = RostyTheme.type.body,
                color = colors.labelPrimary,
                maxLines = 1,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelIcon(
                RostyIcons.Trash,
                stringResource(R.string.wa_record_delete),
                RecordTags.Delete,
                colors.destructive,
            ) {
                actions.onDelete()
            }
            if (record.phase == RecordPhase.Locked) {
                PanelButton(stringResource(R.string.wa_record_stop), RecordTags.Stop, actions.onStop) {
                    Box(
                        Modifier
                            .size(MediaMetrics.StopRing)
                            .background(colors.destructive.copy(alpha = STOP_RING_ALPHA), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(MediaMetrics.StopSquare)
                                .background(colors.destructive, RoundedCornerShape(MediaMetrics.StopCorner)),
                        )
                    }
                }
            }
            PanelButton(stringResource(R.string.wa_record_send), RecordTags.Send, actions.onSend) {
                Box(
                    Modifier.size(MediaMetrics.VoicePlay).background(colors.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    RostyIcon(RostyIcons.ArrowUp, null, tint = colors.onAccent, size = MediaMetrics.MicGlyph)
                }
            }
        }
    }
}

@Composable
private fun PanelIcon(
    icon: ImageVector,
    label: String,
    tag: String,
    tint: Color,
    onClick: () -> Unit,
) {
    PanelButton(label, tag, onClick) { RostyIcon(icon, null, tint = tint, size = MediaMetrics.MicGlyph) }
}

/** A 48 dp round touch target with [content] in the middle. */
@Composable
private fun PanelButton(
    label: String,
    tag: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(Sizes.touch)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The red dot of a recording; it breathes. */
@Composable
private fun RecordingDot() {
    val pulse = rememberInfiniteTransition(label = "recDot")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = DOT_DIM,
        animationSpec = infiniteRepeatable(tween(DOT_MS, easing = LinearEasing), RepeatMode.Reverse),
        label = "recDotAlpha",
    )
    val red = RostyTheme.colors.destructive
    Box(
        Modifier
            .size(MediaMetrics.RecDot)
            .graphicsLayer { this.alpha = alpha }
            .background(red, CircleShape),
    )
}

/** A few bars whose height follows the input [level] (0..1), the middle ones most. Read in the draw phase. */
@Composable
private fun LevelMeter(
    level: () -> Float,
    modifier: Modifier = Modifier,
    bars: Int = METER_BARS,
) {
    val ink = RostyTheme.colors.accent
    Canvas(modifier) {
        val now = level().coerceIn(0f, 1f)
        val slot = size.width / bars
        val width = minOf(slot * BAR_SHARE, MediaMetrics.BarMax.toPx())
        for (i in 0 until bars) {
            // A bell over the bars, so the meter reads as a voice, not as a single gauge.
            val centre = 1f - kotlin.math.abs(i - (bars - 1) / 2f) / bars
            val h = maxOf(MediaMetrics.BarMin.toPx(), size.height * now * centre)
            val x = slot * i + (slot - width) / 2f
            drawRoundRect(ink, Offset(x, (size.height - h) / 2f), Size(width, h), CornerRadius(width / 2f))
        }
    }
}

/**
 * The bars of a voice note across the whole width: each 0..1 of the height (at least a dot), the [progress] part in
 * [played], the rest in [rest].
 */
internal fun DrawScope.drawWaveform(
    bars: List<Float>,
    progress: Float,
    played: Color,
    rest: Color,
    area: Size = size,
) {
    if (bars.isEmpty()) return
    val slot = area.width / bars.size
    val width = minOf(slot * BAR_SHARE, MediaMetrics.BarMax.toPx())
    val until = progress.coerceIn(0f, 1f) * bars.size
    for ((i, value) in bars.withIndex()) {
        val h = maxOf(MediaMetrics.BarMin.toPx(), area.height * value.coerceIn(0f, 1f))
        val x = slot * i + (slot - width) / 2f
        val color = if (i < until) played else rest
        drawRoundRect(color, Offset(x, (area.height - h) / 2f), Size(width, h), CornerRadius(width / 2f))
    }
}

/** A padlock; [closed] 0..1 lowers the shackle into the body. */
private fun DrawScope.drawLock(
    ink: Color,
    closed: Float,
) {
    val stroke = size.width * LOCK_STROKE
    val bodyTop = size.height * LOCK_BODY_TOP
    val shackle = size.width * LOCK_SHACKLE
    val left = (size.width - shackle) / 2f
    val lift = (1f - closed) * size.height * LOCK_LIFT
    drawArc(
        ink,
        startAngle = HALF_TURN,
        sweepAngle = HALF_TURN,
        useCenter = false,
        topLeft = Offset(left, stroke / 2f - lift),
        size = Size(shackle, shackle),
        style = Stroke(stroke, cap = StrokeCap.Round),
    )
    drawLine(ink, Offset(left, shackle / 2f - lift), Offset(left, bodyTop), stroke, StrokeCap.Round)
    drawLine(
        ink,
        Offset(left + shackle, shackle / 2f - lift),
        Offset(left + shackle, bodyTop - lift),
        stroke,
        StrokeCap.Round,
    )
    drawRoundRect(
        ink,
        Offset(0f, bodyTop),
        Size(size.width, size.height - bodyTop),
        CornerRadius(size.width * LOCK_CORNER),
    )
}

private const val VISIBLE_FRACTION = 0.001f
private const val OFFSET_THRESHOLD = 0.5f
private const val HALO_BASE = 1.45f
private const val HALO_SWING = 0.45f
private const val HALO_ALPHA = 0.25f
private const val DISC_REST = 0.6f
private const val DISC_HELD = 1.4f
private const val HINT_FADE = 0.85f
private const val HALF_TURN = 180f
private const val QUARTER_TURN = 90f
private const val LOCK_RISE = 0.5f
private const val STOP_RING_ALPHA = 0.16f
private const val PANEL_METER_BARS = 24
private const val REST_ALPHA = 0.4f
private const val METER_BARS = 5
private const val BAR_SHARE = 0.6f
private const val DOT_DIM = 0.25f
private const val DOT_MS = 600
private const val LOCK_STROKE = 0.14f
private const val LOCK_BODY_TOP = 0.45f
private const val LOCK_SHACKLE = 0.6f
private const val LOCK_LIFT = 0.18f
private const val LOCK_CORNER = 0.15f
