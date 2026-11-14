package dev.rosty.stops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * English is the default and a Turkish phone shows Turkish (owner rule 2026-11-10): every `<string>` and `<plurals>` of
 * the Stops resources has a Turkish entry with the same placeholders, the Turkish files name nothing English does not,
 * and the launcher name is "Stops" / "Duraklar". Plain JUnit over the XML files.
 */
class TurkishCoverageTest {
    private val res: File
        get() {
            val here = File(System.getProperty("user.dir"))
            val module = if (here.name == "stops") here else File(here, "stops")
            return File(module, "src/main/res")
        }

    private fun read(file: File): Map<String, List<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = LinkedHashMap<String, List<String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            if (node.getAttribute("translatable") == "false") continue
            out[node.getAttribute("name")] =
                when (node.tagName) {
                    "plurals" -> {
                        val items = node.getElementsByTagName("item")
                        (0 until items.length).map { items.item(it).textContent }
                    }

                    else -> {
                        listOf(node.textContent)
                    }
                }
        }
        return out
    }

    private fun placeholders(texts: List<String>) =
        texts.flatMap { Regex("%(\\d+\\$)?[sdf]").findAll(it).map { m -> m.value }.toList() }.toSet()

    @Test fun everyStringHasATurkishTwinWithTheSamePlaceholders() {
        for (name in listOf("strings.xml", "launcher.xml")) {
            val en = read(File(res, "values/$name"))
            val tr = read(File(res, "values-tr/$name"))
            assertTrue("$name is empty", en.isNotEmpty())
            assertEquals("$name: the same keys", en.keys, tr.keys)
            for ((key, texts) in en) {
                assertEquals("$name $key placeholders", placeholders(texts), placeholders(tr.getValue(key)))
                assertTrue("$name $key is not translated", tr.getValue(key).none { it.isBlank() })
            }
        }
    }

    @Test fun theLauncherNameIsPlain() {
        assertEquals(listOf("Stops"), read(File(res, "values/launcher.xml"))["launcher_name"])
        assertEquals(listOf("Duraklar"), read(File(res, "values-tr/launcher.xml"))["launcher_name"])
    }
}
