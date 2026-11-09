package dev.rovsti.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `docs/kotlin-ui/platform-port.md` is the source of truth for deleting the GPUI Kotlin (E8.6). Every file under
 * `crates/rovsti_android/kotlin/dev/rovsti/bridge/` needs a row in its "Bridge files" table, and every row needs a
 * status the plan allows. A new bridge file without a row fails here.
 */
class PortInventoryTest {
    private val doc = File("../../docs/kotlin-ui/platform-port.md")
    private val bridge = File("../../crates/rovsti_android/kotlin/dev/rovsti/bridge")

    private fun bridgeRows(): List<List<String>> {
        val lines = doc.readLines()
        val start = lines.indexOfFirst { it.startsWith("## Bridge files") }
        assertTrue("no 'Bridge files' section", start >= 0)
        return lines
            .drop(start + 1)
            .takeWhile { !it.startsWith("## ") }
            .filter { it.startsWith("| `") }
            .map { row ->
                row
                    .trim()
                    .trim('|')
                    .split('|')
                    .map { it.trim() }
            }
    }

    @Test fun everyBridgeFileHasARow() {
        val files = bridge.listFiles { f -> f.extension == "kt" }!!.map { it.name }.sorted()
        assertTrue("no bridge files found at ${bridge.absolutePath}", files.isNotEmpty())
        val rows = bridgeRows().map { it[0].trim('`') }
        assertEquals(emptyList<String>(), files - rows.toSet())
    }

    @Test fun noRowNamesAFileThatNeverExisted() {
        val files = bridge.listFiles { f -> f.extension == "kt" }!!.map { it.name }.toSet()
        val rows = bridgeRows().map { it[0].trim('`') }
        assertEquals(emptyList<String>(), rows.filterNot { it in files })
        assertEquals("a file has two rows", rows.size, rows.toSet().size)
    }

    @Test fun everyRowHasAnAllowedStatus() {
        val allowed =
            listOf("ported", "dropped: ", "R2 E3.8a", "R2 E3.8b", "R2 E3.9", "app module")
        val bad = bridgeRows().filter { row -> allowed.none { row[3].startsWith(it) } }.map { it[0] }
        assertEquals("status must start with one of $allowed", emptyList<String>(), bad)
    }

    @Test fun theDroppedAndDeferredFilesAreWhereThePlanPutThem() {
        val status = bridgeRows().associate { it[0].trim('`') to it[3] }
        for (f in listOf("SpikeProbes.kt", "Epic17Probes.kt", "Mailbox.kt", "RovstiBridge.kt")) {
            assertTrue("$f: ${status[f]}", status[f]!!.startsWith("dropped: "))
        }
        for (f in listOf("Documents.kt", "MediaShare.kt")) assertTrue(f, status[f]!!.startsWith("R2 E3.8a"))
    }
}
