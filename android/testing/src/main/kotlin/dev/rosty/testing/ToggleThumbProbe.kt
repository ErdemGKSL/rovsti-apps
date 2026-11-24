package dev.rosty.testing

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import dev.rosty.design.ios.component.IosToggleTags

/**
 * What a rendered `IosToggle` looks like, from node bounds and a screenshot: the thumb and track boxes in dp, the
 * vertical offset between their centres, and the thumb's centre pixel as 0..255 channels.
 */
data class ToggleThumbProbe(
    val thumbWidthDp: Float,
    val thumbHeightDp: Float,
    val trackWidthDp: Float,
    val trackHeightDp: Float,
    val centreOffsetYDp: Float,
    val red: Int,
    val green: Int,
    val blue: Int,
) {
    val minChannel get() = minOf(red, green, blue)
}

/**
 * Measures the toggle below the node tagged [scopeTag] (the tag the caller put on the toggle's modifier). The
 * screenshot is of the whole root, so whatever covers the thumb (a scrim, a sheet's backdrop) shows in the pixel.
 */
fun SemanticsNodeInteractionsProvider.toggleThumbProbe(
    scopeTag: String,
    density: Float,
    root: SemanticsNodeInteraction = onRoot(),
): ToggleThumbProbe {
    fun part(tag: String) = onNode(hasTestTag(tag).and(hasAnyAncestor(hasTestTag(scopeTag))), useUnmergedTree = true)

    val thumb = part(IosToggleTags.Thumb).fetchSemanticsNode().boundsInRoot
    val track = part(IosToggleTags.Track).fetchSemanticsNode().boundsInRoot
    val image = root.captureToImage().toPixelMap()
    val cx = ((thumb.left + thumb.right) / 2f).toInt().coerceIn(0, image.width - 1)
    val cy = ((thumb.top + thumb.bottom) / 2f).toInt().coerceIn(0, image.height - 1)
    val pixel = image[cx, cy]
    return ToggleThumbProbe(
        thumbWidthDp = thumb.width / density,
        thumbHeightDp = thumb.height / density,
        trackWidthDp = track.width / density,
        trackHeightDp = track.height / density,
        centreOffsetYDp = (thumb.center.y - track.center.y) / density,
        red = (pixel.red * 255f + 0.5f).toInt(),
        green = (pixel.green * 255f + 0.5f).toInt(),
        blue = (pixel.blue * 255f + 0.5f).toInt(),
    )
}
