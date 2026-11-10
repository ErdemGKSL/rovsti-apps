// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
package dev.rovsti.design.controls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme

/**
 * An action drawn as a glyph with a caption and nothing behind it (Remind me, Message, Silence on the Incoming
 * screen): iOS draws these as plain white marks on the call colour, not as buttons. The whole column is the touch
 * target (at least 48 dp square). While a finger is down it dims to 0.5 at once, and on release it
 * eases back over the 200 ms `focusFill` tween that `PressFill` uses for glisse's button press. The click fires on
 * lift. Pass [color] for the glyph and caption.
 */
@Composable
fun PlainIconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    glyphSize: Dp = PlainIconActionDefaults.GlyphSize,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val press = rememberPressFill(source)
    val restAlpha = if (enabled) 1f else PlainIconActionDefaults.DisabledAlpha
    Column(
        modifier
            .graphicsLayer { alpha = restAlpha * (1f - (1f - PlainIconActionDefaults.PressedAlpha) * press.value) }
            .defaultMinSize(PlainIconActionDefaults.MinTarget, PlainIconActionDefaults.MinTarget)
            .clickable(
                enabled = enabled,
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            ).semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PlainIconActionDefaults.CaptionGap, Alignment.CenterVertically),
    ) {
        RovstiIcon(icon, null, tint = color, size = glyphSize)
        RovstiText(
            label,
            style = RovstiTheme.type.footnote,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The numbers of [PlainIconAction]. */
object PlainIconActionDefaults {
    val GlyphSize: Dp = 28.dp

    /** The smallest touch target, both ways. */
    val MinTarget: Dp = 48.dp

    /** The alpha while pressed. */
    const val PressedAlpha = 0.5f
    internal const val DisabledAlpha = 0.5f
    internal val CaptionGap = 4.dp
}
