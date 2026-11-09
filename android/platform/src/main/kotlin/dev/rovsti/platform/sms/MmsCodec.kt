package dev.rovsti.platform.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Base64
import dev.rovsti.bridge.MmsResultReceiver
import java.io.File

/**
 * MMS file plumbing only (AD-MSG1): the PDU is built and parsed in Rust (`rovsti_core::mms_pdu`) and crosses the seam
 * as base64. This object writes it to a cache file, hands the file's content URI to
 * `SmsManager.sendMultimediaMessage` / `downloadMultimediaMessage`, and reports the result.
 *
 * The app manifest declares `dev.rovsti.bridge.MmsFileProvider` (`android:authorities="${applicationId}.mmsfiles"`,
 * not exported, `grantUriPermissions`) and `dev.rovsti.bridge.MmsResultReceiver` (not exported).
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/MmsCodec.kt
 */
object MmsCodec {
    const val ACTION_SENT = "dev.rovsti.bridge.MMS_SENT"
    const val ACTION_DOWNLOADED = "dev.rovsti.bridge.MMS_DOWNLOADED"
    const val EXTRA_LOCAL_ID = "local_id"
    const val EXTRA_FILE = "file"

    /**
     * What happens to a downloaded PDU (base64 `m-retrieve-conf`): the app persists and notifies it (install
     * [MmsInbox.onDownloaded] from `Application.onCreate`, so a cold receiver has it too). Runs on a worker thread.
     * With none set the PDU is dropped.
     */
    fun interface DownloadedHandler {
        fun onDownloaded(
            context: Context,
            localId: Long,
            pduBase64: String,
        )
    }

    @Volatile var downloadedHandler: DownloadedHandler? = null

    /** Largest PDU accepted from Rust (carrier limits are far lower). */
    const val MAX_PDU_BYTES = 8 * 1024 * 1024

    private val SAFE_NAME = Regex("[A-Za-z0-9_.-]{1,80}")

    fun authority(context: Context): String = "${context.packageName}.mmsfiles"

    fun dir(context: Context): File = File(context.cacheDir, "mms_pdu").apply { mkdirs() }

    /** Pure: the cache file name for a send or download; only characters the provider serves. */
    fun fileName(
        prefix: String,
        localId: Long,
    ): String = "$prefix-$localId.pdu"

    /** Pure: whether [name] may be served by `MmsFileProvider` (no separators, no traversal). */
    fun isSafeName(name: String): Boolean = SAFE_NAME.matches(name) && !name.contains("..")

    /** Pure: decodes a base64 PDU; null when it is malformed, empty or over [MAX_PDU_BYTES]. */
    fun decodePdu(base64: String): ByteArray? =
        try {
            Base64.decode(base64, Base64.DEFAULT).takeIf { it.isNotEmpty() && it.size <= MAX_PDU_BYTES }
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun uriFor(
        context: Context,
        name: String,
    ): Uri =
        Uri
            .Builder()
            .scheme("content")
            .authority(authority(context))
            .appendPath(name)
            .build()

    private fun manager(
        context: Context,
        subId: Int?,
    ): SmsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (base == null || subId == null) base else base.createForSubscriptionId(subId)
        } else {
            @Suppress("DEPRECATION")
            if (subId == null) SmsManager.getDefault() else SmsManager.getSmsManagerForSubscriptionId(subId)
        }

    private fun resultIntent(
        context: Context,
        action: String,
        localId: Long,
        file: String,
        code: Int,
    ): PendingIntent {
        val intent =
            Intent(action)
                .setClass(context, MmsResultReceiver::class.java)
                .putExtra(EXTRA_LOCAL_ID, localId)
                .putExtra(EXTRA_FILE, file)
        return PendingIntent.getBroadcast(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /** Sends the base64 [pdu]; the answer arrives as `sms_status` for [localId]. False when it could not start. */
    fun send(
        context: Context,
        localId: Long,
        pdu: String,
        subId: Int?,
    ): Boolean {
        val bytes = decodePdu(pdu) ?: return false
        val name = fileName("send", localId)
        return try {
            File(dir(context), name).writeBytes(bytes)
            val sms = manager(context, subId) ?: return false
            sms.sendMultimediaMessage(
                context,
                uriFor(context, name),
                null,
                null,
                resultIntent(context, ACTION_SENT, localId, name, (localId and 0x7FFF_FFFF).toInt()),
            )
            true
        } catch (_: RuntimeException) {
            false
        } catch (_: java.io.IOException) {
            false
        }
    }

    /** Downloads the message at [locationUrl] (from a WAP push); the PDU comes back through `MmsResultReceiver`. */
    fun download(
        context: Context,
        localId: Long,
        locationUrl: String,
        subId: Int?,
    ): Boolean {
        val name = fileName("recv", localId)
        return try {
            File(dir(context), name).apply {
                delete()
                createNewFile()
            }
            val sms = manager(context, subId) ?: return false
            sms.downloadMultimediaMessage(
                context,
                locationUrl,
                uriFor(context, name),
                null,
                resultIntent(
                    context,
                    ACTION_DOWNLOADED,
                    localId,
                    name,
                    (localId and 0x7FFF_FFFF).toInt() xor 0x40000000,
                ),
            )
            true
        } catch (_: RuntimeException) {
            false
        } catch (_: java.io.IOException) {
            false
        }
    }

    /** Reads and deletes a downloaded PDU as base64; null when the file is missing or empty. */
    fun takeDownloaded(
        context: Context,
        name: String,
    ): String? {
        if (!isSafeName(name)) return null
        val file = File(dir(context), name)
        return try {
            file.takeIf { it.length() in 1..MAX_PDU_BYTES }?.readBytes()?.let {
                Base64.encodeToString(it, Base64.NO_WRAP)
            }
        } finally {
            file.delete()
        }
    }
}
