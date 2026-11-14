package dev.rovsti.core.log

import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The developer-mode log on disk: `rovsti-0.log`, rotated to `rovsti-1.log` past [maxBytes] (so at most [files] of
 * them, about `files * maxBytes` in all). Lines are written by one background thread from a bounded queue: a full queue
 * drops its oldest line, a caller never waits. Only the facade writes here, and only while developer mode is on.
 */
class LogFile(
    private val dir: File,
    private val maxBytes: Long = MAX_BYTES,
    private val files: Int = FILES,
) {
    private val writer =
        ThreadPoolExecutor(
            1,
            1,
            KEEP_ALIVE_S,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(QUEUE),
            { r -> Thread(r, "rovsti-log").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardOldestPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }

    /** The current file, then the rotated ones, newest first. */
    fun paths(): List<File> = (0 until files).map { File(dir, "rovsti-$it.log") }

    /** Queues [entry]; returns at once. */
    fun write(entry: LogEntry) {
        val line = format(entry)
        writer.execute { append(line) }
    }

    /** Deletes every log file (after the lines already queued). */
    fun deleteAll() {
        writer.execute { paths().forEach { it.delete() } }
    }

    /** Waits until everything queued so far is on disk (tests, export). */
    fun flush(timeoutMs: Long = FLUSH_TIMEOUT_MS) {
        val done = java.util.concurrent.CountDownLatch(1)
        writer.execute { done.countDown() }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun append(line: String) {
        try {
            dir.mkdirs()
            val current = paths().first()
            if (current.length() + line.length > maxBytes) rotate()
            current.appendText(line)
        } catch (_: IOException) {
            // A full disk must not take the app down; the memory ring still has the line.
        } catch (_: SecurityException) {
        }
    }

    private fun rotate() {
        val all = paths()
        all.last().delete()
        for (i in all.size - 1 downTo 1) {
            val from = all[i - 1]
            if (from.exists()) from.renameTo(all[i])
        }
    }

    companion object {
        const val MAX_BYTES = 512L * 1024
        const val FILES = 2
        private const val QUEUE = 1_000
        private const val KEEP_ALIVE_S = 30L
        private const val FLUSH_TIMEOUT_MS = 5_000L

        /** `2026-11-14T09:58:34.123Z I sync K text` and a newline. */
        fun format(entry: LogEntry): String {
            val source = if (entry.source == LogSource.Rust) 'R' else 'K'
            val text = entry.text.replace('\n', ' ')
            return "${Instant.ofEpochMilli(entry.timeMs)} ${entry.level.letter} ${entry.tag} $source $text\n"
        }
    }
}
