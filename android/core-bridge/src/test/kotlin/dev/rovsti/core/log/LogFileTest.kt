package dev.rovsti.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogFileTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun entry(
        seq: Long,
        text: String,
    ) = LogEntry(seq, 1_731_600_000_000, LogLevel.Warn, "sync", LogSource.Rust, text)

    @Test
    fun linesAreWrittenInOrderWithLevelTagAndSource() {
        val file = LogFile(tmp.root)
        file.write(entry(1, "first"))
        file.write(entry(2, "second\nline"))
        file.flush()
        val lines = file.paths().first().readLines()
        assertEquals(listOf("2024-11-14T16:00:00Z W sync R first", "2024-11-14T16:00:00Z W sync R second line"), lines)
    }

    @Test
    fun rotationKeepsAtMostTwoFilesUnderTheCap() {
        val file = LogFile(tmp.root, maxBytes = 1_000, files = 2)
        repeat(200) { file.write(entry(it.toLong(), "x".repeat(40))) }
        file.flush()
        val (current, rotated) = file.paths()
        assertTrue(current.exists() && rotated.exists())
        assertTrue(current.length() <= 1_000 && rotated.length() <= 1_000)
        assertEquals(2, tmp.root.listFiles()!!.size)
        // The newest line is in the current file.
        assertTrue(current.readText().trimEnd().endsWith("x".repeat(40)))
    }

    @Test
    fun deleteAllRemovesTheFiles() {
        val file = LogFile(tmp.root)
        file.write(entry(1, "a"))
        file.deleteAll()
        file.flush()
        assertFalse(file.paths().any { it.exists() })
    }

    @Test
    fun theFacadeWritesTheFileOnlyWhenOneIsSet() {
        val file = LogFile(tmp.root)
        RovstiLog.i("test", "before")
        RovstiLog.setFile(file)
        try {
            RovstiLog.event("test", "batch", "n" to 3, "jid" to "905321234567@s.whatsapp.net", "none" to null)
        } finally {
            RovstiLog.setFile(null)
        }
        RovstiLog.i("test", "after")
        file.flush()
        val text = file.paths().first().readText()
        assertTrue(text, text.contains("I test K batch n=3 jid=90***67@s.whatsapp.net none=-"))
        assertFalse(text.contains("before") || text.contains("after"))
    }
}
