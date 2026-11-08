package dev.rovsti.design.conventions

/** One rule hit: [rule] id, repo-relative [path] (below `android/`), 1-based [line] and the offending source line. */
data class Violation(
    val rule: String,
    val path: String,
    val line: Int,
    val text: String,
) {
    override fun toString() = "$rule: $path:$line  ${text.trim()}"
}

/**
 * The source rules of ARCH 4.3 / E1.21 (ORIGINAL). Pure functions over `(path, source)` so every rule is tested with a
 * positive and a negative fixture before it is trusted on the real tree (see `ConventionTest`).
 *
 * Paths are relative to `android/`, e.g. `design/src/main/kotlin/dev/rovsti/design/overlay/Scrim.kt`; the module is the
 * first segment. Comments are blanked out first, so KDoc that names a banned API (as this file does) is not a hit.
 *
 * Rules:
 *  - `material3`: no `androidx.compose.material3` / `androidx.compose.material` import anywhere (ported Convx files
 *    included: `ios.compat` was replaced instead of kept).
 *  - `composed`: no `composed {` modifiers; use `Modifier.Node` elements.
 *  - `window`: `Popup(` / `Dialog(` / `AlertDialog(` and the `androidx.compose.ui.window` imports live only under
 *    `design/.../overlay/` (everything else shows overlays through `OverlayHost`).
 *  - `flowrow`: no `FlowRow` / `ContextualFlowRow` (chips scroll in a single row; wrapping chips were an owner complaint).
 *  - `rail-scroll`: no `animateScrollToItem` in rail / scrubber / scroll-bar files (a scrub must jump, not animate).
 *  - `sleep`: no `Thread.sleep` in main code.
 *  - `wall-clock`: no `System.currentTimeMillis()`, `Instant.now()`, `LocalDate(Time).now()`, `ZonedDateTime.now()`,
 *    `Calendar.getInstance()` outside `design/.../time/` (time comes from the injected `RovstiClock`). `System.nanoTime()`
 *    is monotonic, not wall clock, and the Convx freeze windows depend on it, so it is not covered.
 *  - App modules only (`contacts`, `phone`, `messages`, `stops`, `shell`; none exist yet): `raw-dp` (a numeric `.dp`
 *    literal outside `Spacing`/`Sizes` objects and `theme/`) and `literal-text` (a string literal passed to `Text(`,
 *    `RovstiText(` or `contentDescription =`; text comes from `strings.xml` or Rust sentences).
 */
object ConventionRules {
    val appModules = setOf("contacts", "phone", "messages", "stops", "shell")

    /** `rule@path` -> reason. Empty on purpose: existing hits are fixed, not allow-listed. */
    val allowList: Map<String, String> = emptyMap()

    fun scan(
        path: String,
        source: String,
    ): List<Violation> {
        val lines = blankComments(source).lines()
        val original = source.lines()
        val module = path.substringBefore('/')
        val out = mutableListOf<Violation>()

        fun hit(
            rule: String,
            index: Int,
        ) {
            if ("$rule@$path" !in allowList) out += Violation(rule, path, index + 1, original.getOrElse(index) { "" })
        }

        val inOverlay = "/design/overlay/" in "/$path"
        val inTime = "/design/time/" in "/$path"
        val fileName = path.substringAfterLast('/')
        val isRail = RailFile.containsMatchIn(fileName)
        val isDpHome = fileName in setOf("Spacing.kt", "Sizes.kt") || "/theme/" in path

        lines.forEachIndexed { i, line ->
            if (Material.containsMatchIn(line)) hit("material3", i)
            if (Composed.containsMatchIn(line)) hit("composed", i)
            if (!inOverlay && Window.containsMatchIn(line)) hit("window", i)
            if (FlowRow.containsMatchIn(line)) hit("flowrow", i)
            if (isRail && line.contains("animateScrollToItem")) hit("rail-scroll", i)
            if (Sleep.containsMatchIn(line)) hit("sleep", i)
            if (!inTime && WallClock.containsMatchIn(line)) hit("wall-clock", i)
            if (module in appModules) {
                if (!isDpHome && RawDp.containsMatchIn(line)) hit("raw-dp", i)
                if (LiteralText.containsMatchIn(line)) hit("literal-text", i)
            }
        }
        return out
    }

    private val Material = Regex("""import\s+androidx\.compose\.material3?\b""")
    private val Composed = Regex("""\bcomposed\s*\{|import\s+androidx\.compose\.ui\.composed\b""")
    private val Window =
        Regex("""(?<![\w.])(Popup|Dialog|AlertDialog)\s*\(|import\s+androidx\.compose\.ui\.window\.(Popup|Dialog)\b""")
    private val FlowRow = Regex("""\b(Contextual)?FlowRow\b""")
    private val RailFile = Regex("""(?i)(rail|scrubber|scrollbar)""")
    private val Sleep = Regex("""\bThread\s*\.\s*sleep\s*\(""")
    private val WallClock =
        Regex(
            """System\s*\.\s*currentTimeMillis\s*\(|\bInstant\s*\.\s*now\s*\(|\b(LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime)\s*\.\s*now\s*\(|\bCalendar\s*\.\s*getInstance\s*\(""",
        )
    private val RawDp = Regex("""(?<![\w.])\d+(\.\d+)?f?\.dp\b""")
    private val LiteralText = Regex("""\b(RovstiText|Text)\s*\(\s*(text\s*=\s*)?"[^"]|contentDescription\s*=\s*"[^"]""")

    /**
     * Replaces `//` and block comments by spaces (newlines kept so line numbers hold). String and char literals are
     * skipped, so a `"//"` inside a string does not start a comment. Raw strings are handled; templates are not parsed.
     */
    fun blankComments(source: String): String {
        val sb = StringBuilder(source.length)
        var i = 0
        val n = source.length

        fun blank(c: Char) = if (c == '\n') '\n' else ' '
        while (i < n) {
            val c = source[i]
            val next = if (i + 1 < n) source[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> {
                    while (i < n && source[i] != '\n') sb.append(' ').also { i++ }
                }

                c == '/' && next == '*' -> {
                    var depth = 0
                    while (i < n) {
                        if (source.startsWith("/*", i)) {
                            depth++
                            sb.append("  ")
                            i += 2
                        } else if (source.startsWith("*/", i)) {
                            depth--
                            sb.append("  ")
                            i += 2
                            if (depth == 0) break
                        } else {
                            sb.append(blank(source[i]))
                            i++
                        }
                    }
                }

                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) n else it + 3 }
                    sb.append(source, i, end)
                    i = end
                }

                c == '"' || c == '\'' -> {
                    sb.append(c)
                    i++
                    while (i < n && source[i] != c && source[i] != '\n') {
                        if (source[i] == '\\' && i + 1 < n) {
                            sb.append(source[i])
                            i++
                        }
                        sb.append(source[i])
                        i++
                    }
                    if (i < n && source[i] == c) {
                        sb.append(c)
                        i++
                    }
                }

                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }
}
