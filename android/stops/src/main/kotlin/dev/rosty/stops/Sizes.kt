package dev.rosty.stops

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The dp values of Stops that `:design`'s `Spacing` and `Sizes` do not have (the convention test keeps raw `.dp`
 * literals in this file). Layout numbers come from `docs/design/layout.md` and ARCH 8.4.
 */
internal object StopsSizes {
    /** The 24 x 24 grid of the app's own glyphs ([dev.rosty.stops.home.StopsIcons]). */
    val glyphGrid = 24.dp

    /**
     * End padding of the map button column: its 48 dp hit boxes overhang the 44 dp circles by 2 dp, so the circles end
     * on the 16 dp screen margin (layout.md 2.8), level with the folded tab bar's circle on the other side.
     */
    val buttonColumnEnd = 14.dp

    /** Height of the top search pill (one 48 dp row, layout.md 2.8). */
    val searchPill = 48.dp

    /** The one-line chip row under the pill: 44 dp hit boxes (layout.md 2.9). */
    val chipRow = 44.dp

    /** The sheet's resting heights: peek shows the handle and the first row (ARCH 8.4, ~120 dp). */
    val sheetPeek = 120.dp

    /** Between the sheet's first line and its rows: at peek the rows start under the sheet's visible edge. */
    val peekRowsGap = 40.dp

    /**
     * The side room a wide window needs beside the centred sheet for the chrome to stay put: the folded bar's circle
     * (68 dp) between 16 dp margins, rounded up.
     */
    val sideChromeMin = 104.dp

    /** The floating chrome rides the sheet up to its medium detent, then fades over this distance. */
    val rideFade = 96.dp

    /** The radar placeholder's grid step, drawn while no map engine is wired. */
    val radarGrid = 64.dp

    /** Width of the radar placeholder's grid lines. */
    val radarLine = 1.dp
}

/** The width beside a sheet of at most [sheetMaxWidth] centred in a [windowWidth] window, on one side (pure). */
fun sideRoomDp(
    windowWidth: Dp,
    sheetMaxWidth: Dp,
): Dp = ((windowWidth - sheetMaxWidth) / 2).coerceAtLeast(0.dp)
