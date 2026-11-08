// Rovsti Project (C) 2026, GPL-3.0-or-later. Original code.
package dev.rovsti.catalog

import androidx.compose.runtime.remember
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.rovsti.design.list.AlphabetRailTags
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.OverlayHost
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E1.22 checklist B (found by the font-scale 2.0 sweep): the alphabet rail floats over the end of the list, so the text
 * of a row must stop before it, also at font scale 2.0 where the phone numbers are as wide as the row.
 */
class ListRailInsetTest : ShotTest() {
    @Test
    fun rowTextStopsBeforeTheRailAtFontScaleTwo() {
        for (fontScale in listOf(1f, 2f)) {
            composeFrame(fontScale = fontScale) {
                OverlayHost(remember { OverlayController() }) { ListsPage(imeVisible = { false }) }
            }
            advance(300)
            val rail = compose.onNodeWithTag(AlphabetRailTags.Rail).getUnclippedBoundsInRoot()
            val row =
                compose
                    .onAllNodes(
                        hasText("Mobile, +49", substring = true),
                        useUnmergedTree = true,
                    ).onFirst()
                    .getUnclippedBoundsInRoot()
            assertTrue(
                "row text ends at ${row.right} but the rail starts at ${rail.left} (font scale $fontScale)",
                row.right <= rail.left + 0.5.dp,
            )
        }
    }
}
