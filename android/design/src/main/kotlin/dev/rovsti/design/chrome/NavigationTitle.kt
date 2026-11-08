/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/NavigationTitle.kt
 * Changes for Rovsti: package dev.rovsti.design.chrome; LocalPageMetrics became Spacing; the artwork ambient colour and
 * the "play all" action (music) are gone; the "see all" string is a parameter; AppleTokens.SectionHeader (22 sp bold)
 * is the title2 style of RovstiTheme.
 */
package dev.rovsti.design.chrome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing

/**
 * A section heading: bold title, optional small [label] above it, optional [thumbnail] before it. When [onClick] is set
 * the whole header is tappable and a heavy chevron marks the linked section; [seeAllLabel] adds a text action instead
 * of the chevron.
 */
@Composable
fun NavigationTitle(
    title: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    thumbnail: (@Composable () -> Unit)? = null,
    color: Color = Color.Unspecified,
    seeAllLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = RovstiTheme.colors
    val contentColor = if (color == Color.Unspecified) colors.labelPrimary else color
    val chevron = onClick != null && seeAllLabel == null

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(enabled = onClick != null) { onClick?.invoke() }
                .padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.l, bottom = Spacing.s),
    ) {
        thumbnail?.invoke()

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Column(verticalArrangement = Arrangement.Center, modifier = Modifier.weight(1f, fill = false)) {
                label?.let { text ->
                    IosText(
                        text = text,
                        style = RovstiTheme.type.subheadline,
                        color = contentColor.copy(alpha = 0.6f),
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IosText(
                    text = title,
                    style = RovstiTheme.type.title2.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em),
                    color = contentColor,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            }
            // The whole header is clickable; the chevron marks the linked section. A short heavy stroke about the
            // title's cap height reads better beside a bold header than the regular-weight glyph.
            if (chevron) {
                val chevronColor = colors.labelSecondary
                Canvas(Modifier.padding(start = 6.dp, top = 1.dp).size(width = 9.dp, height = 15.dp)) {
                    val stroke = 2.6.dp.toPx()
                    val inset = stroke / 2
                    drawPath(
                        Path().apply {
                            moveTo(inset, inset)
                            lineTo(size.width - inset, size.height / 2)
                            lineTo(inset, size.height - inset)
                        },
                        color = chevronColor,
                        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }

        if (seeAllLabel != null && onClick != null) {
            IosText(
                text = seeAllLabel,
                style = RovstiTheme.type.body,
                color = contentColor.copy(alpha = 0.6f),
                maxLines = 1,
                modifier = Modifier.clickable(onClick = onClick),
            )
        }
    }
}
