package dev.rovsti.catalog

import dev.rovsti.testing.ShotTest
import org.junit.Test
import org.robolectric.annotation.Config

/** Review shots on a 360 x 640 dp window, the smallest phone the apps must hold together on. */
@Config(qualifiers = "w360dp-h640dp-xxhdpi")
class SmallWindowShotTest : ShotTest() {
    @Test
    fun tabBar() {
        shot("e1.7-tabbar-360x640") { TabBarPage(imeVisible = { false }) }
    }

    @Test
    fun title() {
        shot("e1.8-title-360x640") { LargeTitlePage() }
    }

    @Test
    fun rows() {
        shot("e1.6-rows-360x640") { CatalogApp(initialPage = CatalogPage.Rows) }
    }

    @Test
    fun buttons() {
        shot("e1.6-buttons-360x640") { CatalogApp(initialPage = CatalogPage.Buttons) }
    }
}
