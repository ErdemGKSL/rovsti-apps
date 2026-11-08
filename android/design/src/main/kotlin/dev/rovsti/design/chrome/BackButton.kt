// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// The circle is the glisse glass button; the mirrored chevron and its placement are Rovsti's own.
package dev.rovsti.design.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import dev.rovsti.design.controls.GlassIconButton
import dev.rovsti.design.icons.RovstiIcons

/**
 * The circular glass back button of a pushed page: a 44 dp circle in a 48 dp touch target with a chevron that points
 * to the start edge, so it is mirrored in right-to-left layouts. Its accessibility name is [label] ("Back").
 */
@Composable
fun BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Back",
) {
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.graphicsLayer { scaleX = if (mirrored) -1f else 1f }) {
        GlassIconButton(
            icon = RovstiIcons.Back,
            contentDescription = label,
            onClick = onClick,
            modifier = Modifier.testTag("back-button"),
        )
    }
}
