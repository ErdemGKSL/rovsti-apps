package dev.rovsti.phone.details

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner rule (c): the details sheets pad by the keyboard only through `imeOverlapPadding`. `imePadding()` and
 * `windowInsetsPadding(WindowInsets.ime)` double-pad on phones whose system also resizes the window.
 */
class ImeRuleTest {
    @Test fun noDetailsSourceUsesImePaddingOrTheImeInsetDirectly() {
        val dir = File("src/main/kotlin/dev/rovsti/phone/details")
        val sources = dir.listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue("the details sources are found from ${File(".").absolutePath}", sources.isNotEmpty())
        for (file in sources) {
            val text =
                file
                    .readText()
                    .lines()
                    .filterNot {
                        it.trimStart().startsWith("*") ||
                            it.trimStart().startsWith("//")
                    }.joinToString("\n")
            val bad =
                Regex(
                    """(?<!overlap)imePadding\(|WindowInsets\.ime""",
                ).find(text.replace("imeOverlapPadding", ""))
            assertTrue("${file.name} uses ${bad?.value}", bad == null)
        }
        val call = File(dir, "CallDetailsSheet.kt").readText()
        assertTrue("the note column pads by the keyboard overlap", call.contains(".imeOverlapPadding()"))
    }
}
