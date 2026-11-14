package dev.rosty.catalog

import dev.rosty.testing.ShotTest
import dev.rosty.testing.ShotVariant
import org.junit.Test

class CatalogHomeShotTest : ShotTest() {
    @Test
    fun home() {
        shot("e1.1-catalog-home") { CatalogApp() }
    }

    /** The toolchain page prints font scale, direction and clock, so every variant is visibly different. */
    @Test
    fun toolchainWithVariants() {
        shot(
            "e1.1-catalog-toolchain",
            variants = setOf(ShotVariant.Fs2, ShotVariant.Rtl, ShotVariant.Landscape),
        ) { CatalogApp(initialPage = CatalogPage.Toolchain) }
    }
}
