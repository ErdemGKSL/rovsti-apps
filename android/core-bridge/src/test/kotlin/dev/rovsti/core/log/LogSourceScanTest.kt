package dev.rovsti.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * People sync v2, section 10: app code logs through [RovstiLog] (masked, in the ring, in the developer-mode file), not
 * `android.util.Log` directly; and the sync and WhatsApp code never hands a name or a message text to the log.
 */
class LogSourceScanTest {
    /** The Android modules, from `android/core-bridge` (the test's working directory). */
    private val modules = File("..").canonicalFile

    private fun mainSources(): List<File> =
        modules
            .listFiles()
            .orEmpty()
            .map { File(it, "src/main") }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }

    @Test fun noDirectLogcatOutsideTheAllowedFiles() {
        val sources = mainSources()
        assertTrue("found the sources", sources.size > 100)
        val direct = Regex("""android\.util\.Log\b|(?<![\w.])Log\.(d|i|w|e|v|wtf|println)\(""")
        val offenders =
            sources
                .filter { it.name !in ALLOWED }
                .filter { file ->
                    file.readLines().any { line ->
                        !line.trimStart().startsWith("*") &&
                            direct.containsMatchIn(line)
                    }
                }.map { it.relativeTo(modules).path }
        assertEquals("use RovstiLog (dev.rovsti.core.log)", emptyList<String>(), offenders)
    }

    @Test fun theSyncAndWhatsappNeverLogNamesOrMessageText() {
        val scoped =
            mainSources().filter { file ->
                val path = file.invariantSeparatorsPath
                "/people/" in path || "/wa/" in path
            }
        assertTrue(scoped.isNotEmpty())
        val call = Regex("""RovstiLog\.\w+\(""")
        val forbidden = Regex("""\b(pushName|verifiedName|displayName|body|text)\b""")
        val offenders =
            scoped.flatMap { file ->
                val text = file.readText()
                call.findAll(text).mapNotNull { match ->
                    val args = argumentsAt(text, match.range.last)
                    if (forbidden.containsMatchIn(args)) "${file.name}: ${args.take(80)}" else null
                }
            }
        assertEquals(emptyList<String>(), offenders)
    }

    /** The text between the `(` at [open] and its matching `)`. */
    private fun argumentsAt(
        text: String,
        open: Int,
    ): String {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(open + 1, i)
            }
        }
        return text.substring(open + 1)
    }

    private companion object {
        val ALLOWED =
            setOf(
                // The facade itself writes logcat.
                "RovstiLog.kt",
                // `scripts/perf-run.sh` reads the `RovstiPerf` report from logcat of a release build.
                "IncomingPerf.kt",
                // Its tests read the `RovstiContacts` logcat tag (ShadowLog).
                "ContactResult.kt",
            )
    }
}
