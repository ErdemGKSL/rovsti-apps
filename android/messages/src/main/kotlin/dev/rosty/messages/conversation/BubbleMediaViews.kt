package dev.rosty.messages.conversation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.media.SizedImage
import dev.rosty.design.text.rtlSafe
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.messages.theme.MediaMetrics
import dev.rosty.messages.theme.MessagesMetrics
import dev.rosty.messages.viewer.LoopingVideo

/** Test tags of the media inside a bubble. */
object MediaTags {
    fun voicePlay(key: String) = "voice-play-$key"

    fun voiceBar(key: String) = "voice-bar-$key"

    const val VoiceSpeed = "voice-speed"

    fun save(key: String) = "media-save-$key"

    fun share(key: String) = "media-share-$key"
}

/** What a bubble's media needs from the page (E6.4b). */
internal class MediaHost(
    val bubble: BubbleUi,
    val ink: Color,
    val secondary: Color,
    val maxWidth: Dp,
    val actions: ConversationActions,
    /** The player's state when this bubble is the note in it. */
    val voice: VoiceUi?,
    val speedLabel: String,
)

/** One attachment of a bubble drawn as its [BubbleMedia.kind]: the picture, the video, the player or the file row. */
@Composable
internal fun BubbleMediaView(
    media: BubbleMedia,
    host: MediaHost,
) {
    when (media.kind) {
        BubbleMediaKind.Picture, BubbleMediaKind.Video -> PictureMedia(media, host)

        BubbleMediaKind.Voice -> VoiceNote(media, host)

        BubbleMediaKind.File -> FileRow(media, host)

        BubbleMediaKind.Unavailable -> UnavailableRow(media, host)

        // A sticker alone is drawn without a bubble (StickerBody); with a caption it sits in the bubble as a picture.
        BubbleMediaKind.Sticker -> PictureMedia(media, host)

        null -> Unit
    }
}

/**
 * A picture or a video: the thumbnail first, then the picture, in the size the message names so the bubble does not
 * jump. The mark in the middle says what is going on: a clock while it waits to go out, a spinner while it is sent or
 * downloaded, the retry arrow when sending failed, play on a video, the download arrow when it is not on the phone.
 */
