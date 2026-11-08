/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/ios/component/IosGlyph.kt
 * Changes for Rovsti: package dev.rovsti.design.ios.component; the `SfSymbol` overload (which called Convx's
 * `SymbolIcon` with the bounce-on-touch chrome) is gone because Rovsti has no SF Symbols: `SymbolIcon` becomes
 * `RovstiIcon`, and the vector overload below is the one the menus use.
 */
package dev.rovsti.design.ios.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import dev.rovsti.design.icons.RovstiIcon

/**
 * A glyph (or any vector) fitted inside a [box] square *without* stretching it.
 * SF Symbols are not square -- ellipsis is wide and short, music.note.list wider than
 * tall -- and forcing them into a square Icon squashes them (the "..." drew as "|||").
 */
@Composable
fun IosGlyph(
    vector: ImageVector,
    tint: Color,
    box: Dp,
    modifier: Modifier = Modifier,
) = RovstiIcon(icon = vector, contentDescription = null, modifier = modifier, tint = tint, size = box)

@Composable
fun IosGlyph(
    painter: Painter,
    tint: Color,
    box: Dp,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(box),
    )
}
