package dev.rovsti.messages

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.rovsti.bridge.SmsRequest
import dev.rovsti.bridge.SmsSend

/**
 * `RESPOND_VIA_MESSAGE`: the in-call "Reply with message" (and `mms:`/`mmsto:`) quick reply. The system only
 * binds the default SMS app with `SEND_RESPOND_VIA_MESSAGE`. It sends plain text through [SmsSend] and stops;
 * it never touches the UI or the native library.
 */
class HeadlessSmsSendService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent?.let { requestOf(it) }
        if (request == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        Thread {
            try {
                SmsSend.send(applicationContext, request)
            } finally {
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        /** `TelephonyManager.ACTION_RESPOND_VIA_MESSAGE`, spelled out so the service needs no telephony import. */
        const val ACTION_RESPOND_VIA_MESSAGE = "android.intent.action.RESPOND_VIA_MESSAGE"
    }

    private fun requestOf(intent: Intent): SmsRequest? {
        if (intent.action != ACTION_RESPOND_VIA_MESSAGE) return null
        val to = ComposeRequest.recipients(intent.data?.schemeSpecificPart)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return null
        if (to.isEmpty()) return null
        return SmsRequest(System.currentTimeMillis(), to, text, null)
    }
}
