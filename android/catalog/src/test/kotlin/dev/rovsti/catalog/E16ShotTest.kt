package dev.rovsti.catalog

import dev.rovsti.testing.ShotTest
import dev.rovsti.testing.ShotVariant
import org.junit.Test

/** The E1.6 screenshots: buttons, rows, grouped sections, avatars, chips, empty states (each light and dark). */
class E16ShotTest : ShotTest() {
    @Test
    fun buttons() {
        shot("e1.6-buttons") { CatalogApp(initialPage = CatalogPage.Buttons) }
    }

    @Test
    fun rows() {
        shot("e1.6-rows", variants = setOf(ShotVariant.Fs2)) { CatalogApp(initialPage = CatalogPage.Rows) }
    }

    @Test
    fun groupedSection() {
        shot("e1.6-grouped-section") { CatalogApp(initialPage = CatalogPage.GroupedSection) }
    }

    @Test
    fun avatars() {
        shot("e1.6-avatars") { CatalogApp(initialPage = CatalogPage.Avatars) }
    }

    @Test
    fun chipRowOverflowing() {
        shot("e1.6-chip-row-overflowing") { CatalogApp(initialPage = CatalogPage.Chips) }
    }

    @Test
    fun emptyState() {
        shot("e1.6-empty-state") { CatalogApp(initialPage = CatalogPage.EmptyState) }
    }
}
