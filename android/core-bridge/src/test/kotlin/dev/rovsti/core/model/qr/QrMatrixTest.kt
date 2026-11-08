package dev.rovsti.core.model.qr

import dev.rovsti.core.Envelope
import dev.rovsti.core.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [QrMatrix] against the `qr.encode` contract fixture the Rust fixture test wrote: no native library needed. */
class QrMatrixTest {
    private val dir = File(System.getProperty("rovsti.contract") ?: error("rovsti.contract not set"))

    private fun fixture(): QrEncodeAnswer {
        val bytes = File(dir, "qr.encode.answer.json").readBytes()
        return (Envelope.decode(bytes, QrEncodeAnswer.serializer()) as Outcome.Ok).value
    }

    @Test
    fun theFixtureHasTheShapeOfAVersionTwoCode() {
        val m = QrMatrix.fromAnswer(fixture())
        assertEquals(25, m.side)
        assertTrue(m.darkCount in 200..450)
    }

    @Test
    fun threeFinderPatternsSitInTheCorners() {
        val m = QrMatrix.fromAnswer(fixture())
        for ((ox, oy) in listOf(0 to 0, m.side - 7 to 0, 0 to m.side - 7)) {
            for (i in 0 until 7) {
                // The outer ring is dark, the next ring light, the 3x3 core dark.
                assertTrue(m[ox + i, oy] && m[ox + i, oy + 6] && m[ox, oy + i] && m[ox + 6, oy + i])
            }
            assertFalse(m[ox + 1, oy + 1] || m[ox + 5, oy + 5] || m[ox + 1, oy + 5] || m[ox + 5, oy + 1])
            assertTrue(m[ox + 2, oy + 2] && m[ox + 3, oy + 3] && m[ox + 4, oy + 4])
        }
    }

    @Test
    fun theTimingPatternAlternatesAndOutsideIsLight() {
        val m = QrMatrix.fromAnswer(fixture())
        for (i in 8 until m.side - 8) assertEquals(i % 2 == 0, m[i, 6])
        assertFalse(m[-1, 0] || m[0, -1] || m[m.side, 0] || m[0, m.side])
    }

    @Test
    fun lumaHasAQuietZoneAndOneByteAPixel() {
        val m = QrMatrix.fromAnswer(fixture())
        val luma = m.toLuma(pixelsPerModule = 4, quiet = 4)
        val dim = (m.side + 8) * 4
        assertEquals(dim * dim, luma.size)
        assertEquals(0xFF.toByte(), luma[0])
        // The first dark module (top-left finder corner) starts after the quiet zone.
        assertEquals(0.toByte(), luma[16 * dim + 16])
        assertEquals(0xFF.toByte(), luma[15 * dim + 15])
    }

    @Test
    fun aWrongSizeIsRefused() {
        val a = fixture()
        assertThrows(IllegalArgumentException::class.java) { QrMatrix.of(a.side + 1, a.modules) }
        assertThrows(IllegalArgumentException::class.java) { QrMatrix.of(0, a.modules) }
    }
}
