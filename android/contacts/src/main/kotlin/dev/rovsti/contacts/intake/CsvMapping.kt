package dev.rovsti.contacts.intake

import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import dev.rovsti.contacts.R
import dev.rovsti.design.text.UiText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * What a column of an imported CSV holds, as far as the mapping sheet lets the person say it (E4.10 C39). The Rust
 * reader (`contacts.csv_import`) already knows Google and Outlook headers; this is the way out for a file whose headers
 * it does not know.
 */
enum class CsvRole(
    val label: UiText,
) {
    FirstName(UiText.Res(R.string.contacts_first_name_field)),
    LastName(UiText.Res(R.string.contacts_last_name_field)),
    Phone(UiText.Res(R.string.contacts_phone)),
    Email(UiText.Res(R.string.contacts_email)),
    Ignore(UiText.Res(R.string.contacts_ignore)),
}

/**
 * One column of the file as the sheet lists it: its [header], the first value below it ([sample]) and the role the
 * person gave it ([chosen]; null while they have not touched it, so the Rust reader keeps its own reading).
 * [detected] is the role the header name suggests; it is only what the sheet shows for an untouched column.
 */
@Immutable
data class CsvColumn(
    val index: Int,
    val header: String,
    val sample: String,
    val detected: CsvRole?,
    val chosen: CsvRole?,
) {
    /** The role the sheet shows: the person's choice, else the guess, else null (read as the file says). */
    val shown: CsvRole? get() = chosen ?: detected
}

/** The state of the mapping sheet (the page is in [IntakePhase.Mapping]). */
@Immutable
data class CsvMappingUi(
    val columns: ImmutableList<CsvColumn>,
    /** "12 contacts found" for the mapping as it stands now. */
    val countLine: UiText,
    val canContinue: Boolean,
)

/** U+FEFF, which a CSV written by Excel or by Rovsti starts with. */
internal val BYTE_ORDER_MARK: Char = 0xFEFF.toChar()

/** The header row and the first data row of a CSV text, and where the header row ends. */
internal class CsvHead(
    val header: List<String>,
    val sample: List<String>,
    /** Index in the text after the header row's line break. */
    val bodyStart: Int,
)

/** A small RFC 4180 reader for the first two rows; the Rust reader (`contacts.csv_import`) reads the whole file. */
internal object CsvText {
    private val BOM = BYTE_ORDER_MARK

    fun head(text: String): CsvHead? {
        val first = record(text, if (text.startsWith(BOM)) 1 else 0) ?: return null
        var at = first.second
        var sample: List<String> = emptyList()
        while (at < text.length) {
            val next = record(text, at) ?: break
            at = next.second
            if (next.first.any { it.isNotBlank() }) {
                sample = next.first
                break
            }
        }
        return CsvHead(first.first, sample, first.second)
    }

    /** The cells of the record that starts at [from] and the index after its line break; null at the end. */
    private fun record(
        text: String,
        from: Int,
    ): Pair<List<String>, Int>? {
        if (from >= text.length) return null
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = from
        while (i < text.length) {
            val c = text[i]
            i++
            if (quoted) {
                if (c == '"') {
                    if (i < text.length && text[i] == '"') {
                        cell.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                } else {
                    cell.append(c)
                }
                continue
            }
            when {
                c == '"' && cell.isEmpty() -> {
                    quoted = true
                }

                c == ',' -> {
                    cells += cell.toString()
                    cell.setLength(0)
                }

                c == '\n' || c == '\r' -> {
                    if (c == '\r' && i < text.length && text[i] == '\n') i++
                    cells += cell.toString()
                    return cells to i
                }

                else -> {
                    cell.append(c)
                }
            }
        }
        cells += cell.toString()
        return cells to i
    }

    /** `cells` as one CSV row (quoted when needed) with the CRLF the writer uses. */
    fun row(cells: List<String>): String =
        cells.joinToString(",", postfix = "\r\n") { cell ->
            if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"" + cell.replace("\"", "\"\"") + "\""
            } else {
                cell
            }
        }
}

/**
 * The column mapping of one imported file: the columns the sheet lists and the text the Rust reader is given for the
 * mapping as it stands. A column the person did not touch keeps its own header, so a file the reader already
 * understands loses nothing; a column they gave a role gets the header that role has in the Google layout. Pure, and
 * tested on the JVM.
 */
class CsvMapping private constructor(
    private val text: String,
    private val head: CsvHead,
    private val roles: Map<Int, CsvRole>,
) {
    constructor(text: String) : this(text, requireNotNull(CsvText.head(text)) { "empty CSV" }, emptyMap())

    /** A header row with no cell has no columns to list. */
    val isEmpty: Boolean get() = head.header.all { it.isBlank() }

    val columns: ImmutableList<CsvColumn> by lazy {
        head.header
            .mapIndexed { i, name ->
                CsvColumn(
                    i,
                    name.trim(),
                    head.sample
                        .getOrNull(i)
                        .orEmpty()
                        .trim(),
                    detect(name),
                    roles[i],
                )
            }.toImmutableList()
    }

    /** The same file with [role] given to the column at [index]. */
    fun with(
        index: Int,
        role: CsvRole,
    ): CsvMapping = CsvMapping(text, head, roles + (index to role))

    /** The text to give `contacts.csv_import`: the original, or the original with the headers of the chosen roles. */
    fun rewritten(): String {
        if (roles.isEmpty()) return text
        var phone = FIRST_EXTRA
        var email = FIRST_EXTRA
        val header =
            head.header.mapIndexed { i, original ->
                when (roles[i]) {
                    null -> original
                    CsvRole.FirstName -> "First Name"
                    CsvRole.LastName -> "Last Name"
                    CsvRole.Phone -> "Phone ${phone++} - Value"
                    CsvRole.Email -> "E-mail ${email++} - Value"
                    CsvRole.Ignore -> "Ignored"
                }
            }
        return CsvText.row(header) + text.substring(head.bodyStart)
    }

    private fun detect(header: String): CsvRole? {
        val h = header.trim().trimStart(BYTE_ORDER_MARK).lowercase()
        return when {
            h in FIRST -> CsvRole.FirstName
            h in LAST -> CsvRole.LastName
            "phone" in h || h == "mobile" || h == "tel" || h == "telephone" -> CsvRole.Phone
            "e-mail" in h || "email" in h -> CsvRole.Email
            else -> null
        }
    }

    private companion object {
        /** Numbered columns the person adds start high so they never meet the numbers a Google file already uses. */
        const val FIRST_EXTRA = 100
        val FIRST = setOf("first name", "given name", "firstname", "first")
        val LAST = setOf("last name", "family name", "surname", "lastname", "last")
    }
}
