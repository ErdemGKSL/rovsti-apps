package dev.rovsti.people

import android.util.Base64
import dev.rovsti.core.Outcome
import dev.rovsti.core.log.RovstiLog
import dev.rovsti.core.model.wa.PictureAnswer
import dev.rovsti.core.model.wa.PictureBody
import dev.rovsti.wa.avatars.rateLimit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The full-size WhatsApp picture the photo viewer swaps in over the cached one (S12). It is asked of the gateway once
 * per open (`full = true`) and kept in a small cache folder of the [keep] newest pictures, never in the people cache.
 * Not linked, offline, no picture, a 429 or any error answers null: the viewer keeps the cached picture, silently. The
 * outcome is logged under the tag `people`.
 */
class WaFullPictures(
    private val dir: File,
    private val picture: suspend (PictureBody) -> Outcome<PictureAnswer>?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val keep: Int = KEEP,
) {
    private val lock = Mutex()

    /** The full picture of [jid] (picture [pictureId]) as a file, or null to keep the cached one. */
    suspend fun load(
        jid: String,
        pictureId: String,
    ): File? =
        lock.withLock {
            val target = File(dir, name(jid, pictureId))
            if (withContext(io) { target.isFile }) return@withLock target
            val answer =
                when (val got = picture(PictureBody(jid, full = true))) {
                    null -> {
                        RovstiLog.event(TAG, "full_picture", "result" to "not_linked")
                        return@withLock null
                    }

                    is Outcome.Err -> {
                        val limit = got.error.rateLimit()
                        RovstiLog.event(
                            TAG,
                            "full_picture",
                            "result" to if (limit != null) "rate_limited" else "error",
                            "retry_after_ms" to limit?.retryAfterMs,
                        )
                        return@withLock null
                    }

                    is Outcome.Ok -> {
                        got.value
                    }
                }
            if (answer.kind != "image") {
                RovstiLog.event(TAG, "full_picture", "result" to answer.kind)
                return@withLock null
            }
            withContext(io) { write(answer, target) }.also {
                RovstiLog.event(TAG, "full_picture", "result" to if (it != null) "image" else "cannot_store")
            }
        }

    private fun write(
        answer: PictureAnswer,
        target: File,
    ): File? =
        try {
            dir.mkdirs()
            val part = File(dir, target.name + ".part")
            val inline = answer.dataB64
            val temp = answer.path
            when {
                inline != null -> {
                    part.writeBytes(Base64.decode(inline, Base64.DEFAULT))
                }

                temp != null -> {
                    val source = File(temp)
                    try {
                        source.copyTo(part, overwrite = true)
                    } finally {
                        source.delete()
                    }
                }

                else -> {
                    return null
                }
            }
            if (!part.renameTo(target)) return null
            prune()
            target
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun prune() {
        val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".part") }.orEmpty()
        files.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
    }

    companion object {
        /** The log tag. */
        const val TAG = "people"

        /** Full pictures kept at most (about 100 KB each). */
        const val KEEP = 4

        /** The folder inside the app's cache directory. */
        const val DIR_NAME = "wa-full"

        /** The file of one picture: only safe characters, so no name can leave the folder. */
        fun name(
            jid: String,
            pictureId: String,
        ): String = (jid + "~" + pictureId).replace(Regex("[^A-Za-z0-9._~-]"), "_") + ".jpg"
    }
}
