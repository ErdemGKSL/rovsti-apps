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

    /**
     * Room between the sheet's grabber and the title of every Stops sheet ([dev.rosty.stops.home.OnSheet]): with the
     * grabber's own 2 dp under it, the title starts about 14 dp (plus its line's leading) under the grabber, as in iOS
     * sheets. The map sheet's peek counts it ([dev.rosty.stops.home.MapSheetGrabber]).
     */
    val sheetTitleTop = 12.dp

    /**
     * The map sheet's peek before its content is measured (ARCH 8.4, ~120 dp), and the least the large-text or landscape
     * cap may cut a measured peek to. The measured peek reaches down to the content's buttons
     * ([dev.rosty.stops.home.mapSheetPeekPx]).
     */
    val sheetPeek = 120.dp

    /** The measured peek stays at least this far under the medium detent (large text, landscape). */
    val peekBelowMedium = 48.dp

    /**
     * The side room a wide window needs beside the centred sheet for the chrome to stay put: the folded bar's circle
     * (68 dp) between 16 dp margins, rounded up.
     */
    val sideChromeMin = 104.dp

    /** The floating chrome rides the sheet up to its medium detent, then fades over this distance. */
    val rideFade = 96.dp

    /** The glyph in a map button's 44 dp circle (`:design`'s icon button size). */
    val mapIcon = 20.dp

    /** The QR code card of the QR sheet (the Contacts sheet's size). */
    val qrCode = 200.dp
}

/** The width beside a sheet of at most [sheetMaxWidth] centred in a [windowWidth] window, on one side (pure). */
fun sideRoomDp(
    windowWidth: Dp,
    sheetMaxWidth: Dp,
): Dp = ((windowWidth - sheetMaxWidth) / 2).coerceAtLeast(0.dp)
