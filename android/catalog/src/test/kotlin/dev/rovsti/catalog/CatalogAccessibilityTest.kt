package dev.rovsti.catalog

import dev.rovsti.testing.ShotTest
import org.junit.Test

/**
 * Accessibility checks (touch target >= 48 dp, speakable label, role on toggles; see `Accessibility.kt` in `:testing`)
 * over every catalog page, light and dark. Findings are fixed in the design system, not suppressed.
 */
class CatalogAccessibilityTest : ShotTest() {
    @Test
    fun everyCatalogPagePassesTheChecks() =
        screenTest {
            val failures = mutableListOf<String>()
            for (page in CatalogPage.entries) {
                for (dark in listOf(false, true)) {
                    try {
                        composeFrame(dark = dark) { CatalogApp(initialPage = page) }
                        advance(600)
                        assertAccessible()
                    } catch (e: IllegalStateException) {
                        failures += "${page.name} dark=$dark: ${e.message}"
                    }
                }
            }
            check(failures.isEmpty()) { failures.joinToString("\n") }
        }
}
