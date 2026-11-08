package dev.rovsti.design.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing

private const val MaxShown = 99

/** The text a [Badge] shows for [count]: the number, or "99+" above 99. */
fun badgeText(count: Int): String = if (count > MaxShown) "$MaxShown+" else count.toString()

/**
 * An unread count: accent capsule, on-accent text (4.5:1 in both themes), at least 20 dp tall and wide. Nothing is drawn
 * for a count of zero or less.
 */
@Composable
fun Badge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return
    val colors = RovstiTheme.colors
    Box(
        modifier.defaultMinSize(minWidth = Sizes.badge, minHeight = Sizes.badge).background(colors.accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        RovstiText(
            badgeText(count),
            modifier = Modifier.padding(horizontal = Spacing.xs),
            style = RovstiTheme.type.caption1.copy(fontWeight = FontWeight.SemiBold),
            color = colors.onAccent,
            maxLines = 1,
        )
    }
}

/** A 10 dp dot for "something new" without a number. */
@Composable
fun BadgeDot(modifier: Modifier = Modifier) {
    Box(modifier.size(Sizes.badgeDot).background(RovstiTheme.colors.accent, CircleShape))
}
