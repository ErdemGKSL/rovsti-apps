/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/NewMenuComponents.kt
 * Changes for Rovsti: only the column-count rule and the minimum cell width (lines 100-115 of the original) are ported;
 * the action grid itself is Rovsti's quick-action row in [IosMenu] and the sheet menus arrive with E1.28.
 */
package dev.rovsti.design.ios.component

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite

/** Narrowest action cell that still fits a two-line caption such as "Add to playlist". */
internal val MenuActionMinCellWidth = 120.dp

/**
 * How many actions fit across [width] without going under [minCell], and never above
 * [requestedColumns]. An unbounded width (a menu still being measured) keeps the request.
 */
internal fun menuActionColumnCount(
    width: Dp,
    minCell: Dp,
    gap: Dp,
    requestedColumns: Int,
): Int {
    val requested = requestedColumns.coerceAtLeast(1)
    if (!width.isFinite || width <= 0.dp) return requested
    val step = minCell + gap
    if (step <= 0.dp) return requested
    val fitted = ((width + gap) / step).toInt().coerceAtLeast(1)
    return minOf(requested, fitted)
}
