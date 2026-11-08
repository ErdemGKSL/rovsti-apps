package dev.rovsti.design.conventions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Convention tests (E1.21 AC2/AC2b). Part one proves the scanner itself with a positive fixture (the rule fires) and a
 * negative fixture (it does not, including the allowed locations and commented-out mentions) for every rule. Part two
 * runs the scanner over every `android/<module>/src/main/kotlin` file; the tree must be clean without allow-list
 * entries (`ConventionRules.allowList` is empty; an entry would need a written reason).
 */
class ConventionTest {
    private val design = "design/src/main/kotlin/dev/rovsti/design"

    private fun rules(
        path: String,
        source: String,
    ) = ConventionRules.scan(path, source).map { it.rule }.distinct()

    private fun fires(
        rule: String,
        source: String,
        path: String = "$design/list/Fixture.kt",
    ) = assertEquals("expected only $rule", listOf(rule), rules(path, source))

    private fun clean(
        source: String,
        path: String = "$design/list/Fixture.kt",
    ) = assertEquals(emptyList<String>(), rules(path, source))

    // material3

    @Test
    fun material3Positive() = fires("material3", "import androidx.compose.material3.Text\n")

    @Test
    fun material3PositiveForOldMaterial() = fires("material3", "import androidx.compose.material.Button\n")

    @Test
    fun material3Negative() =
        clean("import androidx.compose.foundation.layout.Box\n// import androidx.compose.material3.Text\n")

    // composed

    @Test
    fun composedPositive() = fires("composed", "fun Modifier.x() = composed {\n  this\n}\n")

    @Test
    fun composedPositiveForImport() = fires("composed", "import androidx.compose.ui.composed\n")

    @Test
    fun composedNegative() =
        clean("/** Not `composed {` any more. */\nfun Modifier.x() = this.then(Node)\nval decomposed = 1\n")

    // Popup / Dialog

    @Test
    fun popupOutsideOverlayPositive() = fires("window", "@Composable fun F() { Popup(alignment = A) {} }\n")

    @Test
    fun dialogOutsideOverlayPositive() = fires("window", "fun F() { Dialog(onDismissRequest = {}) {} }\n")

    @Test
    fun windowImportOutsideOverlayPositive() = fires("window", "import androidx.compose.ui.window.Popup\n")

    @Test
    fun popupInOverlayNegative() = clean("fun F() { Popup(alignment = A) {} }\n", path = "$design/overlay/Fixture.kt")

    @Test
    fun otherNamesNegative() = clean("fun F() { IosDialog() ; ShowPopupLater() ; x.Dialog(1) }\n")

    // FlowRow

    @Test
    fun flowRowPositive() = fires("flowrow", "FlowRow(Modifier) { chips() }\n", path = "$design/controls/ChipRow.kt")

    @Test
    fun flowRowNegative() =
        clean("Row(Modifier.horizontalScroll(s)) { chips() } // not a FlowRow\n", path = "$design/controls/ChipRow.kt")

    // animateScrollToItem in rails

    @Test
    fun railScrollPositive() =
        fires("rail-scroll", "state.animateScrollToItem(3)\n", path = "$design/list/AlphabetScrollBar.kt")

    @Test
    fun railScrollNegativeScrollToItem() = clean("state.scrollToItem(3)\n", path = "$design/list/AlphabetScrollBar.kt")

    @Test
    fun animateScrollOutsideRailNegative() =
        clean("state.animateScrollToItem(0)\n", path = "catalog/src/main/kotlin/DemoPages.kt")

    // Thread.sleep

    @Test
    fun sleepPositive() = fires("sleep", "fun f() { Thread.sleep(10) }\n")

    @Test
    fun sleepNegative() = clean("suspend fun f() { delay(10) }\n// Thread.sleep(10)\n")

    // wall clock

    @Test
    fun wallClockPositive() {
        fires("wall-clock", "val t = System.currentTimeMillis()\n")
        fires("wall-clock", "val t = Instant.now()\n")
        fires("wall-clock", "val t = LocalDateTime.now()\n")
    }

    @Test
    fun wallClockInTimePackageNegative() =
        clean("override fun nowMillis() = System.currentTimeMillis()\n", path = "$design/time/RovstiClock.kt")

    @Test
    fun monotonicClockNegative() = clean("val t = System.nanoTime()\nval c = clock.nowMillis()\n")

    // app modules: raw dp

    @Test
    fun rawDpInAppModulePositive() =
        fires("raw-dp", "Box(Modifier.padding(12.dp))\n", path = "contacts/src/main/kotlin/dev/rovsti/contacts/List.kt")

    @Test
    fun rawDpInSpacingNegative() =
        clean("object Spacing { val m = 12.dp }\n", path = "contacts/src/main/kotlin/dev/rovsti/contacts/Spacing.kt")

    @Test
    fun rawDpOutsideAppModulesNegative() = clean("val x = 12.dp\n")

    @Test
    fun tokenDpInAppModuleNegative() =
        clean(
            "Box(Modifier.padding(Spacing.m, Sizes.row.dp))\n",
            path = "phone/src/main/kotlin/dev/rovsti/phone/Keypad.kt",
        )

    // app modules: literal text

    @Test
    fun literalTextPositive() {
        val path = "messages/src/main/kotlin/dev/rovsti/messages/Thread.kt"
        fires("literal-text", "RovstiText(\"Hello\")\n", path)
        fires("literal-text", "Text(text = \"Hello\")\n", path)
        fires("literal-text", "Icon(x, contentDescription = \"Back\")\n", path)
    }

    @Test
    fun literalTextNegative() {
        val path = "stops/src/main/kotlin/dev/rovsti/stops/Map.kt"
        clean("RovstiText(stringResource(R.string.hello))\nIcon(x, contentDescription = null)\nval k = \"id\"\n", path)
    }

    @Test
    fun literalTextOutsideAppModulesNegative() =
        clean("RovstiText(\"Catalog\")\n", path = "catalog/src/main/kotlin/A.kt")

    // scanner internals

    @Test
    fun commentsAreBlankedKeepingLines() {
        val src = "a\n/* x\n y */ b // c\n\"//\" d\n"
        val blank = ConventionRules.blankComments(src)
        assertEquals(src.lines().size, blank.lines().size)
        assertTrue(blank.contains("\"//\" d"))
        assertTrue(!blank.contains(" c"))
    }

    @Test
    fun violationsCarryTheLine() {
        val v = ConventionRules.scan("$design/x/F.kt", "ok\nThread.sleep(1)\n").single()
        assertEquals(2, v.line)
    }

    @Test
    fun allowListEntriesNeedAReason() {
        assertTrue(ConventionRules.allowList.all { it.value.isNotBlank() })
        assertTrue("allow-list stays empty unless a hit truly cannot be fixed", ConventionRules.allowList.isEmpty())
    }

    // the real tree

    private fun androidRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists() && File(dir, "design").isDirectory) return dir
            dir = dir.parentFile
        }
        error("android/ root not found from ${File("").absolutePath}")
    }

    @Test
    fun theTreeIsClean() {
        val root = androidRoot()
        val files =
            root
                .listFiles { f -> f.isDirectory }
                .orEmpty()
                .map { File(it, "src/main/kotlin") }
                .filter { it.isDirectory }
                .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue("scanned ${files.size} files", files.size > 100)
        val hits =
            files.flatMap { f ->
                ConventionRules.scan(f.relativeTo(root).invariantSeparatorsPath, f.readText())
            }
        assertEquals("convention violations:\n" + hits.joinToString("\n"), emptyList<Violation>(), hits)
    }
}
