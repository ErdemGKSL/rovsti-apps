package dev.rovsti.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rule 3 of the E3 plan: `:platform` has no JNI and no Rust-calls-Kotlin direction. Native work goes through
 * `:core-bridge` only, so a drift here is a build failure, not a review comment.
 */
class PlatformConventionTest {
    private val sources = File("src/main").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test fun thereAreSourcesToScan() = assertTrue(sources.size >= 8)

    private fun offenders(pattern: Regex) =
        sources.filter { pattern.containsMatchIn(it.readText()) }.map { it.relativeTo(File("src/main")).path }

    @Test fun noExternalFun() = assertEquals(emptyList<String>(), offenders(Regex("""\bexternal\s+fun\b""")))

    @Test fun noLoadLibrary() =
        assertEquals(
            emptyList<String>(),
            offenders(Regex("""System\s*\.\s*loadLibrary|Runtime\s*\.\s*getRuntime\(\)\s*\.\s*loadLibrary""")),
        )

    @Test fun noRovstiBridge() = assertEquals(emptyList<String>(), offenders(Regex("""\bRovstiBridge\b""")))

    @Test fun noNativeHeadless() = assertEquals(emptyList<String>(), offenders(Regex("""\bnativeHeadless\b""")))

    /**
     * No app class, import or package. The intent actions the call notifications and receivers keep
     * (`dev.rovsti.phone.END_CALL`, an ALL_CAPS token after the app name) are strings, not references.
     */
    @Test fun noAppPackageIsReferenced() =
        assertEquals(
            emptyList<String>(),
            offenders(Regex("""dev\.rovsti\.(phone|messages|stops|contacts)\.(?![A-Z][A-Z_]*\b)""")),
        )

    @Test fun noDevRovstiPhoneImportOrPackage() =
        assertEquals(
            emptyList<String>(),
            offenders(Regex("""^\s*(import|package)\s+dev\.rovsti\.phone\b""", RegexOption.MULTILINE)),
        )

    /** E3.5: the call registry reaches the app only through `PlatformComponents` and `InCallHost`. */
    @Test fun theCallRegistryNamesNoAppClass() {
        val registry = File("src/main/kotlin/dev/rovsti/platform/telephony/CallRegistry.kt").readText()
        assertTrue(registry.contains("InCallHost"))
        assertEquals(
            emptyList<String>(),
            Regex("""\b(MainActivity|CallActionReceiver|RovstiInCallService|IncomingPerf|NativeIncoming)\b""")
                .findAll(
                    registry
                        .lines()
                        .filterNot {
                            it.trimStart().startsWith("*") || it.trimStart().startsWith("//")
                        }.joinToString("\n"),
                ).map { it.value }
                .toList(),
        )
    }

    @Test fun noMailboxIsPorted() =
        assertEquals(emptyList<String>(), offenders(Regex("""\bobject Mailbox\b|\bclass Mailbox\b""")))
}
