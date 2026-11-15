package dev.rosty.stops.home

/**
 * How far the floating chrome at the bottom (the button column and the folded tab bar) rides up with the sheet, and how
 * opaque it is (ORIGINAL; SKILL navigation-layout 9: "floating buttons ride the sheet via `graphicsLayer` translation
 * up to a limit"). Pure and allocation-free, so a layer lambda reads it every frame; tested on the JVM.
 *
 * The chrome rests a gap above the window's bottom inset ([bottomInsetPx]); the sheet shows [visiblePx] of itself
 * above the window's bottom edge (its glass reaches the edge). The chrome keeps the same gap above the sheet's
 * top edge until the sheet is [maxVisiblePx] tall (the medium detent), then stays put and fades out over [fadePx]
 * while the sheet grows on towards large, where the sheet covers it.
 */
fun sheetRideTranslation(
    visiblePx: Float,
    bottomInsetPx: Float,
    maxVisiblePx: Float,
): Float = -(visiblePx - bottomInsetPx).coerceIn(0f, (maxVisiblePx - bottomInsetPx).coerceAtLeast(0f))

/** The chrome's alpha for [sheetRideTranslation]'s sheet: 1 up to [maxVisiblePx], 0 once [fadePx] beyond it. */
fun sheetRideAlpha(
    visiblePx: Float,
    maxVisiblePx: Float,
    fadePx: Float,
): Float {
    val past = visiblePx - maxVisiblePx
    if (fadePx <= 0f) return if (past > 0f) 0f else 1f
    return (1f - past / fadePx).coerceIn(0f, 1f)
}

/** The visible height of a sheet whose top edge is [offset] px below the top of a [containerHeight] px window. */
fun sheetVisiblePx(
    offset: Float,
    containerHeight: Float,
): Float = (containerHeight - offset).coerceIn(0f, containerHeight.coerceAtLeast(0f))

/**
 * How tall the sheet may get while the bottom chrome still rides it (pure): its medium detent ([mediumVisiblePx]),
 * but never so tall that the riding block ([blockHeightPx], measured from the window's bottom edge, insets included)
 * would come closer than [gapPx] to the top chrome's bottom edge ([topChromeBottomPx]). In a short landscape window
 * this keeps the button column under the search pill. Never below the bottom inset (no ride at all).
 */
fun rideMaxVisiblePx(
    mediumVisiblePx: Float,
    windowHeightPx: Float,
    topChromeBottomPx: Float,
    blockHeightPx: Float,
    bottomInsetPx: Float,
    gapPx: Float,
): Float {
    val room = windowHeightPx - topChromeBottomPx - gapPx - blockHeightPx + bottomInsetPx
    return minOf(mediumVisiblePx, room).coerceAtLeast(bottomInsetPx)
}

/**
 * The top chrome's alpha (pure): 1 while the sheet's top edge ([sheetTopPx], from the window top) stays [fadePx] below
 * the chrome's bottom ([chromeBottomPx]), 0 once the edge reaches it, so the search pill never peeks above a large
 * sheet. A modal sheet's push-back ([pushBack], 0..1) hides it the same way.
 */
fun topChromeAlpha(
    sheetTopPx: Float,
    chromeBottomPx: Float,
    fadePx: Float,
    pushBack: Float = 0f,
): Float {
    val gap = sheetTopPx - chromeBottomPx
    val bySheet = if (fadePx <= 0f) (if (gap > 0f) 1f else 0f) else (gap / fadePx).coerceIn(0f, 1f)
    return minOf(bySheet, 1f - pushBack.coerceIn(0f, 1f))
}
