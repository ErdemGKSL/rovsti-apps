package dev.rovsti.shell.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pure JVM: the parser, and the real `THIRD_PARTY_NOTICES.md` it has to keep honest. */
class NoticesParserTest {
    private val real: Notices by lazy {
        // Gradle runs unit tests with the module directory as the working directory.
        NoticesParser.parse(File("../THIRD_PARTY_NOTICES.md").readText())
    }

    @Test fun everyProjectInTheRealFileHasALicenceLine() {
        assertTrue("projects: ${real.projects.map { it.name }}", real.projects.size >= 8)
        for (p in real.projects) assertTrue("${p.name} has an empty licence", p.licence.isNotBlank())
    }

    @Test fun theNamedProjectsAreThereWithTheirLicences() {
        val byName = real.projects.associate { it.name to it.licence }
        assertEquals("MIT", byName["glisse"])
        assertEquals("GPL-3.0", byName["Convx"])
        assertEquals("Apache-2.0", byName["Kyant0/backdrop v2.0.0"])
        assertEquals("Apache-2.0", byName["Haze"])
        assertEquals("Apache-2.0", byName["compose-floating-tab-bar v1.0.1"])
    }

    @Test fun theApacheTextIsALicenceTextNotAProject() {
        assertTrue(real.projects.none { it.name.startsWith("Apache License") })
        assertEquals(listOf("Apache License 2.0"), real.texts.map { it.name })
        val haze = real.entry("haze")!!
        assertNotNull(real.licenceText(haze))
        assertNull(real.licenceText(real.entry("glisse")!!))
        assertNull(real.licenceText(real.entry("convx")!!))
    }

    @Test fun theConvxSectionCountsItsSettingsFiles() {
        val convx = real.entry("convx")!!
        assertTrue(convx.body.any { "IosSettingsGroup.kt" in it })
        assertTrue("sources: ${convx.sources}", convx.sources >= 3)
    }

    @Test fun glisseKeepsItsFullMitText() {
        val glisse = real.entry("glisse")!!
        val code = NoticesParser.blocks(glisse.body).filterIsInstance<NoticeBlock.Code>().single()
        assertTrue(code.lines.any { it.startsWith("Copyright (c) 2026 Kelvin Zhang") })
        assertTrue(code.lines.first() == "MIT License")
    }

    @Test fun licenceNamesAreCutAtTheirEnd() {
        val md =
            """
            ## A
            - Licence: Apache-2.0. Copyright X
            ## B
            - Licence: GPL-3.0, see below
            ## C
            Licence: MIT (text below)
            """.trimIndent()
        assertEquals(listOf("Apache-2.0", "GPL-3.0", "MIT"), NoticesParser.parse(md).projects.map { it.licence })
    }

    @Test fun aSectionWithoutALicenceLineAndWithoutATextIsRejected() {
        val md = "## Broken\n- Source: somewhere\n"
        val error = runCatching { NoticesParser.parse(md) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException && "Broken" in error.message.orEmpty())
    }

    @Test fun aHeadingInsideACodeFenceDoesNotStartASection() {
        val md = "## P\n- Licence: MIT\n\n```\n## not a heading\n```\n"
        val notices = NoticesParser.parse(md)
        assertEquals(1, notices.projects.size)
        assertTrue(
            notices.projects
                .single()
                .body
                .contains("## not a heading"),
        )
    }

    @Test fun blocksJoinContinuationLinesAndStripBackticks() {
        val blocks =
            NoticesParser.blocks(
                listOf("- Used for: `a/b.kt`,", "  and more.", "", "Plain paragraph", "```", "x", "```"),
            )
        assertEquals(
            listOf(
                NoticeBlock.Bullet("Used for: a/b.kt, and more."),
                NoticeBlock.Paragraph("Plain paragraph"),
                NoticeBlock.Code(listOf("x")),
            ),
            blocks,
        )
    }

    @Test fun paragraphsSplitOnBlankLinesAndJoinHardWraps() {
        assertEquals(
            listOf("one two", "three"),
            NoticesParser.paragraphs(listOf("  one", "two", "", "", "three", "")),
        )
    }

    @Test fun idsAreStable() {
        assertEquals(
            "kyant0-androidliquidglass-kmp-branch",
            NoticesParser.idOf("Kyant0/AndroidLiquidGlass (kmp branch)"),
        )
    }
}
