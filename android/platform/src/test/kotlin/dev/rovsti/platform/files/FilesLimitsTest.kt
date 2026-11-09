package dev.rovsti.platform.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FilesLimitsTest {
    private fun bytes(n: Int) = ByteArray(n) { 'a'.code.toByte() }

    private fun copy(
        n: Int,
        max: Int,
    ): Pair<Boolean, Int> {
        val out = ByteArrayOutputStream()
        val ok = Documents.copyLimited(ByteArrayInputStream(bytes(n)), out, max)
        return ok to out.size()
    }

    @Test fun theConstantsAreTodaysValues() {
        assertEquals(256 * 1024, Documents.MAX_BYTES)
        assertEquals(64 * 1024 * 1024, Documents.MAX_FILE_BYTES)
        assertEquals(20 * 1024 * 1024, Documents.MAX_PHOTO_BYTES)
        assertEquals(8 * 1024 * 1024, Documents.MAX_CONTACTS_BYTES)
    }

    @Test fun copyLimitedAtTheEdges() {
        val max = 100_000
        assertEquals(true to max - 1, copy(max - 1, max))
        assertEquals(true to max, copy(max, max))
        assertFalse(copy(max + 1, max).first)
        assertEquals(true to 0, copy(0, max))
    }

    @Test fun copyLimitedAcrossBufferBoundaries() {
        val buf = 64 * 1024
        assertTrue(copy(buf, buf).first)
        assertFalse(copy(buf + 1, buf).first)
        assertTrue(copy(buf * 2, buf * 2).first)
    }

    @Test fun readLimitedAtTheEdges() {
        val max = Documents.MAX_BYTES
        assertEquals(max - 1, Documents.readLimited(ByteArrayInputStream(bytes(max - 1)))!!.length)
        assertEquals(max, Documents.readLimited(ByteArrayInputStream(bytes(max)))!!.length)
        assertNull(Documents.readLimited(ByteArrayInputStream(bytes(max + 1))))
        assertNotNull(Documents.readLimited(ByteArrayInputStream(ByteArray(0))))
        assertNull(Documents.readLimited(ByteArrayInputStream(bytes(11)), 10))
    }

    @Test fun readLimitedDecodesUtf8() =
        assertEquals("çağrı ✓", Documents.readLimited(ByteArrayInputStream("çağrı ✓".toByteArray())))

    @Test fun fileCapCases() {
        assertEquals(Documents.MAX_FILE_BYTES, Documents.fileCap(0))
        assertEquals(Documents.MAX_FILE_BYTES, Documents.fileCap(-5))
        assertEquals(1_000, Documents.fileCap(1_000))
        assertEquals(Documents.MAX_FILE_BYTES, Documents.fileCap(Documents.MAX_FILE_BYTES))
        assertEquals(Documents.MAX_FILE_BYTES, Documents.fileCap(Int.MAX_VALUE))
    }

    @Test fun kindCaps() {
        assertEquals(Documents.MAX_BYTES, ImportKind.Json.cap(0))
        assertEquals(Documents.MAX_CONTACTS_BYTES, ImportKind.Contacts.cap(0))
        assertEquals(Documents.MAX_FILE_BYTES, ImportKind.Attachment.cap(0))
        assertEquals(500, ImportKind.Gtfs.cap(500))
        assertEquals(Documents.MAX_FILE_BYTES, ImportKind.Gtfs.cap(Int.MAX_VALUE))
    }

    @Test fun cameraNameIsClampedAndMatchesTheWritablePattern() {
        assertEquals("camera-1700000000000.jpg", Documents.cameraName(1_700_000_000_000))
        assertEquals("camera-0.jpg", Documents.cameraName(-9))
        assertTrue(MediaShare.writable(Documents.cameraName(Long.MAX_VALUE)))
    }
}
