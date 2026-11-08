package dev.rovsti.catalog

import androidx.compose.ui.test.onNodeWithTag
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** AC4: on an 840 dp wide window the scaffold's content is one centred column of at most 640 dp. */
@Config(qualifiers = "w840dp-h1200dp-xxhdpi")
class ScaffoldWideTest : ShotTest() {
    @Test
    fun contentIsAtMostSixHundredFortyDpAndCentred() {
        composeFrame { LargeTitlePage() }
        val density = compose.activity.resources.displayMetrics.density
        val root = compose.onNodeWithTag("large-title-page").bounds
        val list = compose.onNodeWithTag("scaffold-list").bounds
        assertEquals(840f * density, root.width, 2f)
        assertTrue("list is ${list.width / density} dp wide", list.width <= 640f * density + 1f)
        assertEquals("centred", root.center.x, list.center.x, 2f)
        val bar = compose.onNodeWithTag("title-bar").bounds
        assertTrue("bar is ${bar.width / density} dp wide", bar.width <= 640f * density + 1f)
    }
}
