package dev.rovsti.core.log

/**
 * Masks what must never reach a log line: connect keys, credentials, phone numbers (bare and inside WhatsApp JIDs),
 * group ids and e-mail addresses. A port of `rovsti_core::redact::mask`, rule for rule; both are checked against
 * `crates/rovsti_core/tests/data/redact_cases.json` (`LogMaskTest`). Idempotent: masking masked text changes nothing.
 *
 * Rules, left to right in one pass (ASCII only; any other character is a separator):
 * - `rwk_<key>` becomes `rwk_***`;
 * - the value after `Authorization:` / `Authorization=` (keeping a `Bearer` or `Basic` scheme) and after `key=` becomes
 *   `***`;
 * - an e-mail address keeps only its domain (`***@example.com`);
 * - a lone run of 7 to 15 digits (an optional `+` before it) keeps its first and last two digits (`90***67`), which
 *   covers user JIDs; a run touching a letter, `_` or another number, and the value of a field whose name ends in `ms`,
 *   is left alone;
 * - a group JID keeps only its suffix (`***@g.us`).
 */
object LogMask {
    private const val STARS = "***"
    private const val MIN_NUMBER = 7
    private const val MAX_NUMBER = 15
    private const val RWK = "rwk_"
    private const val AUTH = "authorization"
    private const val KEY = "key="
    private const val GROUP = "@g.us"
    private val waDomains = setOf("s.whatsapp.net", "g.us", "c.us", "broadcast", "newsletter")

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun Char.isAsciiAlnum() = this in 'a'..'z' || this in 'A'..'Z' || isAsciiDigit()

    private fun Char.isWord() = isAsciiAlnum() || this == '_'

    private fun Char.isLocal() =
        isAsciiAlnum() || this == '.' || this == '_' || this == '%' || this == '+' || this == '-'

    private fun Char.isDomain() = isAsciiAlnum() || this == '.' || this == '-'

    private fun Char.isWhite() = this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000C'

    private fun valueEnd(
        s: String,
        from: Int,
    ): Int {
        var i = from
        while (i < s.length && !s[i].isWhite() && s[i] !in "\"',;&)]}") i++
        return i
    }

    private fun startsWithCi(
        s: String,
        at: Int,
        word: String,
    ): Boolean = s.regionMatches(at, word, 0, word.length, ignoreCase = true)

    private fun endsAt(
        s: String,
        end: Int,
        word: String,
    ): Boolean = end >= word.length && s.regionMatches(end - word.length, word, 0, word.length)

    /** [text] masked; the same instance when nothing needed masking. */
    fun mask(text: String): String {
        val s = text
        val out = StringBuilder(s.length)
        var i = 0
        var copied = 0
        var changed = false

        fun replace(
            upto: Int,
            masked: String,
            resume: Int,
        ) {
            out.append(s, copied, upto).append(masked)
            copied = resume
            i = resume
            changed = true
        }

        while (i < s.length) {
            val prev = if (i == 0) null else s[i - 1]
            val wordStart = prev == null || !prev.isWord()
            // Connect keys.
            if (wordStart && s.startsWith(RWK, i)) {
                var end = i + RWK.length
                while (end < s.length && (s[end].isWord() || s[end] == '-')) end++
                if (end > i + RWK.length) {
                    replace(i + RWK.length, STARS, end)
                    continue
                }
            }
            // Credentials by name.
            val nameStart = prev == null || !prev.isAsciiAlnum()
            if (nameStart && startsWithCi(s, i, AUTH)) {
                var j = i + AUTH.length
                while (j < s.length && s[j] == ' ') j++
                if (j < s.length && (s[j] == ':' || s[j] == '=')) {
                    j++
                    while (j < s.length && s[j] == ' ') j++
                    for (scheme in listOf("bearer ", "basic ")) {
                        if (startsWithCi(s, j, scheme)) {
                            j += scheme.length
                            break
                        }
                    }
                    val end = valueEnd(s, j)
                    if (end > j && s.substring(j, end) != STARS) {
                        replace(j, STARS, end)
                        continue
                    }
                }
            }
            if (nameStart && s.startsWith(KEY, i)) {
                val j = i + KEY.length
                val end = valueEnd(s, j)
                if (end > j && s.substring(j, end) != STARS) {
                    replace(j, STARS, end)
                    continue
                }
            }
            // E-mail addresses (WhatsApp's own domains are JIDs).
            if ((prev == null || !prev.isLocal()) && s[i].isLocal()) {
                var at = i
                while (at < s.length && s[at].isLocal()) at++
                if (at < s.length && s[at] == '@') {
                    var end = at + 1
                    while (end < s.length && s[end].isDomain()) end++
                    while (end > at + 1 && s[end - 1] == '.') end--
                    val domain = s.substring(at + 1, end)
                    val tld = domain.substringAfterLast('.', "")
                    val isMail =
                        domain.contains('.') &&
                            tld.length >= 2 &&
                            tld.all { it in 'a'..'z' || it in 'A'..'Z' } &&
                            domain !in waDomains
                    if (isMail) {
                        replace(i, STARS, at)
                        continue
                    }
                }
            }
            // Numbers, user JIDs and group JIDs.
            val plus = s[i] == '+' && i + 1 < s.length && s[i + 1].isAsciiDigit()
            if ((plus || s[i].isAsciiDigit()) && (prev == null || (!prev.isWord() && prev != '.'))) {
                val digits = if (plus) i + 1 else i
                var end = digits
                while (end < s.length && s[end].isAsciiDigit()) end++
                var groupEnd = end
                if (groupEnd < s.length && s[groupEnd] == '-') {
                    var k = groupEnd + 1
                    while (k < s.length && s[k].isAsciiDigit()) k++
                    if (k > groupEnd + 1) groupEnd = k
                }
                if (!plus && s.startsWith(GROUP, groupEnd)) {
                    replace(i, STARS, groupEnd)
                    continue
                }
                val next = if (end < s.length) s[end] else null
                val touches =
                    next != null &&
                        (next.isWord() || (next == '.' && end + 1 < s.length && s[end + 1].isAsciiDigit()))
                val len = end - digits
                var b = i
                while (b > 0 && s[b - 1] == ' ') b--
                val timestamp = endsAt(s, b, "ms=") || endsAt(s, b, "ms:") || endsAt(s, b, "ms\":")
                if (!touches && len in MIN_NUMBER..MAX_NUMBER && !timestamp) {
                    replace(digits, s.substring(digits, digits + 2) + STARS + s.substring(end - 2, end), end)
                    continue
                }
                // Not a number to mask: skip the whole run, so no inner digit starts one.
                i = end
                continue
            }
            i++
        }
        if (!changed) return text
        out.append(s, copied, s.length)
        return out.toString()
    }
}
