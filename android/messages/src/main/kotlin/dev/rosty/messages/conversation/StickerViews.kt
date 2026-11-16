package dev.rosty.messages.conversation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.media.SizedImage
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.theme.MediaMetrics
import dev.rosty.messages.theme.MessagesMetrics
import dev.rosty.messages.viewer.AnimatedSticker

/** The width and height (dp) a sticker is drawn at. */
data class StickerSize(
    val width: Float,
    val height: Float,
)

/**
 * The size of a sticker of [width] x [height] pixels inside a [box] dp square: the longer side fills the box, the
 * aspect is kept. A sticker without both dimensions (most are 512 x 512) is the full square.
 */
fun stickerSize(
    width: Int?,
    height: Int?,
    box: Float,
): StickerSize {
    val w = width?.takeIf { it > 0 } ?: return StickerSize(box, box)
    val h = height?.takeIf { it > 0 } ?: return StickerSize(box, box)
    return if (w >= h) StickerSize(box, box * h / w) else StickerSize(box * w / h, box)
}

/** Test tags of the sticker and view-once views. */
object StickerTags {
    fun sticker(key: String) = "sticker-$key"

    fun stickerMeta(key: String) = "sticker-meta-$key"

    fun unavailable(key: String) = "unavailable-$key"
}

/** The one sticker of [bubble], when that is all the bubble shows (not deleted). */
internal fun BubbleUi.sticker(): BubbleMedia? =
    media.singleOrNull()?.takeIf { it.kind == BubbleMediaKind.Sticker && !deleted && text.isEmpty() }

/**
 * A sticker message: the sticker at sticker size with no bubble around it, and the time, star and ticks in a small
 * dark pill over its bottom corner. A reply shows its quote above, in a small card of the bubble's colour.
 */
@Composable
internal fun StickerBody(
    bubble: BubbleUi,
    media: BubbleMedia,
    fill: Color,
    ink: Color,
) {
    val size = stickerSize(media.width, media.height, MediaMetrics.Sticker.value)
    Column(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = listOf(media.label, bubble.meta).joinToString(", ")
        },
        horizontalAlignment = if (bubble.outgoing) Alignment.End else Alignment.Start,
    ) {
        bubble.quote?.let { q ->
            Column(
                Modifier
                    .padding(bottom = Spacing.xs)
                    .widthIn(max = MediaMetrics.Sticker)
                    .clip(RoundedCornerShape(MessagesMetrics.FolderTileCorner))
                    .background(fill)
                    .padding(Spacing.s),
            ) {
                RostyText(
                    q.author,
                    style = RostyTheme.type.caption1,
                    color = if (bubble.outgoing) ink else RostyTheme.colors.accent,
                    maxLines = 1,
                )
                RostyText(q.text, style = RostyTheme.type.footnote, color = ink, maxLines = 2)
            }
        }
        Box(Modifier.size(size.width.dp, size.height.dp).testTag(StickerTags.sticker(bubble.key))) {
            val picture: @Composable () -> Unit = {
                if (media.model != null) {
                    val density = LocalDensity.current
                    SizedImage(
                        model = media.model,
                        widthPx = with(density) { size.width.dp.roundToPx() },
                        heightPx = with(density) { size.height.dp.roundToPx() },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(MessagesMetrics.FolderTileCorner))
                            .background(RostyTheme.colors.fillTertiary),
                        contentAlignment = Alignment.Center,
                    ) {
                        RostyIcon(
                            RostyIcons.Photo,
                            null,
                            tint = RostyTheme.colors.labelSecondary,
                            size = MediaMetrics.MarkGlyph,
                        )
                    }
                }
            }
            val file = media.animated
            if (file != null) AnimatedSticker(file, Modifier.fillMaxSize(), picture) else picture()
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = STICKER_PILL_ALPHA))
                    .padding(horizontal = Spacing.s, vertical = Spacing.textGap)
                    .testTag(StickerTags.stickerMeta(bubble.key)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                MarkGlyphs(bubble, Color.White)
                RostyText(bubble.meta, style = RostyTheme.type.caption2, color = Color.White, maxLines = 1)
                if (bubble.outgoing) StateGlyph(bubble.state, Color.White, read = ReadTick)
            }
        }
    }
}

/**
 * What this device cannot show (a view-once message, a bot's message): the view-once glyph (or an info mark) and what
 * to do instead. Nothing to tap.
 */
@Composable
internal fun UnavailableRow(
    media: BubbleMedia,
    host: MediaHost,
) {
    Row(
        Modifier.padding(bottom = Spacing.xs).testTag(StickerTags.unavailable(host.bubble.key)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (media.viewOnce) {
            ViewOnceGlyph(host.ink, MediaMetrics.ViewOnceGlyph)
        } else {
            RostyIcon(RostyIcons.Info, null, tint = host.ink, size = MediaMetrics.ViewOnceGlyph)
        }
        RostyText(media.label, style = RostyTheme.type.subheadline, color = host.ink)
    }
}

/**
 * WhatsApp's view-once mark: a 1 in a circle. [on] fills the circle (the toggle of the send preview when it is set);
 * off, the circle is a broken ring as on WhatsApp.
 */
@Composable
fun ViewOnceGlyph(
    tint: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    cutout: Color = Color.Transparent,
) {
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * GLYPH_STROKE
        val radius = this.size.minDimension / 2f - stroke / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        if (on) {
            drawCircle(tint, radius + stroke / 2f, center)
        } else {
            // A ring with a gap at the top right, the way WhatsApp draws it.
            drawArc(
                tint,
                startAngle = RING_START,
                sweepAngle = RING_SWEEP,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size =
                    androidx.compose.ui.geometry
                        .Size(radius * 2f, radius * 2f),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        val w = this.size.width
        val h = this.size.height
        val one =
            Path().apply {
                moveTo(w * ONE_FLAG_X, h * ONE_FLAG_Y)
                lineTo(w * ONE_X, h * ONE_TOP)
                lineTo(w * ONE_X, h * ONE_BOTTOM)
            }
        drawPath(
            one,
            if (on) cutout.takeIf { it != Color.Transparent } ?: Color.White else tint,
            style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private const val STICKER_PILL_ALPHA = 0.45f
private const val GLYPH_STROKE = 0.09f
private const val RING_START = -45f
private const val RING_SWEEP = 300f
private const val ONE_X = 0.53f
private const val ONE_TOP = 0.3f
private const val ONE_BOTTOM = 0.7f
private const val ONE_FLAG_X = 0.42f
private const val ONE_FLAG_Y = 0.39f
