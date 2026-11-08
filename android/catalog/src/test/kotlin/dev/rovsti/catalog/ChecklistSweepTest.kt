// Rovsti Project (C) 2026, GPL-3.0-or-later. Original code.
package dev.rovsti.catalog

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import dev.rovsti.testing.ShotTest
import org.junit.Test
import java.io.File

/**
 * E1.22 AC3 sweep: every [CatalogPage] at font scale 2.0 (light) and in RTL (dark), with the accessibility checks on.
 * Section B of the checklist asks "nothing clips at font scale 2.0" and "mirrors in RTL" for every page, and only a
 * few pages have goldens for that. The PNGs go to `build/checklist-sweep/` (not goldens, not compared) so the reviewer
 * can open them; the test itself fails on an accessibility finding or a page that does not compose.
 */
class ChecklistSweepTest : ShotTest() {
    private val out = File("build/checklist-sweep").apply { mkdirs() }

    private fun grab(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(
            out,
            "$name.png",
        ).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun everyPageAtFontScaleTwoAndRtl() =
        screenTest {
            val failures = mutableListOf<String>()
            for (page in CatalogPage.entries) {
                for ((tag, fs, rtl, dark) in listOf(Variant("fs2", 2f, false, false), Variant("rtl", 1f, true, true))) {
                    try {
                        composeFrame(dark = dark, fontScale = fs, rtl = rtl) { CatalogApp(initialPage = page) }
                        advance(600)
                        grab("${page.name}-$tag")
                    } catch (e: IllegalStateException) {
                        failures += "${page.name} $tag: ${e.message}"
                    }
                }
            }
            check(failures.isEmpty()) { failures.joinToString("\n") }
        }

    private data class Variant(
        val tag: String,
        val fontScale: Float,
        val rtl: Boolean,
        val dark: Boolean,
    )
}
