package dev.rosty.design.chrome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme

/**
 * The standard content of [RostyTabBar]'s `accessory` slot: a one-line pill (icon, title, detail) such as the Phone
 * "On call 02:14" pill or the Stops trip pill. Pass the `modifier` and `isInline` the slot hands out: the modifier
 * already carries the bar's fill, shadow and glass, so the pill is the same material as the bar and as wide as the bar
 * (folded: as wide as the room beside the circle).
 *
 * Rosty's counterpart of Convx's `FloatingMiniPlayer` shell (ui/component/FloatingMiniPlayer.kt) without the player:
 * heights are Convx's, 54 dp in the expanded bar (`ExpandedAccessoryFallbackHeight`) and 48 dp folded (`InlineRowHeight`).
 */
@Composable
fun TabBarAccessoryPill(
    title: String,
    modifier: Modifier,
    isInline: Boolean,
    onClick: () -> Unit,
    detail: String? = null,
    icon: ImageVector? = null,
) {
    val colors = RostyTheme.colors
    Row(
        modifier
            .height(if (isInline) AccessoryInlineHeight else AccessoryExpandedHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) RostyIcon(icon, null, tint = colors.success, size = 22.dp)
        RostyText(title, style = RostyTheme.type.headline, modifier = Modifier.weight(1f, fill = false))
        if (detail != null && !isInline) {
            RostyText(detail, style = RostyTheme.type.callout, color = colors.labelSecondary)
        }
    }
}

internal val AccessoryExpandedHeight = 54.dp
internal val AccessoryInlineHeight = 48.dp
