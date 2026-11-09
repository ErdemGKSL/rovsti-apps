package dev.rovsti.contacts.photo

import dev.rovsti.core.Outcome
import dev.rovsti.core.model.wa.PictureAnswer
import dev.rovsti.core.model.wa.PictureBody
import dev.rovsti.wa.WaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Base64

/** What fetching a contact's WhatsApp picture came to. */
sealed interface WaPhotoResult {
    /** The large picture was written to [path]. */
    data class Saved(
        val path: String,
    ) : WaPhotoResult

    /** They have no picture, or none you may see. */
    data object NoPicture : WaPhotoResult

    /** The gateway could not be reached (not linked, offline, refused). */
    data object Unreachable : WaPhotoResult

    /** The bytes came, but the phone could not store them. */
    data object CannotStore : WaPhotoResult
}

/**
 * The seam to WhatsApp for "Use WhatsApp Photo". The real one asks the owner's gateway for the large picture of one JID
 * and nothing else (AD-CT6); tests script the answer.
 */
fun interface WaPhotoPort {
    suspend fun fetch(
        jid: String,
        target: File,
    ): WaPhotoResult
}

/**
 * The file the large WhatsApp picture of contact [id] is written to. This is the rule of `wa_photo_path` in
 * `crates/rovsti_app_core/src/contacts/whatsapp.rs` (`wa-photo-<id>.jpg` under the directory); a test pins both.
 */
fun waPhotoFile(
    dir: File,
    id: Long,
): File = File(dir, "wa-photo-$id.jpg")

/**
 * [WaPhotoPort] on `wa.picture` with `full = true`; the bytes are written to the target file. [picture] answers null
 * while there is no open gateway (not linked); [over] builds the real one from the app's [WaRepository].
 */
class GatewayWaPhotoPort(
    private val picture: suspend (PictureBody) -> Outcome<PictureAnswer>?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : WaPhotoPort {
    companion object {
        fun over(wa: WaRepository): GatewayWaPhotoPort = GatewayWaPhotoPort({ body -> wa.gateway()?.picture(body) })
    }

    override suspend fun fetch(
        jid: String,
        target: File,
    ): WaPhotoResult {
        val answer =
            when (val got = picture(PictureBody(jid = jid, full = true))) {
                null -> return WaPhotoResult.Unreachable
                is Outcome.Err -> return WaPhotoResult.Unreachable
                is Outcome.Ok -> got.value
            }
        if (answer.kind != "image") return WaPhotoResult.NoPicture
        return withContext(io) {
            try {
                val inline = answer.dataB64
                when {
                    inline != null -> {
                        target.writeBytes(Base64.getDecoder().decode(inline))
                    }

                    else -> {
                        val temp = answer.path ?: return@withContext WaPhotoResult.NoPicture
                        val source = File(temp)
                        source.copyTo(target, overwrite = true)
                        source.delete()
                    }
                }
                WaPhotoResult.Saved(target.absolutePath)
            } catch (_: IOException) {
                WaPhotoResult.CannotStore
            } catch (_: IllegalArgumentException) {
                WaPhotoResult.CannotStore
            }
        }
    }
}
