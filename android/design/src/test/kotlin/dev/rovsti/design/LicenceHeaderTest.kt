package dev.rovsti.design

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Enforces the attribution rule of the GPL-3.0-or-later relicensing (CLAUDE.md, SKILL.md section 0):
 * files ported from Convx carry `Ported from Convx (...), GPL-3.0, <path>`, files ported from glisse carry the exact
 * glisse line. Passes while no such file exists.
 */
class LicenceHeaderTest {
    private val convxLine =
        Regex(
            """Ported from Convx \(https://github\.com/cosmictaserdev-creator/Convx\), GPL-3\.0, \S+""",
        )
    private val glisseLine =
        "Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang"

    private fun androidRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists() && File(dir, "design").isDirectory) return dir
            dir = dir.parentFile
        }
        error("android/ root not found from ${File("").absolutePath}")
    }

    private fun sources(): List<File> =
        androidRoot().listFiles { f -> f.isDirectory }.orEmpty().flatMap { module ->
            File(module, "src")
                .walkTopDown()
                .filter {
                    it.isFile && (it.extension == "kt" || it.extension == "agsl")
                }.toList()
        }

    private fun head(
        file: File,
        lines: Int,
    ): String = file.useLines { it.take(lines).joinToString("\n") }

    private fun inPortedPackage(file: File): Boolean {
        val p = file.invariantSeparatorsPath
        return "/src/main/kotlin/dev/rovsti/design/backdrop/" in p ||
            "/src/main/kotlin/dev/rovsti/design/shapes/" in p ||
            "/src/main/kotlin/dev/rovsti/design/ios/" in p
    }

    @Test
    fun convxFilesCarryThePortedFromLine() {
        val bad =
            sources().filter { f ->
                f.name != "LicenceHeaderTest.kt" && (inPortedPackage(f) || "Convx" in head(f, 20)) &&
                    !convxLine.containsMatchIn(f.readText())
            }
        assertTrue("Missing 'Ported from Convx' header: ${bad.map { it.path }}", bad.isEmpty())
    }

    @Test
    fun glisseFilesCarryTheExactGlisseLine() {
        val bad =
            sources().filter { f ->
                f.name != "LicenceHeaderTest.kt" && "glisse" in head(f, 5) && glisseLine !in f.readText()
            }
        assertTrue("Missing glisse header: ${bad.map { it.path }}", bad.isEmpty())
    }
}
