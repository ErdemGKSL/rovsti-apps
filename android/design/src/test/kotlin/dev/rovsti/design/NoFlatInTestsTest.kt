package dev.rovsti.design

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source scan: no test (and no code) may force a flat or fallback glass mode to make a golden pass. The old engine's
 * switches (`forTests`, `GlassTier`, `GlassConditions`, `glassSource(`) are gone; the fallback is exercised only by
 * tests named `*Fallback*`, through `GlassSettings(reduceTransparency = true)`.
 */
class NoFlatInTestsTest {
    private val forbidden =
        listOf(
            "forTests",
            "GlassTier",
            "GlassConditions",
            "glassSource(",
            "rememberGlassSource",
            "LocalGlassConditions",
        )

    private fun androidRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists() && File(dir, "design").isDirectory) return dir
            dir = dir.parentFile
        }
        error("android/ root not found from ${File("").absolutePath}")
    }

    @Test
    fun noFlatForcingAnywhere() {
        val hits =
            androidRoot()
                .listFiles { f -> f.isDirectory }
                .orEmpty()
                .flatMap { module ->
                    File(module, "src").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
                }.filter { it.name != "NoFlatInTestsTest.kt" }
                .flatMap { file ->
                    val text = file.readText()
                    forbidden.filter { it in text }.map { "${file.path}: $it" }
                }
        assertTrue("Flat forcing is gone; found $hits", hits.isEmpty())
    }
}
