package dev.rovsti.core.log

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [LogMask] against the cases `rovsti_core::redact` passes too (one fixture, two implementations). */
class LogMaskTest {
    @Serializable
    private data class Case(
        val why: String,
        @SerialName("in") val input: String,
        val out: String,
    )

    private val cases: List<Case> by lazy {
        val path = System.getProperty("rovsti.redact") ?: error("rovsti.redact not set")
        Json.decodeFromString(File(path).readText())
    }

    @Test
    fun everySharedCaseMasksLikeRust() {
        assertTrue(cases.size >= 20)
        for (case in cases) assertEquals(case.why, case.out, LogMask.mask(case.input))
    }

    @Test
    fun maskingTwiceChangesNothing() {
        for (case in cases) assertEquals("${case.why} (twice)", case.out, LogMask.mask(case.out))
    }

    @Test
    fun cleanTextIsTheSameInstance() {
        val clean = "batch stage=lookup n=15 ok"
        assertSame(clean, LogMask.mask(clean))
    }
}
