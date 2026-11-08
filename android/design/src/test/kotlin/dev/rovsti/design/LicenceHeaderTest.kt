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
            "/src/main/kotlin/dev/rovsti/design/ios/" in p ||
            "/src/main/kotlin/dev/rovsti/design/chrome/floatingtabbar/" in p ||
            p.endsWith("/design/glass/GlassEffect.kt") ||
            p.endsWith("/design/glass/TopFadeBlur.kt") ||
            p.endsWith("/design/list/AlphabetScrollBar.kt") ||
            p.endsWith("/design/transition/NavTransitions.kt") ||
            p.endsWith("/design/transition/SharedBounds.kt") ||
            p.endsWith("/design/transition/ArtworkMotion.kt")
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

    /** Every source that was ported or vendored is named in `THIRD_PARTY_NOTICES.md`, with glisse's full MIT text. */
    @Test
    fun noticesListEverySource() {
        val notices = File(androidRoot(), "THIRD_PARTY_NOTICES.md").readText()
        val required =
            listOf(
                "## Convx",
                "Kyant0/backdrop",
                "Kyant0/AndroidLiquidGlass",
                "Kyant0/Capsule",
                "compose-miuix-ui",
                "compose-hig",
                "compose-floating-tab-bar",
                "## Haze",
                "## glisse",
                "Navigation 3",
                "## Coil",
                "Permission is hereby granted, free of charge",
                "GPL-3.0",
                "Apache License",
            )
        val missing = required.filter { it !in notices }
        assertTrue("THIRD_PARTY_NOTICES.md lacks: $missing", missing.isEmpty())
    }

    /** The relicensing of the whole project: root LICENSE is the GPL-3.0 text and the workspace declares it. */
    @Test
    fun projectIsGplThreeOrLater() {
        val repo = androidRoot().parentFile
        assertTrue("LICENSE is not GPL-3.0", File(repo, "LICENSE").readText().contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(
            "Cargo.toml license",
            File(repo, "Cargo.toml").readText().contains("license = \"GPL-3.0-or-later\""),
        )
    }
}
