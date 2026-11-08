package dev.rovsti.design.controls

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
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing

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
    val colors = RovstiTheme.colors
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
        RovstiIcon(icon, null, tint = colors.labelSecondary, size = Sizes.emptyGlyph)
        RovstiText(title, style = RovstiTheme.type.title3, textAlign = TextAlign.Center)
        RovstiText(
            message,
            style = RovstiTheme.type.subheadline,
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
