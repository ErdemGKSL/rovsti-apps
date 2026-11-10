package dev.rovsti.contacts

import dev.rovsti.design.text.UiText
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Resolves [UiText] to its English text in plain JUnit tests (no Robolectric): resource ids are mapped to names by
 * reflection on the generated `R.string` / `R.plurals` classes, and the text is read from the module's
 * `res/values/strings.xml`. English is the default language, so this is what an English phone shows.
 */
object TestText {
    private val modules = listOf("contacts" to "dev.rovsti.contacts", "design" to "dev.rovsti.design")

    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()
    private val stringIds = HashMap<Int, String>()
    private val pluralIds = HashMap<Int, String>()

    init {
        for ((dir, pkg) in modules) {
            readXml(File(moduleDir(dir), "src/main/res/values/strings.xml"))
            idsOf("$pkg.R\$string", stringIds)
            idsOf("$pkg.R\$plurals", pluralIds)
        }
    }

    private fun moduleDir(name: String): File {
        val here = File(System.getProperty("user.dir"))
        // Gradle runs unit tests in the module directory; fall back to a sibling module.
        return if (here.name == name) here else File(here.parentFile, name)
    }

    private fun idsOf(
        className: String,
        into: MutableMap<Int, String>,
    ) {
        val cls = runCatching { Class.forName(className) }.getOrNull() ?: return
        for (field in cls.fields) {
            if (field.type == Int::class.javaPrimitiveType) into[field.getInt(null)] = field.name
        }
    }

    private fun readXml(file: File) {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> {
                    strings[name] = unescape(node.textContent)
                }

                "plurals" -> {
                    val items = node.getElementsByTagName("item")
                    plurals[name] =
                        (0 until items.length).associate {
                            val item = items.item(it) as Element
                            item.getAttribute("quantity") to unescape(item.textContent)
                        }
                }
            }
        }
    }

    /** Android's string escapes: `\'`, `\"`, `\n`, `\@`, `\?`, `\\`, and the surrounding quotes. */
    private fun unescape(raw: String): String {
        var text = raw.trim()
        if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) text = text.substring(1, text.length - 1)
        return text
            .replace("\\n", "\n")
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\@", "@")
            .replace("\\?", "?")
            .replace("\\\\", "\\")
    }

    fun resolve(text: UiText): String =
        when (text) {
            is UiText.Literal -> {
                text.value
            }

            is UiText.Res -> {
                val name = stringIds[text.id] ?: error("no string resource with id ${text.id}")
                format(strings.getValue(name), text.args.map(::arg))
            }

            is UiText.Plural -> {
                val name = pluralIds[text.id] ?: error("no plurals resource with id ${text.id}")
                val items = plurals.getValue(name)
                val template = (if (text.count == 1) items["one"] else null) ?: items.getValue("other")
                format(template, listOf<Any>(text.count) + text.args.map(::arg))
            }
        }

    private fun arg(value: Any): Any = if (value is UiText) resolve(value) else value

    private fun format(
        template: String,
        args: List<Any>,
    ): String = String.format(Locale.ENGLISH, template, *args.toTypedArray())
}

/** The English text of this value (plain JUnit). */
fun UiText.en(): String = TestText.resolve(this)
