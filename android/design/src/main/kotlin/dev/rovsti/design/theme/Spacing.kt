package dev.rovsti.design.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The only spacing values. One gutter, one set of gaps; no other numbers appear in layout code. */
object Spacing {
    val gutter = 16.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp

    /** Between a title and its secondary line. */
    val textGap = 2.dp
    val sectionGap = 24.dp
}

/** Component dimensions. Minimums, not fixed heights: rows grow with font scale. */
object Sizes {
    val rowMin = 56.dp
    val rowMinTwoLine = 64.dp
    val avatar = 40.dp
    val avatarLarge = 44.dp

    /** Smallest touch target. */
    val touch = 48.dp
    val button = 44.dp
    val sourceGlyph = 14.dp
    val tabBarHeight = 60.dp
    val tabBarMaxWidth = 500.dp
    val contentMaxWidth = 640.dp
    val sheetMaxWidth = 560.dp
}

object RovstiShapes {
    /** Inset-grouped sections. */
    val grouped = RoundedCornerShape(20.dp)
    val card = RoundedCornerShape(22.dp)

    /** Popover corner radius range: small menus use the low end, preview cards the high end. */
    val popoverMin = 20.dp
    val popoverMax = 32.dp
    val capsule = CircleShape
}

/** Smallest corner radius [concentric] returns. */
val ConcentricFloor = 4.dp

/** Radius of an inner shape inset by [inset] inside an outer shape of radius [outer], never below 4 dp. */
fun concentric(
    outer: Dp,
    inset: Dp,
): Dp = (outer - inset).coerceAtLeast(ConcentricFloor)
