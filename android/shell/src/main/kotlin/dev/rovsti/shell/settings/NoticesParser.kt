package dev.rovsti.shell.settings

/** One `##` section of `THIRD_PARTY_NOTICES.md` that names a project and its licence. */
data class NoticeEntry(
    /** Stable key: the lower-cased title with every run of other characters turned into `-`. */
    val id: String,
    val name: String,
    /** The licence short name, e.g. `GPL-3.0`, `Apache-2.0`, `MIT`. */
    val licence: String,
    /** How many source files or paths the section names between backticks. */
    val sources: Int,
    /** The section text without its heading, line by line. */
    val body: List<String>,
)

/** A `##` section that is only the full text of a licence (the Apache licence). */
data class NoticeText(
    val name: String,
    val body: List<String>,
)

/** The parsed notices: projects to list and licence texts they refer to. */
data class Notices(
    val projects: List<NoticeEntry>,
    val texts: List<NoticeText>,
) {
    fun entry(id: String): NoticeEntry? = projects.firstOrNull { it.id == id }

    /** The licence text a detail page appends under a project: the Apache section for Apache projects, else none here. */
    fun licenceText(entry: NoticeEntry): List<String>? =
        texts.firstOrNull { it.name.startsWith(entry.licence.substringBefore('-'), ignoreCase = true) }?.body
}

/** A block of a section body, for display. */
sealed interface NoticeBlock {
    data class Paragraph(
        val text: String,
    ) : NoticeBlock

    data class Bullet(
        val text: String,
    ) : NoticeBlock

    /** A fenced block: shown monospaced, line breaks kept. */
    data class Code(
        val lines: List<String>,
    ) : NoticeBlock
}

/**
 * Reads `THIRD_PARTY_NOTICES.md` (ORIGINAL). Pure: no Android, no files. A `##` section is a project when it carries a
 * `Licence:` / `License:` statement, and a licence text when it has a fenced block but no such statement (the Apache
 * licence). Anything else is a malformed notice and throws, which is what `NoticesParserTest` relies on to keep the file
 * honest: every project in the notices names its licence.
 */
object NoticesParser {
    private val licenceStatement = Regex("""Licen[cs]e:\s*([A-Za-z0-9][A-Za-z0-9.+\-]*)""")
    private val backtick = Regex("`([^`]+)`")

    fun parse(markdown: String): Notices {
        val projects = ArrayList<NoticeEntry>()
        val texts = ArrayList<NoticeText>()
        for ((title, body) in sections(markdown)) {
            val licence =
                licenceStatement
                    .find(body.joinToString("\n"))
                    ?.groupValues
                    ?.get(1)
                    ?.trimEnd('.', ',')
            when {
                licence != null -> {
                    projects += NoticeEntry(idOf(title), title, licence, sourcesIn(body), body)
                }

                body.any { it.trimStart().startsWith("```") } -> {
                    texts += NoticeText(title, body)
                }

                else -> {
                    throw IllegalArgumentException("Notice '$title' has no 'Licence:' line and is not a licence text")
                }
            }
        }
        return Notices(projects, texts)
    }

    /** The paragraphs, bullets (continuation lines joined) and fenced blocks of a section body. */
    fun blocks(body: List<String>): List<NoticeBlock> {
        val out = ArrayList<NoticeBlock>()
        var i = 0
        while (i < body.size) {
            val line = body[i]
            when {
                line.isBlank() -> {
                    i++
                }

                line.trimStart().startsWith("```") -> {
                    val code = ArrayList<String>()
                    i++
                    while (i < body.size && !body[i].trimStart().startsWith("```")) code += body[i++]
                    i++
                    out += NoticeBlock.Code(code)
                }

                else -> {
                    val bullet = line.startsWith("- ")
                    val text = StringBuilder(if (bullet) line.drop(2).trim() else line.trim())
                    i++
                    while (i < body.size && body[i].isNotBlank() && !body[i].startsWith("- ") &&
                        !body[i].trimStart().startsWith("```")
                    ) {
                        text.append(' ').append(body[i].trim())
                        i++
                    }
                    val plain = text.toString().replace("`", "")
                    out += if (bullet) NoticeBlock.Bullet(plain) else NoticeBlock.Paragraph(plain)
                }
            }
        }
        return out
    }

    /** A licence text as paragraphs: blank lines separate them and the hard line breaks inside one become spaces. */
    fun paragraphs(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        val current = ArrayList<String>()

        fun flush() {
            if (current.isNotEmpty()) out += current.joinToString(" ")
            current.clear()
        }
        for (line in lines) {
            if (line.isBlank()) flush() else current += line.trim()
        }
        flush()
        return out
    }

    private fun sections(markdown: String): List<Pair<String, List<String>>> {
        val out = ArrayList<Pair<String, List<String>>>()
        var title: String? = null
        var body = ArrayList<String>()
        var fenced = false
        for (line in markdown.lines()) {
            if (line.trimStart().startsWith("```")) fenced = !fenced
            if (!fenced && line.startsWith("## ")) {
                title?.let { out += it to trimmed(body) }
                title = line.removePrefix("## ").trim()
                body = ArrayList()
            } else if (title != null) {
                body += line
            }
        }
        title?.let { out += it to trimmed(body) }
        return out
    }

    private fun trimmed(lines: List<String>): List<String> =
        lines.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }

    private fun sourcesIn(body: List<String>): Int =
        body
            .flatMap { line -> backtick.findAll(line).map { it.groupValues[1] }.toList() }
            .filter { '/' in it || it.endsWith(".kt") }
            .toSet()
            .size

    internal fun idOf(title: String): String = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
