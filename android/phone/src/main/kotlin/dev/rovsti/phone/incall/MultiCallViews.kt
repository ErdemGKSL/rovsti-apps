package dev.rovsti.phone.incall

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.rovsti.design.glass.GlassPreset
import dev.rovsti.design.glass.rovstiGlass
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.shapes.ContinuousRoundedRectangle
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.InCallMetrics
import kotlin.math.roundToInt

private val CardShape = ContinuousRoundedRectangle(percent = 50)
private const val MAX_CLUSTER = 3
private const val HOLD_AWAY_FROM = 0.86f
private const val SWAP_FROM = 0.8f

/**
 * The Held card (E5.7b): the call that waits, as a frosted pill ([GlassPreset.CallControl]) beside the collapse button.
 * A tap swaps. S19: when the live call goes on hold the card grows in from 0.86 of its size on the hold-away spring
 * (0.86 / 380); the card is keyed on the held call, so a swap re-runs it for the call that just stepped back.
 */
@Composable
internal fun HeldCard(
    held: HeldCardUi,
    strings: InCallStrings,
    enabled: Boolean,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduce = LocalReduceMotion.current
    val p = remember(held.callId) { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(held.callId) { if (!reduce) p.animateTo(1f, RovstiMotion.holdAway(0.001f)) }
    Row(
        modifier
            .heightIn(min = InCallMetrics.TopBar)
            .graphicsLayer {
                val t = p.value
                val scale = HOLD_AWAY_FROM + (1f - HOLD_AWAY_FROM) * t
                scaleX = scale
                scaleY = scale
                alpha = t.coerceIn(0f, 1f)
            }.clip(CardShape)
            .rovstiGlass(GlassPreset.CallControl, CardShape, "call.held", outerShadow = false)
            .testTag(MultiCallTags.Held)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = strings.heldCardHint, onClick = onSwap)
            .padding(start = Spacing.xs, end = Spacing.m, top = Spacing.xs, bottom = Spacing.xs)
            .semantics(mergeDescendants = true) { contentDescription = strings.heldCard(held.title) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Avatar(name = held.title, size = InCallMetrics.HeldAvatar, colorKey = held.avatarKey, onAura = true)
        Column(Modifier.weight(1f)) {
            RovstiText(
                held.title,
                style = RovstiTheme.type.subheadline,
                color = OnAura,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RovstiText(strings.held, style = RovstiTheme.type.caption1, color = OnAuraSecondary, maxLines = 1)
        }
        RovstiIcon(RovstiIcons.Swap, null, tint = OnAura, size = InCallMetrics.SmallGlyph)
    }
}

/**
 * S20, the swap cross-over: when the live call changes while a call is held, the identity block (avatar, name) rises
 * from the card's side and settles on the swap spring (0.8 / 420), so the two avatars read as trading places. The first
 * composition and a call that simply appears do not animate. The value is 0 at the start and 1 settled.
 */
@Stable
internal class SwapProgress(
    val value: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
)

@Composable
internal fun rememberSwapProgress(
    callId: Int?,
    twoCalls: Boolean,
): SwapProgress {
    val reduce = LocalReduceMotion.current
    val progress = remember { SwapProgress(Animatable(1f)) }
    val last = remember { intArrayOf(callId ?: -1, if (twoCalls) 1 else 0) }
    LaunchedEffect(callId, twoCalls) {
        val before = last[0]
        val hadTwo = last[1] == 1
        last[0] = callId ?: -1
        last[1] = if (twoCalls) 1 else 0
        // A swap: another call took the stage while two calls were (or still are) up.
        if (callId != null && before != -1 && before != callId && (hadTwo || twoCalls) && !reduce) {
            progress.value.snapTo(0f)
            progress.value.animateTo(1f, RovstiMotion.swapCross(0.001f))
        }
    }
    return progress
}

/** Applies [SwapProgress]: scale from 0.8, rising from [SwapRise] below the stage top, fading in. */
internal fun Modifier.swapCross(progress: SwapProgress): Modifier =
    graphicsLayer {
        val t = progress.value.value
        val scale = SWAP_FROM + (1f - SWAP_FROM) * t
        scaleX = scale
        scaleY = scale
        translationY = -(1f - t) * InCallMetrics.SwapRise.toPx()
        alpha = t.coerceIn(0f, 1f)
    }

/**
 * The conference face: up to three avatars side by side (never stacked on each other). S21, the merge: they start wide
 * apart and pull together on the merge spring (0.75 / 500), a little past rest and back.
 */
@Composable
internal fun ConferenceAvatars(
    people: List<ParticipantUi>,
    fallbackName: String,
    modifier: Modifier = Modifier,
) {
    val reduce = LocalReduceMotion.current
    val pull = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduce) pull.animateTo(1f, RovstiMotion.mergePull(0.001f)) }
    val density = LocalDensity.current
    val shown = people.take(MAX_CLUSTER)
    Box(modifier.fillMaxWidth().height(InCallMetrics.Avatar), contentAlignment = Alignment.Center) {
        if (shown.isEmpty()) {
            Avatar(name = fallbackName, size = InCallMetrics.ClusterAvatar, onAura = true)
        }
        shown.forEachIndexed { index, person ->
            Avatar(
                name = person.label,
                size = InCallMetrics.ClusterAvatar,
                colorKey = person.avatarKey,
                onAura = true,
                modifier =
                    Modifier.offset {
                        val far = with(density) { InCallMetrics.ClusterStepFar.toPx() }
                        val near = with(density) { InCallMetrics.ClusterStepNear.toPx() }
                        val step = far + (near - far) * pull.value
                        IntOffset(((index - (shown.size - 1) / 2f) * step).roundToInt(), 0)
                    },
            )
        }
    }
}

/** "3 people": the pill under the conference's name; a tap opens the panel. */
@Composable
internal fun PeoplePill(
    text: String,
    strings: InCallStrings,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .heightIn(min = InCallMetrics.ChipHeight)
            .clip(CardShape)
            .rovstiGlass(GlassPreset.CallControl, CardShape, "call.people", outerShadow = false)
            .testTag(MultiCallTags.People)
            .clickable(role = Role.Button, onClickLabel = strings.confOpen, onClick = onClick)
            .padding(horizontal = InCallMetrics.ChipPadX, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        RovstiIcon(RovstiIcons.People, null, tint = OnAura, size = InCallMetrics.PillGlyph)
        RovstiText(text, style = RovstiTheme.type.footnote, color = OnAura, maxLines = 1)
    }
}
