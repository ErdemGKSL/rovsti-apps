package dev.rosty.messages.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import dev.rosty.messages.theme.MediaMetrics

/**
 * The two glyphs of the video controls the shared icon set has no use for elsewhere, drawn on its 24-unit grid with
 * its 2-unit round stroke (`RostyIcons`): a play triangle and the loudspeaker with a stroke through it.
 */
object MediaIcons {
    /** A play triangle, nudged right: a triangle's weight sits left of its box. */
    val Play: ImageVector by lazy {
        ImageVector
            .Builder("Play", MediaMetrics.IconGrid, MediaMetrics.IconGrid, 24f, 24f)
            .path(
                fill = SolidColor(Color.Black),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(8f, 5.5f)
                lineTo(18.5f, 12f)
                lineTo(8f, 18.5f)
                close()
            }.build()
    }

    /** The loudspeaker of `RostyIcons.Speaker` without its waves and with a cross: the video is muted. */
    val SpeakerSlash: ImageVector by lazy {
        ImageVector
            .Builder("SpeakerSlash", MediaMetrics.IconGrid, MediaMetrics.IconGrid, 24f, 24f)
            .path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4f, 9.5f)
                horizontalLineTo(7.5f)
                lineTo(12f, 5.5f)
                verticalLineTo(18.5f)
                lineTo(7.5f, 14.5f)
                horizontalLineTo(4f)
                close()
                moveTo(15.5f, 9.5f)
                lineTo(20.5f, 14.5f)
                moveTo(20.5f, 9.5f)
                lineTo(15.5f, 14.5f)
            }.build()
    }
}
