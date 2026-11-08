package dev.rovsti.design.ios.component

import androidx.compose.ui.geometry.Offset
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.toSections
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the menu port: keyboard stepping, the facade's mapping, the preview's rubber band and dismiss rule. */
class IosMenuTest {
    private val entries =
        listOf(
            IosMenuItem("A"),
            IosMenuItem("B"),
            IosMenuItem("Off", enabled = false),
            IosMenuItem("D", role = IosButtonRole.Destructive),
        )

    @Test
    fun arrowsWrapAndSkipDisabledRows() {
        assertEquals(0, iosMenuStep(entries, -1, +1))
        assertEquals(3, iosMenuStep(entries, -1, -1))
        assertEquals(1, iosMenuStep(entries, 0, +1))
        assertEquals(3, iosMenuStep(entries, 1, +1))
        assertEquals(0, iosMenuStep(entries, 3, +1))
        assertEquals(3, iosMenuStep(entries, 0, -1))
    }

    @Test
    fun aMenuWithNothingEnabledStepsNowhere() {
        assertEquals(-1, iosMenuStep(listOf(IosMenuItem("x", enabled = false)), -1, +1))
        assertEquals(-1, iosMenuStep(emptyList(), -1, +1))
    }

    @Test
    fun theFacadeSplitsSectionsAtSeparatorsAndMapsFlags() {
        val sections =
            persistentListOf<MenuItem>(
                MenuItem.Action("One", onClick = {}),
                MenuItem.Action("Sorted", checked = true, onClick = {}),
                MenuItem.Separator,
                MenuItem.Separator,
                MenuItem.Action("Delete", destructive = true, enabled = false, onClick = {}),
            ).toSections()
        assertEquals(2, sections.size)
        assertEquals(listOf("One", "Sorted"), sections[0].items.map { it.title })
        assertFalse(sections[0].items[0].checkable)
        assertTrue(sections[0].items[1].checkable && sections[0].items[1].selected)
        assertEquals(IosButtonRole.Destructive, sections[1].items[0].role)
        assertFalse(sections[1].items[0].enabled)
        assertNull(sections[0].items[0].role)
    }

    @Test
    fun anUncheckedRowIsCheckableButNotSelected() {
        val row =
            persistentListOf<MenuItem>(
                MenuItem.Action("Off", checked = false, onClick = {}),
            ).toSections()[0].items[0]
        assertTrue(row.checkable)
        assertFalse(row.selected)
    }

    /** AppleContextMenu.kt: sideways resists to 0.6, upward to 0.3, downward follows the finger. */
    @Test
    fun theRubberBandFollowsConvx() {
        val down = rubberBand(Offset(100f, 100f))
        assertEquals(60f, down.x, 0.01f)
        assertEquals(100f, down.y, 0.01f)
        val up = rubberBand(Offset(-100f, -100f))
        assertEquals(-60f, up.x, 0.01f)
        assertEquals(-30f, up.y, 0.01f)
    }

    /** AppleContextMenu.kt: past 64 dp down, or 40 % of the way back home, dismisses. */
    @Test
    fun theDismissRuleFollowsConvx() {
        val home = Offset(-100f, -200f)
        assertTrue(shouldDismissPreview(Offset(0f, 70f), home, 64f))
        assertFalse(shouldDismissPreview(Offset(0f, 50f), home, 64f))
        assertFalse(shouldDismissPreview(Offset(-10f, -20f), home, 64f))
        // 0.4 * |home| along home = 89.4 px
        assertTrue(shouldDismissPreview(Offset(-45f, -90f), home, 64f))
    }
}
