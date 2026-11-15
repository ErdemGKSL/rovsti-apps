package dev.rosty.messages.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.rosty.design.haptics.LocalRostyHaptics
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.media.SizedImage
import dev.rosty.design.motion.RostyMotion
import dev.rosty.design.overlay.LocalOverlayOrNull
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.menuTrigger
import dev.rosty.design.text.rtlSafe
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.messages.theme.MediaMetrics
import dev.rosty.messages.theme.MessagesMetrics
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** Test tags of the reply swipe and the bars above the composer. */
object ReplyTags {
    const val Bar = "reply-bar"
    const val Cancel = "reply-cancel"
    const val Attachment = "attachment-bar"
    const val AttachmentRemove = "attachment-remove"
    const val AttachmentSend = "attachment-send"
    const val Attach = "composer-attach"
    const val Glyph = "reply-glyph"
}

/**
 * The numbers of the reply swipe (M31), the GPUI `bubble_gesture` rules: the bubble follows the finger to the right, a
 * release past [commit] answers the message, and past that point the bubble resists so it never travels beyond [max].
 */
object ReplySwipe {
    /** Where the bubble sits for a finger [raw] px to the right of where it went down. */
    fun offset(
        raw: Float,
        commit: Float,
        max: Float,
    ): Float {
        if (raw <= 0f) return 0f
        if (max <= commit) return raw.coerceAtMost(max.coerceAtLeast(0f))
        if (raw <= commit) return raw
        val room = max - commit
        val over = raw - commit
        return commit + room * (1f - 1f / (over * RESISTANCE / room + 1f))
    }

    /** 0 at rest, 1 once a release would answer: the reply glyph fades and grows in with it. */
    fun progress(
        offset: Float,
        commit: Float,
    ): Float = if (commit <= 0f) 0f else (offset / commit).coerceIn(0f, 1f)

    fun commits(
        offset: Float,
        commit: Float,
    ): Boolean = offset >= commit

    /** UIKit's rubber-band constant. */
    private const val RESISTANCE = 0.55f
}

/** The travel of one bubble's reply swipe: the finger's distance and where the bubble is drawn. */
internal class ReplySwipeState {
    val offset = Animatable(0f)
    var raw = 0f
    var armed = false
}

/**
 * Swiping this bubble to the right answers it (M31). The finger owns the offset while it is down; on release a spring
 * brings the bubble back, seeded with the finger's velocity. A tick plays when the swipe passes the point of no return.
 * Vertical scrolling is untouched: the drag only starts once the finger moved sideways.
 */
@Composable
internal fun Modifier.swipeToReply(
    state: ReplySwipeState,
    enabled: Boolean,
    onReply: () -> Unit,
): Modifier {
    if (!enabled) return this
    val scope = rememberCoroutineScope()
    val haptics = LocalRostyHaptics.current
    val reply by rememberUpdatedState(onReply)
    val density = LocalDensity.current
    val commit = with(density) { MediaMetrics.SwipeCommit.toPx() }
    val max = with(density) { MediaMetrics.SwipeMax.toPx() }
    val drag =
        rememberDraggableState { delta ->
            state.raw = (state.raw + delta).coerceAtLeast(0f)
            val next = ReplySwipe.offset(state.raw, commit, max)
            val armed = ReplySwipe.commits(next, commit)
            if (armed && !state.armed) haptics.tick()
            state.armed = armed
            scope.launch(start = CoroutineStart.UNDISPATCHED) { state.offset.snapTo(next) }
        }
    return draggable(
        state = drag,
        orientation = Orientation.Horizontal,
        onDragStarted = {
            state.raw = state.offset.value
            state.armed = false
        },
        onDragStopped = { velocity ->
            if (state.armed) reply()
            state.armed = false
            state.raw = 0f
            state.offset.animateTo(0f, RostyMotion.LiftRest.spec(SWIPE_THRESHOLD), initialVelocity = velocity)
        },
    )
}

/** The arrow that shows behind a bubble being swiped; it fades and grows in with the swipe. */
@Composable
internal fun ReplyGlyph(
    offset: Animatable<Float, AnimationVector1D>,
    modifier: Modifier = Modifier,
) {
    val commit = with(LocalDensity.current) { MediaMetrics.SwipeCommit.toPx() }
    Box(
        modifier
            .size(MediaMetrics.ReplyGlyph)
            .graphicsLayer {
                val p = ReplySwipe.progress(offset.value, commit)
                alpha = p
                scaleX = GLYPH_MIN_SCALE + (1f - GLYPH_MIN_SCALE) * p
                scaleY = scaleX
            }.clip(CircleShape)
            .background(RostyTheme.colors.fillSecondary)
            .testTag(ReplyTags.Glyph),
        contentAlignment = Alignment.Center,
    ) {
        // The forward arrow mirrored: it points back at the message.
        RostyIcon(
            RostyIcons.Forward,
            null,
            tint = RostyTheme.colors.accent,
            size = MessagesMetrics.FolderGlyph,
            modifier = Modifier.graphicsLayer { scaleX = -1f },
        )
    }
}

