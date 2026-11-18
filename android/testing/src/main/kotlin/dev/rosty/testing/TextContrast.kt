package dev.rosty.testing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onRoot
import dev.rosty.design.theme.contrastRatio
import dev.rosty.design.theme.relativeLuminance
import kotlin.math.abs

/** The two pixel clusters of a text region: [ink] is the text side, [fill] the background side. */
data class TextClusters(
    val ink: Color,
    val fill: Color,
    val ratio: Double,
)

/**
 * Measures text contrast in [bounds] of a rendered [image]: the pixels are split into two luminance clusters
 * (2-means, at most 8 iterations), the middle 20 % of the luminance span (anti-aliasing) is dropped, and the
 * WCAG ratio of the two cluster medians is returned. A flat region (no text) returns 1.0.
 */
fun textContrast(
    image: ImageBitmap,
    bounds: Rect,
): Double = textClusters(image, bounds).ratio

/** Like [textContrast] but also returns the two cluster colours, for failure messages. */
fun textClusters(
    image: ImageBitmap,
    bounds: Rect,
): TextClusters {
    val map = image.toPixelMap()
    val left = bounds.left.toInt().coerceIn(0, map.width)
    val right = bounds.right.toInt().coerceIn(left, map.width)
    val top = bounds.top.toInt().coerceIn(0, map.height)
    val bottom = bounds.bottom.toInt().coerceIn(top, map.height)
    val pixels = ArrayList<Color>((right - left) * (bottom - top))
    for (y in top until bottom) for (x in left until right) pixels += map[x, y]
    if (pixels.isEmpty()) return TextClusters(Color.Black, Color.Black, 1.0)
    val lum = DoubleArray(pixels.size) { relativeLuminance(pixels[it]) }
    var lo = lum.min()
    var hi = lum.max()
    if (hi - lo < FLAT) return TextClusters(pixels[0], pixels[0], 1.0)
    repeat(MAX_ITERATIONS) {
        var loSum = 0.0
        var loN = 0
        var hiSum = 0.0
        var hiN = 0
        for (l in lum) {
            if (abs(l - lo) <= abs(l - hi)) {
                loSum += l
                loN++
            } else {
                hiSum += l
                hiN++
            }
        }
        if (loN > 0) lo = loSum / loN
        if (hiN > 0) hi = hiSum / hiN
    }
    val span = hi - lo
    val loCut = lo + span * (0.5 - DROPPED_MIDDLE / 2)
    val hiCut = lo + span * (0.5 + DROPPED_MIDDLE / 2)
    val low = ArrayList<Int>()
    val high = ArrayList<Int>()
    for (i in lum.indices) {
        when {
            lum[i] <= loCut -> low += i
            lum[i] >= hiCut -> high += i
        }
    }
    if (low.isEmpty() || high.isEmpty()) return TextClusters(pixels[0], pixels[0], 1.0)
    val a = pixels[low.sortedBy { lum[it] }[low.size / 2]]
    val b = pixels[high.sortedBy { lum[it] }[high.size / 2]]
    return TextClusters(a, b, contrastRatio(a, b))
}

/** Fails with the measured ratio and both cluster colours when the text in [bounds] reads below [min]. */
fun assertReadable(
    image: ImageBitmap,
    bounds: Rect,
    min: Double,
    what: String,
) {
    val c = textClusters(image, bounds)
    check(c.ratio >= min) {
        "$what: text contrast %.2f:1 is below %.2f:1 (colours %s and %s)".format(c.ratio, min, hex(c.ink), hex(c.fill))
    }
}

private fun hex(color: Color): String = "#%08X".format(color.toArgb())

private const val FLAT = 0.002
private const val MAX_ITERATIONS = 8
private const val DROPPED_MIDDLE = 0.2

/**
 * Captures the root of this test now and asserts that every node [matcher] finds (in the unmerged tree, at least one)
 * reads at [min] in the rendered pixels. Use it in a shot test after the frame has settled.
 */
fun ShotTest.assertNodeReadable(
    matcher: androidx.compose.ui.test.SemanticsMatcher,
    min: Double,
    what: String,
) {
    val density = compose.activity.resources.displayMetrics.density
    val nodes = compose.onAllNodes(matcher, useUnmergedTree = true)
    val count = nodes.fetchSemanticsNodes().size
    check(count > 0) { "$what: no node found to measure" }
    val image = compose.onRoot().captureToImage()
    for (i in 0 until count) {
        val b = nodes[i].getBoundsInRoot()
        val rect =
            Rect(b.left.value * density, b.top.value * density, b.right.value * density, b.bottom.value * density)
        assertReadable(image, rect, min, "$what #$i")
    }
}

/**
 * Asserts that every text inside the node tagged [tag] (a button, say) reads at [min] in the rendered pixels. Only the
 * text bounds are measured, so the rounded corners of the button (the page behind them) do not join the clusters.
 */
fun ShotTest.assertTagTextReadable(
    tag: String,
    min: Double,
    what: String,
) = assertNodeReadable(
    androidx.compose.ui.test
        .hasAnyAncestor(
            androidx.compose.ui.test
                .hasTestTag(tag),
        ) and
        androidx.compose.ui.test
            .hasText("", substring = true),
    min,
    what,
)
