package dev.rovsti.contacts

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * English is the default language and a Turkish phone shows Turkish: every `<string>` and `<plurals>` of the five
 * modules that carry user-visible text (`res/values/strings.xml`) has an entry in `res/values-tr/strings.xml`, with
 * the same format placeholders, and the Turkish file names nothing English does not. Entries marked
 * `translatable="false"` (brand names, codes) are exempt. Plain JUnit: it reads the XML files, no Android.
 */
class TurkishCoverageTest {
    private val modules = listOf("contacts", "design", "platform", "shell", "wa")

    private class Entry(
        val kind: String,
        val texts: Map<String, String>,
    )

    private fun moduleRes(module: String): File {
        val here = File(System.getProperty("user.dir"))
        val root = if (here.name == "contacts") here.parentFile else here
        return File(root, "$module/src/main/res")
    }

    private fun read(file: File): Map<String, Entry> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.documentElement.childNodes
        val out = LinkedHashMap<String, Entry>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            if (node.getAttribute("translatable") == "false") continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> {
                    out[name] = Entry("string", mapOf("" to node.textContent))
                }

                "plurals" -> {
                    val items = node.getElementsByTagName("item")
                    out[name] =
                        Entry(
                            "plurals",
                            (0 until items.length).associate {
                                val item = items.item(it) as Element
                                item.getAttribute("quantity") to item.textContent
                            },
                        )
                }
            }
        }
        return out
    }

    private fun placeholders(text: String): List<String> =
        Regex("%(\\d+\\$)?[sdf]|%%")
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()

    @Test fun everyEnglishEntryHasATurkishOne() {
        val missing = mutableListOf<String>()
        for (module in modules) {
            val res = moduleRes(module)
            val en = read(File(res, "values/strings.xml"))
            val trFile = File(res, "values-tr/strings.xml")
            assertTrue("$module has no values-tr/strings.xml", trFile.isFile)
            val tr = read(trFile)
            for ((name, entry) in en) {
                if (tr[name]?.kind != entry.kind) missing += "$module: $name"
            }
        }
        assertTrue("No Turkish entry for:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test fun turkishNamesNothingEnglishLacks() {
        val orphans = mutableListOf<String>()
        for (module in modules) {
            val res = moduleRes(module)
            val en = read(File(res, "values/strings.xml"))
            for (name in read(File(res, "values-tr/strings.xml")).keys) {
                if (name !in en) orphans += "$module: $name"
            }
        }
        assertTrue("Turkish entries without an English one:\n" + orphans.joinToString("\n"), orphans.isEmpty())
    }

    @Test fun turkishKeepsTheFormatPlaceholders() {
        val wrong = mutableListOf<String>()
        for (module in modules) {
            val res = moduleRes(module)
            val en = read(File(res, "values/strings.xml"))
            val tr = read(File(res, "values-tr/strings.xml"))
            for ((name, entry) in en) {
                val turkish = tr[name] ?: continue
                val wanted =
                    entry.texts.values
                        .flatMap(::placeholders)
                        .toSet()
                // A plural's "one" may leave the number out ("a day"), so compare what each entry uses as a whole.
                val got =
                    turkish.texts.values
                        .flatMap(::placeholders)
                        .toSet()
                if (wanted != got) wrong += "$module: $name wants $wanted, Turkish has $got"
            }
        }
        assertTrue("Placeholders differ:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test fun turkishPluralsHaveAnOtherForm() {
        val bad = mutableListOf<String>()
        for (module in modules) {
            val tr = read(File(moduleRes(module), "values-tr/strings.xml"))
            for ((name, entry) in tr) {
                if (entry.kind == "plurals" && "other" !in entry.texts) bad += "$module: $name"
            }
        }
        assertTrue("Plurals without an \"other\" item:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
