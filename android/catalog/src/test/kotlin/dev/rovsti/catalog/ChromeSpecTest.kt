package dev.rovsti.catalog

import dev.rovsti.design.transition.TabBarMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The catalog's `chromeFor` is pure, so the tab bar rules are checked without a UI. */
class ChromeSpecTest {
    @Test
    fun tabRootsShowTheBar() {
        assertEquals(TabBarMode.Visible, catalogChromeFor(CatalogRoute.Home).tabBar)
        assertEquals(TabBarMode.Visible, catalogChromeFor(CatalogRoute.Contacts).tabBar)
    }

    @Test
    fun detailKeepsTheBarButSubDetailHidesIt() {
        assertEquals(TabBarMode.Visible, catalogChromeFor(CatalogRoute.ContactDetail(3)).tabBar)
        assertEquals(TabBarMode.Hidden, catalogChromeFor(CatalogRoute.ContactNotes(3)).tabBar)
    }

    @Test
    fun componentPagesHideTheBar() {
        for (page in CatalogPage.entries.filter { it != CatalogPage.Home }) {
            assertEquals("bar on $page", TabBarMode.Hidden, catalogChromeFor(CatalogRoute.Page(page)).tabBar)
        }
    }

    @Test
    fun largeTitlesFollowThePage() {
        assertEquals("Contacts", catalogChromeFor(CatalogRoute.Contacts).largeTitle)
        assertEquals("Contact 07", catalogChromeFor(CatalogRoute.ContactDetail(7)).largeTitle)
        assertNull(catalogChromeFor(CatalogRoute.Page(CatalogPage.Buttons)).largeTitle)
        assertNotNull(catalogChromeFor(CatalogRoute.Home).largeTitle)
    }
}