@Composable
private fun PictureMedia(
    media: BubbleMedia,
    host: MediaHost,
) {
    val aspect = media.aspect ?: DEFAULT_MEDIA_ASPECT
    val shape = RoundedCornerShape(MessagesMetrics.FolderTileCorner)
    val anchors = host.actions.mediaAnchors
    val key = host.bubble.key
    if (anchors != null) DisposableEffect(anchors, key) { onDispose { anchors.remove(key) } }
    Box(
        Modifier
            .padding(bottom = Spacing.xs)
            .width(minOf(host.maxWidth, MediaMetrics.PictureMax))
            .aspectRatio(aspect)
            .then(
                if (anchors ==
                    null
                ) {
                    Modifier
                } else {
                    Modifier.onGloballyPositioned { anchors.put(key, it.boundsInWindow()) }
                },
            ).clip(shape)
            .background(host.secondary.copy(alpha = PLACEHOLDER_ALPHA))
            .semantics { contentDescription = media.label },
        contentAlignment = Alignment.Center,
    ) {
        if (media.model != null) {
            val widthPx = with(LocalDensity.current) { minOf(host.maxWidth, MediaMetrics.PictureMax).roundToPx() }
            SizedImage(
                model = media.model,
                widthPx = widthPx,
                heightPx = (widthPx / aspect).toInt(),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!media.busy) {
            RostyIcon(
                if (media.kind == BubbleMediaKind.Video) RostyIcons.Camera else RostyIcons.Photo,
                null,
                tint = host.secondary,
                size = MediaMetrics.MarkGlyph,
            )
        }
        // A GIF on the phone plays where it is, muted and over and over; its thumbnail stays under it until it runs.
        media.loop?.let { LoopingVideo(it, Modifier.fillMaxSize()) }
        val video = media.kind == BubbleMediaKind.Video && media.loop == null
        when {
            media.busy -> Mark { Spinner() }
            host.bubble.state == BubbleState.Waiting -> Mark { MarkIcon(RostyIcons.Clock) }
            host.bubble.state == BubbleState.Failed -> Mark { MarkIcon(RostyIcons.Rotate) }
            video -> Mark { PlayGlyph(Color.White, MediaMetrics.MarkGlyph) }
            !media.local && media.model == null -> Unit
            !media.local -> Mark { MarkIcon(RostyIcons.Download) }
        }
        // The length of a video, or that a file is no longer available.
        if (media.detail != null) {
            RostyText(
                media.detail,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(Spacing.xs)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = MARK_ALPHA))
                        .padding(horizontal = Spacing.s, vertical = Spacing.textGap),
                style = RostyTheme.type.caption2,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

/** The dark disc over a picture that carries [content]. */
@Composable
private fun Mark(content: @Composable () -> Unit) {
    Box(
        Modifier.size(MediaMetrics.Mark).clip(CircleShape).background(Color.Black.copy(alpha = MARK_ALPHA)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun MarkIcon(icon: ImageVector) {
    RostyIcon(icon, null, tint = Color.White, size = MediaMetrics.MarkGlyph)
}

/** The turning arc of an upload or download; the gateway reports no bytes, so it has no fill level. */
@Composable
private fun Spinner(tint: Color = Color.White) {
    val turn = rememberInfiniteTransition(label = "media-spin")
    val angle by turn.animateFloat(
        0f,
        FULL_TURN,
        infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "media-spin-angle",
    )
    Canvas(Modifier.size(MediaMetrics.MarkGlyph)) {
        rotate(angle) {
            drawArc(
                tint,
                startAngle = 0f,
                sweepAngle = SPIN_SWEEP,
                useCenter = false,
                style = Stroke(MediaMetrics.MarkStroke.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/** A play triangle; the icon set has none. */
@Composable
internal fun PlayGlyph(
    tint: Color,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path =
            Path().apply {
                // Nudged right: a triangle's weight sits left of its box.
                moveTo(w * PLAY_LEFT, h * PLAY_INSET)
                lineTo(w * PLAY_RIGHT, h / 2f)
                lineTo(w * PLAY_LEFT, h * (1f - PLAY_INSET))
                close()
            }
        drawPath(path, tint)
    }
}

/**
 * A voice note or audio file (M17): play/pause, the bar, the time and, on the note that is loaded, the speed pill. The
 * bar of the loaded note takes taps and drags to scrub. Only one note plays at a time; the others show their length.
 */
@Composable
private fun VoiceNote(
    media: BubbleMedia,
    host: MediaHost,
) {
    val bubble = host.bubble
    val voice = host.voice
    val playing = voice?.playing == true
    val progress = voice?.progress ?: 0f
    val actions = host.actions
    val onInk = if (bubble.outgoing) RostyTheme.colors.accent else RostyTheme.colors.onAccent
    val disc = if (bubble.outgoing) host.ink else RostyTheme.colors.accent
    Row(
        Modifier.padding(bottom = Spacing.xs).width(MediaMetrics.VoiceWidth),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        val label = stringResource(if (playing) R.string.wa_voice_pause else R.string.wa_voice_play)
        Box(
            Modifier
                .size(MediaMetrics.VoicePlay)
                .clip(CircleShape)
                .background(disc)
                .clickable(role = Role.Button, onClickLabel = label) { actions.onVoiceToggle(bubble) }
                .semantics { contentDescription = listOf(label, media.label).joinToString(", ") }
                .testTag(MediaTags.voicePlay(bubble.key)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                media.busy -> Spinner(onInk)
                playing -> RostyIcon(RostyIcons.Pause, null, tint = onInk, size = MediaMetrics.VoiceGlyph)
                else -> PlayGlyph(onInk, MediaMetrics.VoiceGlyph)
            }
        }
        Column(Modifier.weight(1f)) {
            VoiceBar(
                progress = progress,
                track = host.secondary.copy(alpha = TRACK_ALPHA),
                fill = host.ink,
                waveform = media.waveform,
                enabled = voice != null,
                onSeek = { actions.onVoiceSeek(bubble, it) },
                modifier = Modifier.testTag(MediaTags.voiceBar(bubble.key)),
            )
            RostyText(
                voice?.timeLabel ?: media.detail ?: media.label,
                style = RostyTheme.type.caption2,
                color = host.secondary,
                maxLines = 1,
            )
        }
        if (voice != null && host.speedLabel.isNotEmpty()) {
            val speed = stringResource(R.string.wa_voice_speed, host.speedLabel)
            Box(
                Modifier
                    .size(MediaMetrics.SpeedPillWidth, MediaMetrics.SpeedPillHeight)
                    .clip(CircleShape)
                    .background(host.secondary.copy(alpha = TRACK_ALPHA))
                    .clickable(role = Role.Button, onClickLabel = speed, onClick = actions.onVoiceSpeed)
                    .semantics { contentDescription = speed }
                    .testTag(MediaTags.VoiceSpeed),
                contentAlignment = Alignment.Center,
            ) {
                RostyText(host.speedLabel, style = RostyTheme.type.caption1, color = host.ink, maxLines = 1)
            }
        }
    }
}

/**
 * The scrub bar: a track, the played part and a thumb; with a [waveform] (the bars WhatsApp carries) the bars in
 * place of the track, the played ones in [fill]. A tap or a drag on an [enabled] bar seeks.
 */
@Composable
private fun VoiceBar(
    progress: Float,
    track: Color,
    fill: Color,
    waveform: List<Float>,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val seek by rememberUpdatedState(onSeek)
    Box(
        modifier
            .fillMaxWidth()
            .height(MediaMetrics.VoiceTouch)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) }
            }.pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    seek((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }.drawBehind {
                val thumb = MediaMetrics.VoiceThumb.toPx()
                val played = (size.width - thumb) * progress.coerceIn(0f, 1f)
                if (waveform.isNotEmpty()) {
                    val wave = MediaMetrics.WaveHeight.toPx()
                    translate(top = (size.height - wave) / 2f) {
                        drawWaveform(waveform, progress, fill, track, Size(size.width, wave))
                    }
                } else {
                    val thickness = MediaMetrics.VoiceTrack.toPx()
                    val y = (size.height - thickness) / 2f
                    val radius = CornerRadius(thickness / 2f)
                    drawRoundRect(track, Offset(0f, y), Size(size.width, thickness), radius)
                    drawRoundRect(fill, Offset(0f, y), Size(played + thumb / 2f, thickness), radius)
                }
                drawCircle(fill, thumb / 2f, Offset(played + thumb / 2f, size.height / 2f))
            },
    )
}

/** A received or sent file: what it is called, its type and size, and Save and Share once it has gone out. */
@Composable
private fun FileRow(
    media: BubbleMedia,
    host: MediaHost,
) {
    val bubble = host.bubble
    val queued =
        bubble.state == BubbleState.Waiting || bubble.state == BubbleState.Sending || bubble.state == BubbleState.Failed
    Row(
        Modifier.padding(bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Box(
            Modifier
                .size(MediaMetrics.FileTile)
                .clip(RoundedCornerShape(MessagesMetrics.FolderTileCorner))
                .background(host.secondary.copy(alpha = TRACK_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            if (media.busy) {
                Spinner(host.ink)
            } else {
                RostyIcon(RostyIcons.Document, null, tint = host.ink, size = MediaMetrics.MarkGlyph)
            }
        }
        Column(Modifier.weight(1f, fill = false)) {
            RostyText(
                media.label.rtlSafe(),
                style = RostyTheme.type.subheadline,
                color = host.ink,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            media.detail?.let {
                RostyText(it, style = RostyTheme.type.caption1, color = host.secondary, maxLines = 1)
            }
        }
        if (!queued) {
            FileAction(
                RostyIcons.Download,
                stringResource(R.string.viewer_save),
                host.ink,
                Modifier.testTag(MediaTags.save(bubble.key)),
            ) { host.actions.onMediaAction(bubble, MediaAction.Save) }
            FileAction(
                RostyIcons.Share,
                stringResource(R.string.viewer_share),
                host.ink,
                Modifier.testTag(MediaTags.share(bubble.key)),
            ) { host.actions.onMediaAction(bubble, MediaAction.Share) }
        }
    }
}

@Composable
private fun FileAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(MediaMetrics.FileAction)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { RostyIcon(icon, null, tint = tint, size = MessagesMetrics.FolderGlyph) }
}

internal const val DEFAULT_MEDIA_ASPECT = 4f / 3f
private const val PLACEHOLDER_ALPHA = 0.16f
private const val MARK_ALPHA = 0.5f
private const val TRACK_ALPHA = 0.25f
private const val FULL_TURN = 360f
private const val SPIN_SWEEP = 270f
private const val SPIN_MS = 900
private const val PLAY_LEFT = 0.22f
private const val PLAY_RIGHT = 0.92f
private const val PLAY_INSET = 0.1f
