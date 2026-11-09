package dev.rovsti.bridge

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.rovsti.platform.PlatformEvents
import dev.rovsti.platform.sms.MmsCodec
import dev.rovsti.platform.sms.ScheduledSms
import dev.rovsti.platform.sms.SmsSend
import java.io.File

// The SMS-side manifest components. They live in this package, not dev.rovsti.platform.sms, because the manifests of
// the update-in-place apps and the PendingIntents already in the system name `dev.rovsti.bridge.*` (ARCH 7.2); a
// rename would drop a user's armed alarms. FqcnTest pins the names. The logic is in `platform.sms`.

/** Where the cold receivers do their blocking work; tests run it inline. */
internal object ReceiverWork {
    @Volatile var executor: (Runnable) -> Unit = { Thread(it).start() }

    fun run(block: () -> Unit) = executor(Runnable(block))
}

/** Receives the sent and delivery broadcasts of [SmsSend]; declare it (not exported) in the app manifest. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val kind = intent.getStringExtra(SmsSend.EXTRA_KIND) ?: return
        val ok = resultCode == Activity.RESULT_OK
        val part = intent.getIntExtra(SmsSend.EXTRA_PART, 0)
        val parts = intent.getIntExtra(SmsSend.EXTRA_PARTS, 1)
        val state = SmsSend.stateFor(kind, ok, part, parts) ?: return
        val localId = intent.getLongExtra(SmsSend.EXTRA_LOCAL_ID, 0)
        val uri = intent.getStringExtra(SmsSend.EXTRA_URI)
        SmsSend.updateProvider(context, uri?.let(Uri::parse), state)
        SmsSend.emitStatus(localId, uri, state, if (ok) null else resultCode)
    }
}

/** Serves the PDU files in `cache/mms_pdu` to the telephony service. */
class MmsFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (!MmsCodec.isSafeName(name)) return null
        val file = File(MmsCodec.dir(ctx), name)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(
        uri: Uri,
        p: Array<out String>?,
        s: String?,
        a: Array<out String>?,
        o: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String = "application/vnd.wap.mms-message"

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        s: String?,
        a: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        v: ContentValues?,
        s: String?,
        a: Array<out String>?,
    ): Int = 0
}

/** The result of an MMS send or download. */
class MmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val localId = intent.getLongExtra(MmsCodec.EXTRA_LOCAL_ID, 0)
        val ok = resultCode == Activity.RESULT_OK
        when (intent.action) {
            MmsCodec.ACTION_SENT -> {
                intent
                    .getStringExtra(MmsCodec.EXTRA_FILE)
                    ?.takeIf(MmsCodec::isSafeName)
                    ?.let { File(MmsCodec.dir(context), it).delete() }
                SmsSend.emitStatus(localId, null, if (ok) "sent" else "failed", if (ok) null else resultCode)
            }

            MmsCodec.ACTION_DOWNLOADED -> {
                val name = intent.getStringExtra(MmsCodec.EXTRA_FILE) ?: return
                val pdu = if (ok) MmsCodec.takeDownloaded(context, name) else null
                if (pdu != null) {
                    // The app decodes the PDU through `mms_retrieved`, persists it and notifies.
                    val app = context.applicationContext
                    val result: PendingResult? = goAsync()
                    ReceiverWork.run {
                        try {
                            MmsCodec.downloadedHandler?.onDownloaded(app, localId, pdu)
                            PlatformEvents.emit("sms_changed", "{}")
                        } finally {
                            result?.finish()
                        }
                    }
                }
            }
        }
    }
}

/** Alarm target and boot receiver for [ScheduledSms]; declare it in the app manifest (`BOOT_COMPLETED`, [ScheduledSms.ACTION_FIRE]). */
class ScheduledSmsReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ScheduledSms.rearmAll(context)
            return
        }
        val id = intent.getLongExtra(ScheduledSms.EXTRA_ID, 0)
        if (id <= 0) return
        val result: PendingResult? = goAsync()
        ReceiverWork.run {
            try {
                ScheduledSms.fire(context.applicationContext, id)
            } finally {
                result?.finish()
            }
        }
    }
}