/** The quoted composer bar (M31): whom and what the next message answers, and × to make it a plain message again. */
@Composable
internal fun ReplyBar(
    reply: ReplyUi,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RostyTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter)
            .padding(top = Spacing.s)
            .clip(RoundedCornerShape(MessagesMetrics.BubbleCorner))
            .background(colors.fillSecondary)
            .padding(start = Spacing.m)
            .testTag(ReplyTags.Bar),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Box(
            Modifier
                .width(MediaMetrics.BarStripe)
                .height(MediaMetrics.BarButton)
                .clip(CircleShape)
                .background(colors.accent),
        )
        Column(Modifier.weight(1f).padding(vertical = Spacing.s)) {
            RostyText(
                reply.author.rtlSafe(),
                style = RostyTheme.type.caption1,
                color = colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RostyText(
                reply.text.rtlSafe(),
                style = RostyTheme.type.footnote,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BarClose(stringResource(R.string.wa_reply_cancel), onCancel, Modifier.testTag(ReplyTags.Cancel))
    }
}

/**
 * The file that goes out with the next Send: its picture or a file tile, name and size, × to drop it, and a Send
 * button of its own because the composer's only shows once there is text (the text is the caption).
 */
@Composable
internal fun AttachmentBar(
    attachment: AttachmentUi,
    sendLabel: String,
    onRemove: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RostyTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter)
            .padding(top = Spacing.s)
            .clip(RoundedCornerShape(MessagesMetrics.BubbleCorner))
            .background(colors.fillSecondary)
            .padding(start = Spacing.s)
            .heightIn(min = Spacing.xl + Spacing.xl)
            .testTag(ReplyTags.Attachment),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        val tile = RoundedCornerShape(MessagesMetrics.FolderTileCorner)
        if (attachment.model != null) {
            val px = with(LocalDensity.current) { MediaMetrics.BarThumb.roundToPx() }
            SizedImage(
                model = attachment.model,
                widthPx = px,
                heightPx = px,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(MediaMetrics.BarThumb).clip(tile),
            )
        } else {
            Box(
                Modifier.size(MediaMetrics.BarThumb).clip(tile).background(colors.fillTertiary),
                contentAlignment = Alignment.Center,
            ) { RostyIcon(RostyIcons.Document, null, tint = colors.labelSecondary, size = MediaMetrics.MarkGlyph) }
        }
        Column(Modifier.weight(1f)) {
            RostyText(
                attachment.name.rtlSafe(),
                style = RostyTheme.type.subheadline,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            RostyText(
                attachment.detail,
                style = RostyTheme.type.caption1,
                color = colors.labelSecondary,
                maxLines = 1,
            )
        }
        BarClose(
            stringResource(R.string.wa_attachment_remove),
            onRemove,
            Modifier.testTag(ReplyTags.AttachmentRemove),
        )
        Box(
            Modifier
                .padding(end = Spacing.s)
                .size(MediaMetrics.BarButton)
                .clip(CircleShape)
                .background(colors.accent)
                .clickable(role = Role.Button, onClickLabel = sendLabel, onClick = onSend)
                .semantics { contentDescription = sendLabel }
                .testTag(ReplyTags.AttachmentSend),
            contentAlignment = Alignment.Center,
        ) { RostyIcon(RostyIcons.ArrowUp, null, tint = colors.onAccent, size = MessagesMetrics.FolderGlyph) }
    }
}

@Composable
private fun BarClose(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(MediaMetrics.FileAction)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        RostyIcon(
            RostyIcons.Xmark,
            null,
            tint = RostyTheme.colors.labelSecondary,
            size = MessagesMetrics.RowAccessory,
        )
    }
}

/** The + beside the composer: its menu offers what can be attached. */
@Composable
internal fun AttachButton(
    menu: ImmutableList<MenuItem>,
    modifier: Modifier = Modifier,
) {
    val overlays = LocalOverlayOrNull.current ?: return
    val label = stringResource(R.string.wa_attach)
    Box(
        modifier
            .size(MediaMetrics.AttachButton)
            .clip(CircleShape)
            .background(RostyTheme.colors.fillSecondary)
            .menuTrigger(overlays, menu, label)
            .testTag(ReplyTags.Attach),
        contentAlignment = Alignment.Center,
    ) { RostyIcon(RostyIcons.Plus, null, tint = RostyTheme.colors.accent, size = MessagesMetrics.FolderGlyph) }
}

private const val SWIPE_THRESHOLD = 0.5f
private const val GLYPH_MIN_SCALE = 0.6f
