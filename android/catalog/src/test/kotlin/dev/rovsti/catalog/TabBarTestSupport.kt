package dev.rovsti.catalog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import dev.rovsti.design.chrome.TabBarSpec
import dev.rovsti.testing.ShotTest

internal val SemanticsNodeInteraction.bounds: Rect get() = fetchSemanticsNode().boundsInRoot

/** Everything the tab bar tests need to aim a finger: where the bar is and how wide a slot is, in root px. */
internal class BarGeometry(
    val test: ShotTest,
    val count: Int = CatalogTabs.size,
) {
    private val density get() = test.compose.activity.resources.displayMetrics.density
    val surface: Rect get() = test.compose.onNodeWithTag("tab-surface").bounds
    val padding get() = TabBarSpec.PaddingDp * density
    val slot get() = (surface.width - 2 * padding) / count

    /** Centre of slot [i], vertically centred in the bar. */
    fun slotCentre(i: Float): Offset = Offset(surface.left + padding + (i + 0.5f) * slot, surface.center.y)

    fun dp(value: Float) = value * density
}
