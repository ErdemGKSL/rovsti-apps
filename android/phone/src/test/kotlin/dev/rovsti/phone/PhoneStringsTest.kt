package dev.rovsti.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Phone speaks Turkish (definition of done 6): every string of `values/strings.xml` has a `values-tr` twin with the
 * same placeholders, no Turkish string is left in English, and no screen builds its words from literals.
 */
class PhoneStringsTest {
    private val res = File("src/main/res")

    private fun strings(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val e = nodes.item(it) as Element
            e.getAttribute("name") to e.textContent
        }
    }

    private val placeholder = Regex("%(\\d+\\$)?[sdf]")

    @Test fun everyEnglishStringHasATurkishTwin() {
        val en = strings("values")
        val tr = strings("values-tr")
        assertTrue("English strings are present", en.size > 250)
        assertEquals("missing in values-tr", emptySet<String>(), en.keys - tr.keys)
        assertEquals("only in values-tr", emptySet<String>(), tr.keys - en.keys)
    }

    @Test fun placeholdersMatch() {
        val en = strings("values")
        val tr = strings("values-tr")
        for ((name, text) in en) {
            val want =
                placeholder
                    .findAll(text)
                    .map { it.value }
                    .sorted()
                    .toList()
            val got =
                placeholder
                    .findAll(tr.getValue(name))
                    .map { it.value }
                    .sorted()
                    .toList()
            assertEquals("placeholders of $name", want, got)
        }
    }

    @Test fun turkishIsTranslatedNotCopied() {
        val en = strings("values")
        val tr = strings("values-tr")
        // Words that are the same in both languages by nature (brand names, symbols, shared loanwords).
        val same = setOf("WhatsApp", "vCard", "%1\$s, %2\$s", "Note ✓", "Not ✓", "+E.164")
        val copied =
            en
                .filter { (name, text) ->
                    val turkish = tr.getValue(name)
                    turkish == text && text !in same && text.any { it.isLetter() }
                }.keys
        assertEquals("Turkish text identical to English", emptySet<String>(), copied)
    }

    @Test fun turkishUsesTurkishLetters() {
        val tr = strings("values-tr").values.joinToString(" ")
        assertTrue("dotless i or dotted capital I present", tr.any { it in "ıİşŞğĞçÇöÖüÜ" })
    }

    @Test fun diagnosticsLabelsAreResources() {
        val src = File("src/main/kotlin/dev/rovsti/phone/diagnostics/DiagnosticsViewModel.kt").readText()
        // The labels are in DiagnosticsLabels (defaults for tests); build() and report() use only that object.
        val body = src.substringAfter("private fun build(")
        for (literal in listOf(
            "\"Rust core\"",
            "\"Block guard\"",
            "\"Last ring\"",
            "\"Call events\"",
            "\"not loaded\"",
        )) {
            assertTrue("$literal is hard-coded in build/report", literal !in body)
        }
    }
}
