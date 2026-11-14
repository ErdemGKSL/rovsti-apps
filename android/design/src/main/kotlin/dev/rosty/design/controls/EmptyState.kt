package dev.rosty.design.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing

/**
 * Nothing here yet (ORIGINAL): a 48 dp glyph, a `title3` title, ONE `subheadline` line of explanation and an optional
 * capsule action. Centred in the space the parent gives it; the text is centred and wraps rather than clipping.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = RostyTheme.colors
    Column(
        modifier
            .widthIn(
                max = 320.dp,
            ).padding(horizontal = Spacing.xl, vertical = Spacing.xl)
            .semantics(mergeDescendants = true) {
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        RostyIcon(icon, null, tint = colors.labelSecondary, size = Sizes.emptyGlyph)
        RostyText(title, style = RostyTheme.type.title3, textAlign = TextAlign.Center)
        RostyText(
            message,
            style = RostyTheme.type.subheadline,
            color = colors.labelSecondary,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            CapsuleButton(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier.padding(top = Spacing.s),
                style = CapsuleStyle.Filled,
            )
        }
    }
}
