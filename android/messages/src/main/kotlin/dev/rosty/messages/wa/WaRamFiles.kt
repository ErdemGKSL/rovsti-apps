package dev.rosty.messages.wa

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Where WhatsApp media lives since the vault (`plans/wa-vault.md` 7, 13.1): in RAM, never on disk. A file here is an
 * anonymous memory file with a path ([put]), so the image loader, the voice and video players and the gateway's send
 * read it like any file, while nothing reaches flash. The store's LRU (its media cap, [WaOffline.RAM_CAP_BYTES]) decides
 * what stays; [release] frees one, [clear] all of them (lock, unlink).
 */
interface WaRamFiles {
    /** Keeps [bytes] as a file named [name] (for the reader's eyes only); null when memory refused it. */
    fun put(
        name: String,
        bytes: ByteArray,
    ): File?

    /** True for a path [put] answered and [release] has not freed. */
    fun owns(path: String): Boolean

    fun release(path: String)

    /** Frees every file. */
    fun clear()

    /** Bytes held now. */
    val bytes: Long
}

/**
 * [WaRamFiles] over `memfd_create`: each file is anonymous shared memory, read through `/proc/self/fd/<n>` inside this
 * process only. Closing the descriptor frees it; a killed process leaves nothing behind.
 */
class MemfdRamFiles : WaRamFiles {
    private val open = ConcurrentHashMap<String, Pair<ParcelFileDescriptor, Long>>()

    override fun put(
        name: String,
        bytes: ByteArray,
    ): File? =
        try {
            val raw = Os.memfd_create(name.take(MAX_NAME).ifEmpty { "wa" }, 0)
            val pfd = ParcelFileDescriptor.dup(raw)
            Os.close(raw)
            var written = 0
            while (written < bytes.size) written += Os.write(pfd.fileDescriptor, bytes, written, bytes.size - written)
            val path = "/proc/self/fd/${pfd.fd}"
            open[path] = pfd to bytes.size.toLong()
            File(path)
        } catch (_: ErrnoException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: UnsupportedOperationException) {
            // No memfd here (a JVM test): nothing is written anywhere else.
            null
        }

    override fun owns(path: String): Boolean = open.containsKey(path)

    override fun release(path: String) {
        open.remove(path)?.first?.let { runCatching { it.close() } }
    }

    override fun clear() {
        open.keys.toList().forEach(::release)
    }

    override val bytes: Long get() = open.values.sumOf { it.second }

    private companion object {
        const val MAX_NAME = 60
    }
}
